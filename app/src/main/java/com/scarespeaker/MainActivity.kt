package com.scarespeaker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var listenSwitch: SwitchCompat
    private lateinit var testSwitch: SwitchCompat
    private lateinit var testPanel: LinearLayout
    private lateinit var nameRow: LinearLayout
    private val scenarioButtons = mutableListOf<Pair<Cmd, Button>>()
    private var selectedOpt = NameOpt.N1
    private var generating = false
    private var updatingSwitches = false

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.RECORD_AUDIO] == true && pendingListen) {
            pendingListen = false
            ScareService.send(this, ScareService.ACTION_LISTEN_ON)
        }
        pendingListen = false
        refresh()
    }
    private var pendingListen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) askPerms.launch(missing.toTypedArray())

        if (!Prefs.roleChosen) chooseRole(first = true)
    }

    override fun onResume() {
        super.onResume()
        ScareService.stateListener = { runOnUiThread { refresh() } }
        rebuildScenarioLabels()
        refresh()
        if (Prefs.roleChosen) ensureClips()
    }

    override fun onPause() {
        ScareService.stateListener = null
        super.onPause()
    }

    // ---------------- 화면 ----------------

    private fun buildUi() {
        val root = Ui.column(this, 16)

        val top = Ui.row(this)
        top.addView(Ui.title(this, "이놈아저씨 👹", 26f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(Ui.smallButton(this, "⚙ 설정") { startActivity(Intent(this, SettingsActivity::class.java)) })
        root.addView(top)

        status = Ui.text(this, "", 14f).apply { setPadding(0, Ui.dp(this@MainActivity, 6), 0, Ui.dp(this@MainActivity, 6)) }
        root.addView(status)

        val modes = Ui.card(this)
        listenSwitch = Ui.switch(this, "🎙 음성 인식 모드", false) { on ->
            if (updatingSwitches) return@switch
            if (on) {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    pendingListen = true
                    askPerms.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                } else ScareService.send(this, ScareService.ACTION_LISTEN_ON)
            } else if (ScareService.listening) {
                ScareService.send(this, ScareService.ACTION_LISTEN_OFF)
            }
        }
        testSwitch = Ui.switch(this, "🧪 테스트 모드", Prefs.testMode) { on ->
            if (updatingSwitches) return@switch
            Prefs.testMode = on
            refresh()
        }
        modes.addView(listenSwitch)
        modes.addView(testSwitch)
        root.addView(modes)
        root.addView(Ui.spacer(this, 12))

        // 이름 선택
        nameRow = Ui.row(this)
        root.addView(Ui.text(this, "누구 이름으로?", 14f))
        root.addView(nameRow)
        root.addView(Ui.spacer(this, 8))

        // 시나리오 버튼
        for (cmd in listOf(Cmd.COME, Cmd.TAKE, Cmd.NOSLEEP, Cmd.GOAWAY, Cmd.END)) {
            val color = if (cmd.stopsCurrent) Ui.RED_DARK else Ui.RED
            val b = Ui.bigButton(this, cmd.label, color) { play(cmd) }
            scenarioButtons += cmd to b
            root.addView(b)
        }

        // 테스트 패널
        testPanel = Ui.card(this)
        testPanel.addView(Ui.text(this, "테스트 모드: \"마이크 테스트\"라고 말하거나 아래 버튼", 14f, Ui.TEXT))
        testPanel.addView(Ui.spacer(this, 8))
        val testBtn = Ui.bigButton(this, "", Ui.GREEN, 60) { play(Cmd.TEST) }
        testBtn.tag = "testBtn"
        testPanel.addView(testBtn)
        testPanel.addView(Ui.stepper(this, "스피커 음량", Ui::castVolumeText, Ui::castMinus, Ui::castPlus))
        testPanel.addView(Ui.stepper(this, "스탠바이미 음량", { "${Prefs.dlnaVolume}" }, Ui::dlnaMinus, Ui::dlnaPlus))
        testPanel.addView(Ui.stepper(this, "스탠바이미 지연", Ui::delayText,
            { Prefs.dlnaDelayMs -= 10 }, { Prefs.dlnaDelayMs += 10 }))
        testPanel.addView(Ui.text(this, "지연: 스탠바이미가 늦게 들리면 −, 빨리 들리면 + (다음 재생부터 적용)", 12f))
        root.addView(testPanel)
        root.addView(Ui.spacer(this, 12))

        root.addView(Ui.bigButton(this, "⏹ 정지", 0xFF3A3A46.toInt(), 64) {
            ScareService.send(this, ScareService.ACTION_STOP)
        })

        root.addView(Ui.text(this, "버전 ${packageManager.getPackageInfo(packageName, 0).versionName}", 12f).apply {
            gravity = Gravity.CENTER; setPadding(0, Ui.dp(this@MainActivity, 8), 0, 0)
        })

        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(root) }
        setContentView(scroll)
    }

    private fun rebuildScenarioLabels() {
        nameRow.removeAllViews()
        for (opt in listOf(NameOpt.N1, NameOpt.N2, NameOpt.BOTH)) {
            val sel = opt == selectedOpt
            val b = Ui.smallButton(this, Scenario.nameLabel(opt), if (sel) Ui.RED else 0xFF3A3A46.toInt()) {
                selectedOpt = opt
                rebuildScenarioLabels()
            }
            nameRow.addView(b, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f).apply {
                rightMargin = Ui.dp(this@MainActivity, 6)
            })
        }
        val name = Scenario.nameLabel(selectedOpt)
        for ((cmd, b) in scenarioButtons) b.text = cmd.label.replace("○○", name)
        testPanel.findViewWithTag<Button>("testBtn")?.text = "▶ 테스트 재생 (${Prefs.testSound.label})"
    }

    private fun refresh() {
        updatingSwitches = true
        listenSwitch.isChecked = ScareService.listening
        testSwitch.isChecked = Prefs.testMode
        updatingSwitches = false
        testPanel.visibility = if (Prefs.testMode) View.VISIBLE else View.GONE
        val playing = ScareService.currentTitle
        val lines = mutableListOf("사용자: ${Prefs.role.label}  ·  재생: ${Prefs.target.label}")
        if (ScareService.listening) lines += "🎙 듣는 중" + if (Prefs.testMode) " (테스트 모드)" else ""
        if (playing.isNotEmpty()) lines += "▶ $playing"
        if (generating) lines += "음성 준비 중…"
        status.text = lines.joinToString("\n")
        for ((_, b) in scenarioButtons) b.isEnabled = !generating
        testPanel.findViewWithTag<Button>("testBtn")?.text = "▶ 테스트 재생 (${Prefs.testSound.label})"
    }

    private fun play(cmd: Cmd) {
        if (generating) return
        ScareService.send(this, ScareService.ACTION_PLAY, cmd, if (cmd.usesName) selectedOpt else NameOpt.NONE)
    }

    // ---------------- 사용자 선택 / 음성 만들기 ----------------

    private fun chooseRole(first: Boolean) {
        val roles = Role.values()
        AlertDialog.Builder(this)
            .setTitle(if (first) "누가 쓰나요?" else "사용자 바꾸기")
            .setItems(roles.map { it.label }.toTypedArray()) { _, i ->
                Prefs.role = roles[i]
                Prefs.roleChosen = true
                refresh()
                ensureClips()
            }
            .setCancelable(!first)
            .show()
    }

    private fun ensureClips() {
        if (generating) return
        val dir = Scenario.clipsDir(this)
        val missing = Prefs.clipsSignature != Prefs.currentSignature() ||
            !Scenario.clipFile(this, "test_music").exists()
        if (!missing && (dir.listFiles()?.size ?: 0) >= 10) return

        generating = true
        refresh()
        val msg = Ui.text(this, "음성 준비 중…", 16f, Ui.TEXT).apply {
            setPadding(Ui.dp(this@MainActivity, 24), Ui.dp(this@MainActivity, 24), Ui.dp(this@MainActivity, 24), Ui.dp(this@MainActivity, 24))
        }
        val dlg = AlertDialog.Builder(this).setTitle("음성 만들기").setView(msg).setCancelable(false).show()
        Thread {
            var err: String? = null
            try {
                ClipGenerator(applicationContext).generateAll { p -> runOnUiThread { msg.text = p } }
            } catch (e: Exception) {
                err = e.message
                AppLog.d("음성 만들기 실패: ${e.message}")
            }
            runOnUiThread {
                generating = false
                try { dlg.dismiss() } catch (_: Exception) {}
                refresh()
                if (err != null) {
                    AlertDialog.Builder(this).setTitle("음성을 만들지 못했어요")
                        .setMessage("$err\n\n폰 설정 > 일반 > 텍스트 음성 변환에서 한국어 음성이 설치돼 있는지 확인해 주세요.")
                        .setPositiveButton("다시 시도") { _, _ -> ensureClips() }
                        .setNegativeButton("닫기", null).show()
                }
            }
        }.start()
    }
}
