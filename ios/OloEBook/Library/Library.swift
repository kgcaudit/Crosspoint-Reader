import Foundation
import OloCore
import UIKit

/// 서재의 책 한 권.
struct LibraryBook: Identifiable, Hashable {
    /// 바뀌지 않는 이름. 앱 폴더는 `app:상대경로`, 연결한 폴더는 `link:<폴더 id>:상대경로`. 절대 경로를 쓰지 않는다 —
    /// 앱을 고쳐 깔면 앱 폴더의 절대 경로가 바뀌어 읽던 자리 · 책갈피를 모두 잃는다.
    let id: String
    let url: URL
    let displayName: String
    let format: Format
    let sizeBytes: Int64
    let modified: Date
    let added: Date
    /// 연결한 폴더의 책이면 그 폴더(읽는 동안 접근을 열어 둬야 한다).
    let folderId: String?
    /// 책 안의 제목 · 저자(구상안 29A). 제목이 없으면 확장자를 뗀 파일 이름.
    var title = ""
    var author: String?

    /// 서재에 보이는 이름(안드로이드 `label`): 책 제목, 모르면 파일 이름.
    var label: String { title.isEmpty ? displayName : title }

    enum Format: String { case epub = "EPUB", txt = "TXT", pdf = "PDF", comic = "만화" }

    /// 조판 캐시 키의 판. 같은 이름으로 고친 책에 옛 쪽이 보이지 않게.
    var edition: String { "\(sizeBytes)-\(Int64(modified.timeIntervalSince1970))" }

    /// 책이 든 폴더 이름(목록 보기의 "84KB · 소설"). 앱 폴더 바로 안이면 "OLO eBook" — 실제 이름 "Documents" 는
    /// 파일 앱에서도 보이지 않는 이름이라 사람이 알아보지 못한다.
    @MainActor var folderLabel: String {
        let parent = url.deletingLastPathComponent()
        return parent.standardizedFileURL.path == Library.appFolder.standardizedFileURL.path ? "OLO eBook" : parent.lastPathComponent
    }
}

/// 서재 한 줄 = 책 + 읽기 기록.
struct ShelfItem: Identifiable, Hashable {
    let book: LibraryBook
    let openedAt: Date?
    let finishedAt: Date?
    let percent: Double?
    let bookmarkCount: Int
    var id: String { book.id }
}

/// 연결한 폴더. 보안 범위 북마크로 다시 열 때도 접근할 수 있다.
struct LinkedFolder: Codable, Identifiable, Hashable {
    let id: String
    var name: String
    let bookmark: Data
}

enum LibrarySort: String, CaseIterable, Identifiable {
    case recent = "최근 읽은 순", name = "이름순", added = "추가한 순", size = "크기순"
    var id: String { rawValue }
}

/// 서재 보기. 세 갈래가 모두 같은 보기를 따른다(안드로이드 0.38). 책장은 원목 판 위에 표지만(0.39 · 0.44).
enum LibraryLayout: String { case grid, list, shelf }

/// 서재. 안드로이드의 폴더 등록(SAF) 대신 세 길로 책을 들인다(2026-10-20 사용자 결정, 구상안 3A):
/// 앱 자기 폴더(파일 앱 › 나의 iPhone › OLO eBook), 연결한 폴더, 다른 앱에서 열기(받은 파일 폴더로 복사).
@MainActor
final class Library: ObservableObject {
    @Published private(set) var reading: [ShelfItem] = []
    @Published private(set) var toRead: [ShelfItem] = []
    @Published private(set) var finished: [ShelfItem] = []
    @Published private(set) var folders: [LinkedFolder] = []
    @Published private(set) var scanning = false
    /// 처음 훑기가 끝났다. 그 전의 빈 서재로 이어 읽기 기록을 올리면 서버의 이 기기 기록이 비었다.
    private(set) var scannedOnce = false
    @Published var sort: LibrarySort { didSet { save(sort.rawValue, "library.sort"); arrange() } }
    @Published var layout: LibraryLayout { didSet { save(layout.rawValue, "library.layout") } }

    let store: ReadingStore
    /// 읽기 기록 백업(구상안 31A). 못 찾은 책의 기록은 기다렸다가 서재를 훑을 때 붙인다.
    let backup: RecordsBackup
    let pageCache: String
    private var books: [LibraryBook] = []
    private var records: [String: ShelfRecord] = [:]

    nonisolated static let appFolder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    /// 이름순 견주기의 말 — 견줄 때마다 만들면 수천 번 만들었다.
    nonisolated static let korean = Locale(identifier: "ko_KR")
    /// 다른 앱에서 받은 책을 두는 곳. 앱 폴더 안에 두어 파일 앱에서도 보이고 지울 수 있다.
    static let incomingFolder = appFolder.appendingPathComponent("받은 파일", isDirectory: true)

