# Room·Compose·코루틴은 자체 consumer 규칙을 싣고 온다. 여기에는 우리 코드만 적는다.
# 지금은 리플렉션을 쓰지 않으므로 비어 있다(가벼움 규칙 6).

# FTP 목록 해석기(commons-net). 서버가 알려 준 시스템 이름으로 해석기를 이름으로 찾아 만드는 길이 있다 — 줄이면 낯선 NAS 의
# LIST 답을 못 읽어 "함께 읽는 기기" 가 비어 보인다.
-keep class org.apache.commons.net.ftp.parser.** { *; }
-dontwarn org.apache.commons.net.**

# SFTP(JSch). 암호 · 키 교환 · 압축 방식을 설정 이름의 클래스 이름으로 찾아 만든다 — 줄이면 릴리스에서만 SFTP 가 "알고리즘
# 없음" 으로 잇지 못한다(시험은 줄이지 않은 판으로 돌아 못 잡는다).
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.slf4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.ietf.jgss.**
-dontwarn com.sun.jna.**
-dontwarn org.newsclub.net.unix.**
-dontwarn org.bouncycastle.**
