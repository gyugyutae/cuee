# 코드 아키텍처: KorailTalk MVP

## 원칙

- 코레일톡 단일 앱만 지원한다.
- Kotlin Android Native 단일 모듈.
- 실제 코레일톡 위 `AccessibilityService` + `TYPE_ACCESSIBILITY_OVERLAY`.
- AI API, 대신 누르기, 자동 예매, 로그인, 결제, 인증, 예매 확정 없음.
- 빠른 안내보다 정확한 안내가 우선.
- Android service는 얇게, 판단 로직은 unit test 가능한 domain layer에 둔다.

## Runtime Flow

```text
cue bubble tap
 -> LISTENING
 -> SpeechController
 -> KorailCommandParser
 -> AccessibilitySnapshotMapper
 -> SafetyPolicy
 -> TargetScorer
 -> CandidateResolver
 -> GuideSession
 -> MaskOverlayController
 -> user taps exposed app area
 -> next accessibility event
 -> repeat up to 3 steps
```

Stop if:

- package가 코레일톡이 아님
- 로그인/개인정보/결제/인증/예매 확정 화면
- 후보 score 낮음
- 후보 겹침 해소 실패
- 사용자 취소
- 3-step 도달

## Package Layout

```text
app/src/main/java/com/cuee/
  data/
    SettingsRepository.kt
    DataStoreSettingsRepository.kt
    UtMetricRepository.kt
  domain/
    command/
      KorailCommand.kt
      KorailCommandParser.kt
      DefaultKorailCommandParser.kt
    safety/
      SafetyPolicy.kt
      DefaultSafetyPolicy.kt
      SafetyDecision.kt
      StopReason.kt
    scoring/
      Bounds.kt
      ScreenNode.kt
      ScreenSnapshot.kt
      TargetCandidate.kt
      TargetScorer.kt
      KorailTargetScorer.kt
      CandidateResolver.kt
      ClusterCandidateResolver.kt
    session/
      GuideState.kt
      GuideSession.kt
      DefaultGuideSession.kt
      GuideStepResult.kt
      OverlayInstruction.kt
  accessibility/
    AccessibilitySnapshotMapper.kt
    KorailScreenAnalyzer.kt
  overlay/
    BubbleOverlayController.kt
    MaskOverlayController.kt
    CandidateHighlighter.kt
    OverlayLayoutCalculator.kt
  speech/
    AndroidSpeechController.kt
    AndroidTtsController.kt
  service/
    CueAccessibilityService.kt
  ui/
    MainActivity.kt
```

## Module Contracts

### CueAccessibilityService

Android lifecycle/orchestration only:

- 접근성 이벤트 수신
- root node 제공
- overlay attach/detach
- speech/domain/overlay 연결

금지:

- parsing/scoring/safety 정책 직접 구현
- 화면/음성 원문 저장

### Speech

```kotlin
interface SpeechController {
    fun startListening(localeTag: String = "ko-KR")
    fun stopListening()
}
```

- MVP locale은 `ko-KR`.
- bubble 외부 터치 또는 코레일톡 화면 클릭 시 listening 중단.
- STT 원문은 parser 입력으로만 사용하고 저장하지 않음.

### Command

```kotlin
interface KorailCommandParser {
    fun parse(utterance: String): KorailCommand?
}
```

- 한국어 명령 2개만 지원.
- 지원 밖 요청은 빠르게 실패.

### Accessibility Mapping

```kotlin
interface AccessibilitySnapshotMapper {
    fun map(root: AccessibilityNodeInfo?): ScreenSnapshot
}
```

- Android node 객체를 오래 보관하지 않음.
- text/contentDescription은 메모리 분석에만 사용.

### Safety

```kotlin
interface SafetyPolicy {
    fun evaluate(snapshot: ScreenSnapshot): SafetyDecision
}
```

차단: 로그인, 개인정보, 인증번호, 결제, 예매 확정, 구매 확정.

### Scoring

```kotlin
interface TargetScorer {
    fun score(snapshot: ScreenSnapshot, command: KorailCommand): List<TargetCandidate>
}

interface CandidateResolver {
    fun resolve(candidates: List<TargetCandidate>): List<TargetCandidate>
}
```

Scoring rules:

- 정확 키워드 +60
- 부분 키워드 +35
- contentDescription +45
- clickable +20
- class/position hint +10
- 일반 텍스트 -20
- 긴 문장 -30
- 민감 키워드는 제외

Candidate rules:

- 80+ strong, 65-79 weak, below 65 excluded.
- 겹치는 rect는 cluster 후 최고 score만 유지.
- 최대 3개 노출.
- 안전하게 분리되지 않으면 실패.

### GuideSession

```kotlin
interface GuideSession {
    fun begin(command: KorailCommand)
    fun next(snapshot: ScreenSnapshot): GuideStepResult
    fun stop(reason: StopReason)
}
```

- 최대 3-step.
- 매 step마다 safety와 score 재계산.
- 정확도 낮으면 중단.

### Overlay

- `BubbleOverlayController`: bubble 표시, drag, 좌우 edge snap, tap.
- `MaskOverlayController`: 후보 영역을 비운 흰색 mask 조각 표시.
- `CandidateHighlighter`: 후보 영역 얇은 초록 테두리.
- `OverlayLayoutCalculator`: padding, rect overlap, mask rectangle 계산.

마스킹 중 텍스트 설명은 표시하지 않는다. 후보 영역에는 overlay를 두지 않아 실제 코레일톡 터치가 전달되어야 한다.

## State

```kotlin
enum class GuideState {
    IDLE,
    LISTENING,
    THINKING,
    GUIDING,
    SENSITIVE_PAUSE,
    FAILED
}
```

```text
IDLE -> LISTENING -> THINKING -> GUIDING
GUIDING -> THINKING
GUIDING -> SENSITIVE_PAUSE
GUIDING -> FAILED
GUIDING -> IDLE
LISTENING -> IDLE
```

## Testing

Unit test:

- `DefaultKorailCommandParser`
- `DefaultSafetyPolicy`
- `KorailTargetScorer`
- `ClusterCandidateResolver`
- `DefaultGuideSession`
- `OverlayLayoutCalculator`

Manual/device test:

- 접근성 서비스 활성화
- 코레일톡 package 감지
- bubble 표시/drag/snap
- listening 시작/중단
- 마스킹 표시
- 실제 앱 터치 전달

Acceptance:

- `./gradlew :app:testDebugUnitTest`
- `./gradlew :app:assembleDebug`

## Competitive Note

Gemini Live는 화면 공유 기반 범용 상담 AI다. cuee는 코레일톡 위에서 실제로 누를 곳만 남기는 app-specific completion layer다.
