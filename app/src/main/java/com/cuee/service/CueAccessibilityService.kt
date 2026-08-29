package com.cuee.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.os.Bundle
import android.util.Log
import android.graphics.Path
import android.accessibilityservice.GestureDescription
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
import com.cuee.domain.demo.DemoBookingPlan
import com.cuee.domain.demo.DemoGuideResult
import com.cuee.domain.demo.DemoSession
import com.cuee.domain.demo.DemoStep
import com.cuee.domain.demo.DemoTargetPlanner
import com.cuee.domain.safety.DefaultSafetyPolicy
import com.cuee.domain.safety.StopReason
import com.cuee.domain.scoring.ClusterCandidateResolver
import com.cuee.domain.scoring.ScreenSnapshot
import com.cuee.domain.scoring.KorailTargetScorer
import com.cuee.domain.session.DefaultGuideSession
import com.cuee.domain.session.GuideSession
import com.cuee.domain.session.GuideState
import com.cuee.domain.session.GuideStepResult
import com.cuee.domain.session.OverlayInstruction
import com.cuee.domain.session.SpokenPrompt
import com.cuee.overlay.BubbleOverlayController
import com.cuee.overlay.CandidateHighlighter
import com.cuee.overlay.GuideControlOverlayController
import com.cuee.overlay.MaskOverlayController
import com.cuee.speech.AndroidSpeechController
import com.cuee.speech.AndroidTtsController
import com.cuee.speech.SpeechController
import com.cuee.speech.SpeechError
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
    private lateinit var speechController: SpeechController
    private lateinit var ttsController: AndroidTtsController
    private lateinit var commandParser: KorailCommandParser
    private lateinit var snapshotMapper: AccessibilitySnapshotMapper
    private lateinit var guideSession: GuideSession
    private val demoTargetPlanner = DemoTargetPlanner()
    private var currentPackageName: String? = null
    private var currentState: GuideState = GuideState.IDLE
    private var awaitingGuidedTap = false
    private var guidanceTimeoutJob: Job? = null
    private var demoRecheckJob: Job? = null
    private var runningMetric: RunningUtMetric? = null
    private var debugReceiver: BroadcastReceiver? = null
    private var demoSession: DemoSession? = null
    private var lastDemoSpokenKey: String? = null
    private var lastInstructionMessage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        settingsRepository = DataStoreSettingsRepository(applicationContext)
        metricRepository = LocalUtMetricRepository(applicationContext)
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        commandParser = DefaultKorailCommandParser()
        snapshotMapper = DefaultAccessibilitySnapshotMapper()
        guideSession = DefaultGuideSession(
            safetyPolicy = DefaultSafetyPolicy(),
            targetScorer = KorailTargetScorer(),
            candidateResolver = ClusterCandidateResolver()
        )
        ttsController = AndroidTtsController(applicationContext)
        speechController = AndroidSpeechController(
            context = applicationContext,
            onResult = { utterance -> handleSpeechResult(utterance) },
            onError = { error -> handleSpeechError(error) }
        )
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
            }
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
        scope.launch {
            settingsRepository.settings.collect { settings ->
                if (settings.hasCurrentConsent() && settings.bubbleEnabled && currentPackageName == KORAIL_PACKAGE) {
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
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventPackageName = event?.packageName?.toString()
        if (eventPackageName == KORAIL_PACKAGE || (eventPackageName != packageName && currentState == GuideState.IDLE)) {
            currentPackageName = eventPackageName
        }
        if (!::bubble.isInitialized || !::settingsRepository.isInitialized) return

        val eventType = event?.eventType ?: return
        val isKorailEvent = currentPackageName == KORAIL_PACKAGE
        val isKorailPackageEvent = eventPackageName == KORAIL_PACKAGE

        if (!isKorailEvent && currentState != GuideState.IDLE) {
            stopGuidance(StopReason.NOT_KORAIL_APP, speak = false)
        } else if (currentState == GuideState.LISTENING && isKorailPackageEvent && eventType.isUserTouchEvent()) {
            stopGuidance(StopReason.USER_CANCELLED, speak = false)
        } else if (currentState == GuideState.GUIDING && awaitingGuidedTap && isKorailPackageEvent && eventType.isGuideAdvanceEvent()) {
            if (demoSession != null) {
                demoSession?.let { session ->
                    val step = session.step
                    if (step.shouldReanalyzeWhileWaiting()) {
                        if (step != DemoStep.SELECT_DEPARTURE_RESULT && step != DemoStep.SELECT_ARRIVAL_RESULT) {
                            session.advance()
                        }
                        scheduleDemoRecheck()
                    } else {
                        awaitingGuidedTap = false
                        if (step == DemoStep.SCAN_VISIBLE_RESULTS || step == DemoStep.SUGGEST_TRAIN) {
                            session.setStep(DemoStep.FOLLOW_USER_SELECTION)
                        } else if (step != DemoStep.FOLLOW_USER_SELECTION) {
                            session.advance()
                        }
                    }
                }
                scope.launch {
                    delay(DEFAULT_POST_TAP_DELAY_MS)
                    analyzeCurrentScreen()
                }
            } else {
                awaitingGuidedTap = false
                analyzeCurrentScreen()
            }
        } else if (
            currentState == GuideState.GUIDING &&
            awaitingGuidedTap &&
            demoSession?.step?.shouldReanalyzeOnScreenMutation() == true &&
            isKorailPackageEvent &&
            eventType.isDemoScreenMutationEvent()
        ) {
            scope.launch {
                delay(DEFAULT_POST_TAP_DELAY_MS)
                analyzeCurrentScreen()
            }
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
        stopGuidance(StopReason.USER_CANCELLED, speak = false)
        if (::bubble.isInitialized) bubble.hide()
        if (::speechController.isInitialized) speechController.destroy()
        if (::ttsController.isInitialized) ttsController.shutdown()
        unregisterDebugReceiver()
        job.cancel()
        super.onDestroy()
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
                debugLog("debug command received: $utterance")
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

    private fun beginListeningFromBubble() {
        if (currentPackageName != KORAIL_PACKAGE) return
        hideGuideOverlay()
        guidanceTimeoutJob?.cancel()
        demoRecheckJob?.cancel()
        currentState = GuideState.LISTENING
        awaitingGuidedTap = false
        bubble.setListening(true)
        speechController.startListening(SpeechController.KO_KR)
    }

    private fun handleSpeechResult(utterance: String) {
        if (currentPackageName != KORAIL_PACKAGE) {
            stopGuidance(StopReason.NOT_KORAIL_APP, speak = false)
            return
        }

        currentState = GuideState.THINKING
        if (::bubble.isInitialized) bubble.setListening(false)
        val command = commandParser.parse(utterance)
        if (command == null) {
            debugLog("unsupported command: $utterance")
            currentState = GuideState.FAILED
            ttsController.speak(PROMPT_UNSUPPORTED_COMMAND)
            return
        }

        debugLog("command parsed: $command")
        if (command == KorailCommand.DEMO_JINJU_TO_SEOUL) {
            demoSession = DemoSession(plan = DemoBookingPlan())
            lastDemoSpokenKey = null
        } else {
            demoSession = null
            lastDemoSpokenKey = null
            guideSession.begin(command)
        }
        beginMetric(command)
        analyzeCurrentScreen()
    }

    private fun handleSpeechError(error: SpeechError) {
        if (currentState != GuideState.LISTENING) return
        if (::bubble.isInitialized) bubble.setListening(false)
        currentState = GuideState.FAILED
        ttsController.speak(
            when (error) {
                SpeechError.NO_MATCH -> PROMPT_LISTENING_FAILED
                SpeechError.UNAVAILABLE -> PROMPT_LISTENING_FAILED
                SpeechError.CANCELLED -> return
            }
        )
    }

    private fun analyzeCurrentScreen() {
        if (currentPackageName != KORAIL_PACKAGE) {
            stopGuidance(StopReason.NOT_KORAIL_APP, speak = false)
            return
        }

        val keepCurrentOverlay = currentState == GuideState.GUIDING && demoSession != null
        currentState = GuideState.THINKING
        if (!keepCurrentOverlay) {
            hideGuideOverlay()
        }
        val snapshot = korailSnapshotInWindows() ?: snapshotMapper.map(rootInActiveWindow)
        val activeDemo = demoSession
        if (activeDemo != null) {
            analyzeDemoScreen(snapshot, activeDemo)
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

    private fun analyzeDemoScreen(snapshot: ScreenSnapshot, session: DemoSession) {
        if (handleDemoInputStep(session)) return

        val result = demoTargetPlanner.plan(snapshot, session)
        debugLog(
            "demo analyzed step=${session.step} package=${snapshot.packageName} nodes=${snapshot.nodes.size} " +
                "target=${result.target?.label}:${result.target?.nodeId} message=${result.message} status=${result.statusText}"
        )
        if (
            result.target == null &&
            (session.step == DemoStep.SELECT_DEPARTURE_RESULT || session.step == DemoStep.SELECT_ARRIVAL_RESULT) &&
            session.retryCount < DEMO_STATION_RESULT_RETRY_COUNT
        ) {
            session.markRetry()
            scope.launch {
                delay(STATION_RESULT_DELAY_MS)
                analyzeCurrentScreen()
            }
            return
        }
        if (
            result.target == null &&
            session.step == DemoStep.SEARCH_TRAINS &&
            session.retryCount < DEMO_RESULT_SCREEN_RETRY_COUNT
        ) {
            session.markRetry()
            scope.launch {
                delay(DEFAULT_POST_TAP_DELAY_MS)
                analyzeCurrentScreen()
            }
            return
        }
        if (
            result.target == null &&
            session.step.allowsTransientTargetRetry() &&
            session.retryCount < DEMO_TRANSITION_RETRY_COUNT
        ) {
            session.markRetry()
            scope.launch {
                delay(DEFAULT_POST_TAP_DELAY_MS)
                analyzeCurrentScreen()
            }
            return
        }
        if (
            result.target == null &&
            (session.step == DemoStep.SCAN_VISIBLE_RESULTS || session.step == DemoStep.SUGGEST_TRAIN) &&
            session.retryCount < DEMO_RESULT_TRANSITION_RETRY_COUNT
        ) {
            session.markRetry()
            scope.launch {
                delay(DEFAULT_POST_TAP_DELAY_MS)
                analyzeCurrentScreen()
            }
            return
        }
        if (result.target == null && (session.step == DemoStep.SCAN_VISIBLE_RESULTS || session.step == DemoStep.SUGGEST_TRAIN) && session.hasNextPolicy()) {
            session.advancePolicy()
            session.setStep(DemoStep.SELECT_DATE_FIELD)
            performGlobalAction(GLOBAL_ACTION_BACK)
            scope.launch {
                delay(POLICY_BACK_DELAY_MS)
                analyzeCurrentScreen()
            }
            return
        }
        if (result.target == null && session.step == DemoStep.SELECT_DATE_FIELD && snapshot.isTrainResultsScreen() && session.retryCount < DEMO_POLICY_BACK_RETRY_COUNT) {
            session.markRetry()
            performGlobalAction(GLOBAL_ACTION_BACK)
            scope.launch {
                delay(POLICY_BACK_DELAY_MS)
                analyzeCurrentScreen()
            }
            return
        }
        if (result.target == null && session.step == DemoStep.SELECT_TIME && session.retryCount < DEMO_TIME_SWIPE_RETRY_COUNT) {
            session.markRetry()
            performTimePickerSwipe()
            scope.launch {
                delay(DEFAULT_POST_TAP_DELAY_MS)
                analyzeCurrentScreen()
            }
            return
        }
        renderDemoResult(result, session)
    }

    private fun handleDemoInputStep(session: DemoSession): Boolean {
        val station = when (session.step) {
            DemoStep.INPUT_DEPARTURE -> session.plan.departureStation
            DemoStep.INPUT_ARRIVAL -> session.plan.arrivalStation
            else -> return false
        }

        if (!setTextOnEditableNode(station)) {
            debugLog("demo station input failed: $station")
            if (session.retryCount < DEMO_INPUT_RETRY_COUNT) {
                session.markRetry()
                scope.launch {
                    delay(STATION_RESULT_DELAY_MS)
                    analyzeCurrentScreen()
                }
                return true
            }
            return false
        }

        debugLog("demo station input succeeded: $station")
        session.advance()
        scope.launch {
            delay(STATION_RESULT_DELAY_MS)
            analyzeCurrentScreen()
        }
        return true
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
            showGuideControls(message)
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

    private fun renderDemoResult(result: DemoGuideResult, session: DemoSession) {
        val target = result.target
        if (target != null) {
            val message = result.message ?: session.step.simpleInstruction()
            val instruction = OverlayInstruction(
                visibleHoles = listOf(target.bounds),
                highlightedBounds = listOf(target.bounds),
                timeoutMs = result.timeoutMs,
                spokenPrompt = SpokenPrompt.PLEASE_TAP
            )
            maskOverlay.show(instruction)
            highlighter.show(instruction.highlightedBounds)
            showGuideControls(message)
            awaitingGuidedTap = !result.doneAfterRender
            currentState = GuideState.GUIDING
            val spokenKey = "${session.step}:${target.nodeId}:$message"
            if (spokenKey != lastDemoSpokenKey) {
                ttsController.speak(message)
                lastDemoSpokenKey = spokenKey
            }
            result.statusText?.let { debugLog("demo status: $it") }

            if (result.doneAfterRender) {
                guidanceTimeoutJob?.cancel()
                demoRecheckJob?.cancel()
                guidanceTimeoutJob = scope.launch {
                    delay(result.timeoutMs)
                    session.stop()
                    stopGuidance(StopReason.USER_CANCELLED, speak = false)
                }
            } else if (result.autoTap) {
                awaitingGuidedTap = false
                guidanceTimeoutJob?.cancel()
                demoRecheckJob?.cancel()
                hideGuideOverlay()
                if (!performClickOnNode(target.nodeId)) {
                    performTap(target.bounds)
                }
                session.advance()
                scope.launch {
                    delay(AUTO_TAP_POST_DELAY_MS)
                    analyzeCurrentScreen()
                }
            } else {
                if (session.step.shouldReanalyzeWhileWaiting()) {
                    scheduleGuidanceTimeout(DEMO_SETUP_TIMEOUT_MS)
                    scheduleDemoRecheck()
                } else {
                    scheduleGuidanceTimeout(result.timeoutMs)
                    demoRecheckJob?.cancel()
                }
            }
            return
        }

        hideGuideOverlay()
        awaitingGuidedTap = false
        if (result.advanceOnRender) {
            session.advance()
            currentState = GuideState.THINKING
            scope.launch {
                delay(DEFAULT_POST_TAP_DELAY_MS)
                analyzeCurrentScreen()
            }
            return
        }
        currentState = GuideState.FAILED
        result.message?.let { ttsController.speak(it) }
        result.statusText?.let { debugLog("demo status: $it") }
        session.stop()
        lastDemoSpokenKey = null
        finishMetric(StopReason.NO_TARGET, 0)
    }

    private fun DemoStep.shouldReanalyzeWhileWaiting(): Boolean {
        return shouldReanalyzeOnScreenMutation()
    }

    private fun DemoStep.allowsTransientTargetRetry(): Boolean {
        return when (this) {
            DemoStep.SELECT_DEPARTURE_FIELD,
            DemoStep.SELECT_ARRIVAL_FIELD,
            DemoStep.SELECT_DATE_FIELD,
            DemoStep.SELECT_TOMORROW,
            DemoStep.CONFIRM_DATE,
            DemoStep.SELECT_PASSENGER_FIELD,
            DemoStep.ADULT_PLUS_1,
            DemoStep.CHILD_PLUS_1,
            DemoStep.CONFIRM_PASSENGER,
            DemoStep.FOLLOW_USER_SELECTION -> true
            else -> false
        }
    }

    private fun DemoStep.shouldReanalyzeOnScreenMutation(): Boolean {
        return when (this) {
            DemoStep.SELECT_DEPARTURE_FIELD,
            DemoStep.INPUT_DEPARTURE,
            DemoStep.SELECT_DEPARTURE_RESULT,
            DemoStep.SELECT_ARRIVAL_FIELD,
            DemoStep.INPUT_ARRIVAL,
            DemoStep.SELECT_ARRIVAL_RESULT,
            DemoStep.SELECT_DATE_FIELD,
            DemoStep.SELECT_TOMORROW,
            DemoStep.SELECT_TIME,
            DemoStep.CONFIRM_DATE,
            DemoStep.SELECT_PASSENGER_FIELD,
            DemoStep.ADULT_PLUS_1,
            DemoStep.CHILD_PLUS_1,
            DemoStep.CONFIRM_PASSENGER,
            DemoStep.SEARCH_TRAINS,
            DemoStep.SCAN_VISIBLE_RESULTS,
            DemoStep.SUGGEST_TRAIN,
            DemoStep.FOLLOW_USER_SELECTION -> true
            DemoStep.APPLY_NEXT_SEARCH_POLICY,
            DemoStep.PAYMENT_ENTRY,
            DemoStep.DONE -> false
        }
    }

    private fun performTap(bounds: com.cuee.domain.scoring.Bounds): Boolean {
        val x = bounds.left + bounds.width / 2f
        val y = bounds.top + bounds.height / 2f
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun ScreenSnapshot.isTrainResultsScreen(): Boolean {
        return nodes.any { node ->
            val text = listOfNotNull(node.text, node.contentDescription).joinToString(" ")
            text.contains("열차 조회") || text.contains("열차조회")
        }
    }

    private fun performTimePickerSwipe(): Boolean {
        val path = Path().apply {
            moveTo(1_010f, 1_338f)
            lineTo(620f, 1_338f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 250))
            .build()
        if (dispatchGesture(gesture, null, null)) return true

        return korailRootNodes()
            .asSequence()
            .mapNotNull { root -> findNodeByIdHint(root, "hourPickScroll") }
            .firstOrNull()
            ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
    }

    private fun performClickOnNode(nodeId: String): Boolean {
        val node = korailRootNodes()
            .asSequence()
            .mapNotNull { root -> findNodeByViewId(root, nodeId) }
            .firstOrNull()
            ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun findNodeByViewId(node: AccessibilityNodeInfo, nodeId: String): AccessibilityNodeInfo? {
        if (
            node.isVisibleToUser &&
            node.isEnabled &&
            node.viewIdResourceName == nodeId
        ) {
            return node
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = findNodeByViewId(child, nodeId)
            if (found != null) return found
        }
        return null
    }

    private fun findNodeByIdHint(node: AccessibilityNodeInfo, idHint: String): AccessibilityNodeInfo? {
        if (
            node.isVisibleToUser &&
            node.isEnabled &&
            node.viewIdResourceName?.contains(idHint, ignoreCase = true) == true
        ) {
            return node
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = findNodeByIdHint(child, idHint)
            if (found != null) return found
        }
        return null
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

    private fun scheduleDemoRecheck() {
        if (demoRecheckJob?.isActive == true) return
        demoRecheckJob = scope.launch {
            delay(DEMO_RECHECK_DELAY_MS)
            if (
                currentState != GuideState.IDLE &&
                demoSession?.step?.shouldReanalyzeWhileWaiting() == true
            ) {
                analyzeCurrentScreen()
            }
            demoRecheckJob = null
            if (
                currentState != GuideState.IDLE &&
                demoSession?.step?.shouldReanalyzeWhileWaiting() == true
            ) {
                scheduleDemoRecheck()
            }
        }
    }

    private fun stopGuidance(reason: StopReason, speak: Boolean) {
        guidanceTimeoutJob?.cancel()
        demoRecheckJob?.cancel()
        if (::speechController.isInitialized) speechController.stopListening()
        if (::bubble.isInitialized) bubble.setListening(false)
        demoSession = null
        lastDemoSpokenKey = null
        lastInstructionMessage = null
        if (::guideSession.isInitialized) guideSession.stop(reason)
        hideGuideOverlay()
        awaitingGuidedTap = false
        currentState = GuideState.IDLE
        finishMetric(reason, guideSessionStepCountOrZero())
        if (speak && ::ttsController.isInitialized) {
            ttsController.speak(reason.toMessage())
        }
    }

    private fun hideGuideOverlay() {
        if (::maskOverlay.isInitialized) maskOverlay.hide()
        if (::highlighter.isInitialized) highlighter.hide()
        if (::guideControls.isInitialized) guideControls.hide()
    }

    private fun showGuideControls(message: String) {
        lastInstructionMessage = message
        guideControls.show(message)
    }

    private fun repeatLastInstruction() {
        lastInstructionMessage?.takeIf { it.isNotBlank() }?.let(ttsController::speak)
    }

    private fun goBackOneStep() {
        if (demoSession?.step == DemoStep.SELECT_DEPARTURE_FIELD) {
            ttsController.speak("지금이 첫 단계예요. 출발역을 눌러 주세요.")
            return
        }
        guidanceTimeoutJob?.cancel()
        demoRecheckJob?.cancel()
        demoSession?.goBackToPreviousUserStep()
        hideGuideOverlay()
        awaitingGuidedTap = false
        currentState = GuideState.THINKING
        performGlobalAction(GLOBAL_ACTION_BACK)
        scope.launch {
            delay(DEFAULT_POST_TAP_DELAY_MS)
            analyzeCurrentScreen()
        }
    }

    private fun DemoStep.simpleInstruction(): String {
        return when (this) {
            DemoStep.SELECT_DEPARTURE_FIELD -> "1단계. 출발역을 눌러 주세요."
            DemoStep.INPUT_DEPARTURE -> "검색창을 눌러 주세요. 진주를 입력해 드릴게요."
            DemoStep.SELECT_DEPARTURE_RESULT -> "검색 결과에서 진주역을 눌러 주세요."
            DemoStep.SELECT_ARRIVAL_FIELD -> "도착역을 눌러 주세요."
            DemoStep.INPUT_ARRIVAL -> "검색창을 눌러 주세요. 서울을 입력해 드릴게요."
            DemoStep.SELECT_ARRIVAL_RESULT -> "검색 결과에서 서울역을 눌러 주세요."
            DemoStep.SELECT_DATE_FIELD -> "가는 날을 눌러 주세요."
            DemoStep.SELECT_TOMORROW -> "달력에서 내일을 눌러 주세요."
            DemoStep.SELECT_TIME -> "출발 시간을 오전 6시 이후로 맞춰 주세요."
            DemoStep.CONFIRM_DATE -> "날짜와 시간을 확인한 뒤 확인을 눌러 주세요."
            DemoStep.SELECT_PASSENGER_FIELD -> "인원을 눌러 주세요."
            DemoStep.ADULT_PLUS_1 -> "어른을 한 명 더해 2명으로 맞춰 주세요."
            DemoStep.CHILD_PLUS_1 -> "어린이를 한 명으로 맞춰 주세요."
            DemoStep.CONFIRM_PASSENGER -> "인원을 확인한 뒤 확인을 눌러 주세요."
            DemoStep.SEARCH_TRAINS -> "열차 조회를 눌러 주세요."
            DemoStep.SCAN_VISIBLE_RESULTS,
            DemoStep.APPLY_NEXT_SEARCH_POLICY -> "조건에 맞는 열차를 찾고 있어요. 잠시 기다려 주세요."
            DemoStep.SUGGEST_TRAIN -> "강조된 열차를 확인하고 직접 선택해 주세요."
            DemoStep.FOLLOW_USER_SELECTION -> "예매 정보가 맞는지 확인하고 다음 버튼을 직접 눌러 주세요."
            DemoStep.PAYMENT_ENTRY -> "결제 전 정보는 직접 확인해 주세요. 큐 안내는 여기서 멈춥니다."
            DemoStep.DONE -> "안내를 마쳤어요."
        }
    }

    private fun Int.isUserTouchEvent(): Boolean {
        return this == AccessibilityEvent.TYPE_VIEW_CLICKED
    }

    private fun Int.isGuideAdvanceEvent(): Boolean {
        val activeDemo = demoSession
        if (activeDemo == null) {
            return this == AccessibilityEvent.TYPE_VIEW_CLICKED
        }

        return this == AccessibilityEvent.TYPE_VIEW_CLICKED
    }

    private fun Int.isDemoScreenMutationEvent(): Boolean {
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
            (node.isEditable || node.className?.toString()?.contains("EditText", ignoreCase = true) == true)
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
        const val PROMPT_LISTENING = "무엇을 도와드릴까요?"
        const val PROMPT_LISTENING_FAILED = "잘 못 들었어요. 다시 말씀해 주세요."
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
        const val AUTO_TAP_POST_DELAY_MS = 1_000L
        const val DEMO_RECHECK_DELAY_MS = 1_200L
        const val DEMO_SETUP_TIMEOUT_MS = 300_000L
        const val POLICY_BACK_DELAY_MS = 1_800L
        const val STATION_RESULT_DELAY_MS = 700L
        const val DEMO_INPUT_RETRY_COUNT = 3
        const val DEMO_STATION_RESULT_RETRY_COUNT = 3
        const val DEMO_RESULT_SCREEN_RETRY_COUNT = 4
        const val DEMO_TRANSITION_RETRY_COUNT = 4
        const val DEMO_RESULT_TRANSITION_RETRY_COUNT = 4
        const val DEMO_POLICY_BACK_RETRY_COUNT = 2
        const val DEMO_TIME_SWIPE_RETRY_COUNT = 3
    }
}