    var bookCount: Int { books.count }
    var isEmpty: Bool { books.isEmpty }

    init() {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        store = ReadingStore(path: support.appendingPathComponent("reading.tsv").path)
        backup = RecordsBackup(store: store, pendingPath: support.appendingPathComponent("pending-records.json").path)
        pageCache = caches.appendingPathComponent("pages", isDirectory: true).path
        let defaults = UserDefaults.standard
        sort = LibrarySort(rawValue: defaults.string(forKey: "library.sort") ?? "") ?? .recent
        layout = LibraryLayout(rawValue: defaults.string(forKey: "library.layout") ?? "") ?? .grid
        if let data = defaults.data(forKey: "library.folders"),
           let saved = try? JSONDecoder().decode([LinkedFolder].self, from: data) {
            folders = saved
        }
    }

    private func save(_ value: String, _ key: String) { UserDefaults.standard.set(value, forKey: key) }

    // MARK: 훑기

    func refresh() async {
        scanning = true
        renewStaleBookmarks()
        let folders = self.folders
        let known = infos
        let (found, seen, readBooks) = await Task.detached(priority: .userInitiated) { Library.scan(folders: folders, known: known) }.value
        let (units, files, readComics) = await Task.detached(priority: .userInitiated) { Library.scanComics(folders: folders) }.value
        comicUnits = units
        comicFiles = files
        books = found
        infos = seen
        Library.saveInfos(seen)
        adoptMoved(read: readBooks.filter(readComics.contains))
        // 백업에서 기다리던 책 · 만화가 이제 보이면 붙인다(책 폴더를 추가한 뒤 등).
        _ = backup.resumePending(shelf: shelfBooks, comics: shelfComics)
        scannedOnce = true
        reloadRecords()
        scanning = false
    }

    /// 옮긴 파일의 기록 잇기(안드로이드 0.50.0, 1A 2A 3A): 사라진 파일의 기록을 같은 이름 · 크기로 다시 나타난 파일로 옮긴다. [read] 는
    /// 이번에 책 · 만화 훑기가 모두 실제로 읽은 폴더의 id 머리 — 접근이 끊긴 폴더의 파일을 사라진 것으로 치면, 같은 파일이 다른 폴더에
    /// 하나 있을 때 기록이 그리로 넘어가 버린다. 알림은 띄우지 않는다(안드로이드와 같다).
    private func adoptMoved(read: [String]) {
        let visible = books.map { KnownFile(id: $0.id, kind: KnownFile.companion.BOOK, name: $0.displayName, size: KotlinLong(value: $0.sizeBytes), pages: nil) }
            + comicUnits.map { KnownFile(id: $0.id, kind: $0.kind.name, name: $0.name, size: $0.sizeBytes, pages: $0.pageCount) }
        for pair in store.adoptMoved(visible: visible, scanned: read) {
            // 고른 표지도 따라간다 — 책은 책 id, 만화는 빼낸 작품(`#own:` 권 id)만 권 id 를 열쇠로 쓴다.
            if pair.comic {
                CoverStore.shared.move(IosCovers.shared.workId(workKey: "#own:" + pair.from), to: IosCovers.shared.workId(workKey: "#own:" + pair.to))
            } else {
                CoverStore.shared.move(pair.from, to: pair.to)
            }
        }
    }

    // MARK: 기록 백업(구상안 31A)

    /// 맞추기의 열쇠: 파일 이름 + 크기(안드로이드와 같다 — 기기마다 경로가 달라도 같은 파일을 찾는다).
    var shelfBooks: [ShelfBook] {
        books.map { ShelfBook(id: $0.id, name: $0.displayName, size: $0.sizeBytes, title: $0.title.isEmpty ? nil : $0.title, author: $0.author) }
    }

    /// 만화 권의 열쇠도 이름 + 크기. 그림 폴더는 크기가 없다(공통 `ComicUnit.sizeBytes` 가 null) — 이름 하나로 맞춘다.
    var shelfComics: [ShelfComic] {
        comicUnits.map { ShelfComic(id: $0.id, name: $0.name, size: $0.sizeBytes) }
    }

    func backupSummary() -> BackupSummary { backup.summary(shelf: shelfBooks, comics: shelfComics) }
    func exportRecords() -> String { backup.export(shelf: shelfBooks, comics: shelfComics, now: Int64(Date().timeIntervalSince1970 * 1000)) }
    func previewRecords(_ text: String) -> ImportPreview? { backup.preview(text: text, shelf: shelfBooks, comics: shelfComics) }

    func importRecords(_ text: String) -> ImportResult? {
        // 작품 설정(방향 · 표지 · 갈래 · 이름)도 들어오므로 아래 reloadRecords 가 작품을 다시 묶는다.
        let result = backup.apply(text: text, shelf: shelfBooks, comics: shelfComics)
        reloadRecords()
        return result
    }

