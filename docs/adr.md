# ADR: Real Korail Demo

## ADR-001: Real KorailTalk Only For This Demo

Decision: test and demo against `com.korail.talk`; do not add mock-korail coverage for this flow.

Intent: the demo must prove value on the real app. Mock work would add maintenance and dilute the current goal.

## ADR-002: Fixed Demo Command

Decision: implement `진주에서 서울 가는 표 예매해줘` as `DEMO_JINJU_TO_SEOUL`.

Intent: fast, reliable demo beats premature generic route parsing. Generalization can follow after the fixed flow works.

## ADR-003: Continuous Guidance After One Command

Decision: one voice command starts a state machine that advances after user taps.

Intent: repeated commands would slow the demo. The product claim is faster booking through continuous next-action guidance.

## ADR-004: User-Tap Guidance, Minimal Automation

Decision: Cuee may auto-fill station text, but route/date/time/passenger/search/reservation/payment-adjacent actions are highlighted for user taps.

Intent: the demo still reduces decision/search time, while the user clearly sees the flow and keeps control.

## ADR-005: Auto-Fill Station Text Only

Decision: attempt `ACTION_SET_TEXT` for station names; fallback to search-field highlight if it fails.

Intent: text entry is low-risk and speeds the demo. Selection remains user-controlled.

## ADR-006: Short Status, TTS Only For Four Cases

Decision: normal steps use green mask/highlight plus compact status text. TTS is limited to candidate found, no-candidate/fallback, login/server/permission, and payment safety.

Intent: status text shows what Cuee is doing without slowing the demo. Voice is reserved for moments requiring user attention.

## ADR-007: Date And Time Search Policy

Decision: search tomorrow first, then the following day. For each date, search 09:00+ first, then 06:00+.

Intent: preserve the user's likely date preference before changing date. 06:00+ improves success without recommending unrealistic overnight departures.

## ADR-008: Current Visible Results Only For MVP

Decision: result scanning considers only currently visible rows and does not auto-scroll in MVP.

Intent: the Sequoia demo values speed and clarity over exhaustive search. Scrolling can be added later if visible-only misses too many valid candidates.

## ADR-009: Candidate Ranking

Decision: rank visible direct-bookable rows by Seoul-station fit and train desirability: KTX standard, KTX premium, ITX/Saemaeul standard, then SRT/Suseo only if no Seoul candidate exists.

Intent: a user who asks for Seoul should not be steered to Suseo unless Seoul-station options are unavailable. Candidate quality matters more than merely finding any button.

## ADR-010: Exclude Reservation Links

Decision: exclude `예약링크` from MVP recommendations.

Intent: reservation links may leave the direct KorailTalk booking flow, reducing demo reliability and user trust.

## ADR-011: Rule Engine Has Final Authority Over AI

Decision: MVP does not require Claude/API. If added later, AI may explain or re-rank valid candidates, but deterministic rules keep final authority and validate the result before highlight.

Intent: AI can improve language and judgment, but it must not choose unsafe, unavailable, external, or off-policy actions.

## ADR-012: Payment Entry Safe Stop

Decision: guide one step past `예매` to `결제/발권` and any safe intermediate `확인`, then highlight payment entry, speak safety message, hold 8 seconds, stop.

Intent: showing the final entry point is enough. Payment remains user responsibility.
