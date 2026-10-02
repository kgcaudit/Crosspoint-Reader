# C++(JNI)가 이름으로 찾는 것들. 릴리스 빌드의 코드 줄이기(R8)가 이름을 바꾸거나 지우면 휴대폰에서만 RAR · 7z 가
# "해제기 없음" 이 되거나(함수를 못 찾아 UnsatisfiedLinkError) 목록이 비어 나온다 — PC 시험은 줄이지 않은 코드로 돈다.
# - Java_io_github_kgcaudit_reader_archive_RarNative_* / SevenZipNative_* : 클래스 이름과 external 함수.
# - Sink 의 entry · progress · cancelled : C++ 가 GetMethodID 로 이름 · 모양을 찾아 부른다.
-keep class io.github.kgcaudit.reader.archive.RarNative { *; }
-keep class io.github.kgcaudit.reader.archive.SevenZipNative { *; }
-keep interface io.github.kgcaudit.reader.archive.RarNative$Sink { *; }
-keep interface io.github.kgcaudit.reader.archive.SevenZipNative$Sink { *; }
-keepclassmembers class * implements io.github.kgcaudit.reader.archive.RarNative$Sink { *; }
-keepclassmembers class * implements io.github.kgcaudit.reader.archive.SevenZipNative$Sink { *; }
