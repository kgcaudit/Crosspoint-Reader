import SwiftUI

/// 화면 방향을 앱이 정한다(구상안 45). 시스템이 창마다 이 값을 묻는다.
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        // 기기 간 이어 읽기의 다시 하기(구상안 50 · 2A). 시스템 규칙: 앱이 다 뜨기 전에 등록해야 깨워 준다.
        SyncCenter.registerBackground()
        return true
    }

    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        ScreenRotation.mask
    }
}

@main
struct OloEBookApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var library = Library()
    @State private var reading: LibraryBook?
    /// 보던 장면에서 표지를 고르는 작품(구상안 44). nil 이면 그냥 읽기.
    @State private var coverPick: String?
    @Environment(\.scenePhase) private var scenePhase

    init() {
        // 글꼴 폴더를 측정기에 알린다 — 첫 조판보다 먼저여야 고른 글꼴로 첫 쪽이 짜인다.
        _ = FontLibrary.shared
        // 서재부터 고른 방향으로 연다(설정은 읽기 화면에 있지만 앱 전체에 걸린다 — 안드로이드와 같다).
        ScreenRotation.apply(UserDefaults.standard.integer(forKey: "reader.rotation"))
        #if DEBUG
        SyncSelfTest.runIfAsked()
        #endif
    }

    var body: some Scene {
        WindowGroup {
            ZStack {
                LibraryView(library: library, open: { coverPick = nil; reading = $0 },
                            pickScene: { book, key in coverPick = key; reading = book })
                if let book = reading {
                    ReaderView(book: book, library: library, pickCover: coverPick, onClose: {
                        reading = nil
                        coverPick = nil
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
            #if DEBUG
            // 화면 확인용: -olo-window 1194x834 는 앱을 그 창 크기로 짜고 화면에 맞게 줄여 보인다 — 시뮬레이터를 명령으로 돌릴 수
            // 없어 iPad 가로 · 나란히 보기 배치(구상안 48)를 이것으로 찍는다. 출시 빌드에는 없다.
            .modifier(DebugWindow())
            #endif
            .task {
                SyncCenter.shared.attach(library)
                await library.refresh()
                // 기기 간 이어 읽기: 훑은 뒤 다른 기기가 남긴 책갈피 · 다 읽은 때를 합치고 이 기기 기록을 올린다(꺼져 있으면 아무것도 하지 않는다).
                Task { await SyncCenter.shared.syncNow() }
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
                    // -olo-pick-cover 는 연 권을 "보던 장면에서 표지 고르기" 로 연다(구상안 44).
                    if args.contains("-olo-pick-cover"), let book = reading { coverPick = library.work(of: book.id)?.key }
                }
                #endif
            }
            // 다른 앱에서 열기("공유 › OLO eBook", 파일 앱에서 누르기): 받은 파일 폴더로 들여와 바로 연다. 이어 읽기 위젯(구상안 47)은
            // olo://continue?book=… 로 그 책 · 권을 연다 — 지금 다른 책을 보고 있으면 그 책을 닫고.
            .onOpenURL { url in
                Task {
                    if let target = WidgetShared.target(of: url) {
                        if library.isEmpty { await library.refresh() }
                        guard !target.id.isEmpty else { return }
                        let book = target.comic
                            ? library.works.flatMap { $0.entries.flatMap { [$0.unit] + $0.copies } }.first { $0.id == target.id }.flatMap(library.comicBook)
                            : library.shelfItem(target.id)?.book
                        if let book, book.id != reading?.id { coverPick = nil; reading = book }
                        return
                    }
                    if let book = await library.receive(url) { reading = book }
                }
            }
            // 앱이 뒤로 가면 위젯 · 이어 읽기를 고쳐 둔다 — 책을 연 채 홈으로 나가면 닫을 때 적는 길을 지나지 않는다.
            .onChange(of: scenePhase) { _, phase in
                if phase == .background {
                    library.reloadRecords()
                    // 이어 읽기: 마지막 자리를 서버에 올린다. 뒤로 간 앱은 몇 초 안에 멈추므로 시간을 빌린다 — 못 올리면 뒤 작업이 다시 한다.
                    let id = UIApplication.shared.beginBackgroundTask(withName: "olo-sync")
                    Task {
                        await SyncCenter.shared.writeOwn()
                        UIApplication.shared.endBackgroundTask(id)
                    }
                }
            }
        }
    }
}

#if DEBUG
private struct DebugWindow: ViewModifier {
    func body(content: Content) -> some View {
        let args = CommandLine.arguments
        if let i = args.firstIndex(of: "-olo-window"), i + 1 < args.count {
            let p = args[i + 1].split(separator: "x").compactMap { Double($0) }
            if p.count == 2 {
                GeometryReader { g in
                    let k = min(g.size.width / p[0], g.size.height / p[1])
                    content.frame(width: p[0], height: p[1]).scaleEffect(k, anchor: .topLeading)
                        .frame(width: g.size.width, height: g.size.height, alignment: .topLeading)
                }
            } else {
                content
            }
        } else {
            content
        }
    }
}
#endif
