package com.cuee.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.graphics.Color
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 60, 48, 48)
            setBackgroundColor(Color.WHITE)
        }
        fun text(value: String) { column.addView(TextView(this).apply { text = value; textSize = 21f; setTextColor(Color.BLACK); setPadding(0, 16, 0, 16) }) }
        fun button(label: String, action: () -> Unit) { column.addView(Button(this).apply { text = label; textSize = 18f; isAllCaps = false; minHeight = 120; setOnClickListener { action() } }) }
        text("어떤 기차를 찾을까요?")
        text(conversation.question)
        status?.let(::text)
        conversation.request()?.let { text(it.summary) } ?: text(listOfNotNull(
            conversation.departure?.let { "출발: $it" }, conversation.arrival?.let { "도착: $it" },
            conversation.date?.let { "날짜: $it" }, conversation.hour?.let { "출발: ${it}시 이후" },
            conversation.passengers?.let { "어른 ${it.adults}, 어린이 ${it.children}, 유아 ${it.infants}, 경로 ${it.seniors}" }).joinToString("\n"))
        input = EditText(this).apply {
            hint = "예: 부산에서 서울, 내일 오후 3시, 어른 2명"
            textSize = 18f
            isSaveEnabled = false
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        column.addView(input)
        button("입력하기") { accept(input.text.toString()) }
        if (conversation.hasUnresolvedInput) button("반영되지 않은 입력 취소") { accept("이전 입력 취소") }
        button("마이크로 말하기") {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10)
            } else { status = "듣고 있어요…"; speech.startListening() }
        }
        conversation.request()?.let { request ->
            button("이 조건으로 안내 시작") {
                val expected = ComponentName(this, CueAccessibilityService::class.java)
                val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                    .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == expected }
                if (!enabled) { startActivity(Intent(this, MainActivity::class.java)); finish(); return@button }
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
        }
        button("조건 처음부터 입력") { JourneyHandoff.conversation = JourneyConversation(); status = null; render() }
        button("돌아가기") { finish() }
        text("어린이·유아·경로의 연령 기준은 코레일+에서 확인해 주세요. 로그인과 예매 확정·결제는 직접 진행합니다.")
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(column) })
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
