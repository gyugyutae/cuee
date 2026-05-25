package com.cuee.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.LinearLayout
import android.widget.ScrollView
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
        requestAudioPermissionIfNeeded()
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
            body("코레일톡 단일 MVP로, 예매와 승차권 확인에서 다음에 누를 곳만 화면 위에 표시해요.")
            body("큐는 대신 누르지 않아요. 사용자가 직접 눌러 거래를 이어갑니다.")
            body("로그인, 결제, 개인정보 화면에서는 안내를 멈추고 직접 확인하도록 알려요.")
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
            body("설정에서 \"큐\" 접근성 서비스를 켜면 코레일톡 위에 큐 버튼이 나타나요.")
            body("화면 원문과 음성 원문은 저장하지 않아요.")
            primaryButton("접근성 설정 열기") {
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
            body("큐는 코레일톡 MVP에서만 동작하며, 대신 누르기와 결제 진행은 하지 않아요.")
            body("로그인, 결제, 개인정보 화면에서는 \"이 화면은 직접 확인해 주세요\"라고 안내하고 멈춰요.")
            body(if (settings.bubbleEnabled) "큐 버튼이 켜져 있어요." else "큐 버튼이 꺼져 있어요.")
            primaryButton(if (settings.bubbleEnabled) "큐 버튼 끄기" else "큐 버튼 켜기") {
                scope.launch {
                    settingsRepository.setBubbleEnabled(!settings.bubbleEnabled)
                    render()
                }
            }
        })
    }

    private fun baseColumn(content: LinearLayout.() -> Unit): ScrollView {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(24))
            setBackgroundColor(Color.WHITE)
            content()
        }
        return ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(
                column,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
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
            gravity = Gravity.CENTER
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

    private fun requestAudioPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQUEST_RECORD_AUDIO = 10
    }
}
