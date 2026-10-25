// 7z 다리(iOS). 안드로이드 archive/src/main/cpp/sevenz_jni.cpp 의 목록 · 풀기를 JNI 없이 옮겼다 — 규칙은 그쪽 주석이 원본이다.
// LZMA SDK(Igor Pavlov, 공개 영역)의 7z 풀기 부분만 쓴다. 7z 를 만드는 코드는 없다.
#include "olo_archive.h"
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include <sys/stat.h>

#include "sevenz/7z.h"
#include "sevenz/7zCrc.h"
#include "sevenz/7zFile.h"

// 안드로이드가 7zDec.c 에 넣은 암호 입구. 여기서는 늘 빈 암호 — 암호 걸린 쪽은 건너뛴다.
extern "C" void SevenZ_SetPassword(const Byte* utf16le, size_t len);

namespace {

const UInt32 K_AES = 0x06F10701;
const size_t kInputBufSize = (size_t)1 << 18;
// 한 번에 메모리로 푸는 통짜 묶음의 한도. 7z 는 묶음을 버퍼 하나에 통째로 푼다 — 몇 GB 묶음이면 시스템이 앱을 끈다.
const UInt64 kMaxFolderBytes = 768ull * 1024 * 1024;

void* szAlloc(ISzAllocPtr, size_t size) { return malloc(size); }
void szFree(ISzAllocPtr, void* address) { free(address); }
const ISzAlloc g_alloc = { szAlloc, szFree };

std::string utf16ToUtf8(const UInt16* w, size_t len) {
    std::string out;
    for (size_t i = 0; i < len; ++i) {
        unsigned int c = w[i];
        if (c >= 0xD800 && c <= 0xDBFF && i + 1 < len) {
            unsigned int lo = w[i + 1];
            if (lo >= 0xDC00 && lo <= 0xDFFF) { c = 0x10000 + ((c - 0xD800) << 10) + (lo - 0xDC00); ++i; }
        }
        if (c < 0x80) out += (char)c;
        else if (c < 0x800) { out += (char)(0xC0 | (c >> 6)); out += (char)(0x80 | (c & 0x3F)); }
        else if (c < 0x10000) { out += (char)(0xE0 | (c >> 12)); out += (char)(0x80 | ((c >> 6) & 0x3F)); out += (char)(0x80 | (c & 0x3F)); }
        else {
            out += (char)(0xF0 | (c >> 18)); out += (char)(0x80 | ((c >> 12) & 0x3F));
            out += (char)(0x80 | ((c >> 6) & 0x3F)); out += (char)(0x80 | (c & 0x3F));
        }
    }
    return out;
}

bool isSupportedMethod(UInt32 id) {
    switch (id) {
        case 0x0: case 0x3: case 0x21: case 0x30101: case 0x30401: case 0x3030103: case 0x3030205:
        case 0x3030401: case 0x3030501: case 0x3030701: case 0x3030805: case 0x303011B: case 0xa: case 0xb:
            return true;
        default:
            return false;
    }
}

void classify(const CSzArEx& db, UInt32 fileIndex, bool& encrypted, bool& unsupported) {
    encrypted = false;
    unsupported = false;
    UInt32 fo = db.FileToFolder[fileIndex];
    if (fo >= db.db.NumFolders) return;
    CSzData sd;
    sd.Data = db.db.CodersData + db.db.FoCodersOffsets[fo];
    sd.Size = db.db.FoCodersOffsets[fo + 1] - db.db.FoCodersOffsets[fo];
    CSzFolder folder;
    if (SzGetNextFolderItem(&folder, &sd) != SZ_OK) { unsupported = true; return; }
    for (UInt32 c = 0; c < folder.NumCoders; ++c) {
        UInt32 id = folder.Coders[c].MethodID;
        if (id == K_AES) encrypted = true;
        else if (!isSupportedMethod(id)) unsupported = true;
    }
}

void makeDirs(const std::string& path) {
    for (size_t i = 1; i < path.size(); ++i) {
        if (path[i] != '/') continue;
        mkdir(path.substr(0, i).c_str(), 0755);
    }
}

// 절대 경로 · ".." 마디가 든 이름은 풀 곳 밖에 쓰인다 — 일부러 만든 cb7 이 앱의 기록을 덮어쓸 수 있다("zip slip").
bool safeName(const std::string& name) {
    if (name.empty() || name[0] == '/') return false;
    size_t start = 0;
    while (start <= name.size()) {
        size_t end = name.find('/', start);
        if (end == std::string::npos) end = name.size();
        if (end - start == 2 && name.compare(start, 2, "..") == 0) return false;
        start = end + 1;
    }
    return true;
}

bool wantsEntry(const std::string& name, const std::vector<std::string>& picks) {
    if (picks.empty()) return true;
    for (const auto& p : picks) {
        if (name == p) return true;
        if (name.size() > p.size() && name.compare(0, p.size(), p) == 0 && name[p.size()] == '/') return true;
    }
    return false;
}

bool entryFault(SRes r) { return r != SZ_ERROR_READ && r != SZ_ERROR_MEM && r != SZ_ERROR_PROGRESS; }
bool nameFault(int err) { return err == ENAMETOOLONG || err == EISDIR || err == ENOTDIR || err == EINVAL || err == EILSEQ; }

std::string nameOf(const CSzArEx& db, UInt32 i, std::vector<UInt16>& scratch) {
    size_t len = SzArEx_GetFileNameUtf16(&db, i, nullptr);
    if (len == 0) return {};
    scratch.resize(len);
    SzArEx_GetFileNameUtf16(&db, i, scratch.data());
    std::string name = utf16ToUtf8(scratch.data(), len - 1);  // len 은 끝의 0 까지
    for (auto& c : name) if (c == '\\') c = '/';
    return name;
}

// 열린 7z 하나: 파일 · 읽기 버퍼 · 목록. 닫을 때 셋 다 놓는다.
struct Opened {
    CFileInStream file;
    CLookToRead2 look;
    CSzArEx db;
    bool ok = false;

