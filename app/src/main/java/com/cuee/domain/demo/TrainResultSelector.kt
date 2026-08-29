package com.cuee.domain.demo

import com.cuee.domain.scoring.Bounds
import com.cuee.domain.scoring.ScreenNode
import com.cuee.domain.scoring.ScreenSnapshot

class TrainResultSelector(
    private val earliestHour: Int
) {
    fun select(snapshot: ScreenSnapshot): DemoGuideResult? {
        val candidates = snapshot.nodes
            .filter { it.enabled && it.bounds.isValid() }
            .filter { it.isSeatAction() }
            .mapNotNull { button -> candidateFor(snapshot, button) }
            .filter { it.excludedReason == null }
            .sortedWith(compareBy<TrainCandidate> { it.rank }.thenBy { it.departureHour ?: 99 }.thenBy { it.bounds.top })

        val best = candidates.firstOrNull() ?: return null
        return DemoGuideResult(
            target = DemoTarget(best.nodeId, best.bounds, best.label),
            message = MSG_CANDIDATE_FOUND,
            statusText = "직접 선택"
        )
    }

    private fun candidateFor(snapshot: ScreenSnapshot, button: ScreenNode): TrainCandidate? {
        val rowNodes = rowNodes(snapshot, button)
        val rowRaw = rowNodes.joinToString(" ") { it.searchable() }
        val rowText = rowRaw.normalize()
        val buttonText = button.searchable().normalize()
        val departureHour = rowNodes.mapNotNull { it.departureHourOrNull() }.minOrNull()
        val trainType = trainType(rowText)
        val seatClass = seatClass(buttonText, rowText)
        val arrival = arrivalStation(rowText)
        val excluded = excludedReason(buttonText, departureHour)

        if (trainType == TrainType.OTHER && seatClass == SeatClass.OTHER) return null

        return TrainCandidate(
            nodeId = button.id,
            bounds = button.bounds,
            label = "${trainType.label} ${seatClass.label}",
            trainType = trainType,
            seatClass = seatClass,
            arrivalStation = arrival,
            departureHour = departureHour,
            excludedReason = excluded,
            rank = rank(trainType, seatClass, arrival)
        )
    }

    private fun rowNodes(snapshot: ScreenSnapshot, button: ScreenNode): List<ScreenNode> {
        return snapshot.nodes.filter { node ->
            node.bounds.isValid() &&
                node.bounds.top < button.bounds.bottom + ROW_TOLERANCE &&
                node.bounds.bottom > button.bounds.top - ROW_TOLERANCE
        }
    }

    private fun excludedReason(buttonText: String, departureHour: Int?): ExcludedReason? {
        return when {
            buttonText.contains("매진") -> ExcludedReason.SOLD_OUT
            buttonText.contains("예약대기") || buttonText.contains("대기") -> ExcludedReason.WAITLIST
            buttonText.contains("예약링크") -> ExcludedReason.RESERVATION_LINK
            buttonText.contains("외부") || buttonText.contains("링크") -> ExcludedReason.EXTERNAL_LINK
            buttonText == "-" || buttonText.contains("특실-") || buttonText.contains("일반실-") -> ExcludedReason.UNAVAILABLE_DASH
            departureHour != null && departureHour < earliestHour -> ExcludedReason.BEFORE_TIME_THRESHOLD
            else -> null
        }
    }

    private fun rank(trainType: TrainType, seatClass: SeatClass, arrivalStation: String?): Int {
        val arrivesSeoul = arrivalStation == null || arrivalStation.contains("서울")
        return when {
            trainType == TrainType.KTX && seatClass == SeatClass.STANDARD && arrivesSeoul -> 0
            trainType == TrainType.KTX && seatClass == SeatClass.PREMIUM && arrivesSeoul -> 1
            trainType == TrainType.KTX && arrivesSeoul -> 2
            trainType == TrainType.SRT && seatClass == SeatClass.STANDARD -> 3
            trainType == TrainType.SRT && seatClass == SeatClass.PREMIUM -> 4
            trainType == TrainType.SRT -> 5
            trainType == TrainType.ITX && seatClass == SeatClass.STANDARD && arrivesSeoul -> 6
            trainType == TrainType.ITX && arrivesSeoul -> 7
            arrivesSeoul -> 8
            else -> 9
        }
    }

    private fun trainType(text: String): TrainType {
        return when {
            text.contains("ktx") -> TrainType.KTX
            text.contains("srt") -> TrainType.SRT
            text.contains("itx") || text.contains("새마을") -> TrainType.ITX
            else -> TrainType.OTHER
        }
    }

    private fun seatClass(buttonText: String, rowText: String): SeatClass {
        val text = "$buttonText $rowText"
        return when {
            text.contains("일반실") || text.contains("일반석") || text.contains("일반") -> SeatClass.STANDARD
            text.contains("특실") || text.contains("우등") -> SeatClass.PREMIUM
            else -> SeatClass.OTHER
        }
    }

    private fun arrivalStation(text: String): String? {
        return when {
            text.contains("서울") -> "서울"
            text.contains("수서") -> "수서"
            else -> null
        }
    }

    private fun ScreenNode.isSeatAction(): Boolean {
        val text = searchable().normalize()
        val idText = id.lowercase()
        val looksLikeSeatAction = text.contains("일반") ||
            text.contains("특실") ||
            text.contains("우등") ||
            text.contains("입석") ||
            text.contains("좌석") ||
            text.contains("예매") ||
            text.contains("예약") ||
            idText.contains("reservebutton") ||
            idText.contains("firsttextview") ||
            idText.contains("secondtextview")
        val isActionSurface = clickable ||
            idText.contains("reservebutton") ||
            idText.contains("firsttextview") ||
            idText.contains("secondtextview")
        return looksLikeSeatAction && isActionSurface
    }

    private fun ScreenNode.departureHourOrNull(): Int? {
        val raw = searchable()
        val match = timeRegex.find(raw) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    private fun ScreenNode.searchable(): String {
        return listOfNotNull(text, contentDescription, parentHint, id).joinToString(" ")
    }

    private fun String.normalize(): String = lowercase().filter { it.isLetterOrDigit() || it == '-' }

    data class TrainCandidate(
        val nodeId: String,
        val bounds: Bounds,
        val label: String,
        val trainType: TrainType,
        val seatClass: SeatClass,
        val arrivalStation: String?,
        val departureHour: Int?,
        val excludedReason: ExcludedReason?,
        val rank: Int
    )

    enum class ExcludedReason {
        SOLD_OUT,
        WAITLIST,
        RESERVATION_LINK,
        UNAVAILABLE_DASH,
        EXTERNAL_LINK,
        BEFORE_TIME_THRESHOLD
    }

    enum class TrainType(val label: String) {
        KTX("KTX"),
        SRT("SRT"),
        ITX("ITX"),
        OTHER("train")
    }

    enum class SeatClass(val label: String) {
        STANDARD("standard"),
        PREMIUM("premium"),
        OTHER("seat")
    }

    private companion object {
        const val ROW_TOLERANCE = 70
        const val MSG_CANDIDATE_FOUND = "추천 후보를 찾았어요. 강조된 버튼을 직접 눌러 선택하세요."
        val timeRegex = Regex("""(\d{1,2})[:시]\d{0,2}""")
    }
}
