"""시험용 cb7 견본을 만든다(0.37.0). 사용자 파일이 아니라 단색 그림 셋. 다시 만들 때: python3 make_cb7.py (py7zr 필요).

- comic.cb7: 001.png · 002.png · 003.png(빨강 · 초록 · 파랑 60×90) + ComicInfo.xml, LZMA2 · 통짜(solid).
- unsafe.cb7: 001.png · ../evil.png · 002.png. py7zr 은 ".." 이름을 쓰지 않으려 해서, 같은 길이의 "xx/evil.png" 로 쓰고 압축하지
  않은 머리에서 이름을 바꾼 뒤 머리 CRC 둘을 다시 적는다. 풀기가 위험한 이름 하나를 건너뛰고 나머지를 푸는지 본다.
"""
import io, struct, zlib, py7zr

def png(w, h, rgb):
    raw = b"".join(b"\x00" + bytes(rgb) * w for _ in range(h))
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")

files = {
    "001.png": png(60, 90, (208, 48, 48)),
    "002.png": png(60, 90, (48, 160, 80)),
    "003.png": png(60, 90, (48, 80, 208)),
    "ComicInfo.xml": "<ComicInfo><Series>칠지</Series><Number>2</Number><Manga>YesAndRightToLeft</Manga></ComicInfo>".encode(),
}
with py7zr.SevenZipFile("comic.cb7", "w") as z:
    for name, data in files.items():
        z.writestr(data, name)

# unsafe.cb7 — 위의 설명대로 이름 하나를 ".." 로 바꾼다.
import zlib
with py7zr.SevenZipFile("unsafe.cb7", "w") as z:
    z.encoded_header_mode = False
    z.writestr(b"one", "001.png")
    z.writestr(b"evil", "xx/evil.png")
    z.writestr(b"two", "002.png")
b = bytearray(open("unsafe.cb7", "rb").read())
old, new = "xx/evil.png".encode("utf-16-le"), "../evil.png".encode("utf-16-le")
at = b.find(old)
b[at:at + len(old)] = new
offset, size = struct.unpack_from("<QQ", b, 12)
struct.pack_into("<I", b, 28, zlib.crc32(bytes(b[32 + offset:32 + offset + size])) & 0xFFFFFFFF)
struct.pack_into("<I", b, 8, zlib.crc32(bytes(b[12:32])) & 0xFFFFFFFF)
open("unsafe.cb7", "wb").write(b)
