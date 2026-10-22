import CryptoKit
import Foundation
import ImageIO
import OloCore
import UIKit

/// 고른 표지(구상안 44, 안드로이드 `CoverStore` 의 custom 칸). 책은 책 id, 작품은 `IosCovers.workId` 로 한 장씩 둔다.
///
/// 캐시 폴더가 아니라 Application Support 에 둔다 — 캐시는 시스템이 공간이 모자라면 말없이 지워, 고른 표지가 어느 날 사라진다.
/// 기록 백업 파일에는 담지 않는다(안드로이드와 같다 — 그림까지 담으면 백업이 커진다). 기기 백업에는 담긴다.
final class CoverStore: ObservableObject, @unchecked Sendable {
    static let shared = CoverStore()

    /// 바뀔 때마다 하나씩 는다 — 표지 칸이 이 값으로 다시 읽는다. 바꾼 표지가 서재에 바로 보이지 않으면 저장이 안 된 줄 안다.
    @Published private(set) var version = 0

    private let dir: URL
    private let cache = NSCache<NSString, UIImage>()
    /// 파일이 없다는 것도 기억한다 — 서재를 넘길 때마다 칸마다 디스크를 묻지 않게.
    private let none = NSCache<NSString, NSNumber>()

    private init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        dir = base.appendingPathComponent("covers/custom", isDirectory: true)
    }

    /// 파일 이름: id 의 SHA-1 앞 20자. id 는 경로라 '/' · 한글이 섞여 그대로 파일 이름이 될 수 없다.
    private func url(_ id: String) -> URL {
        let digest = Insecure.SHA1.hash(data: Data(id.utf8)).map { String(format: "%02x", $0) }.joined()
        return dir.appendingPathComponent(String(digest.prefix(20)) + ".jpg")
    }

    func custom(_ id: String) -> UIImage? {
        let key = id as NSString
        if let img = cache.object(forKey: key) { return img }
        if none.object(forKey: key) != nil { return nil }
        guard let img = UIImage(contentsOfFile: url(id).path) else { none.setObject(1, forKey: key); return nil }
        cache.setObject(img, forKey: key)
        return img
    }

    func has(_ id: String) -> Bool { custom(id) != nil }

    /// 사진 · 파일의 바이트를 표지로. 크기는 `IosCovers.fit`(높이 480, 키우지 않음, 폭 960 까지), 돌려 찍은 사진은 바로 세운다.
    /// 그림이 아니거나 깨졌거나 쓰지 못하면 false — 판이 "이 그림을 표지로 쓸 수 없습니다" 를 띄운다.
    func set(_ id: String, data: Data) -> Bool {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any],
              let w = props[kCGImagePropertyPixelWidth] as? Int, let h = props[kCGImagePropertyPixelHeight] as? Int else { return false }
        let turned = ((props[kCGImagePropertyOrientation] as? Int) ?? 1) >= 5
        guard let size = IosCovers.shared.fit(width: Int32(turned ? h : w), height: Int32(turned ? w : h)) else { return false }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: max(Int(size.width), Int(size.height)),
        ]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, options as CFDictionary) else { return false }
        return write(id, UIImage(cgImage: cg))
    }

    /// 쪽 그림(보던 장면에서)을 표지로. 이미 자른 그림을 받아 크기만 맞춘다.
    func set(_ id: String, image: UIImage) -> Bool {
        guard let cg = image.cgImage, let size = IosCovers.shared.fit(width: Int32(cg.width), height: Int32(cg.height)) else { return false }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let out = UIGraphicsImageRenderer(size: CGSize(width: Int(size.width), height: Int(size.height)), format: format).image { _ in
            image.draw(in: CGRect(x: 0, y: 0, width: Int(size.width), height: Int(size.height)))
        }
        return write(id, out)
    }

    func clear(_ id: String) {
        try? FileManager.default.removeItem(at: url(id))
        changed(id)
    }

    /// 통째로 바꿔 넣는다(atomic) — 쓰다 멈춰도 반쯤 쓴 표지가 남지 않는다. 디스크가 차서 못 쓰면 false.
    private func write(_ id: String, _ image: UIImage) -> Bool {
        guard let data = image.jpegData(compressionQuality: IosCovers.shared.JPEG_QUALITY) else { return false }
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            try data.write(to: url(id), options: .atomic)
        } catch {
            return false
        }
        changed(id)
        return true
    }

    private func changed(_ id: String) {
        cache.removeObject(forKey: id as NSString)
        none.removeObject(forKey: id as NSString)
        if Thread.isMainThread { version += 1 } else { DispatchQueue.main.async { self.version += 1 } }
    }
}
