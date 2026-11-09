package com.scarespeaker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
    private lateinit var prepareSwitch: SwitchCompat
    private lateinit var deviceText: TextView
    private var requested = ""
    private lateinit var testPanel: LinearLayout
    private lateinit var tabRow: LinearLayout
    private lateinit var tabBody: LinearLayout
    private lateinit var testBtn: android.widget.Button
    private val allButtons = mutableListOf<View>()

    private var tab = "sleep"
    private var single = Who.N1          // 잠잘 때/식사: 누구
    private var actor = Who.N1           // 괴롭힘: 누가
    private var target = Who.N2          // 괴롭힘: 누구를
    private var generating = false
    private var updatingSwitches = false
    private var pendingListen = false

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.RECORD_AUDIO] == true && pendingListen) {
            ScareService.send(this, ScareService.ACTION_LISTEN_ON)
        }
        pendingListen = false
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            pendingListen = Prefs.autoListenOnOpen && Prefs.roleChosen
            askPerms.launch(missing.toTypedArray())
        }
        if (!Prefs.roleChosen) chooseRole(first = true)
        Schedule.apply(this)
    }

    override fun onResume() {
        super.onResume()
        ScareService.stateListener = { runOnUiThread { refresh() } }
        if (ScareService.running == null) {
            // 이전 버전에서 남은 '대기 중' 알림 정리
            getSystemService(android.app.NotificationManager::class.java).cancel(7)
        }
        rebuildTab()
        refresh()
        if (Prefs.roleChosen) ensureClips()

        // 앱을 새로 열었을 때(빅스비 "이놈아저씨 열어줘" 포함) 음성 인식 자동 켜기
        if (App.consumeOpened() && Prefs.autoListenOnOpen && Prefs.roleChosen && !ScareService.listening &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            ScareService.send(this, ScareService.ACTION_LISTEN_ON)
        }
        checkUpdate(false)
    }

    override fun onPause() {
        ScareService.stateListener = null
        super.onPause()
    }

    // ---------------- 화면 ----------------

    private fun buildUi() {
        val c = this
        val root = Ui.column(c, 16)

        val top = Ui.row(c)
        top.addView(Ui.title(c, "이놈아저씨 👹", 24f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(Ui.smallButton(c, "❓") { showHelp() })
        top.addView(Ui.smallButton(c, "🔊") { startActivity(Intent(c, PreviewActivity::class.java)) }, margin())
        top.addView(Ui.smallButton(c, "⚙") { startActivity(Intent(c, SettingsActivity::class.java)) }, margin())
        root.addView(top)

        status = Ui.text(c, "", 14f).apply { setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 6)) }
        root.addView(status)

        val modes = Ui.card(c)
        listenSwitch = Ui.switch(c, "🎙 음성 인식 모드", false) { on ->
            if (updatingSwitches) return@switch
            if (on) {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    pendingListen = true
                    askPerms.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                } else ScareService.send(c, ScareService.ACTION_LISTEN_ON)
            } else if (ScareService.listening) {
                ScareService.send(c, ScareService.ACTION_LISTEN_OFF)
            }
        }
        testSwitch = Ui.switch(c, "🧪 테스트 모드", Prefs.testMode) { on ->
            if (updatingSwitches) return@switch
            Prefs.testMode = on
            refresh()
        }
        prepareSwitch = Ui.switch(c, "🔌 기기 준비 (스피커·TV)", false) { on ->
            if (updatingSwitches) return@switch
            ScareService.send(c, if (on) ScareService.ACTION_PREPARE_ON else ScareService.ACTION_PREPARE_OFF)
        }
        deviceText = Ui.text(c, "", 13f)
        val devRow = Ui.row(c)
        devRow.addView(deviceText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        devRow.addView(Ui.smallButton(c, "🔍 상태 확인") { ScareService.send(c, ScareService.ACTION_CHECK) })
        modes.addView(listenSwitch)
        modes.addView(prepareSwitch)
        modes.addView(devRow)
        modes.addView(testSwitch)
        root.addView(modes)
        root.addView(Ui.spacer(c, 12))

        // 상황 탭
        tabRow = Ui.row(c)
        root.addView(tabRow)
        root.addView(Ui.spacer(c, 8))
        tabBody = Ui.column(c)
        root.addView(tabBody)

        // 마무리 (항상 보임)
        root.addView(Ui.text(c, "🚪 마무리", 14f).apply { setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 6)) })
        for (cmd in Scenario.commands.filter { it.category == "end" }.reversed()) {
            root.addView(Ui.bigButton(c, cmd.label, Ui.RED_DARK, 60) { play(cmd.id, Who.NONE, Who.NONE) }.also { allButtons += it })
        }

        // 테스트 패널
        testPanel = Ui.card(c)
        testPanel.addView(Ui.text(c, "테스트 모드: \"마이크 테스트\"라고 말하거나 아래 버튼", 14f, Ui.TEXT))
        testPanel.addView(Ui.spacer(c, 8))
        testBtn = Ui.bigButton(c, "", Ui.GREEN, 60) { play(Scenario.TEST, Who.NONE, Who.NONE) }
        testPanel.addView(testBtn)
        testPanel.addView(Ui.stepper(c, "스피커 음량", Ui::castVolumeText, Ui::castMinus, Ui::castPlus))
        testPanel.addView(Ui.stepper(c, "스탠바이미 음량", { "${Prefs.dlnaVolume}" }, Ui::dlnaMinus, Ui::dlnaPlus))
        testPanel.addView(Ui.stepper(c, "스탠바이미 지연", Ui::delayText,
            { Prefs.dlnaDelayMs -= 10 }, { Prefs.dlnaDelayMs += 10 }))
        testPanel.addView(Ui.text(c, "지연: 스탠바이미가 늦게 들리면 −, 빨리 들리면 + (다음 재생부터 적용)", 12f))
        root.addView(Ui.spacer(c, 10))
        root.addView(testPanel)
        root.addView(Ui.spacer(c, 12))

        root.addView(Ui.bigButton(c, "⏹ 정지", 0xFF3A3A46.toInt(), 64) {
            ScareService.send(c, ScareService.ACTION_STOP)
        })
        root.addView(Ui.text(c, "버전 ${versionName()}", 12f).apply {
            gravity = Gravity.CENTER; setPadding(0, Ui.dp(c, 8), 0, 0)
            setOnClickListener { checkUpdate(true) }
        })

        setContentView(ScrollView(c).apply { setBackgroundColor(Ui.BG); addView(root) })
    }

    private fun margin() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { leftMargin = Ui.dp(this@MainActivity, 6) }

    private fun chipRow(label: String, items: List<Who>, selected: Who, onPick: (Who) -> Unit): LinearLayout {
        val box = Ui.column(this)
        box.addView(Ui.text(this, label, 13f))
        val r = Ui.row(this)
        for (w in items) {
            r.addView(Ui.smallButton(this, if (w == Who.NONE) "이름 없이" else Scenario.whoLabel(w),
                if (w == selected) Ui.RED else 0xFF3A3A46.toInt()) { onPick(w); rebuildTab() },
                LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1f).apply { rightMargin = Ui.dp(this@MainActivity, 6) })
        }
        box.addView(r)
        box.addView(Ui.spacer(this, 6))
        return box
    }

    private fun rebuildTab() {
        tabRow.removeAllViews()
        for (t in Scenario.tabs) {
            tabRow.addView(Ui.smallButton(this, t.label, if (t.id == tab) Ui.RED else 0xFF3A3A46.toInt()) {
                tab = t.id; rebuildTab()
            }, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f).apply { rightMargin = Ui.dp(this@MainActivity, 6) })
        }
        tabBody.removeAllViews()
        val cat = Scenario.tabs.firstOrNull { it.id == tab } ?: return
        val cmds = cat.order.mapNotNull { Scenario.byId(it) }
        if (cmds.any { it.names == "pair" }) {
            tabBody.addView(chipRow("누가?", listOf(Who.N1, Who.N2, Who.NONE), actor) { w ->
                actor = w
                if (w == Who.NONE) target = Who.FRIEND
                else if (target == w) target = if (w == Who.N1) Who.N2 else Who.N1
            })
            if (actor != Who.NONE) {
                val other = if (actor == Who.N1) Who.N2 else Who.N1
                tabBody.addView(chipRow("누구를?", listOf(other, Who.FRIEND), target) { w -> target = w })
            }
        } else if (cmds.any { it.names == "single" }) {
            tabBody.addView(chipRow("누구?", listOf(Who.N1, Who.N2, Who.BOTH, Who.NONE), single) { w -> single = w })
        }
        for (cmd in cmds) {
            val (a, t) = when (cmd.names) {
                "single" -> single to Who.NONE
                "pair" -> if (actor == Who.NONE) Who.NONE to Who.FRIEND else actor to target
                else -> Who.NONE to Who.NONE
            }
            val b = Ui.bigButton(this, Scenario.title(cmd, a, t), Ui.RED) { play(cmd.id, a, t) }
            b.isEnabled = !generating
            tabBody.addView(b)
        }
    }

    private fun refresh() {
        updatingSwitches = true
        listenSwitch.isChecked = ScareService.listening
        prepareSwitch.isChecked = ScareService.devicesOn
        testSwitch.isChecked = Prefs.testMode
        updatingSwitches = false
        testPanel.visibility = if (Prefs.testMode) View.VISIBLE else View.GONE
        testBtn.text = "▶ 테스트 재생 (${Prefs.testSound.label})"
        val lines = mutableListOf("사용자: ${Prefs.role.label}  ·  재생: ${Prefs.target.label}")
        if (ScareService.listening) lines += "🎙 듣는 중" + (if (Prefs.testMode) " (테스트 모드)" else "") + " · ${Prefs.autoOffMin}분 동안 명령 없으면 꺼짐"
        if (ScareService.currentTitle.isNotEmpty()) { lines += "▶ ${ScareService.currentTitle}"; requested = "" }
        else if (requested.isNotEmpty()) lines += "⏳ $requested 재생 준비 중…"
        if (generating) lines += "음성 준비 중…"
        status.text = lines.joinToString("\n")
        deviceText.text = when {
            ScareService.preparing -> "기기 확인 중…"
            Player.deviceStatus.isNotEmpty() -> Player.deviceStatus
            else -> "🔍 상태 확인을 누르면 스피커와 스탠바이미 상태를 보여줘요"
        }
        for (b in allButtons) b.isEnabled = !generating
    }

    private fun play(cmd: String, a: Who, t: Who) {
        if (generating) return
        requested = if (cmd == Scenario.TEST) "테스트" else Scenario.byId(cmd)?.let { Scenario.title(it, a, t) } ?: ""
        refresh()
        status.postDelayed({ if (requested.isNotEmpty() && ScareService.currentTitle.isEmpty()) { requested = ""; refresh() } }, 30_000)
        ScareService.send(this, ScareService.ACTION_PLAY, cmd, a, t)
    }

    private fun versionName(): String = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (_: Exception) { "" }

    // ---------------- 도움말 ----------------

    private fun showHelp() {
        val role = Prefs.role
        val sb = StringBuilder()
        sb.append("이놈아저씨에게 이렇게 말하면, 거실 스피커/TV에서 대사가 나와요.\n")
        sb.append("• [이름]: ${Prefs.name1} / ${Prefs.name2} / ${Scenario.whoLabel(Who.BOTH)}\n")
        sb.append("• [호칭]: 앱에서 고른 사용자 (지금: ${role.label})\n")
        sb.append("• 이름을 못 알아들으면 이름 없이, 괴롭힘의 [누구]는 \"친구\"로 나와요.\n")
        val cats = Scenario.tabs.map { it.id } + "end"
        for (cat in cats) {
            sb.append("\n").append(Scenario.categoryLabel(cat)).append("\n")
            for (cmd in Scenario.commands.filter { it.category == cat }) {
                val (a, t) = when (cmd.names) {
                    "single" -> Who.N1 to Who.NONE
                    "pair" -> Who.N1 to Who.N2
                    else -> Who.NONE to Who.NONE
                }
                sb.append("🗣 ").append(cmd.keyword).append("\n")
                sb.append("   → ").append(Scenario.displayText(cmd, role, a, t)).append("\n")
            }
        }
        sb.append("\n🧪 마이크 테스트 (테스트 모드에서만)\n   → 숫자 세기 또는 잔잔한 음악\n")
        sb.append("\n📌 알아두기\n")
        sb.append("• 문장에 \"아저씨\"와 키워드만 들어가면 돼요. 띄어쓰기·앞뒤 말은 상관없어요.\n")
        sb.append("• 괴롭힘은 먼저 말한 이름이 [누가], 뒤 이름이 [누구]예요.\n")
        sb.append("• 소리가 나오는 동안엔 새 명령을 무시해요. (마무리 명령은 언제든 돼요)\n")
        sb.append("• 음성 인식은 ${Prefs.autoOffMin}분 동안 명령이 없으면 자동으로 꺼져요.\n")
        sb.append("• 여러 폰에서 동시에 말해도 한 번만 재생돼요.\n")
        sb.append("• 앱을 열면 음성 인식이 자동으로 켜져요. 빅스비로 \"이놈아저씨 열어줘\"라고 해도 돼요.\n")
        val tv = Ui.text(this, sb.toString(), 14f, Ui.TEXT).apply { setPadding(48, 24, 48, 24); setTextIsSelectable(true) }
        AlertDialog.Builder(this).setTitle("❓ 도움말")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("닫기", null).show()
    }

    // ---------------- 업데이트 ----------------

    private fun checkUpdate(force: Boolean) {
        val code = try {
            val pi = packageManager.getPackageInfo(packageName, 0)
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        } catch (_: Exception) { 0L }
        Thread {
            val r = Updater.check(code, force)
            runOnUiThread {
                if (r != null && !isFinishing) {
                    AlertDialog.Builder(this).setTitle("새 버전이 있어요")
                        .setMessage("이놈아저씨 ${r.name}\n받아서 덮어 설치하면 설정은 그대로 남아요.")
                        .setPositiveButton("다운로드") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.apkUrl))) }
                        .setNegativeButton("나중에", null).show()
                } else if (force) {
                    android.widget.Toast.makeText(this, "최신 버전이에요", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
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
        val ok = Prefs.clipsSignature == Prefs.currentSignature() && Scenario.clipFile(this, "test_music").exists()
        if (ok) return

        generating = true
        refresh(); rebuildTab()
        val msg = Ui.text(this, "음성 준비 중…", 16f, Ui.TEXT).apply { setPadding(64, 64, 64, 64) }
        val dlg = AlertDialog.Builder(this).setTitle("음성 만들기 (1~2분)").setView(msg).setCancelable(false).show()
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
                refresh(); rebuildTab()
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
