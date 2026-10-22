// 시험용 만화(구상안 35A · 36): CBZ 두 권(오→왼, 4쪽에 펼침면), 다 읽을 한 권, 화마다 폴더인 웹툰.
// swift make_comics.swift <출력 폴더>   (cbz 는 zip 명령으로 묶는다)
import AppKit

let out = URL(fileURLWithPath: CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : ".")
let fm = FileManager.default

func page(_ text: String, wide: Bool = false, color: NSColor) -> Data {
    let size = wide ? NSSize(width: 1400, height: 1000) : NSSize(width: 700, height: 1000)
    let img = NSImage(size: size)
    img.lockFocus()
    NSColor.white.setFill(); NSRect(origin: .zero, size: size).fill()
    color.setStroke()
    let panels = wide ? [NSRect(x: 30, y: 30, width: 1340, height: 940)] :
        [NSRect(x: 30, y: 620, width: 400, height: 350), NSRect(x: 450, y: 620, width: 220, height: 350),
         NSRect(x: 30, y: 30, width: 640, height: 570)]
    for p in panels { let b = NSBezierPath(rect: p); b.lineWidth = 6; b.stroke() }
    (text as NSString).draw(at: NSPoint(x: 60, y: 300), withAttributes: [.font: NSFont.boldSystemFont(ofSize: 90), .foregroundColor: color])
    img.unlockFocus()
    let rep = NSBitmapImageRep(data: img.tiffRepresentation!)!
    return rep.representation(using: .png, properties: [:])!
}

func cbz(_ name: String, pages: [(String, Bool)], color: NSColor, info: String?) {
    let dir = out.appendingPathComponent("tmp-\(UUID().uuidString)")
    try! fm.createDirectory(at: dir, withIntermediateDirectories: true)
    for (i, p) in pages.enumerated() { try! page(p.0, wide: p.1, color: color).write(to: dir.appendingPathComponent(String(format: "%03d.png", i + 1))) }
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
for ep in 1...2 {
    let dir = out.appendingPathComponent("고양이 우체국/\(String(format: "%03d", ep))화")
    try! fm.createDirectory(at: dir, withIntermediateDirectories: true)
    for i in 1...3 { try! page("\(ep)화 \(i)", color: NSColor(red: 0.3, green: 0.5, blue: 0.3, alpha: 1)).write(to: dir.appendingPathComponent("\(i).png")) }
}
print("done")
