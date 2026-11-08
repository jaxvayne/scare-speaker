package com.scarespeaker

import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/** 목소리 고르기 + 모든 대사를 폰 스피커로 미리듣기 (거실 기기로는 안 나감) */
class PreviewActivity : AppCompatActivity() {

    private var player: MediaPlayer? = null
    private lateinit var list: LinearLayout
    private lateinit var voiceLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = this
        val root = Ui.column(c, 16)
        root.addView(Ui.title(c, "목소리 · 미리듣기"))
        root.addView(Ui.text(c, "여기서 듣는 소리는 이 폰에서만 나와요.", 13f))

        root.addView(Ui.section(c, "목소리 스타일"))
        root.addView(Ui.radio(c, Style.values().toList(), { it.label }, Prefs.style, horizontal = false) { Prefs.style = it })

        root.addView(Ui.section(c, "TTS 음성"))
        voiceLabel = Ui.text(c, "", 14f, Ui.TEXT)
        root.addView(voiceLabel)
        updateVoiceLabel()
        val vr = Ui.row(c)
        vr.addView(Ui.smallButton(c, "엔진 고르기") { pickEngine() }, LinearLayout.LayoutParams(0, Ui.dp(c, 48), 1f).apply { rightMargin = Ui.dp(c, 6) })
        vr.addView(Ui.smallButton(c, "음성 고르기") { pickVoice() }, LinearLayout.LayoutParams(0, Ui.dp(c, 48), 1f))
        root.addView(vr)
        root.addView(Ui.text(c, "남성 음성이 있으면 고르는 걸 추천해요. 고른 뒤 샘플로 들어 보세요.", 12f))
        root.addView(Ui.spacer(c, 8))
        root.addView(Ui.bigButton(c, "🔊 샘플 듣기 (지금 고른 스타일)", Ui.GREEN, 56) { playSample() })
        root.addView(Ui.bigButton(c, "✅ 이 목소리로 모든 대사 다시 만들기", Ui.RED, 56) { regenerate() })

        root.addView(Ui.section(c, "대사 미리듣기 (사용자: ${Prefs.role.label})"))
        list = Ui.column(c)
        root.addView(list)
        buildList()

