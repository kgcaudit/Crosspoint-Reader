"""시험용 cb7 견본을 만든다(0.37.0). 사용자 파일이 아니라 단색 그림 셋. 다시 만들 때: python3 make_cb7.py (py7zr 필요).

- comic.cb7: 001.png · 002.png · 003.png(빨강 · 초록 · 파랑 60×90) + ComicInfo.xml, LZMA2 · 통짜(solid).
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
