#!/usr/bin/env python3
"""
암호화된 시험용 PDF 를 만든다(PdfStructureReaderTest 가 읽는다).

Kotlin 복호화 코드와 **따로** 짰다 — 같은 코드로 암호화·복호화를 하면 둘이 같이 틀려도 시험이 통과한다.
AES 는 `openssl` 명령, MD5/SHA 는 hashlib, RC4 는 여기서 직접(명세 그대로).

  python3 make_encrypted.py      # 이 폴더에 encrypted-*.pdf 를 다시 만든다

만드는 파일 (모두 쪽 3장, 목차 2항목 "1장 어린 새"→0쪽 · "2장 검은 숨"→2쪽, 제목 "소년이 온다" · 저자 "한강"):
  encrypted-rc4-128.pdf     V2 R3, RC4 128비트
  encrypted-aes-128.pdf     V4 R4, AESV2
  encrypted-aes-256.pdf     V5 R6, AESV3 (잡지 PDF 와 같은 방식)
  encrypted-aes-256-objstm.pdf  위와 같되 목차·문서 정보가 암호화된 객체 스트림 안, 상호 참조는 스트림
  encrypted-locked.pdf      V5 R6, 사용자 암호 "secret" — 빈 암호로 열리면 안 된다
"""
import hashlib, os, struct, subprocess, zlib

HERE = os.path.dirname(os.path.abspath(__file__))
PAD = bytes([0x28, 0xBF, 0x4E, 0x5E, 0x4E, 0x75, 0x8A, 0x41, 0x64, 0x00, 0x4E, 0x56, 0xFF, 0xFA, 0x01, 0x08,
             0x2E, 0x2E, 0x00, 0xB6, 0xD0, 0x68, 0x3E, 0x80, 0x2F, 0x0C, 0xA9, 0xFE, 0x64, 0x53, 0x69, 0x7A])
ID0 = bytes.fromhex("3F1EFBCA7756038AEE41B24FF3417305")
P = -3392  # 인쇄·복사 제한(실제 잡지와 같은 값)


def rc4(key, data):
    s = list(range(256)); j = 0
    for i in range(256):
        j = (j + s[i] + key[i % len(key)]) & 0xFF; s[i], s[j] = s[j], s[i]
    i = j = 0; out = bytearray()
    for b in data:
        i = (i + 1) & 0xFF; j = (j + s[i]) & 0xFF; s[i], s[j] = s[j], s[i]
        out.append(b ^ s[(s[i] + s[j]) & 0xFF])
    return bytes(out)


def aes_cbc(key, iv, data, encrypt=True):
    """채움 없는 AES-CBC. 열쇠 길이(16/32)로 AES-128/256 을 고른다."""
    cmd = ["openssl", "enc", "-aes-%d-cbc" % (len(key) * 8), "-K", key.hex(), "-iv", iv.hex(), "-nopad"]
    if not encrypt:
        cmd.append("-d")
    return subprocess.run(cmd, input=data, capture_output=True, check=True).stdout


def pkcs7(data):
    n = 16 - len(data) % 16
    return data + bytes([n]) * n


# ── R3/R4 ───────────────────────────────────────────────────────────
def legacy(r, n, aes):
    o = hashlib.sha256(b"owner").digest()  # 소유자 몫. 읽는 쪽은 열쇠 계산에 이 바이트를 쓸 뿐이다.
    md = hashlib.md5(PAD + o + struct.pack("<i", P) + ID0).digest()
    for _ in range(50):
        md = hashlib.md5(md[:n]).digest()
    key = md[:n]
    x = rc4(key, hashlib.md5(PAD + ID0).digest())
    for i in range(1, 20):
        x = rc4(bytes(k ^ i for k in key), x)
    u = x + bytes(16)

    def obj_key(num, gen):
        h = hashlib.md5(key + struct.pack("<i", num)[:3] + struct.pack("<i", gen)[:2] + (b"sAlT" if aes else b"")).digest()
        return h[:min(n + 5, 16)]

    def enc(data, num, gen):
        if aes:
            iv = hashlib.md5(b"iv%d" % num + data).digest()
            return iv + aes_cbc(obj_key(num, gen), iv, pkcs7(data))
        return rc4(obj_key(num, gen), data)

    if aes:
        d = b"<< /Filter /Standard /V 4 /R 4 /Length 128 /CF << /StdCF << /CFM /AESV2 /AuthEvent /DocOpen /Length 16 >> >> /StmF /StdCF /StrF /StdCF"
    else:
        d = b"<< /Filter /Standard /V 2 /R 3 /Length 128"
    d += b" /O <" + o.hex().encode() + b"> /U <" + u.hex().encode() + b"> /P %d >>" % P
    return d, enc


