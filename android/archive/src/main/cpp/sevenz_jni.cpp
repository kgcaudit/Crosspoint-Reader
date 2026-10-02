// JNI bridge to the LZMA SDK's reference 7z extractor.
//
// The 7z sources (sevenz/, lzma-sdk-license.txt) are Igor Pavlov's
// public-domain LZMA SDK. Only the reference 7z-extraction subset is
// vendored -- the files the SDK's own 7zDec sample links -- so LZMA, LZMA2,
// PPMd and the branch filters are 7-Zip's own decoder rather than a Kotlin
// port, where a mistake would be silent corruption. This lists and extracts
// only, and nothing in this app writes a 7z.
//
// AES-256 decryption is added in sevenz/7zDec.c, so a password-protected 7z
// (with a readable header -- the common case) lists and, given the password,
// extracts. The password is set through SevenZ_SetPassword before extraction.
#include <jni.h>
#include <cstdlib>
#include <cstring>
#include <cstdio>
#include <string>
#include <vector>
#include <sys/stat.h>

#include "sevenz/7z.h"
#include "sevenz/7zCrc.h"
#include "sevenz/7zFile.h"

// The password sink patched into the reference decoder (7zDec.c). UTF-16LE,
// which is what 7z hashes to the AES key.
extern "C" void SevenZ_SetPassword(const Byte* utf16le, size_t len);

