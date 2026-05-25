# Data Schema: Cuee Demo

## Persistence Rule

Persist only user settings and anonymous metrics. Do not persist screen text, accessibility dumps, utterance raw text, station search content, passenger details, ticket data, payment data, or auth data.

Runtime snapshots and demo state are memory-only.

## Existing Settings

```kotlin
data class UserSettings(
    val onboardingCompleted: Boolean = false,
    val accessibilityGuideCompleted: Boolean = false,
    val consentVersion: String = "2026-05-20",
    val consentAcceptedAt: Long = 0L,
    val bubbleEnabled: Boolean = true,
    val bubbleEdge: BubbleEdge = BubbleEdge.RIGHT,
    val bubbleYRatio: Float = 0.55f,
    val voiceEnabled: Boolean = true
)

enum class BubbleEdge { LEFT, RIGHT }
```

## Commands

```kotlin
enum class KorailCommand {
    SHOW_MY_TICKET,
    FIND_RESERVATION_START,
    DEMO_JINJU_TO_SEOUL
}
```

`DEMO_JINJU_TO_SEOUL` is triggered by `진주에서 서울 가는 표 예매해줘` and close variants. It should not imply generic arbitrary booking support.

## Demo Plan

Memory-only:

```kotlin
data class DemoBookingPlan(
    val departureStation: String = "진주",
    val arrivalStation: String = "서울",
    val searchPolicies: List<SearchPolicy> = defaultJinjuSeoulPolicies(),
    val passengers: PassengerPlan = PassengerPlan(adults = 2, children = 1),
    val trainPriority: TrainPriority = TrainPriority.DIRECT_BOOKABLE_VISIBLE,
    val stopAtPaymentEntry: Boolean = true
)

data class SearchPolicy(
    val dateOffsetDays: Int,
    val earliestDepartureHour: Int,
    val label: String
)

data class PassengerPlan(
    val adults: Int,
    val children: Int = 0,
    val infants: Int = 0,
    val seniors: Int = 0,
    val severeDisabled: Int = 0,
    val mildDisabled: Int = 0
)

fun defaultJinjuSeoulPolicies() = listOf(
    SearchPolicy(dateOffsetDays = 1, earliestDepartureHour = 9, label = "내일 09시 이후"),
    SearchPolicy(dateOffsetDays = 1, earliestDepartureHour = 6, label = "내일 06시 이후"),
    SearchPolicy(dateOffsetDays = 2, earliestDepartureHour = 9, label = "다음날 09시 이후"),
    SearchPolicy(dateOffsetDays = 2, earliestDepartureHour = 6, label = "다음날 06시 이후")
)

enum class TrainPriority { DIRECT_BOOKABLE_VISIBLE }
```

## Demo State

Memory-only:

```kotlin
enum class DemoStep {
    SELECT_DEPARTURE_FIELD,
    INPUT_DEPARTURE,
    SELECT_DEPARTURE_RESULT,
    SELECT_ARRIVAL_FIELD,
    INPUT_ARRIVAL,
    SELECT_ARRIVAL_RESULT,
    SELECT_DATE_FIELD,
    SELECT_TOMORROW,
    SELECT_TIME,
    CONFIRM_DATE,
    SELECT_PASSENGER_FIELD,
    ADULT_PLUS_1,
    CHILD_PLUS_1,
    CONFIRM_PASSENGER,
    SEARCH_TRAINS,
    SCAN_VISIBLE_RESULTS,
    APPLY_NEXT_SEARCH_POLICY,
    SUGGEST_TRAIN,
    FOLLOW_USER_SELECTION,
    PAYMENT_ENTRY,
    DONE
}

data class DemoSessionState(
    val plan: DemoBookingPlan,
    val step: DemoStep,
    val activePolicyIndex: Int = 0,
    val startedAt: Long,
    val updatedAt: Long,
    val retryCount: Int = 0
)
```

## Runtime Screen Models

Existing `ScreenSnapshot`, `ScreenNode`, and `Bounds` remain the input contract. Text fields are used only for in-memory scoring.

Train result scoring may use a derived memory-only row:

```kotlin
data class TrainCandidateRow(
    val id: String,
    val trainName: String?,
    val departureTime: String?,
    val arrivalStation: String?,
    val arrivalTime: String?,
    val seatClass: String?,
    val availabilityText: String?,
    val reservationButtonBounds: Bounds?,
    val directBookable: Boolean,
    val excludedReason: ExcludedReason? = null,
    val score: Int
)

enum class ExcludedReason {
    SOLD_OUT,
    WAITLIST,
    RESERVATION_LINK,
    UNAVAILABLE_DASH,
    EXTERNAL_LINK,
    DISABLED,
    BEFORE_TIME_THRESHOLD,
    NOT_VISIBLE
}
```

## Stop Reasons

Add demo-specific stop reasons only if needed:

```kotlin
enum class StopReason {
    NO_TARGET,
    LOW_CONFIDENCE,
    OVERLAPPING_CANDIDATES,
    SENSITIVE_LOGIN,
    SENSITIVE_PAYMENT,
    SENSITIVE_PERSONAL_INFO,
    SENSITIVE_AUTH_CODE,
    SENSITIVE_CONFIRMATION,
    NOT_KORAIL_APP,
    USER_CANCELLED,
    MAX_STEPS_REACHED,
    DEMO_OFF_FLOW,
    DEMO_NO_TRAIN_CANDIDATE
}
```

Do not store stop context containing screen text.