# ── R6 (알고리즘 2.B) ────────────────────────────────────────────────
def hash_r6(pw, salt, udata=b""):
    k = hashlib.sha256(pw + salt + udata).digest()
    i = 0
    while True:
        k1 = (pw + k + udata) * 64
        e = aes_cbc(k[:16], k[16:32], k1)
        k = [hashlib.sha256, hashlib.sha384, hashlib.sha512][int.from_bytes(e[:16], "big") % 3](e).digest()
        i += 1
        if i >= 64 and e[-1] <= i - 32:
            return k[:32]


def r6(user_password=b""):
    file_key = hashlib.sha256(b"file key").digest()
    vsalt, ksalt = b"validsal", b"key-salt"
    u = hash_r6(user_password, vsalt) + vsalt + ksalt
    ue = aes_cbc(hash_r6(user_password, ksalt), bytes(16), file_key)
    o = hashlib.sha512(b"owner").digest()[:48]
    oe = bytes(32)

    def enc(data, num, gen):
        iv = hashlib.md5(b"iv%d" % num + data).digest()
        return iv + aes_cbc(file_key, iv, pkcs7(data))

    d = (b"<< /Filter /Standard /V 5 /R 6 /Length 256 /CF << /StdCF << /AuthEvent /DocOpen /CFM /AESV3 /Length 32 >> >>"
         b" /StmF /StdCF /StrF /StdCF /O <" + o.hex().encode() + b"> /U <" + u.hex().encode() +
         b"> /OE <" + oe.hex().encode() + b"> /UE <" + ue.hex().encode() + b"> /Perms <" + bytes(16).hex().encode() +
         b"> /P %d >>" % P)
    return d, enc


# ── PDF 짓기 ─────────────────────────────────────────────────────────
def hexstr(b):
    return b"<" + b.hex().upper().encode() + b">"


def utf16(text):
    return b"\xfe\xff" + text.encode("utf-16-be")