    // MARK: 이름표(구상안 29A)

    /// 책 안의 제목 · 저자를 판(크기 · 고친 때)마다 기억한다. 훑을 때마다 EPUB 수백 권을 열면 서재가 몇 초씩 늦게 뜬다.
    struct Info: Codable, Sendable {
        let title: String
        let author: String?
    }

    private var infos: [String: Info] = Library.loadInfos()

    private nonisolated static var infoFile: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("book-info.json")
    }

    private static func loadInfos() -> [String: Info] {
        // 망가진 파일이면 비우고 다시 읽는다 — 이름표는 책에서 언제든 다시 얻는다.
        (try? Data(contentsOf: infoFile)).flatMap { try? JSONDecoder().decode([String: Info].self, from: $0) } ?? [:]
    }

    private static func saveInfos(_ infos: [String: Info]) {
        if let data = try? JSONEncoder().encode(infos) { try? data.write(to: infoFile, options: .atomic) }
    }

    private nonisolated static func infoKey(_ book: LibraryBook) -> String { "\(book.id)|\(book.edition)" }

    /// 읽고 돌아왔을 때 — 파일은 그대로고 기록만 바뀌었다.
    func reloadRecords() {
        let rows = store.shelf(ids: books.map(\.id))
        records = Dictionary(rows.map { ($0.bookId, $0) }, uniquingKeysWith: { a, _ in a })
        arrange()
        arrangeComics()
        // 이어 읽기 위젯(구상안 47): 책을 닫음 · 새로고침 · 앱이 뒤로 감 — 기록이 바뀌는 때마다.
        WidgetFeed.update(self)
        // 기기 간 이어 읽기(구상안 50): 조금 뒤 이 기기 기록을 올린다. 바뀐 것이 없으면 서버에 잇지도 않는다.
        SyncCenter.shared.recordsChanged()
    }

    // MARK: 만화(구상안 35A · 36)

    /// 만화 작품들(공통 `ComicShelf` 이 묶은 것)과 권마다 읽은 쪽.
    @Published private(set) var works: [Work] = []
    @Published private(set) var comicProgress: [String: ComicProgress] = [:]
    private var comicUnits: [ComicUnit] = []
    /// 단위 id → 그 파일(열 때).
    private var comicFiles: [String: ComicFile] = [:]

    struct ComicFile: Sendable {
        let url: URL
        let folderId: String?
        /// 그림 폴더면 그 안의 파일 이름들. 압축이면 nil.
        let images: [String]?
        let size: Int64
    }

    /// 작품 하나의 갈래(읽는 책 · 읽은 책 · 읽을 책)와 그 열쇠들(공통 `WorkStatuses`).
    func status(_ work: Work) -> WorkStatus { WorkStatuses.shared.of(work: work, progress: comicProgress, mark: work.shelfMark) }

    func arrangeComics() {
        comicProgress = store.comicProgress()
        works = IosComics.shared.group(units: comicUnits, overrides: store.comicOverrides())
    }

    /// 작품 설정을 바꿨다(넘기는 방향 · 표지 · 갈래) — 다시 묶는다.
    func setComicSetting(_ kind: String, work: Work, value: String?) {
        store.setComicSetting(kind: kind, subject: work.key, value: value)
        arrangeComics()
    }

    // 작품 정리(구상안 40): 앱 기록에 적고 다시 묶는다.
    func mergeWork(_ from: Work, into: Work) { store.mergeWork(from: from, into: into); arrangeComics() }
    func splitEntry(_ entry: WorkEntry) { store.splitEntry(entry: entry); arrangeComics() }
    func renameWork(_ work: Work, title: String) { store.renameWork(work: work, title: title); arrangeComics() }
    func preferCopy(_ entry: WorkEntry, unitId: String) { store.preferCopy(entry: entry, unitId: unitId); arrangeComics() }

    // 책 갈래 옮기기(구상안 43): 앱 기록에 적고 서재를 다시 짠다. 읽던 자리 · 독서노트는 그대로다.
    func setFinished(_ book: LibraryBook, _ finished: Bool) {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        store.setFinished(bookId: book.id, finishedAtEpochMs: finished ? KotlinLong(value: now) : nil, nowEpochMs: now)
        reloadRecords()
    }

    func returnToUnread(_ book: LibraryBook) { store.returnToUnread(bookId: book.id); reloadRecords() }

    /// 읽기 화면에서 서재로 돌아오며 띄울 알림(보던 장면에서 표지를 골랐다 등). 서재가 받아 띄우고 비운다.
    @Published var notice: String?

    /// 보던 장면에서 표지 고르기(구상안 44 ④): 이어 볼 권, 없으면 첫 권을 연다(안드로이드 `ComicReading.resume ?: first`).
    func sceneBook(_ workKey: String) -> LibraryBook? {
        guard let work = works.first(where: { $0.key == workKey }) else { return nil }
        let entry = ComicReading.shared.resume(work: work, progress: comicProgress)?.first ?? work.entries.first
        return entry.flatMap { comicBook($0.unit) }
    }

    func workTitle(_ workKey: String) -> String { works.first { $0.key == workKey }?.title ?? "" }

    // MARK: 그림 위주 책(구상안 46)

    /// 사람이 고른 보기("COMIC" · "BOOK"). 고르지 않았으면 nil — 판별대로.
    func bookView(_ id: String) -> String? { store.comicSetting(kind: IosPictureBooks.shared.VIEW, subject: id) }

    /// 판별해 둔 그림책 여부(파일이 바뀌었거나 아직 모르면 nil).
    func cachedPictureBook(_ book: LibraryBook) -> Bool? {
        guard book.format == .epub else { return false }
        return IosPictureBooks.shared.cached(store: store, bookId: book.id, size: book.sizeBytes)?.boolValue
    }

    /// 그림책인가 — 모르면 지금 판별해 둔다(책 판을 열 때 · 책을 열 때, 46 · 2A). 판별은 장마다 글을 훑어 큰 책은 느리다.
    func pictureBook(_ book: LibraryBook) async -> PictureBook? {
        guard book.format == .epub else { return nil }
        let end = beginAccess(book)
        defer { end() }
        let found = try? await IosPictureBooks.shared.detect(path: book.url.path)
        IosPictureBooks.shared.remember(store: store, bookId: book.id, size: book.sizeBytes, picture: found != nil,
                                        rightToLeft: found?.rightToLeft)
        return found
    }

    /// 만화로 볼지 고른다. 서재 표지(펼침면이면 앞 반)도 바뀌니 다시 그린다.
    func setBookView(_ book: LibraryBook, comic: Bool) {
        store.setComicSetting(kind: IosPictureBooks.shared.VIEW, subject: book.id, value: comic ? "COMIC" : "BOOK")
        Covers.shared.forget(book)
        CoverStore.shared.touch()
    }

    /// 만화로 보는 그림책의 넘기는 방향(사람이 고른 것 → 책에 적힌 것 → 왼→오).
    func bookRightToLeft(_ id: String) -> Bool {
        IosPictureBooks.shared.rightToLeft(chosen: store.comicSetting(kind: IosPictureBooks.shared.RTL, subject: id),
                                           book: IosPictureBooks.shared.cachedRightToLeft(store: store, bookId: id))
    }

    func setBookRightToLeft(_ book: LibraryBook, _ rtl: Bool) {
        store.setComicSetting(kind: IosPictureBooks.shared.RTL, subject: book.id, value: rtl ? "1" : "0")
        Covers.shared.forget(book)
        CoverStore.shared.touch()
    }

    /// 서재 표지를 앞 반으로 자를 방향 — 만화로 보는 그림책만(책으로 보면 표지 전체). nil 이면 자르지 않는다.
    func bookFace(_ book: LibraryBook) -> Bool? {
        let pdf = book.format == .pdf
        let picture = cachedPictureBook(book) == true
        guard pdf || picture, IosPictureBooks.shared.opensAsComic(view: bookView(book.id), pdf: pdf, picture: picture) else { return nil }
        return bookRightToLeft(book.id)
    }

    /// 판이 떠 있는 동안 갈래가 바뀌어도 지금 줄을 본다.
    func shelfItem(_ id: String) -> ShelfItem? { (reading + finished + toRead).first { $0.id == id } }

    func work(of unitId: String) -> Work? { works.first { w in w.entries.contains { e in e.unit.id == unitId || e.copies.contains { $0.id == unitId } } } }

    /// 만화 권을 리더가 여는 꼴로.
    func comicBook(_ unit: ComicUnit) -> LibraryBook? {
        guard let f = comicFiles[unit.id] else { return nil }
        return LibraryBook(id: unit.id, url: f.url, displayName: unit.name, format: .comic, sizeBytes: f.size,
                           modified: .distantPast, added: .distantPast, folderId: f.folderId)
    }

    func comicFile(_ id: String) -> ComicFile? { comicFiles[id] }

    /// 만화 단위를 훑는다: cbz · cbt · 그림만 든 zip(35A — cbr · cb7 은 아직 아니다), 그림 3장 이상 든 폴더. 무엇이 만화인지는 공통
    /// `ComicContents` 가 정한다.
    private nonisolated static func scanComics(folders: [LinkedFolder]) -> ([ComicUnit], [String: ComicFile], [String]) {
        var units: [ComicUnit] = []
        var files: [String: ComicFile] = [:]
        var read: [String] = []
        func walk(_ root: URL, _ prefix: String, _ folderId: String?) {
            let keys: [URLResourceKey] = [.isRegularFileKey, .fileSizeKey, .creationDateKey, .contentModificationDateKey, .ubiquitousItemDownloadingStatusKey]
            guard let walker = FileManager.default.enumerator(at: root, includingPropertiesForKeys: keys,
                                                              options: [.skipsPackageDescendants]) else { return }
            read.append(prefix)
            let rootPath = root.standardizedFileURL.path
            var byFolder: [String: [String]] = [:]
            for case let url as URL in walker {
                if Library.skipHidden(url, walker) { continue }
                guard let v = try? url.resourceValues(forKeys: Set(keys)), v.isRegularFile == true, Library.downloaded(url, v) else { continue }
                let path = url.standardizedFileURL.path
                let relative = path.hasPrefix(rootPath) ? String(path.dropFirst(rootPath.count).drop(while: { $0 == "/" })) : url.lastPathComponent
                let parentRel = (relative as NSString).deletingLastPathComponent
                byFolder[parentRel, default: []].append(url.lastPathComponent)
                let name = url.lastPathComponent
                guard IosComics.shared.opensArchive(name: name) else { continue }
                let id = (prefix + relative).precomposedStringWithCanonicalMapping
                let size = Int64(v.fileSize ?? 0)
                let added = Int64((v.creationDate ?? v.contentModificationDate ?? .distantPast).timeIntervalSince1970 * 1000)
                let parents = parentRel.isEmpty ? [] : parentRel.split(separator: "/").map(String.init)
                let probeKey = "\(id)|\(size)|\(v.contentModificationDate?.timeIntervalSince1970 ?? 0)"
                if let unit = Library.probed(probeKey) ?? IosComics.shared.probe(path: url.path, id: id, name: name, folders: parents, sizeBytes: size, addedAt: added) {
                    Library.remember(probeKey, unit)
                    units.append(unit)
                    files[id] = ComicFile(url: url, folderId: folderId, images: nil, size: size)
                }
            }
            // 그림 폴더: 바로 아래 파일들로 판정(앱 폴더 자체는 빼고 — 거기 그림 몇 장은 작품이 아니다).
            for (rel, names) in byFolder where !rel.isEmpty {
                let url = root.appendingPathComponent(rel, isDirectory: true)
                let parts = rel.split(separator: "/").map(String.init)
                let id = (prefix + rel + "/").precomposedStringWithCanonicalMapping
                let added = Int64(((try? url.resourceValues(forKeys: [.creationDateKey]).creationDate) ?? .distantPast).timeIntervalSince1970 * 1000)
                if let unit = IosComics.shared.folder(id: id, name: parts.last ?? rel, folders: Array(parts.dropLast()), fileNames: names, addedAt: added) {
                    units.append(unit)
                    files[id] = ComicFile(url: url, folderId: folderId, images: names, size: 0)
                }
            }
        }
        walk(appFolder, "app:", nil)
        for folder in folders {
            var stale = false
            guard let url = try? URL(resolvingBookmarkData: folder.bookmark, bookmarkDataIsStale: &stale) else { continue }
            let access = url.startAccessingSecurityScopedResource()
            defer { if access { url.stopAccessingSecurityScopedResource() } }
            walk(url, "link:\(folder.id):", folder.id)
        }
        return (units, files, read)
    }

    /// 책들과 그 이름표. 이름표는 폴더 접근이 열려 있는 이 자리에서 읽는다(연결한 폴더는 훑는 동안만 열린다).
    private nonisolated static func scan(folders: [LinkedFolder], known: [String: Info]) -> ([LibraryBook], [String: Info], [String]) {
        var seen: [String: Info] = [:]
        func named(_ books: [LibraryBook]) -> [LibraryBook] {
            books.map { book in
                let key = infoKey(book)
                let info = known[key] ?? {
                    let i = IosBookReader.companion.info(path: book.url.path, displayName: book.displayName)
                    return Info(title: i.title, author: i.author)
                }()
                seen[key] = info
                var b = book
                b.title = info.title
                b.author = info.author
                return b
            }
        }
        var read: [String] = []
        var out: [LibraryBook] = []
        if let found = scan(root: appFolder, prefix: "app:", folderId: nil) {
            out = named(found)
            read.append("app:")
        }
        for folder in folders {
            var stale = false
            guard let url = try? URL(resolvingBookmarkData: folder.bookmark, bookmarkDataIsStale: &stale) else { continue }
            let access = url.startAccessingSecurityScopedResource()
            defer { if access { url.stopAccessingSecurityScopedResource() } }
            // 읽지 못한 폴더(권한이 끊겼다 등)는 "읽은 폴더" 에 넣지 않는다 — 그 파일들을 사라진 것으로 치지 않게.
            if let found = scan(root: url, prefix: "link:\(folder.id):", folderId: folder.id) {
                out += named(found)
                read.append("link:\(folder.id):")
            }
        }
        return (out, seen, read)
    }

    /// 읽지 못하면 nil(빈 폴더와 가린다).
    private nonisolated static func scan(root: URL, prefix: String, folderId: String?) -> [LibraryBook]? {
        let keys: [URLResourceKey] = [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey, .creationDateKey, .ubiquitousItemDownloadingStatusKey]
        guard let walker = FileManager.default.enumerator(at: root, includingPropertiesForKeys: keys,
                                                          options: [.skipsPackageDescendants]) else { return nil }
        let rootPath = root.standardizedFileURL.path
        var out: [LibraryBook] = []
        for case let url as URL in walker {
            if skipHidden(url, walker) { continue }
            let format: LibraryBook.Format
            switch url.pathExtension.lowercased() {
            case "epub": format = .epub
            case "txt": format = .txt
            case "pdf": format = .pdf
            default: continue
            }
            guard let values = try? url.resourceValues(forKeys: Set(keys)), values.isRegularFile == true, downloaded(url, values) else { continue }
            let path = url.standardizedFileURL.path
            let relative = path.hasPrefix(rootPath) ? String(path.dropFirst(rootPath.count).drop(while: { $0 == "/" })) : url.lastPathComponent
            out.append(LibraryBook(
                // 한글을 모아 쓴 꼴(NFC)로 — 훑어 받은 경로는 풀어 쓴 꼴이다. 기록 파일(Kotlin)은 두 꼴을 다른 글로 본다.
                id: (prefix + relative).precomposedStringWithCanonicalMapping,
                url: url,
                displayName: url.lastPathComponent,
                format: format,
                sizeBytes: Int64(values.fileSize ?? 0),
                modified: values.contentModificationDate ?? .distantPast,
                added: values.creationDate ?? values.contentModificationDate ?? .distantPast,
                folderId: folderId
            ))
        }
        return out
    }

    /// 만화 압축 판별 결과(이 실행 동안, 판 열쇠 = id · 크기 · 고친 때). 새로고침 · 받은 파일마다 모든 압축을 다시 열었다.
    nonisolated(unsafe) private static var probes: [String: ComicUnit] = [:]
    nonisolated private static let probeLock = NSLock()

    nonisolated static func probed(_ key: String) -> ComicUnit? {
        probeLock.lock()
        defer { probeLock.unlock() }
        return probes[key]
    }

    nonisolated static func remember(_ key: String, _ unit: ComicUnit) {
        probeLock.lock()
        defer { probeLock.unlock() }
        probes[key] = unit
    }

    /// 숨은 파일 · 폴더는 건너뛴다. 다만 iCloud 에서 아직 내려받지 않은 파일의 자리표(".이름.icloud")는 내려받기를 시작해 둔다 —
    /// 다음 훑기에 나타난다.
    nonisolated static func skipHidden(_ url: URL, _ walker: FileManager.DirectoryEnumerator) -> Bool {
        let name = url.lastPathComponent
        guard name.hasPrefix(".") else { return false }
        if name.hasSuffix(".icloud"), name.count > 8 {
            let real = url.deletingLastPathComponent().appendingPathComponent(String(name.dropFirst().dropLast(7)))
            try? FileManager.default.startDownloadingUbiquitousItem(at: real)
        }
        if (try? url.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true { walker.skipDescendants() }
        return true
    }

    /// 내려받아져 있는가(iCloud 가 아닌 파일은 늘 참). 아니면 내려받기를 시작하고 이번 훑기에서는 뺀다 — 덜 받은 파일을 열어 이름표 ·
    /// 만화 판별을 하면 실패한 결과(파일 이름이 제목 · 만화 아님)가 같은 판의 열쇠로 남아 받은 뒤에도 고쳐지지 않았다.
    nonisolated static func downloaded(_ url: URL, _ values: URLResourceValues) -> Bool {
        // .downloaded(받았지만 최신이 아님)는 읽을 수 있다 — 아예 받지 않은 것만.
        guard values.ubiquitousItemDownloadingStatus == .notDownloaded else { return true }
        try? FileManager.default.startDownloadingUbiquitousItem(at: url)
        return false
    }

    // MARK: 갈래

    private func arrange() {
        let items = books.map { book -> ShelfItem in
            let r = records[book.id]
            return ShelfItem(
                book: book,
                openedAt: r?.openedAtEpochMs.map { Date(timeIntervalSince1970: Double($0.int64Value) / 1000) },
                finishedAt: r?.finishedAtEpochMs.map { Date(timeIntervalSince1970: Double($0.int64Value) / 1000) },
                percent: r?.percent.map { Double($0.floatValue) },
                bookmarkCount: Int(r?.bookmarkCount ?? 0)
            )
        }
        // 안드로이드와 같은 갈래: 연 적 있고 다 읽지 않음 = 읽는 중, 다 읽음 = 읽은 책, 연 적 없음 = 읽을 책.
        reading = sorted(items.filter { $0.openedAt != nil && $0.finishedAt == nil }) { ($0.openedAt ?? .distantPast) > ($1.openedAt ?? .distantPast) }
        finished = sorted(items.filter { $0.finishedAt != nil }) { ($0.finishedAt ?? .distantPast) > ($1.finishedAt ?? .distantPast) }
        // "최근 읽은 순" 은 연 적 없는 책에 뜻이 없어 이름순으로 내려간다(안드로이드와 같다).
        toRead = sorted(items.filter { $0.openedAt == nil }, recent: nil)
    }

    private func sorted(_ items: [ShelfItem], recent: ((ShelfItem, ShelfItem) -> Bool)?) -> [ShelfItem] {
        let byName: (ShelfItem, ShelfItem) -> Bool = {
            $0.book.label.compare($1.book.label, options: [.numeric, .caseInsensitive], locale: Library.korean) == .orderedAscending
        }
        switch sort {
        case .recent: return items.sorted(by: recent ?? byName)
        case .name: return items.sorted(by: byName)
        case .added: return items.sorted { $0.book.added != $1.book.added ? $0.book.added > $1.book.added : byName($0, $1) }
        case .size: return items.sorted { $0.book.sizeBytes != $1.book.sizeBytes ? $0.book.sizeBytes > $1.book.sizeBytes : byName($0, $1) }
        }
    }

    // MARK: 폴더

    func link(folder url: URL) async {
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil) else { return }
        // 같은 폴더를 두 번 연결하면 책이 두 번 보인다. 북마크 바이트는 만들 때마다 달라 경로로 견준다.
        let path = url.standardizedFileURL.path
        if folders.contains(where: { f in
            var stale = false
            return (try? URL(resolvingBookmarkData: f.bookmark, bookmarkDataIsStale: &stale))?.standardizedFileURL.path == path
        }) { return }
        folders.append(LinkedFolder(id: UUID().uuidString, name: url.lastPathComponent, bookmark: data))
        persistFolders()
        await refresh()
    }

    /// 낡은 북마크(폴더를 옮기거나 이름을 바꿈 · iCloud 동기화 뒤)는 아직 풀릴 때 새로 만들어 둔다. 그대로 두면 언젠가 풀리지 않아
    /// 그 폴더의 책들이 말없이 사라진다.
    private func renewStaleBookmarks() {
        var changed = false
        for (i, folder) in folders.enumerated() {
            var stale = false
            guard let url = try? URL(resolvingBookmarkData: folder.bookmark, bookmarkDataIsStale: &stale), stale else { continue }
            let access = url.startAccessingSecurityScopedResource()
            defer { if access { url.stopAccessingSecurityScopedResource() } }
            if let fresh = try? url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil) {
                folders[i] = LinkedFolder(id: folder.id, name: url.lastPathComponent, bookmark: fresh)
                changed = true
            }
        }
        if changed { persistFolders() }
    }

    func unlink(_ folder: LinkedFolder) async {
        folders.removeAll { $0.id == folder.id }
        persistFolders()
        await refresh()
    }

    private func persistFolders() {
        if let data = try? JSONEncoder().encode(folders) { UserDefaults.standard.set(data, forKey: "library.folders") }
    }

    /// 연결한 폴더의 책을 읽는 동안 접근을 열어 둔다. 닫을 때 돌려받은 것을 부른다.
    func beginAccess(_ book: LibraryBook) -> () -> Void { beginAccess(folderId: book.folderId) }

    func beginAccess(folderId: String?) -> () -> Void {
        guard let folderId, let folder = folders.first(where: { $0.id == folderId }) else { return {} }
        var stale = false
        guard let url = try? URL(resolvingBookmarkData: folder.bookmark, bookmarkDataIsStale: &stale) else { return {} }
        let access = url.startAccessingSecurityScopedResource()
        return { if access { url.stopAccessingSecurityScopedResource() } }
    }

    // MARK: 다른 앱에서 열기

    /// 다른 앱이 넘긴 파일을 받은 파일 폴더로 들여와 그 책을 돌려준다. 같은 이름 · 같은 크기면 다시 복사하지 않는다 —
    /// 같은 책을 두 번 열 때마다 사본이 생기면 서재가 같은 책으로 찬다.
    func receive(_ url: URL) async -> LibraryBook? {
        let ext = url.pathExtension.lowercased()
        guard ext == "epub" || ext == "txt" || ext == "pdf" else { return nil }
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        let fm = FileManager.default
        try? fm.createDirectory(at: Library.incomingFolder, withIntermediateDirectories: true)
        let target = Library.incomingFolder.appendingPathComponent(url.lastPathComponent)
        let size = { (u: URL) in (try? fm.attributesOfItem(atPath: u.path)[.size] as? Int64) ?? nil }
        // iOS 가 "복사해서 열기" 로 넘긴 파일은 앱 폴더 안 Inbox 에 놓인다 — 거기서 또 복사하면 앱 폴더를 훑을 때 같은 책이 두 권 보였다.
        // 그때는 옮긴다(받은 파일 폴더 하나에만 남게).
        let inbox = Library.appFolder.appendingPathComponent("Inbox", isDirectory: true).standardizedFileURL.path + "/"
        let fromInbox = url.standardizedFileURL.path.hasPrefix(inbox)
        if url.standardizedFileURL != target.standardizedFileURL {
            if fm.fileExists(atPath: target.path), size(target) == size(url) {
                // 이미 들였다.
                if fromInbox { try? fm.removeItem(at: url) }
            } else {
                try? fm.removeItem(at: target)
                do {
                    if fromInbox { try fm.moveItem(at: url, to: target) } else { try fm.copyItem(at: url, to: target) }
                } catch { return nil }
            }
        }
        await refresh()
        return (reading + toRead + finished).first { $0.book.url.standardizedFileURL == target.standardizedFileURL }?.book
    }
}

