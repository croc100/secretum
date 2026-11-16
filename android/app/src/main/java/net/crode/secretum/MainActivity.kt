package net.crode.secretum

import android.Manifest
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.crode.secretum.audio.SpeechInput
import net.crode.secretum.audio.SpeechOutput
import net.crode.secretum.camera.GlassesCameraSource
import net.crode.secretum.camera.PhoneCameraSource
import net.crode.secretum.core.AssistantPipeline
import net.crode.secretum.glasses.GlassesLink
import net.crode.secretum.net.AskMode
import net.crode.secretum.net.AssistantClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 개발용 콘솔 화면. 안경 없이도 화면 버튼/볼륨 다운 키로 전체 파이프라인을 돌려볼 수 있음.
 */
class MainActivity : ComponentActivity() {

    private lateinit var link: GlassesLink
    private lateinit var speechOut: SpeechOutput
    private lateinit var pipeline: AssistantPipeline
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val denied = res.filterValues { !it }.keys
        if (denied.isNotEmpty()) log("권한 거부: $denied") else log("권한 OK")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)  // MVP: 화면 켜진 동안만 동작
        buildUi()

        permissions.launch(arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        ))

        link = GlassesLink(applicationContext, lifecycleScope)
        speechOut = SpeechOutput(this)
        pipeline = AssistantPipeline(
            scope = lifecycleScope,
            link = link,
            phoneCamera = PhoneCameraSource(this, this),
            glassesCamera = GlassesCameraSource(link),
            speechIn = SpeechInput(this),
            speechOut = speechOut,
            client = AssistantClient(BuildConfig.SERVER_URL, BuildConfig.APP_TOKEN),
            log = ::log,
        )
        pipeline.start()

        lifecycleScope.launch {
            link.state.collect { statusView.text = "안경: $it   서버: ${BuildConfig.SERVER_URL}" }
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32) }
        statusView = TextView(this).apply { textSize = 14f }
        root.addView(statusView)

        fun button(label: String, onClick: () -> Unit) =
            root.addView(Button(this).apply { text = label; setOnClickListener { onClick() } }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        button("안경 연결") { link.connect() }
        button("음성 질문 (A 짧게 / 볼륨 다운)") { pipeline.ask(AskMode.VOICE) }
        button("무음 질문 (A 더블)") { pipeline.ask(AskMode.SILENT) }
        button("중단 (B 길게)") { pipeline.cancel() }
        button("대화 리셋 (A 길게)") { pipeline.resetSession() }

        logView = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }
        root.addView(ScrollView(this).apply { addView(logView) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 개발용 트리거. TODO(phase2): 블루투스 이어폰 버튼은 MediaSession으로 수신
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && event?.repeatCount == 0) {
            pipeline.ask(AskMode.VOICE)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun log(msg: String) {
        runOnUiThread { logView.append("${timeFmt.format(Date())}  $msg\n") }
    }

    override fun onDestroy() {
        pipeline.cancel()
        link.disconnect()
        speechOut.shutdown()
        super.onDestroy()
    }
}
