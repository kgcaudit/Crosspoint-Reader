import SwiftUI

/// 서재 책 찾기의 셈(안드로이드 `findBooks` · `squash` · `matchRange`). 화면과 떨어져 있어 시험하기 쉽다.
enum BookFinder {
    /// 견줄 꼴: 한글을 모아 쓴 꼴(NFC)로 — 맥에서 받은 파일 이름은 풀어 쓴 꼴(NFD)일 수 있어 그대로 견주면 '바다' 가 '바다로 가는 길'
    /// 에 맞지 않는다. 소문자로, 공백은 모두 뺀다('바다로가는' 도 '바다로 가는' 에 맞는다).
    static func squash(_ s: String) -> String {
        String(s.precomposedStringWithCanonicalMapping.lowercased().filter { !$0.isWhitespace })
    }

    /// 제목 · 저자 · 파일 이름 중 어디에든 맞는 책. 갈래 차례(읽는 책 → 읽은 책 → 읽을 책), 같은 갈래는 가나다순. 폴더 이름은 찾지
    /// 않는다 — "소설" 폴더의 책이 모두 나오면 찾은 것이 아니다. 글자 그대로 견준다(정규식이 아니다 — "[(" 를 쳐도 된다).
    static func find(_ query: String, reading: [ShelfItem], finished: [ShelfItem], toRead: [ShelfItem]) -> [ShelfItem] {
        let q = squash(query)
        guard !q.isEmpty else { return [] }
        func hit(_ i: ShelfItem) -> Bool {
            [i.book.label, i.book.author ?? "", i.book.displayName].contains { squash($0).contains(q) }
        }
        let byLabel: (ShelfItem, ShelfItem) -> Bool = {
            $0.book.label.compare($1.book.label, options: [.numeric, .caseInsensitive], locale: Locale(identifier: "ko_KR")) == .orderedAscending
        }
        return [reading, finished, toRead].flatMap { $0.filter(hit).sorted(by: byLabel) }
    }

    /// [text] 에서 [query] 가 맞은 첫 구간(공백을 건너뛰며, 대소문자 무시). '어린왕자' 로 찾으면 '어린 왕자' 전체를 칠한다. 없으면 nil.
    static func matchRange(_ text: String, _ query: String) -> Range<String.Index>? {
        let q = Array(squash(query))
        guard !q.isEmpty else { return nil }
        let chars = Array(text.indices)
        for start in chars.indices {
            guard !text[chars[start]].isWhitespace else { continue }
            var k = 0
            var i = start
            while i < chars.count && k < q.count {
                let c = text[chars[i]]
                if c.isWhitespace { i += 1; continue }
                guard squash(String(c)) == String(q[k]) else { break }
                k += 1
                i += 1
            }
            if k == q.count { return chars[start]..<(i < chars.count ? chars[i] : text.endIndex) }
        }
        return nil
    }
}

/// 서재 책 찾기(구상안 29A, 안드로이드 `BookSearch`): 전체 화면. 칸은 리더 찾기와 같은 부품. 닫았다 열면 찾던 말이 남아 있다.
struct LibrarySearchScreen: View {
    @ObservedObject var library: Library
    @Binding var query: String
    let open: (LibraryBook) -> Void
    let back: () -> Void

    var body: some View {
        let hits = BookFinder.find(query, reading: library.reading, finished: library.finished, toRead: library.toRead)
        VStack(alignment: .leading, spacing: 0) {
            CpSearchField(query: $query, placeholder: "책 제목 · 저자 · 파일 이름", onBack: back, onClear: { query = "" }, onSearch: {})
            if query.trimmingCharacters(in: .whitespaces).isEmpty {
                Text("책 \(library.bookCount)권에서 찾습니다").font(CpFont.subtitle).foregroundStyle(CpColor.textMuted)
                    .frame(maxWidth: .infinity).padding(.top, 80)
                Spacer()
            } else if hits.isEmpty {
                Text("‘\(query.trimmingCharacters(in: .whitespaces))’ — 맞는 책이 없습니다").font(CpFont.subtitle)
                    .foregroundStyle(CpColor.textMuted).multilineTextAlignment(.center)
                    .padding(.horizontal, 24).frame(maxWidth: .infinity).padding(.top, 80)
                Spacer()
            } else {
                Text("\(hits.count)권 · 제목 · 저자 · 파일 이름에서").font(CpFont.caption).foregroundStyle(CpColor.textMuted)
                    .padding(.horizontal, CpMetrics.gutter).padding(.vertical, 6)
                CpDivider().padding(.top, 8)
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(hits) { item in
                            HitRow(item: item, query: query, library: library) { open(item.book) }
                        }
                    }
                    .padding(.bottom, 24)
                }
                .scrollDismissesKeyboard(.immediately)
            }
        }
        .background(CpColor.background)
    }
}

/// 찾은 책 한 줄(안드로이드 `HitRow`): 작은 표지 · 제목 · 저자 · (제목 · 저자에 안 맞았으면) 파일 이름 · 형식 크기 폴더 · 갈래 꼬리표.
private struct HitRow: View {
    let item: ShelfItem
    let query: String
    let library: Library
    let action: () -> Void

    var body: some View {
        let book = item.book
        let inName = BookFinder.matchRange(book.label, query) != nil || BookFinder.matchRange(book.author ?? "", query) != nil
        HStack(alignment: .top, spacing: 14) {
            BookCover(book: book, library: library, small: true).frame(width: 48)
            VStack(alignment: .leading, spacing: 2) {
                Text(marked(book.label)).font(CpFont.body).foregroundStyle(CpColor.text).lineLimit(2)
                if let author = book.author { Text(marked(author)).font(CpFont.subtitle).foregroundStyle(CpColor.textMuted) }
                // 어디서 맞았는지: 제목 · 저자가 아니면 파일 이름에서다.
                if !inName && book.label != book.displayName {
                    Text(marked(book.displayName)).font(CpFont.caption).foregroundStyle(CpColor.textMuted)
                }
                Text("\(book.format.rawValue)  \(ByteCountFormatter.string(fromByteCount: book.sizeBytes, countStyle: .file)) · \(book.folderLabel)")
                    .font(CpFont.caption).foregroundStyle(CpColor.textMuted).lineLimit(1).padding(.top, 2)
            }
            Spacer(minLength: 8)
            Text(tag).font(CpFont.caption).foregroundStyle(CpColor.textMuted)
                .padding(.horizontal, 6).padding(.vertical, 2)
                .overlay(RoundedRectangle(cornerRadius: CpMetrics.cornerChip).stroke(CpColor.outline, lineWidth: 1))
        }
        .padding(.horizontal, CpMetrics.gutter).padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .bookButton(item, action: action)
    }

    private var tag: String {
        if item.isReading { return "읽는 책 \(Int((item.percent ?? 0).rounded()))%" }
        return item.finishedAt != nil ? "읽은 책" : "읽을 책"
    }

    private func marked(_ text: String) -> AttributedString {
        var out = AttributedString(text)
        if let r = BookFinder.matchRange(text, query),
           let lower = AttributedString.Index(r.lowerBound, within: out), let upper = AttributedString.Index(r.upperBound, within: out) {
            out[lower..<upper].backgroundColor = CpColor.accentContainer
        }
        return out
    }
}
