package com.cuee.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.EditText
import com.cuee.speech.AndroidSpeechController
import androidx.core.net.toUri
import com.cuee.data.DataStoreSettingsRepository
import com.cuee.data.UserSettings
import com.cuee.data.hasCurrentConsent
import com.cuee.domain.command.JourneyConversation
import com.cuee.overlay.CUE_GREEN
import com.cuee.service.CueAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : Activity() {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main)
    private lateinit var settingsRepository: DataStoreSettingsRepository
    private var practiceSpeech: AndroidSpeechController? = null
    private var practicing = false
    private val onboardingState by lazy { getSharedPreferences("onboarding_progress", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepository = DataStoreSettingsRepository(applicationContext)
        practicing = onboardingState.getBoolean("practice_active", false)
        if (practicing) showPractice(onboardingState.getInt("practice_step", 0))
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        job.cancel()
        practiceSpeech?.destroy()
        super.onDestroy()
    }

    private fun render() {
        if (practicing) return
        scope.launch {
            val settings = settingsRepository.settings.first()
            when {
                !settings.hasCurrentConsent() && !onboardingState.getBoolean("welcome_seen", false) -> showWelcome()
                !settings.hasCurrentConsent() -> showConsentDisclosure()
                !isCueAccessibilityEnabled() -> showAccessibilitySetup(settings)
                else -> showHome(settings)
            }
        }
    }

    private fun showWelcome() {
        setContentView(baseColumn {
            title("코레일+, 큐와 함께 이용해요")
            body("큐가 다음에 누를 곳을 알려드려요. 버튼은 직접 눌러 주세요.")
            primaryButton("로그인 없이 시작하기") {
                onboardingState.edit().putBoolean("welcome_seen", true).apply()
                showConsentDisclosure()
            }
        })
    }

    private fun showConsentDisclosure() {
        setContentView(baseColumn {
            title("접근성 기능 사용 안내")
            body("큐는 인지적 어려움이나 디지털 사용의 어려움이 있는 사람이 코레일+에서 다음 행동을 찾도록 돕는 접근성 도구입니다.")

            section("접근하는 정보")
            body("안내 중인 코레일+ 화면의 글자, 버튼 이름과 화면 위치를 읽습니다. 마이크 버튼을 누르면 사용자가 말한 명령을 Android 음성 인식 기능으로 처리합니다.")

            section("사용하는 방법")
            body("읽은 정보는 다음에 직접 누를 곳을 강조하고 음성으로 설명하는 데 사용합니다. 역 이름 입력과 시간 목록 이동을 보조할 수 있지만 로그인, 열차 선택, 예매와 결제는 대신 실행하지 않습니다.")

            section("저장과 전송")
            body("큐는 화면 원문과 음성 원문을 저장하거나 개발자 서버로 전송하지 않습니다. 설정, 동의 시각, 작업 종류·소요 시간·성공 여부 같은 사용 결과만 이 기기에 저장합니다. Android 음성 인식 제공자는 기기 설정에 따라 음성을 처리할 수 있습니다.")

            primaryButton("동의하고 계속") {
                scope.launch {
                    settingsRepository.setOnboardingCompleted(true)
                    render()
                }
            }
            secondaryButton("개인정보 처리방침 보기") { showPrivacyPolicy() }
            secondaryButton("동의하지 않고 종료") { finish() }
        })
    }

    private fun showAccessibilitySetup(settings: UserSettings) {
        setContentView(baseColumn {
            title("접근성 설정")
            body("코레일+ 화면에서 다음에 누를 곳을 찾고 그 위에 안내를 표시하려면 Android 접근성 서비스가 필요합니다.")
            body("설정에서 ‘큐’를 선택하고 접근성 사용을 허용해 주세요. 언제든 Android 설정이나 큐 앱에서 사용을 중지할 수 있습니다.")
            primaryButton("접근성 설정 열기") {
                scope.launch { settingsRepository.setAccessibilityGuideCompleted(true) }
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            secondaryButton("개인정보 처리방침 보기") { showPrivacyPolicy() }
            if (settings.accessibilityGuideCompleted) {
                body("설정을 켠 뒤 이 화면으로 돌아오세요.")
            }
        })
    }

    private fun showHome(settings: UserSettings) {
        setContentView(baseColumn {
            title(if (onboardingState.getBoolean("home_seen", false)) "다시 오셨어요!" else "큐가 준비됐어요")
            onboardingState.edit().putBoolean("home_seen", true).apply()
            body("1. 코레일+를 엽니다.\n2. 화면 가장자리의 큐 버튼을 누릅니다.\n3. 원하는 도움을 말합니다.")
            body("예: ‘서울에서 부산 가는 열차 찾아줘’")
            body("로그인, 개인정보, 열차 선택, 예매와 결제는 사용자가 직접 확인하고 진행합니다.")

            primaryButton("코레일+ 켜기") {
                scope.launch { settingsRepository.setBubbleEnabled(true); openKorailPlus() }
            }
            primaryButton("연습하기") { practicing = true; showPractice(0) }
            secondaryButton("여행 조건 입력하기") {
                JourneyHandoff.conversation = JourneyConversation()
                startActivity(Intent(this@MainActivity, JourneyActivity::class.java))
            }
            secondaryButton(if (settings.bubbleEnabled) "큐 버튼 끄기" else "큐 버튼 켜기") {
                scope.launch {
                    settingsRepository.setBubbleEnabled(!settings.bubbleEnabled)
                    render()
                }
            }
            secondaryButton("개인정보 처리방침 보기") { showPrivacyPolicy() }
            secondaryButton("접근성 기능 동의 철회") {
                scope.launch {
                    settingsRepository.withdrawConsent()
                    render()
                }
            }
        })
    }

    private fun showPractice(step: Int) {
        onboardingState.edit().putBoolean("practice_active", true).putInt("practice_step", step).apply()
        setContentView(baseColumn {
            title("처음 사용 연습 ${step + 1}/3")
            when (step) {
                0 -> {
                    body("코레일+ 화면 가장자리의 큐 버튼을 누르면 질문을 입력할 수 있어요.")
                    primaryButton("큐") { showPractice(1) }
                }
                1 -> {
                    body("말하거나 글자로 원하는 도움을 입력해 보세요.")
                    val field = EditText(this@MainActivity).apply { hint = "예: 서울에서 부산"; isSaveEnabled = false }
                    addView(field, matchWrap())
                    primaryButton("글자로 질문하기") { if (field.text.isNotBlank()) { field.text.clear(); showPractice(2) } }
                    primaryButton("마이크로 질문하기") {
                        if (!hasAudioPermission()) requestAudioPermission()
                        else {
                            startPracticeListening()
                        }
                    }
                }
                else -> {
                    body("강조된 버튼은 직접 눌러 주세요. 이 화면은 실제 예매가 없는 연습이에요.")
                    primaryButton("강조된 연습 버튼 누르기") {
                        onboardingState.edit().putBoolean("practice_completed", true).apply()
                        onboardingState.edit().putBoolean("practice_active", false).apply()
                        practicing = false
                        render()
                    }
                }
            }
            secondaryButton("연습 건너뛰기") {
                onboardingState.edit().putBoolean("practice_active", false).apply()
                practicing = false; practiceSpeech?.stopListening(); render()
            }
        })
    }

    private fun startPracticeListening() {
        practiceSpeech?.destroy()
        practiceSpeech = AndroidSpeechController(this,
            onResult = { showPractice(2) }, onError = { showPractice(1) })
        practiceSpeech?.startListening()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO && practicing) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startPracticeListening()
            else showPractice(1)
        }
    }

    private fun showPrivacyPolicy() {
        setContentView(baseColumn {
            title("개인정보 처리방침")
            body("시행일: 2026년 8월 29일")
            section("처리하는 정보")
            body("큐는 접근성 안내를 제공하는 동안 코레일+ 화면의 글자, 버튼 이름과 위치를 일시적으로 처리합니다. 음성 명령을 사용할 때에는 마이크 입력과 인식된 명령을 일시적으로 처리합니다.")
            section("저장하는 정보")
            body("동의 시각, 앱 설정, 작업 종류, 시작·종료 시각, 소요 시간, 성공 여부와 중단 사유를 기기에 최대 200건 저장합니다. 화면 원문과 음성 원문은 저장하지 않습니다.")
            section("제3자 처리")
            body("개발자 서버로 정보를 전송하지 않습니다. 음성 인식과 음성 출력은 사용자의 Android 기기에 설정된 서비스가 처리할 수 있으며 해당 제공자의 정책이 적용될 수 있습니다.")
            section("삭제와 철회")
            body("앱의 저장공간을 삭제하거나 앱을 제거하면 기기에 저장된 정보가 삭제됩니다. 홈 화면의 ‘접근성 기능 동의 철회’를 누르면 큐의 안내가 중지됩니다.")
            section("문의")
            body("개발자 또는 운영 주체: CUEE\n문의 이메일: zn7013@gmail.com\n개인정보 처리방침: https://gyutaetae.github.io/cuee/privacy-policy/")
            primaryButton("돌아가기") { render() }
        })
    }

    private fun baseColumn(content: LinearLayout.() -> Unit): ScrollView {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(32))
            setBackgroundColor(Color.WHITE)
            content()
        }
        return ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            isFillViewport = true
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun LinearLayout.title(textValue: String) {
        addView(textView(textValue, 30f, Typeface.DEFAULT_BOLD), matchWrap().apply { bottomMargin = dp(22) })
    }

    private fun LinearLayout.section(textValue: String) {
        addView(textView(textValue, 21f, Typeface.DEFAULT_BOLD), matchWrap().apply {
            topMargin = dp(14)
            bottomMargin = dp(6)
        })
    }

    private fun LinearLayout.body(textValue: String) {
        addView(textView(textValue, 18f, Typeface.DEFAULT), matchWrap().apply { bottomMargin = dp(12) })
    }

    private fun textView(textValue: String, size: Float, face: Typeface): TextView = TextView(this).apply {
        text = textValue
        textSize = size
        typeface = face
        setTextColor(0xFF1A1A1A.toInt())
        setLineSpacing(0f, 1.18f)
    }

    private fun LinearLayout.primaryButton(textValue: String, onClick: () -> Unit) {
        addView(actionButton(textValue, primary = true, onClick), matchWrap().apply {
            topMargin = dp(16)
            bottomMargin = dp(8)
        })
    }

    private fun LinearLayout.secondaryButton(textValue: String, onClick: () -> Unit) {
        addView(actionButton(textValue, primary = false, onClick), matchWrap().apply { bottomMargin = dp(8) })
    }

    private fun actionButton(textValue: String, primary: Boolean, onClick: () -> Unit): Button = Button(this).apply {
        text = textValue
        textSize = 18f
        gravity = Gravity.CENTER
        isAllCaps = false
        minHeight = dp(56)
        setTextColor(if (primary) Color.WHITE else 0xFF14532D.toInt())
        setBackgroundColor(if (primary) CUE_GREEN else 0xFFE7F5EA.toInt())
        setOnClickListener { onClick() }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun isCueAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, CueAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabled.orEmpty().split(':').any { ComponentName.unflattenFromString(it) == expected }
    }

    private fun hasAudioPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestAudioPermission() {
        if (!hasAudioPermission()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
        }
    }

    private fun openKorailPlus() {
        val launchIntent = packageManager.getLaunchIntentForPackage(KORAIL_PACKAGE)
        if (launchIntent != null) {
            startActivity(launchIntent)
            return
        }
        val marketIntent = Intent(Intent.ACTION_VIEW, "market://details?id=$KORAIL_PACKAGE".toUri())
        val webIntent = Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$KORAIL_PACKAGE".toUri())
        runCatching { startActivity(marketIntent) }.onFailure { startActivity(webIntent) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQUEST_RECORD_AUDIO = 10
        const val KORAIL_PACKAGE = "com.korail.talk"
    }
}
