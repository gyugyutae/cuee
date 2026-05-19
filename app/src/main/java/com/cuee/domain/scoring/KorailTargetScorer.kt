package com.cuee.domain.scoring

import com.cuee.domain.command.KorailCommand

class KorailTargetScorer : TargetScorer {
    override fun score(snapshot: ScreenSnapshot, command: KorailCommand): List<TargetCandidate> {
        val spec = command.spec()
        return snapshot.nodes
            .asSequence()
            .filter { it.enabled && it.visible && it.bounds.isValid() && !it.editable }
            .filterNot { it.containsSensitiveKeyword() }
            .mapNotNull { node -> node.toCandidate(command, spec) }
            .sortedWith(compareByDescending<TargetCandidate> { it.score }.thenBy { it.nodeId })
            .toList()
    }

    private fun ScreenNode.toCandidate(command: KorailCommand, spec: CommandScoringSpec): TargetCandidate? {
        var score = 0
        val normalizedText = text.orEmpty().normalizeForScoring()
        val normalizedDescription = contentDescription.orEmpty().normalizeForScoring()
        val normalizedParent = parentHint.orEmpty().normalizeForScoring()

        val exactTextMatched = spec.exactKeywords.any { normalizedText.contains(it) }
        val partialTextMatched = !exactTextMatched && spec.partialKeywords.any { normalizedText.contains(it) }
        val parentMatched = spec.exactKeywords.any { normalizedParent.contains(it) } ||
            spec.partialKeywords.any { normalizedParent.contains(it) }
        val contentDescriptionMatched = spec.exactKeywords.any { normalizedDescription.contains(it) } ||
            spec.partialKeywords.any { normalizedDescription.contains(it) }
        val textMatched = exactTextMatched || partialTextMatched || parentMatched

        when {
            exactTextMatched -> score += EXACT_TEXT_SCORE
            partialTextMatched || parentMatched -> score += PARTIAL_TEXT_SCORE
        }
        if (contentDescriptionMatched) score += CONTENT_DESCRIPTION_SCORE
        if (clickable) score += CLICKABLE_SCORE

        val classHintMatched = className.orEmpty().contains("button", ignoreCase = true) ||
            clickable && className.orEmpty().contains("textview", ignoreCase = true)
        if (classHintMatched) score += HINT_SCORE

        val positionHintMatched = bounds.top >= 0 && bounds.area >= MIN_TOUCH_AREA
        if (positionHintMatched) score += HINT_SCORE

        if (!textMatched && text.orEmpty().isNotBlank()) score -= GENERAL_TEXT_PENALTY
        if (text.orEmpty().normalizeForScoring().length > LONG_TEXT_THRESHOLD) {
            score -= LONG_TEXT_PENALTY
        }

        if (score <= 0 || (!textMatched && !contentDescriptionMatched)) return null

        return TargetCandidate(
            nodeId = id,
            command = command,
            targetType = spec.targetType,
            bounds = bounds,
            score = score.coerceAtLeast(0),
            evidence = CandidateEvidence(
                textMatched = textMatched,
                contentDescriptionMatched = contentDescriptionMatched,
                clickable = clickable,
                classHintMatched = classHintMatched,
                positionHintMatched = positionHintMatched
            )
        )
    }

    private fun ScreenNode.containsSensitiveKeyword(): Boolean {
        val searchable = listOfNotNull(text, contentDescription, parentHint)
            .joinToString(separator = " ")
            .normalizeForScoring()
        return sensitiveKeywords.any { searchable.contains(it) }
    }

    private fun KorailCommand.spec(): CommandScoringSpec {
        return when (this) {
            KorailCommand.SHOW_MY_TICKET -> CommandScoringSpec(
                targetType = TargetType.MY_TICKET_ENTRY,
                exactKeywords = setOf("승차권확인", "예매확인", "예매내역", "예약내역", "마이티켓", "내승차권", "표확인"),
                partialKeywords = setOf("승차권", "티켓", "표", "예매", "예약")
            )
            KorailCommand.FIND_RESERVATION_START -> CommandScoringSpec(
                targetType = TargetType.RESERVATION_ENTRY,
                exactKeywords = setOf("승차권예매", "열차예매", "예매하기", "기차표예매", "승차권예약"),
                partialKeywords = setOf("예매", "예약", "열차", "기차표", "승차권")
            )
        }
    }

    private fun String.normalizeForScoring(): String {
        return lowercase().filter { it.isLetterOrDigit() }
    }

    private data class CommandScoringSpec(
        val targetType: TargetType,
        val exactKeywords: Set<String>,
        val partialKeywords: Set<String>
    )

    private companion object {
        const val EXACT_TEXT_SCORE = 60
        const val PARTIAL_TEXT_SCORE = 35
        const val CONTENT_DESCRIPTION_SCORE = 45
        const val CLICKABLE_SCORE = 20
        const val HINT_SCORE = 10
        const val GENERAL_TEXT_PENALTY = 20
        const val LONG_TEXT_PENALTY = 30
        const val LONG_TEXT_THRESHOLD = 24
        const val MIN_TOUCH_AREA = 36 * 36

        val sensitiveKeywords: Set<String> = setOf(
            "로그인",
            "아이디",
            "비밀번호",
            "개인정보",
            "인증번호",
            "본인인증",
            "결제",
            "카드번호",
            "예매확정",
            "구매확정"
        )
    }
}
