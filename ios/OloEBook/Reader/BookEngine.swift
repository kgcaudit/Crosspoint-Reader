import OloCore
import UIKit

/// 리더가 부르는 엔진 하나(구상안 32A). 책(EPUB · TXT, Kotlin `IosBookReader`)과 PDF(PDFKit)가 같은 길로 쪽을 낸다 — 넘김 효과 ·
/// 책갈피 · 목차 · 독서노트 · 아래 줄은 한 번만 짠다. 글자를 다루는 기능(찾기 · 형광펜 · 링크 · 듣기)은 PDF 에 아직 없어(2단계)
/// 기본값이 비어 있다 — 부르는 쪽은 빈 결과를 "그런 것이 없다" 로 다룬다.
protocol BookEngine: Actor {
    nonisolated var title: String { get }
    nonisolated var author: String? { get }
    nonisolated var isPdf: Bool { get }
    nonisolated var isComic: Bool { get }

    func next() async throws -> PageFrame?
    func previous() async throws -> PageFrame?
    func seek(_ percent: Double) async throws -> PageFrame
    func peek(_ step: Int) async -> UIImage?
    func toggleBookmark() async throws -> PageFrame
    func removeBookmark(_ id: Int64) async throws -> PageFrame
    func bookmarks() async throws -> [BookmarkItem]
    func goToBookmark(_ id: Int64) async throws -> PageFrame?
    func contents() async throws -> (rows: [ContentsItem], current: Int)
    func goToContents(_ index: Int) async throws -> PageFrame?
    /// 다른 기기가 더 읽은 자리로(구상안 50 "거기로"). 책은 [spine] · [offset], PDF 는 [page], 만화는 [page] · [half], 만화로 연 책은
    /// [locator](책의 자리). 갈 수 없으면 nil.
    func goToPlace(_ place: SyncPlace) async throws -> PageFrame?
    func close()

    // 글자(책만)
    func setShowHighlights(_ on: Bool)
    func chapterCount() async throws -> Int
    func searchChapter(_ spine: Int, _ query: String, firstId: Int) async throws -> [SearchItem]
    func goToHit(_ item: SearchItem) async throws -> PageFrame
    func clearMark() async throws -> PageFrame
    func tapLink(_ x: Double, _ y: Double) async throws -> LinkAction?
    func jumpTo(_ spine: Int, _ anchor: String?, markLength: Int) async throws -> PageFrame
    func returnBack() async throws -> PageFrame?
    func charAt(_ p: CGPoint, after: Bool) async throws -> Int
    func wordAt(_ p: CGPoint) async throws -> (Int, Int)?
    func boxes(_ start: Int, _ end: Int) async throws -> [CGRect]
    func selectionState(_ start: Int, _ end: Int) async throws -> (Bool, Int)
    func continueSelection(_ start: Int, _ end: Int) async throws -> (PageFrame, Int, Int)?
    func textOf(_ start: Int, _ end: Int) async throws -> String
    func highlight(_ start: Int, _ end: Int, pen: Int, note: String?) async throws -> PageFrame
    func recolor(_ id: Int64, pen: Int) async throws -> PageFrame
    func setNote(_ id: Int64, _ note: String?) async throws -> PageFrame
    func removeNote(_ id: Int64) async throws -> PageFrame
    func noteRows() async throws -> [HighlightItem]
    func goToNote(_ id: Int64) async throws -> PageFrame?
    func hasBookFonts() async -> Bool
    func bookFontPreviewPath() -> String?
    func speechSentences(_ spine: Int) async -> [SpokenSentence]
    func spoken(_ spine: Int, _ s: SpokenSentence, join: Int) async -> String
    func speak(_ spine: Int, _ s: SpokenSentence, follow: Bool) async throws -> PageFrame
    func stopSpeaking() async throws -> PageFrame
}

/// 엔진에 없는 기능을 불렀다(PDF 의 찾기 등). 부르는 쪽은 `try?` 로 받아 아무 일도 없게 둔다.
struct UnsupportedFeature: Error {}

/// "거기로" 의 자리(구상안 50).
struct SyncPlace: Sendable {
    var spine: Int?
    var offset: Int?
    var page: Int?
    var half: String?
    var locator: String?
}

extension BookEngine {
    nonisolated var isPdf: Bool { false }
    func goToPlace(_ place: SyncPlace) async throws -> PageFrame? { nil }
    nonisolated var isComic: Bool { false }
    func setShowHighlights(_ on: Bool) {}
    func chapterCount() async throws -> Int { 0 }
    func searchChapter(_ spine: Int, _ query: String, firstId: Int) async throws -> [SearchItem] { [] }
    func goToHit(_ item: SearchItem) async throws -> PageFrame { throw UnsupportedFeature() }
    func clearMark() async throws -> PageFrame { throw UnsupportedFeature() }
    func tapLink(_ x: Double, _ y: Double) async throws -> LinkAction? { nil }
    func jumpTo(_ spine: Int, _ anchor: String?, markLength: Int) async throws -> PageFrame { throw UnsupportedFeature() }
    func returnBack() async throws -> PageFrame? { nil }
    func charAt(_ p: CGPoint, after: Bool) async throws -> Int { -1 }
    func wordAt(_ p: CGPoint) async throws -> (Int, Int)? { nil }
    func boxes(_ start: Int, _ end: Int) async throws -> [CGRect] { [] }
    func selectionState(_ start: Int, _ end: Int) async throws -> (Bool, Int) { (false, 0) }
    func continueSelection(_ start: Int, _ end: Int) async throws -> (PageFrame, Int, Int)? { nil }
    func textOf(_ start: Int, _ end: Int) async throws -> String { "" }
    func highlight(_ start: Int, _ end: Int, pen: Int, note: String?) async throws -> PageFrame { throw UnsupportedFeature() }
    func recolor(_ id: Int64, pen: Int) async throws -> PageFrame { throw UnsupportedFeature() }
    func setNote(_ id: Int64, _ note: String?) async throws -> PageFrame { throw UnsupportedFeature() }
    func removeNote(_ id: Int64) async throws -> PageFrame { throw UnsupportedFeature() }
    func noteRows() async throws -> [HighlightItem] { [] }
    func goToNote(_ id: Int64) async throws -> PageFrame? { nil }
    func hasBookFonts() async -> Bool { false }
    func bookFontPreviewPath() -> String? { nil }
    func speechSentences(_ spine: Int) async -> [SpokenSentence] { [] }
    func spoken(_ spine: Int, _ s: SpokenSentence, join: Int) async -> String { "" }
    func speak(_ spine: Int, _ s: SpokenSentence, follow: Bool) async throws -> PageFrame { throw UnsupportedFeature() }
    func stopSpeaking() async throws -> PageFrame { throw UnsupportedFeature() }
}
