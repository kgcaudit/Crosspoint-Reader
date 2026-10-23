# Room·Compose·코루틴은 자체 consumer 규칙을 싣고 온다. 여기에는 우리 코드만 적는다.
# 지금은 리플렉션을 쓰지 않으므로 비어 있다(가벼움 규칙 6).

# FTP 목록 해석기(commons-net). 서버가 알려 준 시스템 이름으로 해석기를 이름으로 찾아 만드는 길이 있다 — 줄이면 낯선 NAS 의
# LIST 답을 못 읽어 "함께 읽는 기기" 가 비어 보인다.
-keep class org.apache.commons.net.ftp.parser.** { *; }
-dontwarn org.apache.commons.net.**
