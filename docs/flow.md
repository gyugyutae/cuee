# Flow: Jinju-to-Seoul Korail Demo

## Start Preconditions

- For live smoke, the user is logged in to real KorailTalk. Deterministic E2E uses `mock-korail` and needs no account.
- KorailTalk is on the home ticket-booking screen.
- Cuee accessibility service and bubble are enabled.
- User says once: `진주에서 서울 가는 표 예매해줘`.

## Runtime States

```text
IDLE
LISTENING
THINKING
GUIDING
SENSITIVE_PAUSE
FAILED
```

Demo state is memory-only:

```text
DEMO_SELECT_DEPARTURE_FIELD
DEMO_INPUT_DEPARTURE
DEMO_SELECT_DEPARTURE_RESULT
DEMO_SELECT_ARRIVAL_FIELD
DEMO_INPUT_ARRIVAL
DEMO_SELECT_ARRIVAL_RESULT
DEMO_SELECT_DATE_FIELD
DEMO_SELECT_TOMORROW
DEMO_SELECT_TIME
DEMO_SELECT_PASSENGER_FIELD
DEMO_ADULT_PLUS_1
DEMO_CHILD_PLUS_1
DEMO_CONFIRM_PASSENGER
DEMO_SEARCH_TRAINS
DEMO_SCAN_VISIBLE_RESULTS
DEMO_EXPAND_TODAY_TIME
DEMO_SELECT_NEXT_DAY
DEMO_EXPAND_NEXT_DAY_TIME
DEMO_SUGGEST_TRAIN
DEMO_FOLLOW_USER_SELECTION
DEMO_PAYMENT_ENTRY
DEMO_DONE
```

## Main Flow

1. Highlight departure field.
2. On station-search screen, attempt `ACTION_SET_TEXT("진주")`.
3. Highlight `진주` result; user selects it.
4. Highlight arrival field.
5. On station-search screen, attempt `ACTION_SET_TEXT("서울")`.
6. Highlight `서울` result; user selects it.
7. Highlight `가는날`.
8. Set/highlight tomorrow date and 06:00 where possible; use current KorailTalk controls.
9. Highlight passenger field.
10. Highlight adult `+`; user sets adult count from default 1 to adult 2.
11. Highlight child `+`; user sets child count from default 0 to child 1.
12. Highlight passenger `확인` if user confirmation is required.
13. Highlight `열차조회`; user runs search.
14. Scan only currently visible result rows.
15. If a valid candidate exists, highlight it with status `직접 선택`; speak once and keep the mask stable.
16. If none exists, automatically try search policies in order:
    `tomorrow 06:00+` -> `tomorrow all day` -> `following day 06:00+` -> `following day all day`.
17. After user taps a highlighted candidate, highlight `예매`.
18. After user taps `예매`, highlight `결제/발권` or equivalent safe CTA.
19. If an intermediate `확인` dialog appears, highlight `확인`.
20. Highlight payment entry, speak safety message, and stop before payment.

Train candidate, reservation, terms, personal-info, and payment taps are user actions.

## Candidate Policy

Valid candidates must be visible and directly bookable. Exclude `매진`, `예약대기`, `예약링크`, `-`, disabled, external-link, and payment/confirmation controls.

Rank within a search policy:

1. KTX standard to Seoul
2. KTX premium/special to Seoul
3. ITX/Saemaeul standard to Seoul
4. SRT standard/special to Suseo only when no Seoul-station candidate exists
5. Other directly bookable visible candidates

Within the same class, choose the earliest departure at or after the active time threshold.

## Timing

- Default post-tap wait: 700 ms.
- Station search result retry: up to 1500 ms.
- Train result retry: up to 3000 ms.
- Payment-entry retry: up to 2000 ms.
- Recommendation/follow-up highlights do not poll while waiting; keep the mask stable until user tap or timeout.
- `결제하기` highlight duration: 8 seconds, then stop.

## TTS Policy

No TTS for normal setup/search steps. Use green highlight and short status text.

TTS messages:

- Candidate found: `추천 후보를 찾았어요. 강조된 버튼을 직접 눌러 선택하세요.`
- No candidate after all automatic policies: `현재 조건에서는 바로 예매 가능한 좌석이 없어요. 시간을 더 넓히거나 조건을 바꾸면 이어서 찾을 수 있어요.`
- Login/server/permission: `로그인이 필요해요. 로그인 후 다시 이어갈 수 있어요.`
- Payment safety: `결제하기 버튼이에요. 결제는 직접 확인해 주세요.`

Short status text examples: `진주 -> 서울`, `어른 2명`, `어린이 1명`, `내일 06시 이후`, `시간 확장`, `다음날 확인`, `추천`, `직접 선택`, `결제 전 확인`.

## Recovery

After each user tap:

1. Re-analyze current screen after the configured delay.
2. Try the expected demo state target.
3. If missing, try one adjacent previous/next demo state.
4. If still missing, reclassify the current screen and continue only when a safe CTA is clear.
5. Otherwise stop with status `직접 확인`.

Sensitive login, payment details, authentication, personal-info, or final-confirmation screens must stop guidance.
