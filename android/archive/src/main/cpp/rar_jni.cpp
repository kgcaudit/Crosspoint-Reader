// JNI bridge to the UnRAR library.
//
// The UnRAR source (unrar/, license.txt) is Alexander Roshal's free UnRAR
// code. Its licence permits use in any software to handle RAR archives free
// of charge, and forbids using it to build a RAR-compatible archiver or to
// re-create the RAR compression. This bridge only lists and extracts, and
// nothing in this app writes a RAR. RAR 5.0's decoder cannot be ported to
// Kotlin at any reasonable effort or risk, so the reference decoder is
// compiled and driven over its own DLL C API.
#include <jni.h>
#include <clocale>
#include <sys/stat.h>
#include <string>
#include <vector>
#include "unrar/rar.hpp"   // pulls dll.hpp and the _UNIX typedefs it needs

namespace {

std::string toUtf8(JNIEnv* env, jstring s) {
    if (s == nullptr) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

// A wchar_t path (unrar uses wchar_t names) as UTF-8. On Android wchar_t is
// four bytes, so this walks code points rather than assuming UTF-16.
std::string wideToUtf8(const wchar_t* w) {
    std::string out;
    if (!w) return out;
    for (const wchar_t* p = w; *p; ++p) {
        unsigned int c = (unsigned int)*p;
        if (c < 0x80) {
            out += (char)c;
        } else if (c < 0x800) {
            out += (char)(0xC0 | (c >> 6));
            out += (char)(0x80 | (c & 0x3F));
        } else if (c < 0x10000) {
            out += (char)(0xE0 | (c >> 12));
            out += (char)(0x80 | ((c >> 6) & 0x3F));
            out += (char)(0x80 | (c & 0x3F));
        } else {
            out += (char)(0xF0 | (c >> 18));
            out += (char)(0x80 | ((c >> 12) & 0x3F));
            out += (char)(0x80 | ((c >> 6) & 0x3F));
            out += (char)(0x80 | (c & 0x3F));
        }
    }
    return out;
}

// A UTF-8 std::string as a wchar_t path for RARProcessFileW.
std::wstring utf8ToWide(const std::string& s) {
    std::wstring out;
    size_t i = 0;
    while (i < s.size()) {
        unsigned char c = (unsigned char)s[i];
        unsigned int cp; int n;
        if (c < 0x80) { cp = c; n = 0; }
        else if ((c >> 5) == 0x6) { cp = c & 0x1F; n = 1; }
        else if ((c >> 4) == 0xE) { cp = c & 0x0F; n = 2; }
        else if ((c >> 3) == 0x1E) { cp = c & 0x07; n = 3; }
        else { cp = 0xFFFD; n = 0; }
        i++;
        for (int k = 0; k < n && i < s.size(); ++k, ++i) cp = (cp << 6) | (s[i] & 0x3F);
        out += (wchar_t)cp;
    }
    return out;
}

// FILETIME-style low/high 100ns ticks since 1601 -> epoch millis, or 0.
jlong ticksToMillis(unsigned int low, unsigned int high) {
    unsigned long long ticks = ((unsigned long long)high << 32) | low;
    if (ticks == 0) return 0;
    return (jlong)(ticks / 10000ULL - 11644473600000ULL);
}

// Shared across the extract callback and the loop it runs in.
struct ExtractState {
    JNIEnv* env;
    jobject sink;          // io.github.kgcaudit.reader.archive.RarNative$Sink
    jmethodID onProgress;  // (JJLjava/lang/String;)V  -- bytes done, total, name
    jmethodID isCancelled; // ()Z
    jlong doneBytes;
    jlong totalBytes;
    std::string currentName;
    bool cancelled;
    long sinceReport;
};

// Whether the next volume unrar wants exists. p1 is its path -- wide for the
// W message, char for the other. A multi-volume rar names its parts in order
// (movie.part2.rar, movie.r00, ...); unrar derives the next and asks here.
bool nextVolumeExists(UINT msg, LPARAM p1) {
    std::string path = (msg == UCM_CHANGEVOLUMEW)
        ? wideToUtf8((wchar_t*)p1) : std::string((const char*)p1);
    struct stat sb;
    return stat(path.c_str(), &sb) == 0;
}

int CALLBACK extractCallback(UINT msg, LPARAM userData, LPARAM p1, LPARAM p2) {
    ExtractState* st = (ExtractState*)userData;
    switch (msg) {
        case UCM_PROCESSDATA: {
            st->doneBytes += (jlong)p2;
            st->sinceReport += (long)p2;
            // Report at most every megabyte, and check for a stop each time:
            // a four-gigabyte entry must be both watchable and stoppable.
            if (st->sinceReport >= 1000000L) {
                st->sinceReport = 0;
                jstring name = st->env->NewStringUTF(st->currentName.c_str());
                jvalue args[3];
                args[0].j = st->doneBytes;
                args[1].j = st->totalBytes;
                args[2].l = name;
                st->env->CallVoidMethodA(st->sink, st->onProgress, args);
                st->env->DeleteLocalRef(name);
                if (st->env->CallBooleanMethod(st->sink, st->isCancelled)) {
                    st->cancelled = true;
                    return -1;  // abort extraction
                }
            }
            return 1;
        }
        case UCM_NEEDPASSWORD:
        case UCM_NEEDPASSWORDW:
            // The password is set before the loop; being asked again means
            // it was wrong. Aborting turns into ERAR_BAD_PASSWORD below.
            return -1;
        case UCM_CHANGEVOLUME:
        case UCM_CHANGEVOLUMEW:
            // Continue to the next volume when it is there, so a multi-volume
            // archive unpacks across its parts; stop when it is genuinely
            // missing rather than prompting for it.
            if (p2 == RAR_VOL_ASK) return nextVolumeExists(msg, p1) ? 0 : -1;
            return 0;
        default:
            return 0;
    }
}

// The listing needs volume changes handled too, or a multi-volume archive
// shows only the files that start in its first part. It never needs the
// password -- names are readable unless the header itself is encrypted.
int CALLBACK listCallback(UINT msg, LPARAM, LPARAM p1, LPARAM p2) {
    switch (msg) {
        case UCM_CHANGEVOLUME:
        case UCM_CHANGEVOLUMEW:
            if (p2 == RAR_VOL_ASK) return nextVolumeExists(msg, p1) ? 0 : -1;
            return 0;
        default:
            return 0;
    }
}

// nativeExtract's "went through to the end, but some entries could not be
// unpacked" (encrypted with no password, damaged). The same archive gives the
// same result next time, so the caller can keep what it got instead of
// unpacking again on every open. Same value as the 7z bridge.
const jint kSkippedSome = 3;

// Whether an entry's failure is the device's rather than the entry's: the
// archive or a volume could not be read, the destination could not be
// created or written (a full disk), memory ran out. These stop the run so
// nothing is marked done; anything else is the entry's own damage and is
// skipped.
bool deviceFault(int code) {
    return code == ERAR_EOPEN || code == ERAR_EREAD || code == ERAR_ECREATE || code == ERAR_EWRITE ||
        code == ERAR_ECLOSE || code == ERAR_NO_MEMORY;
}

// Whether RARReadHeaderEx read a header. unrar's error state is sticky: after
// one damaged entry, every later RARReadHeaderEx returns that entry's error
// (ERAR_BAD_DATA) even though it read the next header fine -- going by the
// return code alone stopped at the first damaged page and left all the pages
// after it blank. A header that really could not be read returns before the
// name is filled in (the caller zeroes it before every read).
bool headerRead(int result, const RARHeaderDataEx& header) {
    return result == ERAR_SUCCESS || (result != ERAR_END_ARCHIVE && header.FileName[0] != '\0');
}

bool wantsEntry(const std::string& name, const std::vector<std::string>& picks) {
    if (picks.empty()) return true;
    for (const auto& p : picks) {
        if (name == p) return true;
        if (name.size() > p.size() && name.compare(0, p.size(), p) == 0 && name[p.size()] == '/') return true;
    }
    return false;
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    // So unrar builds destination paths in UTF-8, which is what Android
    // filenames are; without it a Korean target folder could be mangled.
    setlocale(LC_ALL, "C.UTF-8");
    return JNI_VERSION_1_6;
}

JNIEXPORT jint JNICALL
Java_io_github_kgcaudit_reader_archive_RarNative_nativeVersion(JNIEnv*, jclass) {
    return RARGetDllVersion();
}

// Lists the archive by calling back sink.entry(name, size, isDir, mtime, encrypted).
JNIEXPORT jint JNICALL
Java_io_github_kgcaudit_reader_archive_RarNative_nativeList(
        JNIEnv* env, jclass, jstring jpath, jobject sink) {
    std::string path = toUtf8(env, jpath);

    RAROpenArchiveDataEx open;
    memset(&open, 0, sizeof(open));
    open.ArcName = (char*)path.c_str();
    open.OpenMode = RAR_OM_LIST;
    open.Callback = listCallback;

    HANDLE h = RAROpenArchiveEx(&open);
    if (open.OpenResult != ERAR_SUCCESS) {
        if (h) RARCloseArchive(h);
        return -(jint)open.OpenResult;
    }

    jclass sinkClass = env->GetObjectClass(sink);
    jmethodID entry = env->GetMethodID(sinkClass, "entry", "(Ljava/lang/String;JZJZ)V");
    if (entry == nullptr) { env->ExceptionClear(); RARCloseArchive(h); return -100; }

    RARHeaderDataEx header;
    int result;
    // The header must be zeroed before every read: CmtBuf and RedirName are
    // pointers unrar writes into when an entry has a comment or is a link,
    // and left as stack garbage the first such write is a crash. This was
    // the crash on opening a rar -- the same fix nativeExtract already had.
    for (memset(&header, 0, sizeof(header));
         (result = RARReadHeaderEx(h, &header)) == ERAR_SUCCESS;
         memset(&header, 0, sizeof(header))) {
        // RAR 5 stores names as UTF-8, which unrar hands back in FileName
        // as-is. FileNameW goes through the C locale and comes back mangled
        // on a build with no UTF-8 locale, so the char field is the true one.
        std::string name = header.FileName;
        for (auto& c : name) if (c == '\\') c = '/';
        jlong size = ((jlong)header.UnpSizeHigh << 32) | (unsigned int)header.UnpSize;
        bool isDir = (header.Flags & RHDF_DIRECTORY) != 0;
        bool encrypted = (header.Flags & RHDF_ENCRYPTED) != 0;
        jlong mtime = ticksToMillis(header.MtimeLow, header.MtimeHigh);
        jstring jname = env->NewStringUTF(name.c_str());
        // CallVoidMethodA, not the varargs form: mixing jlong and jboolean
        // through C varargs is where a JNI call goes wrong on arm64.
        jvalue args[5];
        args[0].l = jname;
        args[1].j = size;
        args[2].z = (jboolean)isDir;
        args[3].j = mtime;
        args[4].z = (jboolean)encrypted;
        env->CallVoidMethodA(sink, entry, args);
        env->DeleteLocalRef(jname);
        if (RARProcessFile(h, RAR_SKIP, nullptr, nullptr) != ERAR_SUCCESS) break;
    }
    RARCloseArchive(h);
    return (result == ERAR_END_ARCHIVE || result == ERAR_SUCCESS) ? 0 : -(jint)result;
}

// Extracts into destDir. picks null/empty = everything, else the named
// entries and what is under them. Reports and cancels through sink.
//
// An entry that cannot be unpacked -- encrypted with no password given, or
// damaged -- is skipped and the rest still unpack. Stopping at an encrypted
// entry left every later page blank, and because the run never finished the
// caller unpacked the whole archive again on every open. (unrar deletes a
// file whose data fails its check, so a skipped entry leaves nothing behind.)
//
// Returns 0 everything unpacked, kSkippedSome (3) reached the end but skipped
// entries, 1 cancelled, 2 wrong password (only when one was given), negative
// an ERAR_* error -- the run stopped (could not read, could not write, out of
// memory) and may go differently next time.
JNIEXPORT jint JNICALL
Java_io_github_kgcaudit_reader_archive_RarNative_nativeExtract(
        JNIEnv* env, jclass, jstring jpath, jstring jdest,
        jobjectArray jpicks, jstring jpassword, jlong jtotalBytes,
        jboolean jskipExisting, jobject sink) {
    std::string path = toUtf8(env, jpath);
    std::string dest = toUtf8(env, jdest);

    std::vector<std::string> picks;
    if (jpicks) {
        jsize n = env->GetArrayLength(jpicks);
        for (jsize i = 0; i < n; ++i) {
            jstring s = (jstring)env->GetObjectArrayElement(jpicks, i);
            picks.push_back(toUtf8(env, s));
            env->DeleteLocalRef(s);
        }
    }

    ExtractState st;
    st.env = env;
    st.sink = sink;
    jclass sinkClass = env->GetObjectClass(sink);
    st.onProgress = env->GetMethodID(sinkClass, "progress", "(JJLjava/lang/String;)V");
    st.isCancelled = env->GetMethodID(sinkClass, "cancelled", "()Z");
    if (st.onProgress == nullptr || st.isCancelled == nullptr) { env->ExceptionClear(); return -100; }
    st.doneBytes = 0;
    st.totalBytes = jtotalBytes;
    st.cancelled = false;
    st.sinceReport = 0;

    RAROpenArchiveDataEx open;
    memset(&open, 0, sizeof(open));
    open.ArcName = (char*)path.c_str();
    open.OpenMode = RAR_OM_EXTRACT;
    open.Callback = extractCallback;
    open.UserData = (LPARAM)&st;

    HANDLE h = RAROpenArchiveEx(&open);
    if (open.OpenResult != ERAR_SUCCESS) {
        if (h) RARCloseArchive(h);
        return -(jint)open.OpenResult;
    }

    std::string password = toUtf8(env, jpassword);
    if (!password.empty()) RARSetPassword(h, (char*)password.c_str());

    std::wstring wdest = utf8ToWide(dest);

    RARHeaderDataEx header;
    int result;
    jint outcome = 0;
    bool skipped = false;
    for (memset(&header, 0, sizeof(header));
         headerRead(result = RARReadHeaderEx(h, &header), header);
         memset(&header, 0, sizeof(header))) {
        std::string name = header.FileName;
        for (auto& c : name) if (c == '\\') c = '/';
        bool take = !(header.Flags & RHDF_DIRECTORY) && wantsEntry(name, picks);
        // No password to try: skip it without asking unrar, which would only
        // fail it as a missing password.
        if (take && password.empty() && (header.Flags & RHDF_ENCRYPTED)) {
            take = false;
            skipped = true;
        }
        if (take && jskipExisting) {
            // Leave a file that is already unpacked, the same as the Kotlin
            // readers do -- unrar overwrites by default, so this is where
            // "keep what is there" is enforced for rar.
            std::string full = dest + "/" + name;
            struct stat sb;
            if (stat(full.c_str(), &sb) == 0) take = false;
        }
        st.currentName = name;
        int op = take ? RAR_EXTRACT : RAR_SKIP;
        int pr = RARProcessFileW(h, op, (wchar_t*)wdest.c_str(), nullptr);
        if (pr != ERAR_SUCCESS) {
            if (st.cancelled) { outcome = 1; break; }
            if (!password.empty() && (pr == ERAR_BAD_PASSWORD || pr == ERAR_MISSING_PASSWORD)) { outcome = 2; break; }
            if (deviceFault(pr)) { outcome = -(jint)pr; break; }
            // The entry's own damage (or, in a solid archive, an encrypted
            // entry it had to pass through): skip it, unpack the rest.
            skipped = true;
        }
    }
    RARCloseArchive(h);
    // The header loop must have reached the end of the archive -- a header
    // that broke half way means the rest was never seen, not that it was
    // skipped.
    if (outcome == 0 && result != ERAR_END_ARCHIVE && !headerRead(result, header) && !st.cancelled) {
        outcome = -(jint)result;
    }
    if (outcome == 0 && skipped) outcome = kSkippedSome;
    return outcome;
}

} // extern "C"
