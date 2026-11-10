package com.cuee.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.EditText
import com.cuee.domain.command.JourneyConversation
import com.cuee.domain.command.JourneyRequest
import com.cuee.domain.command.JourneyConversationState
import com.cuee.domain.demo.PassengerPlan
import com.cuee.service.CueAccessibilityService
import com.cuee.speech.AndroidSpeechController
import androidx.core.net.toUri

/** Only structured conditions are held in process memory; never save the input/transcript to disk. */
object JourneyHandoff {
    var conversation = JourneyConversation()
    var pending: JourneyRequest? = null
    var onConfirmed: (() -> Unit)? = null
}

class JourneyActivity : Activity() {
    private lateinit var speech: AndroidSpeechController
    private lateinit var input: EditText
    private var status: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getBundle(DRAFT)?.let { JourneyHandoff.conversation = restore(it) }
        speech = AndroidSpeechController(this,
            onResult = { accept(it) },
            onError = { status = "음성을 듣지 못했어요. 다시 누르거나 글자로 입력해 주세요."; render() })
        render()
    }

    override fun onDestroy() { speech.destroy(); super.onDestroy() }

    override fun onSaveInstanceState(outState: Bundle) {
        val state = JourneyHandoff.conversation.snapshot()
        outState.putBundle(DRAFT, Bundle().apply {
            putString("departure", state.departure); putString("arrival", state.arrival)
            putString("date", state.date?.toString()); state.hour?.let { putInt("hour", it) }
            state.passengers?.let { putIntArray("passengers", intArrayOf(it.adults, it.children, it.infants, it.seniors)) }
            putStringArrayList("preferences", ArrayList(state.selectionPreferences))
            putStringArrayList("unresolved", ArrayList(state.unresolved))
        })
        super.onSaveInstanceState(outState)
    }

    private fun restore(bundle: Bundle) = JourneyConversation.restore(JourneyConversationState(
        bundle.getString("departure"), bundle.getString("arrival"),
        bundle.getString("date")?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() },
        if (bundle.containsKey("hour")) bundle.getInt("hour") else null,
        bundle.getIntArray("passengers")?.takeIf { it.size == 4 }?.let { PassengerPlan(it[0], it[1], it[2], it[3]) },
        bundle.getStringArrayList("preferences")?.toSet().orEmpty(),
        bundle.getStringArrayList("unresolved")?.toSet().orEmpty()
    ))

    private fun accept(text: String) {
        status = JourneyHandoff.conversation.accept(text)
        input.text.clear()
        render()
    }

    private fun render() {
        val conversation = JourneyHandoff.conversation
        val request = conversation.request()
        setContentView(cuePage {
            hero("어떤 기차를 찾을까요?")
            block {
                section(conversation.question)
                status?.let { note(it) }
                val summary = request?.summary ?: listOfNotNull(
                    conversation.departure?.let { "출발: $it" }, conversation.arrival?.let { "도착: $it" },
                    conversation.date?.let { "날짜: $it" }, conversation.hour?.let { "출발: ${it}시 이후" },
                    conversation.passengers?.let { "어른 ${it.adults}, 어린이 ${it.children}, 유아 ${it.infants}, 경로 ${it.seniors}" }).joinToString("\n")
                if (summary.isNotBlank()) body(summary)
                input = cueInput("예: 부산에서 서울, 내일 오후 3시, 어른 2명").apply {
                    importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
                    inputType = android.text.InputType.TYPE_CLASS_TEXT
                }
                // 조건이 다 모이면 주 행동은 '안내 시작' 하나 — 그 전에는 '입력하기'.
                cueButton("입력하기", if (request == null) ButtonKind.PRIMARY else ButtonKind.SECONDARY) { accept(input.text.toString()) }
                cueButton("마이크로 말하기") {
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10)
                    } else { status = "듣고 있어요…"; speech.startListening() }
                }
                if (conversation.hasUnresolvedInput) cueButton("반영되지 않은 입력 취소", ButtonKind.QUIET) { accept("이전 입력 취소") }
                request?.let { confirmed -> cueButton("이 조건으로 안내 시작", ButtonKind.PRIMARY) { startGuide(confirmed) } }
            }
            block {
                note("어린이·유아·경로의 연령 기준은 코레일+에서 확인해 주세요. 로그인과 예매 확정·결제는 직접 진행합니다.")
            }
            block {
                cueButton("조건 처음부터 입력", ButtonKind.QUIET) { JourneyHandoff.conversation = JourneyConversation(); status = null; render() }
                cueButton("돌아가기", ButtonKind.QUIET) { finish() }
            }
        })
    }

    private fun startGuide(request: JourneyRequest) {
        val expected = ComponentName(this, CueAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == expected }
        if (!enabled) { startActivity(Intent(this, MainActivity::class.java)); finish(); return }
        val launch = packageManager.getLaunchIntentForPackage("com.korail.talk")
        if (launch == null) {
            startActivity(Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=com.korail.talk".toUri()))
        } else {
            JourneyHandoff.pending = request
            if (com.cuee.BuildConfig.DEBUG) android.util.Log.d("CueAccessibilityService", "journey confirmed by user")
            startActivity(launch)
            JourneyHandoff.onConfirmed?.invoke()
            finish()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) speech.startListening()
            else { status = "마이크 없이 글자로 입력할 수 있어요."; render() }
        }
    }

    private companion object { const val DRAFT = "structured_journey_draft" }
}
