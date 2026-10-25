import SwiftUI

/// 앱에 들어간 남의 것 하나(구상안 51 · 1A, 안드로이드 `OpenLicense`). 본문은 원문 그대로 싣는다 — BSD · Apache · MIT 모두 "배포할
/// 때 고지문을 함께 싣는다" 가 허가 조건이다. 이 화면이 생기기 전 iOS 앱에는 어디에도 고지가 없었다.
struct OpenLicense: Identifiable {
    let title: String
    /// 목록에서 제목 아래 — 무엇이고 어디에 쓰는지.
    let subtitle: String
    /// 목록 오른쪽의 짧은 이름.
    let badge: String
    /// 본문 화면 머리줄 부제.
    let licenseName: String
    /// 본문 위 한 줄 — 출처(묶은 줄은 든 것 모두 · 판).
    let source: String
    /// 앱 자원(Resources/Licenses)의 파일 이름들, 차례대로 이어 붙인다 — Apache 는 알림(NOTICE)이 먼저.
    let files: [String]
    var id: String { title }

    /// 문단들(제목이면 true). Apache 본문(1만 자)을 한 덩이로 그리면 처음 열 때 멈칫한다 — 문단마다 한 항목(안드로이드와 같다).
    /// 알림(NOTICE)이 붙은 줄은 알림 · 허가 본문 앞에 제목을 둔다(구상안 51 ②) — 두 글이 이어 붙어 어디서 바뀌는지 보이지 않았다.
    var paragraphs: [(text: String, heading: Bool)] {
        let notices = files.contains { $0.hasPrefix("notice_") }
        return files.flatMap { name -> [(text: String, heading: Bool)] in
            guard let url = Bundle.main.url(forResource: name, withExtension: "txt"),
                  let text = try? String(contentsOf: url, encoding: .utf8) else { return [] }
            let body = text.components(separatedBy: "\n\n").map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
            guard notices else { return body.map { ($0, false) } }
            return [(name.hasPrefix("notice_") ? "알림(NOTICE)" : licenseName, true)] + body.map { ($0, false) }
        }
    }

    /// 구상안 51 · 1A: 같은 곳(Apple) · 같은 허가인 스위프트 패키지 8개는 한 줄로 — 판 · 출처는 Package.resolved 그대로.
    static let all: [OpenLicense] = [
        OpenLicense(title: "Adobe-KR 글자 표", subtitle: "깨진 PDF 글꼴의 한글을 되살릴 때 씁니다", badge: "BSD",
                    licenseName: "BSD 3조항 라이선스",
                    source: "Adobe cmap-resources의 Adobe-KR-9 표 (github.com/adobe-type-tools/cmap-resources)",
                    files: ["license_adobe_kr"]),
        OpenLicense(title: "LZMA SDK (7-Zip)", subtitle: "cb7(7z) 만화를 풀 때 씁니다", badge: "공개 영역",
                    licenseName: "공개 영역(Public Domain)",
                    source: "이고르 파블로프의 LZMA SDK 26.03, 7z 해제 부분 (7-zip.org/sdk.html)",
                    files: ["license_lzma_sdk"]),
        OpenLicense(title: "UnRAR", subtitle: "cbr(RAR) 만화를 풀 때 씁니다 — 읽기만 합니다", badge: "UnRAR",
                    licenseName: "UnRAR 라이선스",
                    source: "알렉산드르 로샬의 무료 UnRAR 원본 (rarlab.com/rar_add.htm)",
                    files: ["license_unrar"]),
        OpenLicense(title: "Kotlin · kotlinx.coroutines", subtitle: "JetBrains — 안드로이드와 함께 쓰는 읽기 엔진", badge: "Apache 2.0",
                    licenseName: "Apache 라이선스 2.0",
                    source: "JetBrains (kotlinlang.org · github.com/Kotlin/kotlinx.coroutines)",
                    files: ["license_apache_2_0"]),
        OpenLicense(title: "SwiftNIO · Swift Crypto 외 6개", subtitle: "Apple — 기기 간 이어 읽기의 서버 연결", badge: "Apache 2.0",
                    licenseName: "Apache 라이선스 2.0",
                    source: "Apple 의 swift-nio 2.104.0 · swift-nio-ssh 0.3.7(Wellz26 판) · swift-crypto 3.15.1(BoringSSL 포함) · swift-asn1 1.7.3 · "
                        + "swift-atomics 1.3.1 · swift-collections 1.7.4 · swift-log 1.16.2 · swift-system 1.8.1 (github.com/apple)",
                    files: ["notice_apple_swift", "license_apache_2_0"]),
        OpenLicense(title: "Citadel", subtitle: "기기 간 이어 읽기의 SFTP 연결", badge: "MIT",
                    licenseName: "MIT 라이선스",
                    source: "Orlandos 의 Citadel 0.12.1 (github.com/orlandos-nl/Citadel)",
                    files: ["license_citadel"]),
        OpenLicense(title: "BigInt", subtitle: "SFTP 연결의 키 계산", badge: "MIT",
                    licenseName: "MIT 라이선스",
                    source: "Károly Lőrentey 의 BigInt 5.7.0 (github.com/attaswift/BigInt)",
                    files: ["license_bigint"]),
    ]
}

/// 라이선스 본문(구상안 51 ②): 머리 부제는 허가 이름, 맨 위 출처 한 줄, 아래는 원문. 넓은 화면은 600 폭(2A — 앱 정보와 같다).
struct LicenseScreen: View {
    let license: OpenLicense
    let back: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            CpHeader(title: license.title, subtitle: license.licenseName,
                     leading: AnyView(CpIconButton(icon: .back, label: "뒤로", action: back))) {}
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 16) {
                    Text(license.source).font(CpFont.subtitle).foregroundStyle(CpColor.textMuted)
                        .fixedSize(horizontal: false, vertical: true).padding(.top, 8)
                    ForEach(Array(license.paragraphs.enumerated()), id: \.offset) { _, p in
                        Text(p.text).font(p.heading ? CpFont.label : CpFont.caption).foregroundStyle(CpColor.text)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .padding(.horizontal, CpMetrics.gutter).padding(.bottom, 24)
                // 앱 정보와 같이 왼쪽에 붙인 600 — 머리줄(왼쪽)과 본문의 시작선이 어긋나지 않게.
                .frame(maxWidth: 600, alignment: .leading)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .background(CpColor.background)
    }
}
