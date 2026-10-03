package com.cuee.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import com.cuee.BuildConfig
import com.cuee.accessibility.AccessibilitySnapshotMapper
import com.cuee.accessibility.DefaultAccessibilitySnapshotMapper
import com.cuee.data.DataStoreSettingsRepository
import com.cuee.data.LocalUtMetricRepository
import com.cuee.data.UtMetric
import com.cuee.data.UtMetricRepository
import com.cuee.data.UtResult
import com.cuee.data.UtTaskType
import com.cuee.data.hasCurrentConsent
import com.cuee.domain.command.DefaultKorailCommandParser
import com.cuee.domain.command.KorailCommand
import com.cuee.domain.command.KorailCommandParser
import com.cuee.domain.demo.DemoStep
import com.cuee.domain.demo.DemoTargetPlanner
import com.cuee.domain.demo.JourneyPlanner
import com.cuee.ui.JourneyActivity
import com.cuee.ui.JourneyHandoff
import com.cuee.domain.demo.simpleInstruction
import com.cuee.domain.safety.DefaultSafetyPolicy
import com.cuee.domain.safety.SafetyPolicy
import com.cuee.domain.safety.StopReason
import com.cuee.domain.scoring.ClusterCandidateResolver
import com.cuee.domain.scoring.ScreenSnapshot
import com.cuee.domain.scoring.KorailTargetScorer
import com.cuee.domain.session.DefaultGuideSession
import com.cuee.domain.session.GuideSession
import com.cuee.domain.session.GuideState
import com.cuee.domain.session.GuideStepResult
import com.cuee.domain.session.SpokenPrompt
import com.cuee.overlay.BubbleOverlayController
import com.cuee.overlay.CandidateHighlighter
import com.cuee.overlay.GuideControlOverlayController
import com.cuee.overlay.MaskOverlayController
import com.cuee.speech.AndroidTtsController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class CueAccessibilityService : AccessibilityService() {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main)
    private lateinit var settingsRepository: DataStoreSettingsRepository
    private lateinit var metricRepository: UtMetricRepository
    private lateinit var bubble: BubbleOverlayController
    private lateinit var maskOverlay: MaskOverlayController
    private lateinit var highlighter: CandidateHighlighter
    private lateinit var guideControls: GuideControlOverlayController
    private lateinit var ttsController: AndroidTtsController
    private lateinit var commandParser: KorailCommandParser
    private lateinit var safetyPolicy: SafetyPolicy
    private lateinit var snapshotMapper: AccessibilitySnapshotMapper
    private lateinit var guideSession: GuideSession
    private val demoTargetPlanner = DemoTargetPlanner()
    private var currentPackageName: String? = null
    private var currentState: GuideState = GuideState.IDLE
    private var awaitingGuidedTap = false
    private var guidanceTimeoutJob: Job? = null
    private var runningMetric: RunningUtMetric? = null
    private var debugReceiver: BroadcastReceiver? = null
    private var lastSpokenMessage: String? = null
    private var lastInstructionMessage: String? = null
    private var journeyPlanner: JourneyPlanner? = null
    private var journeyPollJob: Job? = null
    private var consentGranted = false
    private var pendingJourneyJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        settingsRepository = DataStoreSettingsRepository(applicationContext)
        metricRepository = LocalUtMetricRepository(applicationContext)
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        commandParser = DefaultKorailCommandParser()
        snapshotMapper = DefaultAccessibilitySnapshotMapper()
        safetyPolicy = DefaultSafetyPolicy()
        guideSession = DefaultGuideSession(
            safetyPolicy = safetyPolicy,
            targetScorer = KorailTargetScorer(),
            candidateResolver = ClusterCandidateResolver()
        )
        ttsController = AndroidTtsController(applicationContext)
        maskOverlay = MaskOverlayController(
            context = applicationContext,
            windowManager = windowManager,
            onClose = { stopGuidance(StopReason.USER_CANCELLED, speak = false) }
        )
        highlighter = CandidateHighlighter(
            context = applicationContext,
            windowManager = windowManager
        )
        guideControls = GuideControlOverlayController(
            context = applicationContext,
            windowManager = windowManager,
            onRepeat = { repeatLastInstruction() },
            onBack = { goBackOneStep() },
            onStop = {
                ttsController.speak(PROMPT_GUIDANCE_STOPPED)
                stopGuidance(StopReason.USER_CANCELLED, speak = false)
            },
            onEdit = { beginListeningFromBubble(keepConditions = true) }
        )
        bubble = BubbleOverlayController(
            context = applicationContext,
            windowManager = windowManager,
            onTap = { beginListeningFromBubble() },
            onDismissed = { scope.launch { settingsRepository.setBubbleEnabled(false) } },
            onPositionSaved = { edge, ratio ->
                scope.launch { settingsRepository.setBubblePosition(edge, ratio) }
            }
        )
        currentPackageName = rootInActiveWindow?.packageName?.toString()
        scope.launch {
            settingsRepository.settings.collect { settings ->
                consentGranted = settings.hasCurrentConsent()
                if (settings.hasCurrentConsent() && settings.bubbleEnabled && currentPackageName == KORAIL_PACKAGE && journeyPlanner == null) {
                    bubble.show(settings.bubbleEdge, settings.bubbleYRatio)
                } else {
                    bubble.hide()
                    if (!settings.hasCurrentConsent() && currentState != GuideState.IDLE) {
                        stopGuidance(StopReason.USER_CANCELLED, speak = false)
                    }
                }
            }
        }
        registerDebugReceiver()
        JourneyHandoff.onConfirmed = { watchPendingJourney() }
        if (JourneyHandoff.pending != null) watchPendingJourney()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventPackageName = event?.packageName?.toString()
        currentPackageName = resolveForegroundPackage(
            currentPackageName = currentPackageName,
            eventPackageName = eventPackageName,
            activeWindowPackageName = rootInActiveWindow?.packageName?.toString(),
            servicePackageName = packageName,
            targetPackageName = KORAIL_PACKAGE,
            eventType = event?.eventType,
            focusedApplicationPackageName = windows.firstOrNull {
                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused
            }?.root?.packageName?.toString()
        )
        if (!::bubble.isInitialized || !::settingsRepository.isInitialized) return

        val eventType = event?.eventType ?: return
        val isKorailEvent = currentPackageName == KORAIL_PACKAGE
        val isKorailPackageEvent = eventPackageName == KORAIL_PACKAGE

        if (isKorailEvent && isKorailPackageEvent && JourneyHandoff.pending != null && korailSnapshotInWindows() != null) {
            beginPendingJourney()
            return
        }
        if (journeyPlanner != null && isKorailEvent) {
            if (eventType.isScreenMutationEvent() || eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                if (journeyPollJob?.isActive != true) scheduleJourneyRecheck()
            }
            return
        }

        if (!isKorailEvent && currentState != GuideState.IDLE) {
            stopGuidance(StopReason.NOT_KORAIL_APP, speak = false)
        } else if (currentState == GuideState.GUIDING && awaitingGuidedTap && isKorailPackageEvent && eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            awaitingGuidedTap = false
            analyzeCurrentScreen()
        }

        scope.launch {
            val settings = settingsRepository.settings.first()
            if (settings.hasCurrentConsent() && settings.bubbleEnabled && currentPackageName == KORAIL_PACKAGE) {
                bubble.show(settings.bubbleEdge, settings.bubbleYRatio)
            } else {
                bubble.hide()
            }
        }
    }

    override fun onInterrupt() {
        stopGuidance(StopReason.USER_CANCELLED, speak = false)
        if (::bubble.isInitialized) bubble.hide()
    }

    override fun onDestroy() {
        JourneyHandoff.onConfirmed = null
        pendingJourneyJob?.cancel()
        stopGuidance(StopReason.USER_CANCELLED, speak = false)
        if (::bubble.isInitialized) bubble.hide()
        if (::ttsController.isInitialized) ttsController.shutdown()
        unregisterDebugReceiver()
        job.cancel()
        super.onDestroy()
    }

    private fun watchPendingJourney() {
        pendingJourneyJob?.cancel()
        pendingJourneyJob = scope.launch {
            repeat(50) {
                delay(200L)
                if (JourneyHandoff.pending == null) return@launch
                val focused = windows.firstOrNull {
                    it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused
                }?.root?.packageName?.toString()
                if (focused == KORAIL_PACKAGE && korailSnapshotInWindows() != null) {
                    currentPackageName = KORAIL_PACKAGE
                    beginPendingJourney()
                    return@launch
                }
            }
            JourneyHandoff.pending = null
        }
    }

    private fun beginPendingJourney() {
        if (!consentGranted) { JourneyHandoff.pending = null; return }
        val request = JourneyHandoff.pending ?: return
        JourneyHandoff.pending = null
        journeyPlanner = JourneyPlanner(request)
        debugLog("confirmed journey started")
        lastInstructionMessage = null
        currentState = GuideState.GUIDING
        beginMetric(KorailCommand.GUIDED_ROUTE_SEARCH)
        bubble.hide()
        analyzeCurrentScreen()
    }

    private fun registerDebugReceiver() {
        if (!BuildConfig.DEBUG || debugReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_DEBUG_STOP) {
                    debugLog("debug stop received")
                    stopGuidance(StopReason.USER_CANCELLED, speak = false)
                    return
                }
                if (intent?.action != ACTION_DEBUG_COMMAND) return
                val utterance = intent.getStringExtra(EXTRA_UTTERANCE).orEmpty()
                if (utterance.isBlank()) return
                debugLog("debug command received")
                currentPackageName = KORAIL_PACKAGE
                handleSpeechResult(utterance)
            }
        }
        debugReceiver = receiver
        val filter = IntentFilter().apply {
            addAction(ACTION_DEBUG_COMMAND)
            addAction(ACTION_DEBUG_STOP)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    private fun unregisterDebugReceiver() {
        debugReceiver?.let { runCatching { unregisterReceiver(it) } }
        debugReceiver = null
    }

    private fun beginListeningFromBubble(keepConditions: Boolean = false) {
        if (currentPackageName != KORAIL_PACKAGE) return
        stopGuidance(StopReason.USER_CANCELLED, speak = false)
        if (!keepConditions) JourneyHandoff.conversation = com.cuee.domain.command.JourneyConversation()
        startActivity(Intent(this, JourneyActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun handleSpeechResult(utterance: String) {
        if (currentPackageName != KORAIL_PACKAGE) {
            stopGuidance(StopReason.NOT_KORAIL_APP, speak = false)
            return
        }

        currentState = GuideState.THINKING
        if (::bubble.isInitialized) bubble.setListening(false)
        val command = commandParser.parse(utterance)
        if (command == KorailCommand.DEMO_JINJU_TO_SEOUL || command == KorailCommand.GUIDED_ROUTE_SEARCH) {
            JourneyHandoff.conversation.accept(utterance)
            startActivity(Intent(this, JourneyActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        if (command == null) {
            debugLog("unsupported command")
            currentState = GuideState.FAILED
            ttsController.speak(PROMPT_UNSUPPORTED_COMMAND)
            return
        }

        debugLog("command parsed: $command")
        guideSession.begin(command)
        beginMetric(command)
        analyzeCurrentScreen()
    }

    private fun analyzeCurrentScreen() {
        if (currentPackageName != KORAIL_PACKAGE) {
            stopGuidance(StopReason.NOT_KORAIL_APP, speak = false)
            return
        }

        val keepCurrentOverlay = currentState == GuideState.GUIDING && journeyPlanner != null
        currentState = GuideState.THINKING
        if (!keepCurrentOverlay) {
            hideGuideOverlay()
        }
        val snapshot = korailSnapshotInWindows() ?: snapshotMapper.map(rootInActiveWindow)
        journeyPlanner?.let { planner ->
            val safety = safetyPolicy.evaluate(snapshot)
            if (!safety.allowed) { stopGuidance(safety.reason ?: StopReason.NO_TARGET, speak = true); return }
            val result = planner.plan(snapshot)
            val target = result.target
            val targetBounds = target?.bounds?.let { " bounds=${it.left},${it.top},${it.right},${it.bottom}" }.orEmpty()
            debugLog("journey target=${target?.nodeId ?: "none"}$targetBounds")
            debugLog("journey result gate=${planner.resultsGate.name.lowercase()}")
            if (planner.routeSession.step == DemoStep.INPUT_DEPARTURE || planner.routeSession.step == DemoStep.INPUT_ARRIVAL) {
                val station = if (planner.routeSession.step == DemoStep.INPUT_DEPARTURE) planner.request.departure else planner.request.arrival
                if (setTextOnEditableNode(station)) planner.routeSession.advance()
            }
            currentState = GuideState.GUIDING
            if (result.target != null) {
                highlighter.show(listOf(result.target.bounds))
                maskOverlay.hide()
            } else { highlighter.hide(); maskOverlay.hide() }
            val message = result.message ?: planner.routeSession.step.simpleInstruction(planner.routeSession.plan)
            val coveredAction = snapshot.nodes.firstOrNull {
                it.visible && (it.text == "선택 완료" || it.text == "확인")
            }?.bounds
            lastInstructionMessage = message
            guideControls.show(message, result.target?.bounds ?: coveredAction, preferTop = result.preferTopPanel)
            if (lastSpokenMessage != message) { ttsController.speak(message); lastSpokenMessage = message }
            scheduleJourneyRecheck()
            return
        }
        val result = guideSession.next(snapshot)
        debugLog(
            "analyzed package=${snapshot.packageName} nodes=${snapshot.nodes.size} " +
                "state=${result.state} stop=${result.stopReason} candidates=${result.candidates.map { it.nodeId to it.score }}"
        )
        currentState = result.state
        renderGuideResult(result)
    }

    private fun korailSnapshotInWindows(): ScreenSnapshot? {
        val roots = windows
            .mapNotNull { window -> window.root }
            .toMutableList()
        rootInActiveWindow?.let { roots += it }
        return roots
            .asSequence()
            .filter { root -> root.packageName?.toString() == KORAIL_PACKAGE }
            .map { root -> snapshotMapper.map(root) }
            .maxByOrNull { snapshot -> snapshot.korailSnapshotPriority() }
    }

    private fun ScreenSnapshot.korailSnapshotPriority(): Int {
        var score = nodes.size
        if (nodes.any { it.id.contains("stationNameEdit", ignoreCase = true) || it.searchableText().contains("역 명") }) {
            score += 10_000
        }
        if (nodes.any { it.id.contains("dateCellText", ignoreCase = true) || it.id.contains("hourPick", ignoreCase = true) }) {
            score += 9_000
        }
        if (nodes.any { it.searchableText().contains("어른") && it.searchableText().contains("어린이") }) {
            score += 8_000
        }
        if (nodes.any { it.id.contains("trainList", ignoreCase = true) || it.searchableText().contains("특실") || it.searchableText().contains("일반실") }) {
            score += 7_000
        }
        return score
    }

    private fun com.cuee.domain.scoring.ScreenNode.searchableText(): String {
        return listOfNotNull(text, contentDescription, id, parentHint)
            .joinToString(separator = " ")
    }

    private fun renderGuideResult(result: GuideStepResult) {
        val instruction = result.instruction
        if (result.isGuiding && instruction != null) {
            val message = instruction.spokenPrompt.toMessage(result.stopReason)
            maskOverlay.show(instruction)
            highlighter.show(instruction.highlightedBounds)
            showGuideControls(message, instruction.highlightedBounds.firstOrNull())
            awaitingGuidedTap = true
            ttsController.speak(message)
            scheduleGuidanceTimeout(instruction.timeoutMs)
            return
        }

        hideGuideOverlay()
        awaitingGuidedTap = false
        ttsController.speak(instruction?.spokenPrompt.toMessage(result.stopReason))
        result.stopReason?.let { finishMetric(it, result.stepCount) }
    }

    private fun scheduleGuidanceTimeout(timeoutMs: Long) {
        guidanceTimeoutJob?.cancel()
        guidanceTimeoutJob = scope.launch {
            delay(timeoutMs)
            if (currentState == GuideState.GUIDING) {
                stopGuidance(StopReason.USER_CANCELLED, speak = false)
            }
        }
    }

    private fun stopGuidance(reason: StopReason, speak: Boolean) {
        debugLog("guidance stopped: $reason")
        journeyPollJob?.cancel()
        journeyPollJob = null
        journeyPlanner = null
        guidanceTimeoutJob?.cancel()
        if (::bubble.isInitialized) bubble.setListening(false)
        lastSpokenMessage = null
        lastInstructionMessage = null
        if (::guideSession.isInitialized) guideSession.stop(reason)
        hideGuideOverlay()
        awaitingGuidedTap = false
        currentState = GuideState.IDLE
        finishMetric(reason, guideSessionStepCountOrZero())
        if (speak && ::ttsController.isInitialized) {
            ttsController.speak(reason.toMessage())
            android.widget.Toast.makeText(applicationContext, reason.toMessage(), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun scheduleJourneyRecheck() {
        if (journeyPollJob?.isActive == true) return
        journeyPollJob = scope.launch {
            delay(SCREEN_RECHECK_DELAY_MS)
            journeyPollJob = null
            if (journeyPlanner != null && currentPackageName == KORAIL_PACKAGE) analyzeCurrentScreen()
        }
    }

    private fun hideGuideOverlay() {
        if (::maskOverlay.isInitialized) maskOverlay.hide()
        if (::highlighter.isInitialized) highlighter.hide()
        if (::guideControls.isInitialized) guideControls.hide()
    }

    private fun showGuideControls(message: String, target: com.cuee.domain.scoring.Bounds? = null) {
        lastInstructionMessage = message
        guideControls.show(message, target)
    }

    private fun repeatLastInstruction() {
        lastInstructionMessage?.takeIf { it.isNotBlank() }?.let(ttsController::speak)
    }

    private fun goBackOneStep() {
        guidanceTimeoutJob?.cancel()
        hideGuideOverlay()
        awaitingGuidedTap = false
        currentState = GuideState.THINKING
        performGlobalAction(GLOBAL_ACTION_BACK)
        scope.launch {
            delay(DEFAULT_POST_TAP_DELAY_MS)
            analyzeCurrentScreen()
        }
    }

    private fun Int.isScreenMutationEvent(): Boolean {
        return this == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            this == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
    }

    private fun SpokenPrompt?.toMessage(stopReason: StopReason?): String {
        return stopReason?.toMessage() ?: when (this) {
            SpokenPrompt.PLEASE_TAP -> PROMPT_PLEASE_TAP
            SpokenPrompt.TRY_AGAIN -> PROMPT_TRY_AGAIN
            SpokenPrompt.CHECK_DIRECTLY -> PROMPT_CHECK_DIRECTLY
            null -> PROMPT_TRY_AGAIN
        }
    }

    private fun StopReason.toMessage(): String {
        return when (this) {
            StopReason.SENSITIVE_LOGIN,
            StopReason.SENSITIVE_PERSONAL_INFO,
            StopReason.SENSITIVE_PAYMENT,
            StopReason.SENSITIVE_AUTH_CODE,
            StopReason.SENSITIVE_CONFIRMATION,
            StopReason.MAX_STEPS_REACHED -> PROMPT_CHECK_DIRECTLY
            StopReason.NO_TARGET -> PROMPT_NO_TARGET
            StopReason.LOW_CONFIDENCE,
            StopReason.OVERLAPPING_CANDIDATES -> PROMPT_AMBIGUOUS
            StopReason.NOT_KORAIL_APP,
            StopReason.USER_CANCELLED -> ""
        }
    }

    private fun beginMetric(command: KorailCommand) {
        runningMetric = RunningUtMetric(
            sessionId = System.currentTimeMillis().toString(),
            taskType = command.toUtTaskType(),
            startedAt = System.currentTimeMillis()
        )
    }

    private fun finishMetric(reason: StopReason, stepCount: Int) {
        val metric = runningMetric ?: return
        runningMetric = null
        val finishedAt = System.currentTimeMillis()
        scope.launch {
            metricRepository.append(
                UtMetric(
                    sessionId = metric.sessionId,
                    taskType = metric.taskType,
                    startedAt = metric.startedAt,
                    finishedAt = finishedAt,
                    elapsedMs = (finishedAt - metric.startedAt).coerceAtLeast(0L),
                    result = reason.toUtResult(),
                    stopReason = reason,
                    stepCount = stepCount.coerceAtLeast(0)
                )
            )
        }
    }

    private fun guideSessionStepCountOrZero(): Int {
        return if (::guideSession.isInitialized) guideSession.stepCount else 0
    }

    private fun KorailCommand.toUtTaskType(): UtTaskType {
        return when (this) {
            KorailCommand.SHOW_MY_TICKET -> UtTaskType.SHOW_MY_TICKET
            KorailCommand.FIND_RESERVATION_START -> UtTaskType.FIND_RESERVATION_START
            KorailCommand.SELECT_DEPARTURE_STATION -> UtTaskType.SELECT_DEPARTURE_STATION
            KorailCommand.GUIDED_ROUTE_SEARCH -> UtTaskType.GUIDED_ROUTE_SEARCH
            KorailCommand.DEMO_JINJU_TO_SEOUL -> UtTaskType.DEMO_JINJU_TO_SEOUL
        }
    }

    private fun setTextOnEditableNode(text: String): Boolean {
        val editable = korailRootNodes()
            .asSequence()
            .mapNotNull { root -> findEditableNode(root) }
            .firstOrNull()
            ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun korailRootNodes(): List<AccessibilityNodeInfo> {
        return windows
            .mapNotNull { it.root }
            .filter { it.packageName?.toString() == KORAIL_PACKAGE }
            .ifEmpty { listOfNotNull(rootInActiveWindow).filter { it.packageName?.toString() == KORAIL_PACKAGE } }
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (
            node.isVisibleToUser &&
            node.isEnabled &&
            (node.isEditable || node.className?.toString()?.contains("EditText", ignoreCase = true) == true) &&
            demoTargetPlanner.hasStationSearchField(snapshotMapper.map(node))
        ) {
            return node
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = findEditableNode(child)
            if (found != null) return found
        }
        return null
    }

    private fun StopReason.toUtResult(): UtResult {
        return when (this) {
            StopReason.USER_CANCELLED -> UtResult.CANCELLED
            StopReason.SENSITIVE_LOGIN,
            StopReason.SENSITIVE_PERSONAL_INFO,
            StopReason.SENSITIVE_PAYMENT,
            StopReason.SENSITIVE_AUTH_CODE,
            StopReason.SENSITIVE_CONFIRMATION -> UtResult.SENSITIVE_PAUSE
            else -> UtResult.FAILED
        }
    }

    private data class RunningUtMetric(
        val sessionId: String,
        val taskType: UtTaskType,
        val startedAt: Long
    )

    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    private companion object {
        const val KORAIL_PACKAGE = "com.korail.talk"
        const val PROMPT_UNSUPPORTED_COMMAND = "아직 이 도움은 준비 중이에요."
        const val PROMPT_NO_TARGET = "지금 화면에서는 찾지 못했어요."
        const val PROMPT_AMBIGUOUS = "정확히 고르기 어려워요. 다시 말해 주세요."
        const val PROMPT_CHECK_DIRECTLY = "이 화면은 직접 확인해 주세요."
        const val PROMPT_PLEASE_TAP = "초록색 테두리 안을 눌러주세요."
        const val PROMPT_TRY_AGAIN = "다시 말해 주세요."
        const val PROMPT_GUIDANCE_STOPPED = "안내를 종료했어요."
        const val ACTION_DEBUG_COMMAND = "com.cuee.DEBUG_COMMAND"
        const val ACTION_DEBUG_STOP = "com.cuee.DEBUG_STOP"
        const val EXTRA_UTTERANCE = "utterance"
        const val TAG = "CueAccessibilityService"
        const val DEFAULT_POST_TAP_DELAY_MS = 700L
        const val SCREEN_RECHECK_DELAY_MS = 1_200L
    }
}

internal fun resolveForegroundPackage(
    currentPackageName: String?,
    eventPackageName: String?,
    activeWindowPackageName: String?,
    servicePackageName: String,
    targetPackageName: String,
    eventType: Int?,
    focusedApplicationPackageName: String? = null
): String? = when {
    focusedApplicationPackageName != null -> focusedApplicationPackageName
    activeWindowPackageName != null && activeWindowPackageName != servicePackageName -> activeWindowPackageName
    eventPackageName == targetPackageName -> targetPackageName
    eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && eventPackageName != servicePackageName -> eventPackageName
    else -> currentPackageName
}