def book(enc_dict, enc, objstm=False):
    # 객체: 1 Catalog, 2 Outlines, 3 Pages, 4-6 Page, 7-8 목차 항목, 9 Info, 10 Encrypt
    strings = {  # (객체 번호) → 암호화할 문자열들을 담은 본문 틀
        7: (b"<< /Title %s /Parent 2 0 R /Next 8 0 R /Dest [4 0 R /Fit] >>", utf16("1장 어린 새")),
        8: (b"<< /Title %s /Parent 2 0 R /Prev 7 0 R /Dest [6 0 R /Fit] >>", utf16("2장 검은 숨")),
    }
    plain = {
        1: b"<< /Type /Catalog /Pages 3 0 R /Outlines 2 0 R >>",
        2: b"<< /Type /Outlines /First 7 0 R /Last 8 0 R /Count 2 >>",
        3: b"<< /Type /Pages /Kids [4 0 R 5 0 R 6 0 R] /Count 3 >>",
        4: b"<< /Type /Page /Parent 3 0 R /MediaBox [0 0 425 623] >>",
        5: b"<< /Type /Page /Parent 3 0 R /MediaBox [0 0 425 623] >>",
        6: b"<< /Type /Page /Parent 3 0 R /MediaBox [0 0 425 623] >>",
    }
    info_title, info_author = utf16("소년이 온다"), utf16("한강")

    out = bytearray(b"%PDF-1.7\n%\xe2\xe3\xcf\xd3\n")
    offsets = {}

    def put(num, body):
        offsets[num] = len(out)
        out.extend(b"%d 0 obj\n" % num + body + b"\nendobj\n")

    if not objstm:
        for n, body in plain.items():
            put(n, body)
        for n, (tmpl, s) in strings.items():
            put(n, tmpl % hexstr(enc(s, n, 0)))
        put(9, b"<< /Title " + hexstr(enc(info_title, 9, 0)) + b" /Author " + hexstr(enc(info_author, 9, 0)) + b" >>")
        put(10, enc_dict)
        xref = len(out)
        out.extend(b"xref\n0 11\n0000000000 65535 f \n")
        for n in range(1, 11):
            out.extend(b"%010d 00000 n \n" % offsets[n])
        out.extend(b"trailer\n<< /Size 11 /Root 1 0 R /Info 9 0 R /Encrypt 10 0 R /ID [<" + ID0.hex().encode() +
                   b"> <" + ID0.hex().encode() + b">] >>\nstartxref\n%d\n%%%%EOF\n" % xref)
        return bytes(out)

    # 객체 스트림(11번) 안에: 1,2,7,8,9. 안의 문자열은 따로 암호화하지 않는다(스트림째 암호화).
    packed = [(1, plain[1]), (2, plain[2]),
              (7, strings[7][0] % hexstr(strings[7][1])), (8, strings[8][0] % hexstr(strings[8][1])),
              (9, b"<< /Title " + hexstr(info_title) + b" /Author " + hexstr(info_author) + b" >>")]
    head, body = b"", b""
    for n, b in packed:
        head += b"%d %d " % (n, len(body)); body += b + b" "
    data = enc(zlib.compress(head + body), 11, 0)
    for n in (3, 4, 5, 6):
        put(n, plain[n])
    put(10, enc_dict)
    put(11, b"<< /Type /ObjStm /N %d /First %d /Filter /FlateDecode /Length %d >>\nstream\n" % (len(packed), len(head), len(data)) + data + b"\nendstream")
    # 상호 참조 스트림(12번, 암호화하지 않는다): W [1 4 2], PNG Up 예측자.
    rows = []
    for n in range(13):
        if n == 0:
            rows.append(bytes([0, 0, 0, 0, 0, 0xFF, 0xFF]))
        elif n in offsets:
            rows.append(bytes([1]) + offsets[n].to_bytes(4, "big") + bytes(2))
        elif n == 12:
            rows.append(None)
        else:
            idx = [p[0] for p in packed].index(n)
            rows.append(bytes([2]) + (11).to_bytes(4, "big") + idx.to_bytes(2, "big"))
    xref_at = len(out)
    rows[12] = bytes([1]) + xref_at.to_bytes(4, "big") + bytes(2)
    pred, prev = bytearray(), bytes(7)
    for r in rows:
        pred.append(2); pred.extend((r[i] - prev[i]) & 0xFF for i in range(7)); prev = r
    x = zlib.compress(bytes(pred))
    out.extend(b"12 0 obj\n<< /Type /XRef /Size 13 /W [1 4 2] /Root 1 0 R /Info 9 0 R /Encrypt 10 0 R /ID [<" +
               ID0.hex().encode() + b"> <" + ID0.hex().encode() + b">] /Filter /FlateDecode /DecodeParms << /Predictor 12 /Columns 7 >> /Length %d >>\nstream\n" % len(x) +
               x + b"\nendstream\nendobj\nstartxref\n%d\n%%%%EOF\n" % xref_at)
    return bytes(out)


if __name__ == "__main__":
    files = {
        "encrypted-rc4-128.pdf": book(*legacy(3, 16, aes=False)),
        "encrypted-aes-128.pdf": book(*legacy(4, 16, aes=True)),
        "encrypted-aes-256.pdf": book(*r6()),
        "encrypted-aes-256-objstm.pdf": book(*r6(), objstm=True),
        "encrypted-locked.pdf": book(*r6(b"secret")),
    }
    for name, data in files.items():
        with open(os.path.join(HERE, name), "wb") as f:
            f.write(data)
        print(name, len(data))