namespace {

// Custom SRes the AES path returns when a folder will not decrypt -- almost
// always a wrong or missing password. Matches SZ_ERROR_7Z_AES in 7zDec.c.
const int kSevenZAesError = 100;

std::vector<Byte> toUtf16le(const std::string& utf8) {
    std::vector<Byte> out;
    size_t i = 0;
    while (i < utf8.size()) {
        unsigned char c = (unsigned char)utf8[i];
        unsigned int cp; int n;
        if (c < 0x80) { cp = c; n = 0; }
        else if ((c >> 5) == 0x6) { cp = c & 0x1F; n = 1; }
        else if ((c >> 4) == 0xE) { cp = c & 0x0F; n = 2; }
        else if ((c >> 3) == 0x1E) { cp = c & 0x07; n = 3; }
        else { cp = 0xFFFD; n = 0; }
        i++;
        for (int k = 0; k < n && i < utf8.size(); ++k, ++i) cp = (cp << 6) | (utf8[i] & 0x3F);
        if (cp >= 0x10000) {
            cp -= 0x10000;
            unsigned int hi = 0xD800 + (cp >> 10), lo = 0xDC00 + (cp & 0x3FF);
            out.push_back((Byte)(hi & 0xFF)); out.push_back((Byte)(hi >> 8));
            out.push_back((Byte)(lo & 0xFF)); out.push_back((Byte)(lo >> 8));
        } else {
            out.push_back((Byte)(cp & 0xFF)); out.push_back((Byte)(cp >> 8));
        }
    }
    return out;
}

// The one method the app cannot do: AES-256, the coder 7z uses for
// encryption. The SDK's reference decoder does not include it.
const UInt32 K_AES = 0x06F10701;

// The read buffer for the archive stream. The sample uses the same size.
const size_t kInputBufSize = (size_t)1 << 18;

// malloc/free as an ISzAlloc, so nothing here depends on the SDK's own
// Alloc.c -- which the reference 7zDec object list leaves out.
void* szAlloc(ISzAllocPtr, size_t size) { return malloc(size); }
void szFree(ISzAllocPtr, void* address) { free(address); }
const ISzAlloc g_alloc = { szAlloc, szFree };

std::string toUtf8(JNIEnv* env, jstring s) {
    if (s == nullptr) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

// A UTF-16 name (what 7z stores) as UTF-8, joining surrogate pairs.
std::string utf16ToUtf8(const UInt16* w, size_t len) {
    std::string out;
    for (size_t i = 0; i < len; ++i) {
        unsigned int c = w[i];
        if (c >= 0xD800 && c <= 0xDBFF && i + 1 < len) {
            unsigned int lo = w[i + 1];
            if (lo >= 0xDC00 && lo <= 0xDFFF) {
                c = 0x10000 + ((c - 0xD800) << 10) + (lo - 0xDC00);
                ++i;
            }
        }
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

// FILETIME 100ns ticks since 1601 -> epoch millis, or 0. Same as the rar bridge.
jlong ticksToMillis(UInt32 low, UInt32 high) {
    unsigned long long ticks = ((unsigned long long)high << 32) | low;
    if (ticks == 0) return 0;
    return (jlong)(ticks / 10000ULL - 11644473600000ULL);
}

// Everything the reference decoder can unpack. A folder using anything else
// is shown but marked out of reach, the same as an unknown method in the
// Kotlin readers.
bool isSupportedMethod(UInt32 id) {
    switch (id) {
        case 0x0:        // Copy
        case 0x3:        // Delta
        case 0x21:       // LZMA2
        case 0x30101:    // LZMA
        case 0x30401:    // PPMd
        case 0x3030103:  // BCJ x86
        case 0x3030205:  // PPC
        case 0x3030401:  // IA64
        case 0x3030501:  // ARM
        case 0x3030701:  // ARMT
        case 0x3030805:  // SPARC
        case 0x303011B:  // BCJ2
        case 0xa:        // ARM64
        case 0xb:        // RISC-V
            return true;
        default:
            return false;
    }
}

// Classifies the folder a file lives in: encrypted (AES, no decoder here) or
// using a method this build does not do. A file with no folder -- a
// directory or an empty file -- is neither.
void classify(const CSzArEx& db, UInt32 fileIndex, bool& encrypted, bool& unsupported) {
    encrypted = false;
    unsupported = false;
    UInt32 fo = db.FileToFolder[fileIndex];
    if (fo >= db.db.NumFolders) return;

    CSzData sd;
    sd.Data = db.db.CodersData + db.db.FoCodersOffsets[fo];
    sd.Size = db.db.FoCodersOffsets[fo + 1] - db.db.FoCodersOffsets[fo];
    CSzFolder folder;
    if (SzGetNextFolderItem(&folder, &sd) != SZ_OK) {
        unsupported = true;
        return;
    }
    for (UInt32 c = 0; c < folder.NumCoders; ++c) {
        UInt32 id = folder.Coders[c].MethodID;
        if (id == K_AES) encrypted = true;
        else if (!isSupportedMethod(id)) unsupported = true;
    }
}

// Makes every folder on the way to a file, like mkdir -p. Slashes only:
// names are normalised to '/' before they get here.
void makeDirs(const std::string& path) {
    for (size_t i = 1; i < path.size(); ++i) {
        if (path[i] != '/') continue;
        std::string part = path.substr(0, i);
        mkdir(part.c_str(), 0755);
    }
}

bool wantsEntry(const std::string& name, const std::vector<std::string>& picks) {
    if (picks.empty()) return true;
    for (const auto& p : picks) {
        if (name == p) return true;
        if (name.size() > p.size() && name.compare(0, p.size(), p) == 0 && name[p.size()] == '/') return true;
    }
    return false;
}

// A read stream that counts what it hands out and can stop the decode.
//
// Why this exists: SzArEx_Extract decodes a whole solid folder in one call
// before it returns a single byte, so a big 7z looked frozen -- no progress
// moved and the stop button did nothing until the decode finished, which
// for half a gigabyte is many seconds. The decoder reads its input through
// this stream as it works, so counting those reads is a live measure of how
// far the decode has got, and refusing a read is how it is stopped part way.
// It sits under the real file stream and forwards to it; the SDK is not
// touched.
struct CountingStream {
    ISeekInStream vt;      // first, so the interface pointer is this struct
    ISeekInStream* inner;  // the real file stream
    JNIEnv* env;
    jobject sink;
    jmethodID onProgress;
    jmethodID isCancelled;
    UInt64 consumed;       // compressed bytes read so far
    UInt64 totalPacked;    // compressed bytes in the file
    jlong totalUnpacked;   // uncompressed total, for the bar's scale
    UInt64 sinceReport;
    const std::string* name;
    bool active;           // off during the header read, on during extract
    bool cancelled;
};

SRes counting_Read(ISeekInStreamPtr pp, void* buf, size_t* size) {
    CountingStream* s = (CountingStream*)pp;
    SRes r = s->inner->Read(s->inner, buf, size);
    if (r != SZ_OK) return r;
    if (!s->active) return SZ_OK;
    s->consumed += *size;
    s->sinceReport += *size;
    // Every megabyte of input: report where the decode is and ask to stop.
    if (s->sinceReport >= (UInt64)(1 << 20)) {
        s->sinceReport = 0;
        // Scaled to the uncompressed total, so the bar means what the user
        // is unpacking rather than the smaller compressed size.
        jlong done = (jlong)s->consumed;
        jlong total = (jlong)s->totalPacked;
        if (s->totalUnpacked > 0 && s->totalPacked > 0) {
            done = (jlong)((double)s->consumed / (double)s->totalPacked * (double)s->totalUnpacked);
            total = s->totalUnpacked;
        }
        if (done > total) done = total;
        jstring jn = s->env->NewStringUTF(s->name ? s->name->c_str() : "");
        jvalue a[3];
        a[0].j = done;
        a[1].j = total;
        a[2].l = jn;
        s->env->CallVoidMethodA(s->sink, s->onProgress, a);
        s->env->DeleteLocalRef(jn);
        if (s->env->CallBooleanMethod(s->sink, s->isCancelled)) {
            s->cancelled = true;
            return SZ_ERROR_PROGRESS;  // stops SzArEx_Extract part way through
        }
    }
    return SZ_OK;
}

SRes counting_Seek(ISeekInStreamPtr pp, Int64* pos, ESzSeek origin) {
    CountingStream* s = (CountingStream*)pp;
    return s->inner->Seek(s->inner, pos, origin);
}

// Parses the 7z header, reading through [realStream] (the volumes, or the
// counting stream in front of them). The caller owns the volume files and
// closes them. On success the caller must SzArEx_Free the db and free
// look->buf. Returns SZ_OK or an SRes.
SRes openArchive(CLookToRead2* look, CSzArEx* db, ISeekInStream* realStream) {
    look->buf = (Byte*)malloc(kInputBufSize);
    if (!look->buf) return SZ_ERROR_MEM;
    LookToRead2_CreateVTable(look, False);
    look->bufSize = kInputBufSize;
    look->realStream = realStream;
    LookToRead2_INIT(look)
    CrcGenerateTable();
    SzArEx_Init(db);
    SRes res = SzArEx_Open(db, &look->vt, &g_alloc, &g_alloc);
    if (res != SZ_OK) {
        SzArEx_Free(db, &g_alloc);
        free(look->buf);
    }
    return res;
}

// A set of split volumes read as one stream. A .7z can be split into
// name.7z.001, .002, ...: each part is a raw slice, so the whole archive is
// their concatenation. The single-file case is just one volume.
struct MultiVolume {
    ISeekInStream vt;      // first, so the interface pointer is this struct
    std::vector<CSzFile> files;
    std::vector<UInt64> sizes;
    UInt64 total;
    UInt64 pos;
};

SRes multi_Read(ISeekInStreamPtr pp, void* buf, size_t* size) {
    MultiVolume* m = (MultiVolume*)pp;
    UInt64 avail = m->total - m->pos;
    size_t want = *size;
    if ((UInt64)want > avail) want = (size_t)avail;
    if (want == 0) { *size = 0; return SZ_OK; }
    // The volume the current position falls in.
    UInt64 base = 0;
    size_t vi = 0;
    for (; vi + 1 < m->files.size(); ++vi) {
        if (m->pos < base + m->sizes[vi]) break;
        base += m->sizes[vi];
    }
    UInt64 localOff = m->pos - base;
    UInt64 volRemain = m->sizes[vi] - localOff;
    size_t n = want;
    if ((UInt64)n > volRemain) n = (size_t)volRemain;  // one read stays in one volume
    Int64 sp = (Int64)localOff;
    if (File_Seek(&m->files[vi], &sp, SZ_SEEK_SET) != 0) return SZ_ERROR_READ;
    size_t got = n;
    if (File_Read(&m->files[vi], buf, &got) != 0) return SZ_ERROR_READ;
    m->pos += got;
    *size = got;
    return SZ_OK;
}

SRes multi_Seek(ISeekInStreamPtr pp, Int64* pos, ESzSeek origin) {
    MultiVolume* m = (MultiVolume*)pp;
    Int64 p = *pos;
    if (origin == SZ_SEEK_CUR) p += (Int64)m->pos;
    else if (origin == SZ_SEEK_END) p += (Int64)m->total;
    if (p < 0 || (UInt64)p > m->total) return SZ_ERROR_PARAM;
    m->pos = (UInt64)p;
    *pos = p;
    return SZ_OK;
}

// Opens every path as a volume in order. Returns the joined stream, or null
// (closing any it opened) if one cannot be opened. Sets *total to the sum.
ISeekInStream* openVolumes(const std::vector<std::string>& paths, MultiVolume* m, UInt64* total) {
    m->vt.Read = multi_Read;
    m->vt.Seek = multi_Seek;
    m->total = 0;
    m->pos = 0;
    for (const auto& path : paths) {
        CSzFile f;
        File_Construct(&f);
        if (InFile_Open(&f, path.c_str()) != 0) {
            for (auto& of : m->files) File_Close(&of);
            m->files.clear();
            return nullptr;
        }
        UInt64 len = 0;
        File_GetLength(&f, &len);
        m->files.push_back(f);
        m->sizes.push_back(len);
        m->total += len;
    }
    if (total) *total = m->total;
    return &m->vt;
}

void closeVolumes(MultiVolume* m) {
    for (auto& f : m->files) File_Close(&f);
    m->files.clear();
}

std::vector<std::string> pathsFrom(JNIEnv* env, jobjectArray jvolumes) {
    std::vector<std::string> out;
    if (!jvolumes) return out;
    jsize n = env->GetArrayLength(jvolumes);
    for (jsize i = 0; i < n; ++i) {
        jstring s = (jstring)env->GetObjectArrayElement(jvolumes, i);
        out.push_back(toUtf8(env, s));
        env->DeleteLocalRef(s);
    }
    return out;
}

std::string nameOf(const CSzArEx& db, UInt32 i, std::vector<UInt16>& scratch) {
    size_t len = SzArEx_GetFileNameUtf16(&db, i, nullptr);
    if (len == 0) return {};
    scratch.resize(len);
    SzArEx_GetFileNameUtf16(&db, i, scratch.data());
    // len includes the terminating zero.
    std::string name = utf16ToUtf8(scratch.data(), len - 1);
    for (auto& c : name) if (c == '\\') c = '/';
    return name;
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_io_github_kgcaudit_reader_archive_SevenZipNative_nativeVersion(JNIEnv*, jclass) {
    return 1;
}

// Lists the archive by calling back sink.entry(name, size, isDir, mtime,
// encrypted, unsupported). Returns 0, or a negative SRes on a header this
// reader cannot parse (an encrypted header among them).
JNIEXPORT jint JNICALL
Java_io_github_kgcaudit_reader_archive_SevenZipNative_nativeList(
        JNIEnv* env, jclass, jobjectArray jvolumes, jobject sink) {
    std::vector<std::string> volumes = pathsFrom(env, jvolumes);
    if (volumes.empty()) return -(jint)SZ_ERROR_INPUT_EOF;

    MultiVolume vols;
    CLookToRead2 look;
    CSzArEx db;
    ISeekInStream* stream = openVolumes(volumes, &vols, nullptr);
    if (stream == nullptr) return -(jint)SZ_ERROR_INPUT_EOF;
    SRes res = openArchive(&look, &db, stream);
    if (res != SZ_OK) { closeVolumes(&vols); return -(jint)res; }

    jclass sinkClass = env->GetObjectClass(sink);
    jmethodID entry = env->GetMethodID(sinkClass, "entry", "(Ljava/lang/String;JZJZZ)V");
    if (entry == nullptr) {
        env->ExceptionClear();
        SzArEx_Free(&db, &g_alloc);
        free(look.buf);
        closeVolumes(&vols);
        return -100;
    }

    std::vector<UInt16> scratch;
    for (UInt32 i = 0; i < db.NumFiles; ++i) {
        bool isDir = SzArEx_IsDir(&db, i);
        std::string name = nameOf(db, i, scratch);
        jlong size = isDir ? 0 : (jlong)SzArEx_GetFileSize(&db, i);
        jlong mtime = SzBitWithVals_Check(&db.MTime, i)
            ? ticksToMillis(db.MTime.Vals[i].Low, db.MTime.Vals[i].High) : 0;
        bool encrypted = false, unsupported = false;
        if (!isDir) classify(db, i, encrypted, unsupported);

        jstring jname = env->NewStringUTF(name.c_str());
        jvalue args[6];
        args[0].l = jname;
        args[1].j = size;
        args[2].z = (jboolean)isDir;
        args[3].j = mtime;
        args[4].z = (jboolean)encrypted;
        args[5].z = (jboolean)unsupported;
        env->CallVoidMethodA(sink, entry, args);
        env->DeleteLocalRef(jname);
    }

    SzArEx_Free(&db, &g_alloc);
    free(look.buf);
    closeVolumes(&vols);
    return 0;
}

// Extracts into destDir. picks null/empty = everything, else the named
// entries and what is under them. Reports and cancels through sink.
// Returns 0 ok, 1 cancelled, 2 wrong/missing password, negative an SRes error.
JNIEXPORT jint JNICALL
Java_io_github_kgcaudit_reader_archive_SevenZipNative_nativeExtract(
        JNIEnv* env, jclass, jobjectArray jvolumes, jstring jdest,
        jobjectArray jpicks, jstring jpassword, jlong jtotalBytes, jboolean jskipExisting, jobject sink) {
    std::vector<std::string> volumes = pathsFrom(env, jvolumes);
    if (volumes.empty()) return -(jint)SZ_ERROR_INPUT_EOF;
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

    jclass sinkClass = env->GetObjectClass(sink);
    jmethodID onProgress = env->GetMethodID(sinkClass, "progress", "(JJLjava/lang/String;)V");
    jmethodID isCancelled = env->GetMethodID(sinkClass, "cancelled", "()Z");
    if (onProgress == nullptr || isCancelled == nullptr) { env->ExceptionClear(); return -100; }

    MultiVolume vols;
    CLookToRead2 look;
    CSzArEx db;

    // The counting stream sits between the buffered reader and the volumes,
    // so the decode's reads move the bar and can be stopped. It is off while
    // the header is parsed -- those reads are not the work being watched.
    CountingStream cs;
    memset(&cs, 0, sizeof(cs));
    cs.vt.Read = counting_Read;
    cs.vt.Seek = counting_Seek;
    cs.env = env;
    cs.sink = sink;
    cs.onProgress = onProgress;
    cs.isCancelled = isCancelled;
    cs.totalUnpacked = jtotalBytes;
    cs.active = false;
    cs.cancelled = false;

    // Open the volumes first so the counting stream can point at them, then
    // open the archive through the counting stream. inner must be set before
    // the header parse, which already reads through the counting stream. The
    // compressed total is the sum of the volumes, for scaling the bar.
    UInt64 packedTotal = 0;
    cs.inner = openVolumes(volumes, &vols, &packedTotal);
    if (cs.inner == nullptr) return -(jint)SZ_ERROR_INPUT_EOF;
    cs.totalPacked = packedTotal;
    SRes res = openArchive(&look, &db, &cs.vt);
    if (res != SZ_OK) { closeVolumes(&vols); return -(jint)res; }

    mkdir(dest.c_str(), 0755);

    // The password for any encrypted folders, as UTF-16LE. Empty when none
    // was given; an encrypted folder then fails and the caller re-asks.
    std::vector<Byte> pw;
    if (jpassword) pw = toUtf16le(toUtf8(env, jpassword));
    SevenZ_SetPassword(pw.empty() ? (const Byte*)"" : pw.data(), pw.size());

    // The folder cache: a 7z folder holds several files together, so
    // decoding it once and keeping the buffer means the files in it are
    // copied out without decoding it again. Must start as shown.
    UInt32 blockIndex = 0xFFFFFFFF;
    Byte* outBuffer = nullptr;
    size_t outBufferSize = 0;

    jint outcome = 0;
    std::vector<UInt16> scratch;
    cs.active = true;

    for (UInt32 i = 0; i < db.NumFiles; ++i) {
        if (env->CallBooleanMethod(sink, isCancelled)) { outcome = 1; break; }

        std::string name = nameOf(db, i, scratch);
        std::string full = dest + "/" + name;
        if (SzArEx_IsDir(&db, i)) {
            makeDirs(full + "/");
            continue;
        }
        if (!wantsEntry(name, picks)) continue;
        if (jskipExisting) {
            struct stat sb;
            if (stat(full.c_str(), &sb) == 0) continue;
        }
        makeDirs(full);

        // What the progress line names while this file's folder decodes.
        cs.name = &name;
        size_t offset = 0, outSizeProcessed = 0;
        SRes r = SzArEx_Extract(&db, &look.vt, i, &blockIndex,
            &outBuffer, &outBufferSize, &offset, &outSizeProcessed, &g_alloc, &g_alloc);
        if (r != SZ_OK) {
            if (cs.cancelled) { outcome = 1; break; }
            // An encrypted folder that would not decode is a wrong or missing
            // password: stop and let the caller re-ask, since one password
            // covers the whole archive. Other failures are a bad entry --
            // reported at the end, the rest still extracted.
            bool enc = false, uns = false;
            classify(db, i, enc, uns);
            if (enc && (r == kSevenZAesError || r == SZ_ERROR_CRC || r == SZ_ERROR_DATA)) {
                outcome = 2;
                break;
            }
            outcome = -(jint)r;
            continue;
        }

        FILE* fp = fopen(full.c_str(), "wb");
        if (!fp) { outcome = -(jint)SZ_ERROR_WRITE; continue; }
        if (outSizeProcessed > 0) {
            fwrite(outBuffer + offset, 1, outSizeProcessed, fp);
        }
        fclose(fp);
    }

    SevenZ_SetPassword((const Byte*)"", 0);
    ISzAlloc_Free(&g_alloc, outBuffer);
    SzArEx_Free(&db, &g_alloc);
    free(look.buf);
    closeVolumes(&vols);
    return outcome;
}

} // extern "C"
