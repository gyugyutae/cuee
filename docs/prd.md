# PRD: Cuee Real Korail Demo

## Product Intent

Cuee is an Android accessibility guide for KorailTalk. It is not an auto-booking bot. It reduces booking time by continuously highlighting the next correct action after one user command, while the user remains responsible for every selection, reservation, and payment decision.

Target demo goal: reduce the time to start a Jinju-to-Seoul booking by about 50% versus unaided KorailTalk use.

## Demo Command

`진주에서 서울 가는 표 예매해줘`

Interpreted as fixed demo flow `DEMO_JINJU_TO_SEOUL`.

## Fixed Demo Parameters

- App: real KorailTalk package `com.korail.talk`
- Start screen: KorailTalk home ticket-booking screen
- Login: user completes login before demo
- Route: `진주` -> `서울`
- Date search order: tomorrow first, then the following day only if needed
- Time search order per date: 09:00 or later first, then 06:00 or later
- Passengers: adults 2, child 1
- Train: recommend a visible, directly bookable candidate by policy; highlight only
- End: guide through `예매` -> `결제/발권` -> safe final `확인` if shown; then highlight payment entry and stop

## UX Principles

- One initial voice command; no repeated commands per step.
- Cuee continues screen analysis after each user tap.
- General steps use existing green border/mask highlight plus very short status text.
- TTS is allowed only for candidate found, fallback/exception, login/server/permission, and payment safety.
- Cuee may auto-fill station text with accessibility `ACTION_SET_TEXT`.
- Cuee may auto-fill station text; visible navigation and booking actions are highlighted for user taps.
- Cuee must not auto-tap train candidates, reservation/confirmation, terms, personal-info, cart, or payment actions.
- Existing field values are ignored; the flow resets fields by guiding the user through them again.
- Product message: Cuee narrows the path quickly; the user makes every material choice.

## Scope

In scope:

- Real KorailTalk only.
- Departure/arrival station reset and station search guidance.
- Automatic text input attempt for `진주` and `서울`; fallback to search-field highlight.
- Guided setup for tomorrow, 09:00, adult 2, and child 1.
- Automatic fallback search to tomorrow 06:00, following day 09:00, then following day 06:00.
- Current visible result-screen candidate analysis only; no automatic result-list scrolling for MVP.
- Train search and recommendation guidance using direct-bookability and route-fit rules.
- Payment-entry highlight and safe stop.

Out of scope:

- `mock-korail` work for this demo.
- Login automation.
- Payment or final purchase automation.
- Generic arbitrary route/date/passenger parsing.
- Server/Claude API in MVP. Future AI may assist explanations/re-ranking, but rules keep final authority.
- Stored screen/audio/ticket/payment/auth content.

## Success Criteria

- `DEMO_JINJU_TO_SEOUL` command is parsed from the demo utterance.
- Real KorailTalk home screen produces departure-field guidance.
- Station search auto-fill is attempted and falls back safely when unavailable.
- The flow advances automatically after user taps.
- Setup reaches `진주 -> 서울`, adult 2 + child 1, and the active search time/date policy without extra user commands.
- Visible result candidates exclude `매진`, `예약대기`, `예약링크`, `-`, and external-link flows.
- Recommendation priority favors 서울역 direct candidates before SRT/Suseo alternatives.
- No candidate produces a friendly, non-broken next-action message.
- `결제하기` is highlighted with: `결제하기 버튼이에요. 결제는 직접 확인해 주세요.`
- Unit tests pass and real-device smoke uses Maestro + ADB debug broadcast + screenshots + logcat.
