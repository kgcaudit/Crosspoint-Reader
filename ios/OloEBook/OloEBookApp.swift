import SwiftUI

@main
struct OloEBookApp: App {
    @StateObject private var library = Library()
    @State private var reading: LibraryBook?

    init() {
        // 글꼴 폴더를 측정기에 알린다 — 첫 조판보다 먼저여야 고른 글꼴로 첫 쪽이 짜인다.
        _ = FontLibrary.shared
    }

    var body: some Scene {
        WindowGroup {
            ZStack {
                LibraryView(library: library) { reading = $0 }
                if let book = reading {
                    ReaderView(book: book, library: library, onClose: {
                        reading = nil
                        library.reloadRecords()
                    }, openBook: { next in
                        // 만화 권 끝의 "이어서 읽기": 리더를 다음 권으로 새로 연다(.id 가 바뀐다).
                        library.reloadRecords()
                        reading = next
                    })
                    .id(book.id)
                    .transition(.move(edge: .trailing))
                    .zIndex(1)
                }
            }
            .animation(.easeOut(duration: 0.2), value: reading?.id)
            .task {
                await library.refresh()
                #if DEBUG
                // 화면 확인용(시뮬레이터는 명령으로 누를 수 없다): -olo-open "<파일 이름>" 으로 그 책을 연다. 출시 빌드에는 없다.
                let args = CommandLine.arguments
                // -olo-landscape 는 가로로 돌린다(두 쪽 보기 확인 — 시뮬레이터를 명령으로 돌릴 수 없다).
                if args.contains("-olo-landscape"),
                   let scene = UIApplication.shared.connectedScenes.first(where: { $0 is UIWindowScene }) as? UIWindowScene {
                    scene.requestGeometryUpdate(.iOS(interfaceOrientations: .landscapeRight))
                }
                if let i = args.firstIndex(of: "-olo-open"), i + 1 < args.count {
                    reading = (library.reading + library.toRead + library.finished).first { $0.book.displayName == args[i + 1] }?.book
                        ?? library.works.flatMap { $0.entries.map(\.unit) }.first { $0.name == args[i + 1] }.flatMap(library.comicBook)
                        // 이름이 겹치는 화 폴더("001화")는 "작품 폴더/001화" 처럼 경로로.
                        ?? library.works.flatMap { $0.entries.map(\.unit) }.first { $0.id.contains(args[i + 1]) }.flatMap(library.comicBook)
                }
                #endif
            }
            // 다른 앱에서 열기("공유 › OLO eBook", 파일 앱에서 누르기): 받은 파일 폴더로 들여와 바로 연다.
            .onOpenURL { url in
                Task {
                    if let book = await library.receive(url) { reading = book }
                }
            }
        }
    }
}
