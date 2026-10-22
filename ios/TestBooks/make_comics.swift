// 시험용 만화(구상안 35A · 36 · 38): CBZ 두 권(오→왼, 4쪽에 펼침면), 다 읽을 한 권, 화마다 폴더인 웹툰, 옛 스캔본(스캐너 띠 ·
// 누런 종이 · 흐린 먹 — 여백 자르기 · 색 보정 · 왼→오 펼침면 나누기 확인).
// swift make_comics.swift <출력 폴더>   (cbz 는 zip 명령으로 묶는다)
import AppKit

let out = URL(fileURLWithPath: CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : ".")
let fm = FileManager.default

func page(_ text: String, wide: Bool = false, color: NSColor, scanned: Bool = false) -> Data {
    let size = wide ? NSSize(width: 1400, height: 1000) : NSSize(width: 700, height: 1000)
    let img = NSImage(size: size)
    img.lockFocus()
    if scanned {
        // 스캐너 바탕(회색 띠) 안에 누렇게 바랜 종이 — 위아래 · 좌우 띠 폭이 다르다(스캔은 대개 비뚤다).
        NSColor(red: 0x9C / 255, green: 0x98 / 255, blue: 0x90 / 255, alpha: 1).setFill(); NSRect(origin: .zero, size: size).fill()
        NSColor(red: 0xE9 / 255, green: 0xDD / 255, blue: 0xBC / 255, alpha: 1).setFill()
        NSRect(x: 50, y: 40, width: size.width - 80, height: size.height - 100).fill()
    } else {
        NSColor.white.setFill(); NSRect(origin: .zero, size: size).fill()
    }
    color.setStroke()
    let inset: CGFloat = scanned ? 90 : 30
    let panels = wide ? [NSRect(x: inset, y: inset, width: size.width - inset * 2, height: size.height - inset * 2)] :
        [NSRect(x: inset, y: 620, width: 400 - inset + 30, height: 350 - inset + 30), NSRect(x: 450, y: 620, width: 250 - inset, height: 350 - inset + 30),
         NSRect(x: inset, y: inset, width: 700 - inset * 2, height: 600 - inset)]
    for p in panels { let b = NSBezierPath(rect: p); b.lineWidth = 6; b.stroke() }
    if wide {
        // 펼침면의 두 반을 알아볼 수 있게 — 왼쪽 반 · 오른쪽 반에 따로 적는다.
        ("\(text) 왼" as NSString).draw(at: NSPoint(x: inset + 40, y: 450), withAttributes: [.font: NSFont.boldSystemFont(ofSize: 80), .foregroundColor: color])
        ("\(text) 오" as NSString).draw(at: NSPoint(x: size.width / 2 + 40, y: 450), withAttributes: [.font: NSFont.boldSystemFont(ofSize: 80), .foregroundColor: color])
    } else {
    (text as NSString).draw(at: NSPoint(x: 60 + inset - 30, y: 300), withAttributes: [.font: NSFont.boldSystemFont(ofSize: 90), .foregroundColor: color])
    }
    img.unlockFocus()
    let rep = NSBitmapImageRep(data: img.tiffRepresentation!)!
    return rep.representation(using: .png, properties: [:])!
}

func cbz(_ name: String, pages: [(String, Bool)], color: NSColor, info: String?, scanned: Bool = false) {
    let dir = out.appendingPathComponent("tmp-\(UUID().uuidString)")
    try! fm.createDirectory(at: dir, withIntermediateDirectories: true)
    for (i, p) in pages.enumerated() { try! page(p.0, wide: p.1, color: color, scanned: scanned).write(to: dir.appendingPathComponent(String(format: "%03d.png", i + 1))) }
    if let info { try! info.write(to: dir.appendingPathComponent("ComicInfo.xml"), atomically: true, encoding: .utf8) }
    let task = Process()
    task.executableURL = URL(fileURLWithPath: "/usr/bin/zip")
    task.currentDirectoryURL = dir
    task.arguments = ["-q", "-0", out.appendingPathComponent(name).path] + (try! fm.contentsOfDirectory(atPath: dir.path)).sorted()
    try! task.run(); task.waitUntilExit()
    try! fm.removeItem(at: dir)
}

let rtl = "<ComicInfo><Series>바다 마을 이야기</Series><Manga>YesAndRightToLeft</Manga></ComicInfo>"
let red = NSColor(red: 0.73, green: 0.36, blue: 0.23, alpha: 1)
cbz("[올로] 바다 마을 이야기 01권.cbz", pages: (1...8).map { ("1권 \($0)쪽", $0 == 4) }, color: red, info: rtl)
cbz("바다 마을 이야기 02권.cbz", pages: (1...6).map { ("2권 \($0)쪽", false) }, color: red, info: rtl)
cbz("등대의 아이 (완).cbz", pages: (1...4).map { ("등대 \($0)", false) }, color: NSColor(red: 0.25, green: 0.42, blue: 0.55, alpha: 1), info: nil)
let ink = NSColor(red: 0x55 / 255, green: 0x4E / 255, blue: 0x44 / 255, alpha: 1)
cbz("옛 스캔본 01권.cbz", pages: (1...6).map { ("옛 \($0)쪽", $0 == 3) }, color: ink, info: nil, scanned: true)
for ep in 1...2 {
    let dir = out.appendingPathComponent("고양이 우체국/\(String(format: "%03d", ep))화")
    try! fm.createDirectory(at: dir, withIntermediateDirectories: true)
    for i in 1...3 { try! page("\(ep)화 \(i)", color: NSColor(red: 0.3, green: 0.5, blue: 0.3, alpha: 1)).write(to: dir.appendingPathComponent("\(i).png")) }
}
print("done")