/// EPUB 표지 그림. 서재를 넘길 때마다 zip 을 다시 열지 않게 기억한다.
final class Covers: @unchecked Sendable {
    static let shared = Covers()
    private let cache: NSCache<NSString, UIImage> = {
        let c = NSCache<NSString, UIImage>()
        // 줄인 표지 한 장이 1MB 남짓 — 100MB 를 넘기면 오래 안 본 것부터 내려놓는다.
        c.totalCostLimit = 100 * 1024 * 1024
        return c
    }()
    private let missing = NSCache<NSString, NSNumber>()

    /// 서재 칸에 쓰는 표지 크기(긴 변, 픽셀). 원본 그대로 쥐면 EPUB 표지 한 장이 10MB 를 넘기도 해, 책이 수백 권이면 메모리가 넘쳤다.
    static let maxPixel: CGFloat = 900

    /// 서재에 보일 표지: 고른 표지(구상안 44)가 있으면 그것, 없으면 책의 것. 만화로 보는 그림책은 펼침면 표지의 앞 반(구상안 46,
    /// 공통 `CoverCrop.front`) — 만화 탭의 권 표지와 같은 규칙.
    func load(_ book: LibraryBook, library: Library) async -> UIImage? {
        if let picked = CoverStore.shared.custom(book.id) { return picked }
        guard let image = await own(book, library: library) else { return nil }
        guard let rtl = await library.bookFace(book), let cg = image.cgImage, image.size.width > image.size.height * 1.15 else { return image }
        let range = CoverCrop.shared.front(width: Int32(cg.width), height: Int32(cg.height), rightToLeft: rtl)
        let rect = CGRect(x: Int(range.first), y: 0, width: Int(range.last) - Int(range.first) + 1, height: cg.height)
        return cg.cropping(to: rect).map { UIImage(cgImage: $0) } ?? image
    }

