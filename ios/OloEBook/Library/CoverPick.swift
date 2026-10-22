import OloCore
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

/// 표지를 어디서 고르나(구상안 44 · 1A). iOS 의 사진 고르기 창은 파일 앱을 보여 주지 않아, 안드로이드의 한 창을 둘로 나눈다.
enum CoverSource { case photos, files }

/// OloCore 를 들이지 않는 화면(서재)이 쓰는 글 · 열쇠 — OloCore 의 `Text` 가 SwiftUI 의 것과 이름이 겹친다.
enum CoverText {
    static func workId(_ workKey: String) -> String { IosCovers.shared.workId(workKey: workKey) }
    static func changed(_ label: String) -> String { IosCovers.shared.changed(label: label.isEmpty ? nil : label) }
}

/// 고른 그림을 어디에 둘까: 책 id 또는 `IosCovers.workId`, 알림에 쓸 이름.
struct CoverTarget: Equatable {
    let id: String
    let label: String
}

/// "사진 · 파일에서 표지 고르기" 한 줄. 누르면 작은 메뉴(사진 보관함 · 파일) — 줄은 안드로이드와 같은 한 줄로 둔다(44 · 1A).
struct CoverPickRow: View {
    let pick: (CoverSource) -> Void

    var body: some View {
        Menu {
            Button { pick(.photos) } label: { Label("사진 보관함", systemImage: "photo") }
            Button { pick(.files) } label: { Label("파일", systemImage: "folder") }
        } label: {
            CpListRow(icon: .folder, tile: CpColor.tileOther, title: IosCovers.shared.PICK_ROW)
        }
        .buttonStyle(.plain)
    }
}

/// 되돌리기 줄. 고른 표지가 없으면 흐리게 두고, 눌러도 판만 닫는다(안드로이드 `enabled = custom`).
struct CoverRevertRow: View {
    let title: String
    let enabled: Bool
    let revert: () -> Void
    let close: () -> Void

    var body: some View {
        CpListRow(icon: .refresh, tile: CpColor.tileOther, title: title) { enabled ? revert() : close() }
            .opacity(enabled ? 1 : 0.45)
    }
}

/// 사진 고르기 창 · 파일 고르기 창을 붙인다. 고른 그림은 `CoverStore` 가 줄여 쓰고, 결과를 [done] 으로 알린다.
/// 파일 고르기 창은 따로 선 빈 뷰에 붙인다 — 서재에 이미 폴더 고르기 창이 있어 한 뷰에 둘을 겹치면 하나만 열린다.
struct CoverPickers: ViewModifier {
    @Binding var target: CoverTarget?
    @Binding var source: CoverSource?
    let done: (Bool, CoverTarget) -> Void
    @State private var item: PhotosPickerItem?

    func body(content: Content) -> some View {
        content
            .photosPicker(isPresented: shown(.photos), selection: $item, matching: .images)
            .onChange(of: item) { _, picked in
                guard let picked, let t = target else { return }
                item = nil
                Task {
                    let data = try? await picked.loadTransferable(type: Data.self)
                    finish(data, t)
                }
            }
            .background {
                Color.clear.fileImporter(isPresented: shown(.files), allowedContentTypes: [.image]) { result in
                    guard let t = target, case .success(let url) = result else { return }
                    let scoped = url.startAccessingSecurityScopedResource()
                    defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                    finish(try? Data(contentsOf: url), t)
                }
            }
    }

    private func shown(_ s: CoverSource) -> Binding<Bool> {
        Binding(get: { source == s }, set: { if !$0, source == s { source = nil } })
    }

    /// 큰 사진을 푸는 일이라 화면 줄 밖에서.
    private func finish(_ data: Data?, _ t: CoverTarget) {
        Task.detached(priority: .userInitiated) {
            let ok = data.map { CoverStore.shared.set(t.id, data: $0) } ?? false
            await MainActor.run { done(ok, t) }
        }
    }
}

/// 고른 그림을 쓰지 못했다(그림이 아니거나 깨졌거나 저장 공간이 없다).
struct CoverErrorPopup: View {
    let close: () -> Void

    var body: some View {
        CpPopup(onDismiss: close) {
            Text(IosCovers.shared.CANNOT_USE_TITLE).font(CpFont.title).foregroundStyle(CpColor.text)
            Text(IosCovers.shared.CANNOT_USE_BODY).font(CpFont.body).foregroundStyle(CpColor.textMuted)
                .fixedSize(horizontal: false, vertical: true)
            HStack { Spacer(); CpButton(title: "확인", action: close) }
        }
    }
}

