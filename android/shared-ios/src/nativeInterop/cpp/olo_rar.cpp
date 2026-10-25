// UnRAR 다리(iOS). 안드로이드 archive/src/main/cpp/rar_jni.cpp 의 목록 · 풀기를 JNI 없이 옮겼다 — 규칙은 그쪽 주석이 원본이다.
// UnRAR 원본(Alexander Roshal, unrar/license.txt)은 RAR 을 다루는 데 무료로 쓸 수 있고, RAR 을 만드는 데는 쓸 수 없다. 여기는
// 목록과 풀기뿐이다.
#include "olo_archive.h"
#include <clocale>
#include <cstring>
#include <sys/stat.h>
#include <string>
#include <vector>
#include "unrar/rar.hpp"

namespace {

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

std::string wideToUtf8(const wchar_t* w) {
    std::string out;
    if (!w) return out;
    for (const wchar_t* p = w; *p; ++p) {
        unsigned int c = (unsigned int)*p;
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

// 여러 권으로 나뉜 rar 은 다음 권이 있을 때만 이어 간다 — 없는데 물으면 멈춘다.
bool nextVolumeExists(UINT msg, LPARAM p1) {
    std::string path = (msg == UCM_CHANGEVOLUMEW) ? wideToUtf8((wchar_t*)p1) : std::string((const char*)p1);
    struct stat sb;
    return stat(path.c_str(), &sb) == 0;
}

int CALLBACK callback(UINT msg, LPARAM, LPARAM p1, LPARAM p2) {
    switch (msg) {
        case UCM_NEEDPASSWORD:
        case UCM_NEEDPASSWORDW:
            return -1;  // 암호는 묻지 않는다 — 그 항목은 건너뛴다
        case UCM_CHANGEVOLUME:
        case UCM_CHANGEVOLUMEW:
            if (p2 == RAR_VOL_ASK) return nextVolumeExists(msg, p1) ? 0 : -1;
            return 0;
        default:
            return 0;
    }
}

bool deviceFault(int code) {
    return code == ERAR_EOPEN || code == ERAR_EREAD || code == ERAR_ECREATE || code == ERAR_EWRITE ||
        code == ERAR_ECLOSE || code == ERAR_NO_MEMORY;
}

// unrar 의 오류는 남는다: 깨진 항목 하나 뒤로는 머리를 잘 읽어도 같은 오류를 돌려준다 — 결과 값만 보면 깨진 쪽 뒤가 모두
// 빈 쪽이 됐다(안드로이드에서 찾음). 이름이 채워졌으면 머리는 읽은 것이다.
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

void utf8Locale() {
    // unrar 가 풀 곳의 경로를 UTF-8 로 만들게 — 없으면 한글 폴더 이름이 깨진다.
    static bool done = false;
    if (!done) { setlocale(LC_CTYPE, "en_US.UTF-8"); done = true; }
}

} // namespace

extern "C" int olo_rar_list(const char* path, olo_entry_fn fn, void* ctx) {
    utf8Locale();
    RAROpenArchiveDataEx open;
    memset(&open, 0, sizeof(open));
    open.ArcName = (char*)path;
    open.OpenMode = RAR_OM_LIST;
    open.Callback = callback;
    HANDLE h = RAROpenArchiveEx(&open);
    if (open.OpenResult != ERAR_SUCCESS) {
        if (h) RARCloseArchive(h);
        return -(int)open.OpenResult;
    }
    RARHeaderDataEx header;
    int result;
    // 머리는 읽을 때마다 비운다 — 주석 · 링크 항목이면 unrar 가 쓰레기 포인터에 써서 죽었다(안드로이드에서 찾음).
    for (memset(&header, 0, sizeof(header)); (result = RARReadHeaderEx(h, &header)) == ERAR_SUCCESS; memset(&header, 0, sizeof(header))) {
        // RAR 5 의 이름은 FileName 에 UTF-8 그대로. FileNameW 는 로캘을 거쳐 깨질 수 있다.
        std::string name = header.FileName;
        for (auto& c : name) if (c == '\\') c = '/';
        long long size = ((long long)header.UnpSizeHigh << 32) | (unsigned int)header.UnpSize;
        fn(ctx, name.c_str(), size, (header.Flags & RHDF_DIRECTORY) != 0, (header.Flags & RHDF_ENCRYPTED) != 0);
        if (RARProcessFile(h, RAR_SKIP, nullptr, nullptr) != ERAR_SUCCESS) break;
    }
    RARCloseArchive(h);
    return (result == ERAR_END_ARCHIVE || result == ERAR_SUCCESS) ? 0 : -result;
}

extern "C" int olo_rar_extract(const char* path, const char* dest, const char* const* picks, int pick_count) {
    utf8Locale();
    std::vector<std::string> wanted;
    for (int i = 0; i < pick_count; ++i) wanted.push_back(picks[i]);

    RAROpenArchiveDataEx open;
    memset(&open, 0, sizeof(open));
    open.ArcName = (char*)path;
    open.OpenMode = RAR_OM_EXTRACT;
    open.Callback = callback;
    HANDLE h = RAROpenArchiveEx(&open);
    if (open.OpenResult != ERAR_SUCCESS) {
        if (h) RARCloseArchive(h);
        return -(int)open.OpenResult;
    }
    std::wstring wdest = utf8ToWide(dest);
    RARHeaderDataEx header;
    int result;
    int outcome = OLO_UNPACKED;
    bool skipped = false;
    for (memset(&header, 0, sizeof(header)); headerRead(result = RARReadHeaderEx(h, &header), header); memset(&header, 0, sizeof(header))) {
        std::string name = header.FileName;
        for (auto& c : name) if (c == '\\') c = '/';
        bool take = !(header.Flags & RHDF_DIRECTORY) && wantsEntry(name, wanted);
        if (take && (header.Flags & RHDF_ENCRYPTED)) { take = false; skipped = true; }
        int pr = RARProcessFileW(h, take ? RAR_EXTRACT : RAR_SKIP, (wchar_t*)wdest.c_str(), nullptr);
        if (pr != ERAR_SUCCESS) {
            if (deviceFault(pr)) { outcome = -pr; break; }
            skipped = true;  // 그 항목의 손상 — 건너뛰고 나머지를 푼다
        }
    }
    RARCloseArchive(h);
    // 머리가 중간에 깨졌으면 나머지는 본 적도 없는 것이다 — 건너뛴 것이 아니다.
    if (outcome == OLO_UNPACKED && result != ERAR_END_ARCHIVE && !headerRead(result, header)) outcome = -result;
    if (outcome == OLO_UNPACKED && skipped) outcome = OLO_SKIPPED_SOME;
    return outcome;
}
