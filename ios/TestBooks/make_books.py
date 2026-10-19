"""시뮬레이터에서 서재 · 리더를 확인할 시험 책을 짓는다(글은 모두 이 저장소에서 쓴 것). 표준 라이브러리만 쓴다.

    python3 -I make_books.py <내보낼 폴더>

- 비 오는 아침.epub : 세 장, 표지 그림 있음(표지 그림 경로 시험)
- 바다로 가는 길.epub : 두 장, 표지 없음(이름 표지로 내려가는지)
- 짧은 메모.txt : UTF-8, 한 쪽짜리(첫 쪽이 곧 끝 쪽)
등대지기의 저녁.txt(CP949 · CRLF)는 손으로 둔 파일이다.
"""
import struct
import sys
import zlib
import zipfile
from pathlib import Path


def png(width, height, pixel):
    rows = b"".join(b"\x00" + b"".join(bytes(pixel(x, y)) for x in range(width)) for y in range(height))

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)) + \
        chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b"")


def cover(x, y):
    # 클레이 바탕에 수평선 몇 줄 — 비 내리는 창문.
    if 200 <= y <= 204 or 260 <= y <= 262 or (x % 37 == 0 and y % 9 < 5):
        return (247, 244, 239)
    return (185, 91, 59)


PARAGRAPHS = {
    "rain": [
        "아침부터 비가 내렸다. 우산을 쓰고 정류장까지 걷는 동안 신발 앞코가 젖었다.",
        "버스 안은 습기로 유리창이 뿌옇게 흐려져 있었고, 사람들은 저마다 휴대폰을 들여다보고 있었다.",
        "나는 가방에서 어젯밤의 책을 꺼내 몇 쪽을 더 읽었다. 창밖의 빗소리가 책장 넘기는 소리와 섞였다.",
        "The rain kept falling, and the bus moved slowly through the grey streets of the old town.",
    ],
    "sea": [
        "바다로 가는 길은 생각보다 멀었다. 기차는 두 번 갈아타야 했고, 마지막 역에서는 한참을 걸어야 했다.",
        "모래 언덕을 넘자 갑자기 바람이 짜졌다. 멀리 하얀 등대가 보였다.",
        "등대 아래에는 작은 찻집이 있었다. 주인은 말없이 따뜻한 보리차를 내주었다.",
    ],
}


def chapter(title, kind, count):
    body = "".join(f"<p>{PARAGRAPHS[kind][i % len(PARAGRAPHS[kind])]}</p>" for i in range(count))
    return (
        '<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml">'
        f"<head><title>{title}</title></head><body><h1>{title}</h1>{body}</body></html>"
    )


def epub(path, title, chapters, cover_png=None):
    manifest = "".join(
        f'<item id="c{i}" href="c{i}.xhtml" media-type="application/xhtml+xml"/>' for i in range(len(chapters))
    )
    spine = "".join(f'<itemref idref="c{i}"/>' for i in range(len(chapters)))
    meta_cover = ""
    if cover_png:
        manifest += '<item id="cover" href="cover.png" media-type="image/png" properties="cover-image"/>'
        meta_cover = '<meta name="cover" content="cover"/>'
    nav_items = "".join(f'<li><a href="c{i}.xhtml">{t}</a></li>' for i, (t, _, _) in enumerate(chapters))
    manifest += '<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>'
    opf = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">'
        '<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">'
        f"<dc:title>{title}</dc:title><dc:creator>OLO 시험 책</dc:creator><dc:language>ko</dc:language>"
        f'<dc:identifier id="id">olo-test-{abs(hash(title)) % 10**8}</dc:identifier>{meta_cover}</metadata>'
        f"<manifest>{manifest}</manifest><spine>{spine}</spine></package>"
    )
    nav = (
        '<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" '
        'xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol>'
        f"{nav_items}</ol></nav></body></html>"
    )
    with zipfile.ZipFile(path, "w") as z:
        z.writestr(zipfile.ZipInfo("mimetype"), "application/epub+zip", compress_type=zipfile.ZIP_STORED)
        z.writestr(
            "META-INF/container.xml",
            '<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">'
            '<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>'
            "</rootfiles></container>",
            compress_type=zipfile.ZIP_DEFLATED,
        )
        z.writestr("OEBPS/content.opf", opf, compress_type=zipfile.ZIP_DEFLATED)
        z.writestr("OEBPS/nav.xhtml", nav, compress_type=zipfile.ZIP_DEFLATED)
        for i, (t, kind, count) in enumerate(chapters):
            z.writestr(f"OEBPS/c{i}.xhtml", chapter(t, kind, count), compress_type=zipfile.ZIP_DEFLATED)
        if cover_png:
            z.writestr("OEBPS/cover.png", cover_png, compress_type=zipfile.ZIP_STORED)


def main(out):
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    epub(out / "비 오는 아침.epub", "비 오는 아침",
         [("첫째 장 정류장", "rain", 14), ("둘째 장 버스 안", "rain", 20), ("셋째 장 창밖", "rain", 12)],
         cover_png=png(280, 400, cover))
    epub(out / "바다로 가는 길.epub", "바다로 가는 길", [("첫째 장 기차", "sea", 12), ("둘째 장 등대", "sea", 16)])
    (out / "짧은 메모.txt").write_text("장 볼 것: 우유, 달걀, 보리차.\n", encoding="utf-8")


if __name__ == "__main__":
    main(sys.argv[1])
