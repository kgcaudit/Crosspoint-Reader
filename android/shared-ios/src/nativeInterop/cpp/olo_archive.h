// OLO eBook iOS: RAR · 7z 해제기(안드로이드 :archive 의 UnRAR · LZMA SDK 원본 그대로)를 Kotlin/Native 에서 부르는 C 다리.
// 안드로이드 rar_jni.cpp · sevenz_jni.cpp 와 같은 규칙(이름 정리 · 건너뛰기 · 결과 값)을 따르고, iOS 만화에 쓰지 않는 진행 표시 ·
// 취소 · 암호는 뺐다.
#ifndef OLO_ARCHIVE_H
#define OLO_ARCHIVE_H

#ifdef __cplusplus
extern "C" {
#endif

/** 목록의 항목 하나. 이름은 UTF-8, 나눔표는 '/'. */
typedef void (*olo_entry_fn)(void* ctx, const char* name, long long size, int is_dir, int encrypted);

/** 끝까지 풀었다. */
#define OLO_UNPACKED 0
/** 끝까지 갔지만 풀 수 없는 항목(암호 · 깨짐 · 위험한 이름)을 건너뛰었다 — 다시 풀어도 같으니 끝난 것이다. */
#define OLO_SKIPPED_SOME 3

/** 목록. 0, 또는 머리를 읽지 못하면 음수. */
int olo_rar_list(const char* path, olo_entry_fn fn, void* ctx);
int olo_7z_list(const char* path, olo_entry_fn fn, void* ctx);

/**
 * [dest] 아래에 푼다. [picks] 가 비면(개수 0) 전부, 아니면 그 항목과 그 아래. OLO_UNPACKED · OLO_SKIPPED_SOME, 또는 음수(못 읽음 ·
 * 못 씀 · 메모리 — 도중에 멈췄고 다음에는 다를 수 있다).
 */
int olo_rar_extract(const char* path, const char* dest, const char* const* picks, int pick_count);
int olo_7z_extract(const char* path, const char* dest, const char* const* picks, int pick_count);

#ifdef __cplusplus
}
#endif

#endif
