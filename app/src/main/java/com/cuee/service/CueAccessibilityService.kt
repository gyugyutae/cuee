package com.cuee.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.cuee.data.DataStoreSettingsRepository
import com.cuee.overlay.BubbleOverlayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class CueAccessibilityService : AccessibilityService() {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main)
    private lateinit var settingsRepository: DataStoreSettingsRepository
    private lateinit var bubble: BubbleOverlayController
    private var currentPackageName: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        settingsRepository = DataStoreSettingsRepository(applicationContext)
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        bubble = BubbleOverlayController(
            context = applicationContext,
            windowManager = windowManager,
            onTap = { },
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
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        currentPackageName = event?.packageName?.toString()
        if (!::bubble.isInitialized || !::settingsRepository.isInitialized) return
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
        if (::bubble.isInitialized) bubble.hide()
    }

    override fun onDestroy() {
        if (::bubble.isInitialized) bubble.hide()
        job.cancel()
        super.onDestroy()
    }

    private companion object {
        const val KORAIL_PACKAGE = "com.korail.talk"
    }
}
