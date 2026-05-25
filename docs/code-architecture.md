# Code Architecture: Real Korail Demo

## Architecture Intent

Keep Android service orchestration thin and move demo decisions into testable domain classes. The real app surface is unstable; scoring and state transitions must be unit-testable without a device.

## Current Boundaries

```text
service/CueAccessibilityService.kt
  Android lifecycle, accessibility events, speech, overlay, debug broadcast

accessibility/
  Android AccessibilityNodeInfo -> ScreenSnapshot

domain/command/
  utterance -> KorailCommand

domain/session/
  GuideSession state and next instruction

domain/scoring/
  target scoring and candidate resolution

domain/safety/
  sensitive-screen stop rules

overlay/
  bubble, mask, highlighter
```

## Demo Additions

Recommended additions:

```text
domain/demo/
  DemoBookingPlan.kt
  DemoStep.kt
  DemoSession.kt
  DemoScreenClassifier.kt
  DemoTargetPlanner.kt
  DemoSearchPolicy.kt
  StationInputAction.kt
  TrainResultSelector.kt
```

Responsibilities:

- `DemoSession`: holds `DemoBookingPlan`, current `DemoStep`, retry counters, and transition rules.
- `DemoScreenClassifier`: identifies home, station search, date/time, passenger, train results, payment-entry, off-flow.
- `DemoTargetPlanner`: returns the next highlight or fallback message for the current step.
- `StationInputAction`: attempts `ACTION_SET_TEXT` on the active station search field.
- `DemoSearchPolicy`: owns the ordered search policies: tomorrow 09:00+, tomorrow 06:00+, following day 09:00+, following day 06:00+.
- `TrainResultSelector`: ranks currently visible direct-bookable result rows and skips excluded states.

Keep these classes Android-free except `StationInputAction`, which may need node actions.

## Service Orchestration

`CueAccessibilityService` should:

1. Parse `DEMO_JINJU_TO_SEOUL`.
2. Start demo session.
3. Analyze the best `com.korail.talk` window snapshot.
4. Ask demo planner for action.
5. Render existing green mask/highlight.
6. Render compact status text for setup/search/recommendation states.
7. Auto-fill station text only; render route/date/time/passenger/search actions for user taps.
8. On user tap, wait 700 ms and continue.
9. Use longer retry windows for station results, train results, and payment entry.
10. Speak only the four approved TTS categories.

Avoid embedding screen-specific demo heuristics directly in the service.

## Highlight Contract

Use existing mask/highlighter:

- one strong highlighted target per demo step
- compact status text for normal steps
- target area must remain touch-through
- payment highlight remains for 8 seconds before stop
- train candidate/reservation/payment controls are highlighted only, never auto-tapped
- recommendation highlights are stable: speak once, avoid polling redraws while waiting for user tap

## Timing Constants

```kotlin
const val DEFAULT_POST_TAP_DELAY_MS = 700L
const val STATION_RESULT_RETRY_MS = 1_500L
const val TRAIN_RESULT_RETRY_MS = 3_000L
const val PAYMENT_ENTRY_RETRY_MS = 2_000L
const val PAYMENT_HIGHLIGHT_TIMEOUT_MS = 8_000L
```

## Testing

Unit tests:

- command parser maps demo utterance to `DEMO_JINJU_TO_SEOUL`
- demo session transitions through expected steps
- tomorrow date calculation
- passenger plus sequence from adult 1 to adult 2 and child 0 to child 1
- search policy order: tomorrow 09, tomorrow 06, following day 09, following day 06
- train selector skips `매진`, `예약대기`, `예약링크`, `-`, external, disabled, and before-threshold rows
- train selector prioritizes Seoul-station KTX/ITX over SRT/Suseo unless no Seoul candidate exists
- fallback/status messages return for no candidate, login/server/permission, payment, and off-flow

Real-device smoke:

- use actual KorailTalk only
- use Maestro for entrypoint assertions
- use ADB debug broadcast for demo command
- capture screenshots and logcat
- verify setup reaches route/date/time/passenger, then result recommendation or friendly no-candidate stop

Do not add mock-korail work for this demo unless the product scope changes.