    explicit Opened(const char* path) {
        FileInStream_CreateVTable(&file);
        File_Construct(&file.file);
        look.buf = nullptr;
        if (InFile_Open(&file.file, path) != 0) return;
        look.buf = (Byte*)malloc(kInputBufSize);
        if (!look.buf) { File_Close(&file.file); return; }
        LookToRead2_CreateVTable(&look, False);
        look.bufSize = kInputBufSize;
        look.realStream = &file.vt;
        LookToRead2_INIT(&look)
        CrcGenerateTable();
        SzArEx_Init(&db);
        if (SzArEx_Open(&db, &look.vt, &g_alloc, &g_alloc) != SZ_OK) {
            SzArEx_Free(&db, &g_alloc);
            free(look.buf);
            look.buf = nullptr;
            File_Close(&file.file);
            return;
        }
        ok = true;
    }

    ~Opened() {
        if (!ok) return;
        SzArEx_Free(&db, &g_alloc);
        free(look.buf);
        File_Close(&file.file);
    }
};

} // namespace

extern "C" int olo_7z_list(const char* path, olo_entry_fn fn, void* ctx) {
    Opened a(path);
    if (!a.ok) return -(int)SZ_ERROR_ARCHIVE;
    std::vector<UInt16> scratch;
    for (UInt32 i = 0; i < a.db.NumFiles; ++i) {
        bool isDir = SzArEx_IsDir(&a.db, i);
        std::string name = nameOf(a.db, i, scratch);
        long long size = isDir ? 0 : (long long)SzArEx_GetFileSize(&a.db, i);
        bool encrypted = false, unsupported = false;
        if (!isDir) classify(a.db, i, encrypted, unsupported);
        // 풀 수 없는 방식은 암호와 같이 다룬다 — 목록에는 있되 쪽으로 쓰지 않는다.
        fn(ctx, name.c_str(), size, isDir, encrypted || unsupported);
    }
    return 0;
}

extern "C" int olo_7z_extract(const char* path, const char* dest, const char* const* picks, int pick_count) {
    std::vector<std::string> wanted;
    for (int i = 0; i < pick_count; ++i) wanted.push_back(picks[i]);
    Opened a(path);
    if (!a.ok) return -(int)SZ_ERROR_ARCHIVE;
    std::string root = dest;
    mkdir(root.c_str(), 0755);
    SevenZ_SetPassword((const Byte*)"", 0);

    // 묶음 하나에 여러 파일이 든다 — 한 번 풀어 둔 버퍼에서 같은 묶음의 파일들을 꺼낸다.
    UInt32 blockIndex = 0xFFFFFFFF;
    Byte* outBuffer = nullptr;
    size_t outBufferSize = 0;
    int outcome = OLO_UNPACKED;
    bool skipped = false;
    std::vector<UInt16> scratch;
    for (UInt32 i = 0; i < a.db.NumFiles; ++i) {
        std::string name = nameOf(a.db, i, scratch);
        if (!safeName(name)) { skipped = true; continue; }
        std::string full = root + "/" + name;
        if (SzArEx_IsDir(&a.db, i)) { makeDirs(full + "/"); continue; }
        if (!wantsEntry(name, wanted)) continue;
        bool enc = false, uns = false;
        classify(a.db, i, enc, uns);
        if (enc || uns) { skipped = true; continue; }
        UInt32 folder = a.db.FileToFolder[i];
        if (folder != (UInt32)-1 && SzAr_GetFolderUnpackSize(&a.db.db, folder) > kMaxFolderBytes) { skipped = true; continue; }
        makeDirs(full);
        size_t offset = 0, processed = 0;
        SRes r = SzArEx_Extract(&a.db, &a.look.vt, i, &blockIndex, &outBuffer, &outBufferSize, &offset, &processed, &g_alloc, &g_alloc);
        if (r != SZ_OK) {
            if (!entryFault(r)) { outcome = -(int)r; break; }
            skipped = true;
            continue;
        }
        FILE* fp = fopen(full.c_str(), "wb");
        if (!fp) {
            if (nameFault(errno)) { skipped = true; continue; }
            outcome = -(int)SZ_ERROR_WRITE;
            break;
        }
        // 다 쓰지 못한 파일(공간 부족)은 지운다 — 잘린 쪽이 온전한 쪽처럼 보이지 않게.
        bool ok = processed == 0 || fwrite(outBuffer + offset, 1, processed, fp) == processed;
        if (fclose(fp) != 0) ok = false;
        if (!ok) { remove(full.c_str()); outcome = -(int)SZ_ERROR_WRITE; break; }
    }
    ISzAlloc_Free(&g_alloc, outBuffer);
    if (outcome == OLO_UNPACKED && skipped) outcome = OLO_SKIPPED_SOME;
    return outcome;
}