/// 책을 길게 누르거나 목록 줄 ⋮(구상안 43 · 44, 안드로이드 `CoverMenu`): 갈래 옮기기 · 표지 고르기 · 되돌리기.
/// 설명 줄은 지금 어떤 표지인가(44 · 2A) — 표지 줄이 생겨 무엇을 바꾸는지 먼저 알린다. '만화로 보기' 는 그 기능과 함께.
struct BookMenu: View {
    @ObservedObject var library: Library
    let bookId: String
    let pick: (CoverSource) -> Void
    let close: () -> Void
    let done: (String) -> Void
    /// 책에 든 표지가 있나(EPUB 표지 그림 · PDF 첫 쪽). 묻는 동안은 nil — 설명 줄 자리는 비워 둔다.
    @State private var hasOwn: Bool?
    /// 그림 위주 책인가(구상안 46) — 판을 열 때 모르면 판별한다(46 · 2A). 묻는 동안 nil.
    @State private var picture: Bool?
    @ObservedObject private var picked = CoverStore.shared

    var body: some View {
        if let item = library.shelfItem(bookId) { menu(item) }
    }

    private func menu(_ item: ShelfItem) -> some View {
        let book = item.book
        let finished = item.finishedAt != nil
        let custom = picked.has(book.id)
        let own = hasOwn ?? false
        let name = "‘\(book.label)’"
        return CpPopup(onDismiss: close) {
            Text(book.label).font(CpFont.title).foregroundStyle(CpColor.text).lineLimit(2)
            Text(hasOwn == nil && !custom ? " " : IosCovers.shared.bookMessage(custom: custom, hasOwn: own, pdf: book.format == .pdf))
                .font(CpFont.body).foregroundStyle(CpColor.textMuted).fixedSize(horizontal: false, vertical: true)
            VStack(spacing: 0) {
                if finished {
                    CpListRow(icon: .book, tile: CpColor.tileOther, title: "읽는 책으로 옮기기") {
                        library.setFinished(book, false)
                        done("\(name) — 읽는 책으로 옮겼습니다")
                    }
                } else {
                    CpListRow(icon: .bookmark, tile: CpColor.tileOther, title: "읽은 책으로 옮기기") {
                        library.setFinished(book, true)
                        done("\(name) — 읽은 책으로 옮겼습니다")
                    }
                }
                // 읽을 책에 있는 책은 이미 거기 있다.
                if item.openedAt != nil || finished {
                    CpListRow(icon: .back, tile: CpColor.tileOther, title: "읽을 책으로 옮기기") {
                        library.returnToUnread(book)
                        done("\(name) — 읽을 책으로 옮겼습니다")
                    }
                }
                if book.format == .pdf || picture == true {
                    // 만화로 보는 중이면 "책으로 보기", 아니면 "만화로 보기"(안드로이드 `CoverMenu`). PDF 는 판별하지 않고 늘 고를 수 있다
                    // (46 · 1A). 책 이름 뒤에 조사는 붙이지 않는다.
                    let comic = IosPictureBooks.shared.opensAsComic(view: library.bookView(book.id), pdf: book.format == .pdf,
                                                                    picture: picture == true)
                    CpListRow(icon: comic ? .book : .grid, tile: CpColor.tileOther, title: comic ? "책으로 보기" : "만화로 보기") {
                        library.setBookView(book, comic: !comic)
                        done("\(name) — \(comic ? "책으로" : "만화로") 봅니다")
                    }
                }
                CoverPickRow(pick: pick)
                CoverRevertRow(title: IosCovers.shared.revertLabel(hasOwn: own), enabled: custom, revert: {
                    CoverStore.shared.clear(book.id)
                    done(IosCovers.shared.reverted(label: book.label))
                }, close: close)
            }
            HStack { Spacer(); CpButton(title: "닫기", kind: .secondary, action: close) }
        }
        .task(id: book.id) {
            hasOwn = await Covers.shared.own(book, library: library) != nil
            if let known = library.cachedPictureBook(book) {
                picture = known
            } else {
                picture = await library.pictureBook(book) != nil
            }
        }
    }
}