        setContentView(ScrollView(c).apply { setBackgroundColor(Ui.BG); addView(root) })
    }

    override fun onDestroy() {
        stopLocal()
        super.onDestroy()
    }

    private fun updateVoiceLabel() {
        voiceLabel.text = "엔진: ${Prefs.ttsEngine.ifEmpty { "기본" }}\n음성: ${Prefs.ttsVoice.ifEmpty { "기본" }}"
    }

    private fun buildList() {
        list.removeAllViews()
        val role = Prefs.role
        val cats = Scenario.tabs.map { it.id } + "end"
        for (cat in cats) {
            list.addView(Ui.text(this, Scenario.categoryLabel(cat), 15f, 0xFFE8665C.toInt()).apply {
                setPadding(0, Ui.dp(this@PreviewActivity, 14), 0, Ui.dp(this@PreviewActivity, 4))
            })
            for (cmd in Scenario.commands.filter { it.category == cat }) {
                for ((a, t) in Scenario.variants(cmd)) {
                    val key = Scenario.clipKey(cmd, role, a, t)
                    addRow(Scenario.title(cmd, a, t), Scenario.displayText(cmd, role, a, t), key)
                }
            }
        }
        list.addView(Ui.text(this, "🧪 테스트", 15f, 0xFFE8665C.toInt()).apply { setPadding(0, Ui.dp(this@PreviewActivity, 14), 0, 0) })
        addRow("마이크 테스트", "숫자 세기", "test_count")
        addRow("마이크 테스트", "잔잔한 음악", "test_music")
    }

    private fun addRow(title: String, line: String, key: String) {
        val card = Ui.card(this).apply {
            isClickable = true
            setOnClickListener { playFile(Scenario.clipFile(this@PreviewActivity, key)) }
        }
        card.addView(Ui.text(this, "▶ $title", 15f, Ui.TEXT))
        card.addView(Ui.text(this, line, 13f))
        list.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = Ui.dp(this@PreviewActivity, 8)
        })
    }

    // ---------------- 폰에서 재생 ----------------

    private fun playFile(f: File) {
        if (!f.exists()) { toast("음성 파일이 없어요. 아래 '다시 만들기'를 눌러 주세요"); return }
        stopLocal()
        val am = getSystemService(AudioManager::class.java)
        // 음성 인식 중이면 폰 미디어 소리를 꺼 두므로 잠깐 켬
        try { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0) } catch (_: Exception) {}
        player = MediaPlayer().apply {
            setDataSource(f.absolutePath)
            setOnCompletionListener { stopLocal() }
            prepare()
            start()
        }
    }

    private fun stopLocal() {
        player?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        player = null
        if (ScareService.listening && Prefs.muteBeep) {
            try {
                getSystemService(AudioManager::class.java).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            } catch (_: Exception) {}
        }
    }

    private fun busy(msg: String): Pair<AlertDialog, TextView> {
        val tv = Ui.text(this, msg, 16f, Ui.TEXT).apply { setPadding(48, 48, 48, 48) }
        return AlertDialog.Builder(this).setView(tv).setCancelable(false).show() to tv
    }

    private fun playSample() {
        val (dlg, _) = busy("샘플 만드는 중…")
        val out = File(cacheDir, "sample.wav")
        Thread {
            val err = try { ClipGenerator(applicationContext).sample(Prefs.style, out); null } catch (e: Exception) { e.message }
            runOnUiThread {
                dlg.dismiss()
                if (err != null) toast("샘플을 만들지 못했어요: $err") else playFile(out)
            }
        }.start()
    }

    private fun regenerate() {
        val (dlg, tv) = busy("음성 준비 중…")
        Thread {
            val err = try {
                ClipGenerator(applicationContext).generateAll { p -> runOnUiThread { tv.text = p } }; null
            } catch (e: Exception) { e.message }
            runOnUiThread {
                dlg.dismiss()
                if (err != null) toast("만들지 못했어요: $err") else { toast("모든 대사를 새 목소리로 만들었어요"); buildList() }
            }
        }.start()
    }

    private fun pickEngine() {
        val (dlg, _) = busy("TTS 엔진 확인 중…")
        Thread {
            val (engines, _) = try { ClipGenerator.listVoices(applicationContext, Prefs.ttsEngine) } catch (e: Exception) { emptyList<Pair<String, String>>() to emptyList<String>() }
            runOnUiThread {
                dlg.dismiss()
                if (engines.isEmpty()) { toast("TTS 엔진을 찾지 못했어요"); return@runOnUiThread }
                AlertDialog.Builder(this).setTitle("TTS 엔진")
                    .setItems(engines.map { "${it.second} (${it.first})" }.toTypedArray()) { _, i ->
                        Prefs.ttsEngine = engines[i].first
                        Prefs.ttsVoice = ""
                        updateVoiceLabel()
                    }.show()
            }
        }.start()
    }

    private fun pickVoice() {
        val (dlg, _) = busy("한국어 음성 확인 중…")
        Thread {
            val (_, voices) = try { ClipGenerator.listVoices(applicationContext, Prefs.ttsEngine) } catch (e: Exception) { emptyList<Pair<String, String>>() to emptyList<String>() }
            runOnUiThread {
                dlg.dismiss()
                if (voices.isEmpty()) { toast("고를 수 있는 한국어 음성이 없어요"); return@runOnUiThread }
                val items = listOf("기본") + voices
                AlertDialog.Builder(this).setTitle("한국어 음성 (${voices.size}개)")
                    .setItems(items.toTypedArray()) { _, i ->
                        Prefs.ttsVoice = if (i == 0) "" else voices[i - 1]
                        updateVoiceLabel()
                        toast("샘플 듣기로 확인해 보세요")
                    }.show()
            }
        }.start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
