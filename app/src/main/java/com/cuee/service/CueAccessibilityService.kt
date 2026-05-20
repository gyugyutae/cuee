package com.cuee.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.pm.ApplicationInfo
import android.os.Build
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.cuee.accessibility.AccessibilitySnapshotMapper
import com.cuee.accessibility.DefaultAccessibilitySnapshotMapper
import com.cuee.data.DataStoreSettingsRepository
import com.cuee.data.LocalUtMetricRepository
import com.cuee.data.UtMetric
import com.cuee.data.UtMetricRepository
import com.cuee.data.UtResult
import com.cuee.data.UtTaskType
import com.cuee.domain.command.DefaultKorailCommandParser
import com.cuee.domain.command.KorailCommand
import com.cuee.domain.command.KorailCommandParser
import com.cuee.domain.safety.DefaultSafetyPolicy
import com.cuee.domain.safety.StopReason
import com.cuee.domain.scoring.ClusterCandidateResolver
import com.cuee.domain.scoring.KorailTargetScorer
import com.cuee.domain.session.DefaultGuideSession
import com.cuee.domain.session.GuideSession
import com.cuee.domain.session.GuideState
import com.cuee.domain.session.GuideStepResult
import com.cuee.domain.session.SpokenPrompt
import com.cuee.overlay.BubbleOverlayController
import com.cuee.overlay.CandidateHighlighter
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
    private lateinit var speechController: SpeechController
    private lateinit var ttsController: AndroidTtsController
    private lateinit var commandParser: KorailCommandParser
    private lateinit var snapshotMapper: AccessibilitySnapshotMapper
    private lateinit var guideSession: GuideSession
    private var currentPackageName: String? = null
    private var currentState: GuideState = GuideState.IDLE
    private var awaitingGuidedTap = false
    private var guidanceTimeoutJob: Job? = null
    private var runningMetric: RunningUtMetric? = null
    private var debugReceiver: BroadcastReceiver? = null

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
                if (settings.bubbleEnabled && currentPackageName == KORAIL_PACKAGE) {
                    bubble.show(settings.bubbleEdge, settings.bubbleYRatio)
                } else {
                    bubble.hide()
                }
            }
        }
        registerDebugReceiver()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventPackageName = event?.packageName?.toString()
        if (eventPackageName != packageName) {
            currentPackageName = eventPackageName
        }
        if (!::bubble.isInitialized || !::settingsRepository.isInitialized) return

        val eventType = event?.eventType ?: return
        val isKorailEvent = currentPackageName == KORAIL_PACKAGE

        if (!isKorailEvent && currentState != GuideState.IDLE) {
            stopGuidance(StopReason.NOT_KORAIL_APP, speak = false)
        } else if (currentState == GuideState.LISTENING && isKorailEvent && eventType.isUserTouchEvent()) {
            stopGuidance(StopReason.USER_CANCELLED, speak = false)
        } else if (currentState == GuideState.GUIDING && awaitingGuidedTap && isKorailEvent && eventType.isGuideAdvanceEvent()) {
            awaitingGuidedTap = false
            analyzeCurrentScreen()
        }

        scope.launch {
            val settings = settingsRepository.settings.first()
            if (settings.bubbleEnabled && currentPackageName == KORAIL_PACKAGE) {
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
        val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!isDebuggable || debugReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != ACTION_DEBUG_COMMAND) return
                val utterance = intent.getStringExtra(EXTRA_UTTERANCE).orEmpty()
                if (utterance.isBlank()) return
                currentPackageName = KORAIL_PACKAGE
                handleSpeechResult(utterance)
            }
        }
        debugReceiver = receiver
        val filter = IntentFilter(ACTION_DEBUG_COMMAND)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
    }

    private fun unregisterDebugReceiver() {
        debugReceiver?.let { runCatching { unregisterReceiver(it) } }
        debugReceiver = null
    }

    private fun beginListeningFromBubble() {
        if (currentPackageName != KORAIL_PACKAGE) return
        hideGuideOverlay()
        guidanceTimeoutJob?.cancel()
        currentState = GuideState.LISTENING
        awaitingGuidedTap = false
        ttsController.speak(PROMPT_LISTENING)
        speechController.startListening(SpeechController.KO_KR)
    }

    private fun handleSpeechResult(utterance: String) {
        if (currentPackageName != KORAIL_PACKAGE) {
            stopGuidance(StopReason.NOT_KORAIL_APP, speak = false)
            return
        }

        currentState = GuideState.THINKING
        val command = commandParser.parse(utterance)
        if (command == null) {
            currentState = GuideState.FAILED
            ttsController.speak(PROMPT_UNSUPPORTED_COMMAND)
            return
        }

        guideSession.begin(command)
        beginMetric(command)
        analyzeCurrentScreen()
    }

    private fun handleSpeechError(error: SpeechError) {
        if (currentState != GuideState.LISTENING) return
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

        currentState = GuideState.THINKING
        hideGuideOverlay()
        val snapshot = snapshotMapper.map(rootInActiveWindow)
        val result = guideSession.next(snapshot)
        currentState = result.state
        renderGuideResult(result)
    }

    private fun renderGuideResult(result: GuideStepResult) {
        val instruction = result.instruction
        if (result.isGuiding && instruction != null) {
            maskOverlay.show(instruction)
            highlighter.show(instruction.highlightedBounds)
            awaitingGuidedTap = true
            ttsController.speak(instruction.spokenPrompt.toMessage(result.stopReason))
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
        guidanceTimeoutJob?.cancel()
        if (::speechController.isInitialized) speechController.stopListening()
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
    }

    private fun Int.isUserTouchEvent(): Boolean {
        return this == AccessibilityEvent.TYPE_VIEW_CLICKED
    }

    private fun Int.isGuideAdvanceEvent(): Boolean {
        return this == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            this == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
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
        }
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
        const val ACTION_DEBUG_COMMAND = "com.cuee.DEBUG_COMMAND"
        const val EXTRA_UTTERANCE = "utterance"
    }
}
