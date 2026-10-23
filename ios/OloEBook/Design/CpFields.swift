import SwiftUI

/// 이름이 붙은 한 줄 입력칸(안드로이드 `CpTextField`, 이어 읽기의 서버 설정). 이름은 칸 위에 늘 보인다 — 칸 안의 흐린 글만 두면 글을
/// 넣는 순간 무슨 칸이었는지 사라져, 주소 · 사용자 · 경로처럼 비슷한 칸이 여럿일 때 헷갈린다. 테두리는 평소 outline, 입력 중이면 강조색 2.
///
/// [secret] 은 비밀번호: 글자를 점으로 가리고 자판의 낱말 추천 · 기억을 끈다(입력한 비밀번호가 추천 목록에 남지 않게).
struct CpTextField: View {
    let label: String
    @Binding var text: String
    var placeholder = ""
    var secret = false
    var keyboard: UIKeyboardType = .default
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).font(CpFont.caption).foregroundStyle(CpColor.textMuted)
            Group {
                if secret {
                    SecureField("", text: $text, prompt: Text(placeholder).foregroundStyle(CpColor.textMuted))
                        .textContentType(.password)
                } else {
                    TextField("", text: $text, prompt: Text(placeholder).foregroundStyle(CpColor.textMuted))
                        .keyboardType(keyboard)
                }
            }
            .font(CpFont.body).foregroundStyle(CpColor.text).tint(CpColor.accent)
            .textInputAutocapitalization(.never).autocorrectionDisabled()
            .focused($focused)
            .padding(.horizontal, 14)
            .frame(minHeight: CpMetrics.touchTarget)
            .overlay(RoundedRectangle(cornerRadius: CpMetrics.cornerMedium).stroke(focused ? CpColor.accent : CpColor.outline, lineWidth: focused ? 2 : 1))
            .accessibilityLabel(label)
        }
        .padding(.horizontal, CpMetrics.gutter).padding(.vertical, 6)
    }
}

/// 리더 위 "더 읽었습니다" 띠(안드로이드 `CpSyncPrompt`, 결정 8-2). 읽기를 막지 않는 알림 — 띠 안을 누른 것이 아래 쪽 넘김으로 새지
/// 않게 띠가 먹는다. 태블릿 · 가로에서 폭 끝까지 늘면 글과 단추가 양 끝으로 갈라져 한 덩이로 읽히지 않아 600 까지만.
struct CpSyncPrompt: View {
    let title: String
    let detail: String
    var action = "거기로"
    let onGo: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(CpFont.body).foregroundStyle(CpColor.text).lineLimit(2)
                Text(detail).font(CpFont.caption).foregroundStyle(CpColor.textMuted)
            }
            Spacer(minLength: 0)
            Button(action: onGo) {
                Text(action).font(CpFont.label).foregroundStyle(CpColor.accentText)
                    .padding(.horizontal, 12).frame(minHeight: CpMetrics.touchTarget).contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .padding(.leading, 16).padding(.trailing, 8).padding(.vertical, 10)
        .background(CpColor.surface)
        .clipShape(RoundedRectangle(cornerRadius: CpMetrics.cornerMedium))
        .shadow(color: .black.opacity(0.18), radius: 6, y: 2)
        .contentShape(Rectangle())
        .onTapGesture {}
        .frame(maxWidth: 600)
        .padding(16)
        .accessibilityElement(children: .contain)
    }
}

/// 다른 기기가 읽은 때(안드로이드 `syncWhenText`): 오늘이면 "오늘 21:40", 어제면 "어제 21:40", 올해면 "10월 3일 21:40", 그 전이면
/// "2025년 10월 3일". 24시간제로 적는다 — 띠의 한 줄 안에 "오후" 까지 넣으면 쪽 수가 밀려 잘린다.
enum SyncWhen {
    static func text(_ date: Date, now: Date = Date(), calendar: Calendar = .current) -> String {
        let c = calendar.dateComponents([.year, .month, .day, .hour, .minute], from: date)
        let clock = String(format: "%d:%02d", c.hour ?? 0, c.minute ?? 0)
        if calendar.isDate(date, inSameDayAs: now) { return "오늘 \(clock)" }
        if let y = calendar.date(byAdding: .day, value: -1, to: now), calendar.isDate(date, inSameDayAs: y) { return "어제 \(clock)" }
        if c.year == calendar.component(.year, from: now) { return "\(c.month ?? 0)월 \(c.day ?? 0)일 \(clock)" }
        return "\(c.year ?? 0)년 \(c.month ?? 0)월 \(c.day ?? 0)일"
    }
}
