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
                    ReaderView(book: book, library: library) {
                        reading = nil
                        library.reloadRecords()
                    }
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
                if let i = args.firstIndex(of: "-olo-open"), i + 1 < args.count {
                    reading = (library.reading + library.toRead + library.finished).first { $0.book.displayName == args[i + 1] }?.book
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
