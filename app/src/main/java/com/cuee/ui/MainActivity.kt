package com.cuee.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.LinearLayout
import android.widget.TextView
import com.cuee.data.DataStoreSettingsRepository
import com.cuee.data.UserSettings
import com.cuee.overlay.CUE_GREEN
import com.cuee.overlay.rounded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : Activity() {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main)
    private lateinit var settingsRepository: DataStoreSettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepository = DataStoreSettingsRepository(applicationContext)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        job.cancel()
        super.onDestroy()
    }

    private fun render() {
        scope.launch {
            val settings = settingsRepository.settings.first()
            when {
                !settings.onboardingCompleted -> showOnboarding()
                !isCueAccessibilityEnabled() -> showAccessibilitySetup(settings)
                else -> showHome(settings)
            }
        }
    }

    private fun showOnboarding() {
        setContentView(baseColumn {
            title("큐")
            body("코레일톡에서 다음에 누를 곳을 찾기 어려울 때 화면 위에 표시해요.")
            body("큐는 대신 누르지 않고, 로그인이나 결제 화면에서는 멈춰요.")
            primaryButton("동의하고 시작") {
                scope.launch {
                    settingsRepository.setOnboardingCompleted(true)
                    showAccessibilitySetup(settingsRepository.settings.first())
                }
            }
        })
    }

    private fun showAccessibilitySetup(settings: UserSettings) {
        setContentView(baseColumn {
            title("접근성 설정")
            body("코레일톡 화면 위에 안내를 표시하려면 Android 접근성 서비스가 필요해요.")
            body("화면 원문과 음성 원문은 저장하지 않아요.")
            primaryButton("설정 열기") {
                scope.launch { settingsRepository.setAccessibilityGuideCompleted(true) }
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            if (settings.accessibilityGuideCompleted) {
                body("설정을 켠 뒤 이 화면으로 돌아오세요.")
            }
        })
    }

    private fun showHome(settings: UserSettings) {
        setContentView(baseColumn {
            title("큐가 준비됐어요")
            body("코레일톡을 직접 열고 화면 가장자리의 큐 버튼을 사용하세요.")
            body(if (settings.bubbleEnabled) "큐 버튼이 켜져 있어요." else "큐 버튼이 꺼져 있어요.")
            primaryButton(if (settings.bubbleEnabled) "큐 버튼 끄기" else "큐 버튼 켜기") {
                scope.launch {
                    settingsRepository.setBubbleEnabled(!settings.bubbleEnabled)
                    render()
                }
            }
        })
    }

    private fun baseColumn(content: LinearLayout.() -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(24))
            setBackgroundColor(Color.WHITE)
            content()
        }
    }

    private fun LinearLayout.title(textValue: String) {
        addView(TextView(this@MainActivity).apply {
            text = textValue
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(22)
        })
    }

    private fun LinearLayout.body(textValue: String) {
        addView(TextView(this@MainActivity).apply {
            text = textValue
            textSize = 18f
            setTextColor(0xFF222222.toInt())
            setLineSpacing(0f, 1.12f)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })
    }

    private fun LinearLayout.primaryButton(textValue: String, onClick: () -> Unit) {
        addView(TextView(this@MainActivity).apply {
            text = textValue
            textSize = 20f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(CUE_GREEN, dp(8))
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(20)
            bottomMargin = dp(16)
        })
    }

    private fun isCueAccessibilityEnabled(): Boolean {
        val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val services = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return services.any { service ->
            val info = service.resolveInfo.serviceInfo
            info.packageName == packageName && info.name == "com.cuee.service.CueAccessibilityService"
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
