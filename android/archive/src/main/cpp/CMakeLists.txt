cmake_minimum_required(VERSION 3.22.1)
project(olorar)

# OLO eBook: OLO Explorer(kgcaudit/Filezilla-Client app/src/main/cpp)에서 그대로 가져왔다. 바꾼 것은 JNI 함수 이름
# (우리 패키지)과 아래 PC 빌드 갈래뿐이다. PC(리눅스 · JVM) 빌드는 시험용이다 — 같은 원본으로 PC 시험에서 실제 RAR · 7z 를
# 풀어 본다(Explorer 는 기기에서만 확인했다).
if(NOT ANDROID)
    find_package(JNI REQUIRED)
    include_directories(${JNI_INCLUDE_DIRS})
endif()

# UnRAR, built from Alexander Roshal's free UnRAR source (license.txt in
# unrar/). RAR 5.0 cannot be read in Kotlin at any sane effort -- its LZ,
# PPMd and filter VM are the reference decoder's alone -- so the reference
# decoder is compiled here and driven over its own DLL C API. Only the
# files the upstream makefile's library target lists are compiled; several
# .cpp are #included by others, so globbing would double their symbols.
set(UNRAR_SOURCES
    unrar/rar.cpp unrar/strlist.cpp unrar/strfn.cpp unrar/pathfn.cpp
    unrar/smallfn.cpp unrar/global.cpp unrar/file.cpp unrar/filefn.cpp
    unrar/filcreat.cpp unrar/archive.cpp unrar/arcread.cpp unrar/unicode.cpp
    unrar/system.cpp unrar/crypt.cpp unrar/crc.cpp unrar/rawread.cpp
    unrar/encname.cpp unrar/resource.cpp unrar/match.cpp unrar/timefn.cpp
    unrar/rdwrfn.cpp unrar/consio.cpp unrar/options.cpp unrar/errhnd.cpp
    unrar/rarvm.cpp unrar/secpassword.cpp unrar/rijndael.cpp unrar/getbits.cpp
    unrar/sha1.cpp unrar/sha256.cpp unrar/blake2s.cpp unrar/hash.cpp
    unrar/extinfo.cpp unrar/extract.cpp unrar/volume.cpp unrar/list.cpp
    unrar/find.cpp unrar/unpack.cpp unrar/headers.cpp unrar/threadpool.cpp
    unrar/rs16.cpp unrar/cmddata.cpp unrar/ui.cpp unrar/largepage.cpp
    unrar/filestr.cpp unrar/scantree.cpp unrar/dll.cpp unrar/qopen.cpp
)

add_library(olorar SHARED rar_jni.cpp ${UNRAR_SOURCES})

target_compile_definitions(olorar PRIVATE
    RARDLL _FILE_OFFSET_BITS=64 _LARGEFILE_SOURCE
)

# The upstream code is warning-heavy by modern clang and builds with these
# off in its own makefile; treat it as the third-party code it is.
target_compile_options(olorar PRIVATE
    -O2 -fexceptions -w
)

set_target_properties(olorar PROPERTIES CXX_STANDARD 14 CXX_STANDARD_REQUIRED ON)

if(ANDROID)
    find_library(log-lib log)
    target_link_libraries(olorar ${log-lib})
endif()

# 7z, built from Igor Pavlov's public-domain LZMA SDK (lzma-sdk-license.txt
# in sevenz/). Only the reference 7z-extraction subset is vendored -- the
# same files the SDK's own 7zDec sample links -- so the decoder is 7-Zip's
# own code rather than a Kotlin port of LZMA2, PPMd and the branch filters,
# where a mistake would be silent corruption. It reads only; nothing here
# writes a 7z. The upstream makefile's 7zdec object list, minus its CLI
# (7zMain) and the x86 assembly (LzmaDec.c has a C path). AES is not in the
# reference decoder, so an encrypted 7z lists but is marked unreadable.
set(SEVENZ_SOURCES
    sevenz/Bcj2.c sevenz/Bra.c sevenz/Bra86.c sevenz/BraIA64.c
    sevenz/CpuArch.c sevenz/Delta.c sevenz/Lzma2Dec.c sevenz/LzmaDec.c
    sevenz/Ppmd7.c sevenz/Ppmd7Dec.c sevenz/7zCrc.c sevenz/7zCrcOpt.c
    sevenz/7zAlloc.c sevenz/7zArcIn.c sevenz/7zBuf.c sevenz/7zBuf2.c
    sevenz/7zDec.c sevenz/7zFile.c sevenz/7zStream.c
    # AES-256 + SHA-256, for reading password-protected 7z. The *Opt files
    # carry the CPU-accelerated paths; both compile to the plain C path when
    # the build has no crypto extensions enabled, which is this one.
    sevenz/Aes.c sevenz/AesOpt.c sevenz/Sha256.c sevenz/Sha256Opt.c
)

add_library(olo7z SHARED sevenz_jni.cpp ${SEVENZ_SOURCES})

# Z7_PPMD_SUPPORT turns on the PPMd coder, the one method the sample gates
# off by default; the branch filters and LZMA/LZMA2 are on without a flag.
target_compile_definitions(olo7z PRIVATE Z7_PPMD_SUPPORT)

target_compile_options(olo7z PRIVATE -O2 -w)

if(ANDROID)
    target_link_libraries(olo7z ${log-lib})
endif()
