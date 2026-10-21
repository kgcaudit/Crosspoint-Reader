// 시험용 PDF(구상안 32A · 33): 목차 · 제목이 든 6쪽 한글 설명서, 사용자 암호가 걸린 문서, 깨진 문서.
// swift make_pdfs.swift <출력 폴더>
import AppKit
import PDFKit

let out = URL(fileURLWithPath: CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : ".")
let pageRect = CGRect(x: 0, y: 0, width: 595, height: 842)

func makeManual() -> PDFDocument {
    let data = NSMutableData()
    var box = pageRect
    let ctx = CGContext(consumer: CGDataConsumer(data: data)!, mediaBox: &box, nil)!
    let chapters = ["표지", "차례", "1장 시작하기", "1장 시작하기", "2장 책 넣기", "2장 책 넣기"]
    for (i, head) in chapters.enumerated() {
        ctx.beginPDFPage(nil)
        let ns = NSGraphicsContext(cgContext: ctx, flipped: false)
        NSGraphicsContext.current = ns
        if i == 0 {
            NSColor(red: 0.73, green: 0.36, blue: 0.23, alpha: 1).setFill()
            NSBezierPath(rect: CGRect(x: 0, y: 500, width: 595, height: 342)).fill()
            ("OLO 사용 설명서" as NSString).draw(at: CGPoint(x: 60, y: 620), withAttributes: [.font: NSFont.boldSystemFont(ofSize: 40), .foregroundColor: NSColor.white])
        } else {
            (head as NSString).draw(at: CGPoint(x: 60, y: 760), withAttributes: [.font: NSFont.boldSystemFont(ofSize: 26)])
            let body = String(repeating: "OLO eBook 은 휴대폰에 있는 EPUB · TXT · PDF 를 읽습니다. 파일 앱의 OLO eBook 폴더에 책을 넣거나, 다른 앱에서 공유로 넘기면 서재에 바로 올라옵니다. ", count: 9)
            (body as NSString).draw(in: CGRect(x: 60, y: 160, width: 475, height: 570), withAttributes: [.font: NSFont.systemFont(ofSize: 13)])
            NSColor(white: 0.85, alpha: 1).setFill()
            NSBezierPath(rect: CGRect(x: 60, y: 80, width: 475, height: 60)).fill()
        }
        ("\(i + 1)" as NSString).draw(at: CGPoint(x: 292, y: 40), withAttributes: [.font: NSFont.systemFont(ofSize: 10)])
        ctx.endPDFPage()
    }
    ctx.closePDF()
    let doc = PDFDocument(data: data as Data)!
    let root = PDFOutline()
    for (label, page) in [("1장 시작하기", 2), ("2장 책 넣기", 4)] {
        let item = PDFOutline()
        item.label = label
        item.destination = PDFDestination(page: doc.page(at: page)!, at: CGPoint(x: 0, y: 842))
        root.insertChild(item, at: root.numberOfChildren)
    }
    doc.outlineRoot = root
    doc.documentAttributes = [PDFDocumentAttribute.titleAttribute: "Microsoft Word - OLO 사용 설명서.docx",
                              PDFDocumentAttribute.authorAttribute: "올로"]
    return doc
}

let manual = makeManual()
manual.write(to: out.appendingPathComponent("OLO 사용 설명서.pdf"))
manual.write(to: out.appendingPathComponent("잠긴 문서.pdf"),
             withOptions: [PDFDocumentWriteOption.userPasswordOption: "olo", PDFDocumentWriteOption.ownerPasswordOption: "olo-owner"])
try! Data("%PDF-1.4\n깨진 파일입니다".utf8).write(to: out.appendingPathComponent("깨진 문서.pdf"))
print("done")