    /// 보기 · 방향이 바뀌었다 — 다음에 그릴 때 새로.
    func forget(_ book: LibraryBook) { cache.removeObject(forKey: key(book)) }

    /// 책에 든 표지(EPUB 표지 그림 · PDF 첫 쪽). 판의 "원래 표지로" · 설명 줄이 이것이 있는지 본다.
    func own(_ book: LibraryBook, library: Library) async -> UIImage? {
        let key = key(book)
        if let image = cache.object(forKey: key) { return image }
        if missing.object(forKey: key) != nil || book.format == .txt { return nil }
        let end = await library.beginAccess(book)
        defer { end() }
        let path = book.url.path
        let pdf = book.format == .pdf
        let image = await Task.detached(priority: .utility) { () -> UIImage? in
            let full = pdf ? PdfPages.cover(url: URL(fileURLWithPath: path)) : IosBookReader.companion.cover(path: path)
            return full.map { Covers.shrink($0) }
        }.value
        if let image {
            cache.setObject(image, forKey: key, cost: Int(image.size.width * image.size.height * image.scale * image.scale * 4))
        } else {
            missing.setObject(1, forKey: key)
        }
        return image
    }

    private func key(_ book: LibraryBook) -> NSString { "\(book.id)|\(book.edition)" as NSString }

    /// 긴 변 [maxPixel] 로 줄인다(더 작으면 그대로). 원본은 이 함수를 나가면 놓인다.
    static func shrink(_ image: UIImage) -> UIImage {
        let w = image.size.width * image.scale, h = image.size.height * image.scale
        let k = maxPixel / max(w, h)
        guard k < 1 else { return image }
        return image.preparingThumbnail(of: CGSize(width: (w * k).rounded(), height: (h * k).rounded())) ?? image
    }
}
