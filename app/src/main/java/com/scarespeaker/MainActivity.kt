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
    private lateinit var endBox: LinearLayout
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
        showWhatsNew()
    }

    private fun currentCode(): Int = try {
        val pi = packageManager.getPackageInfo(packageName, 0)
        @Suppress("DEPRECATION")
        (if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else pi.versionCode)
    } catch (_: Exception) { 0 }

    /** 업데이트 후 처음 열면 바뀐 점 안내 */
    private fun showWhatsNew() {
        val code = currentCode()
        if (Prefs.lastSeenVersion >= code) return
        if (Prefs.lastSeenVersion == 0 || Prefs.hideWhatsNew) { Prefs.lastSeenVersion = code; return }
        val news = Changelog.all(this).filter { it.version in (Prefs.lastSeenVersion + 1)..code }
        Prefs.lastSeenVersion = code
        if (news.isEmpty()) return
        val text = news.joinToString("\n\n") { e -> "■ ${e.name}\n" + e.items.joinToString("\n") { "• $it" } }
        AlertDialog.Builder(this).setTitle("새로워진 점")
            .setMessage(text)
            .setPositiveButton("확인", null)
            .setNegativeButton("다시 보지 않기") { _, _ -> Prefs.hideWhatsNew = true }
            .show()
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
        listenSwitch = Ui.switch(c, "음성 인식 모드", false) { on ->
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
        listenSwitch.compoundDrawablePadding = Ui.dp(c, 10)
        prepareSwitch = Ui.switch(c, "🔌 기기 준비", false) { on ->
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
        tabRow = Ui.column(c)
        root.addView(tabRow)
        root.addView(Ui.spacer(c, 8))
        tabBody = Ui.column(c)
        root.addView(tabBody)

        // 마무리 (항상 보임)
        root.addView(Ui.text(c, "🚪 마무리", 14f).apply { setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 6)) })
        endBox = Ui.column(c)
        root.addView(endBox)

        // 테스트 패널
        testPanel = Ui.card(c)
        testPanel.addView(Ui.text(c, "테스트 모드: \"마이크 테스트\"라고 말하거나 아래 버튼", 14f, Ui.TEXT))
        testPanel.addView(Ui.spacer(c, 8))
        testBtn = Ui.bigButton(c, "", Ui.GREEN, 60) { play(Scenario.TEST, Who.NONE, Who.NONE) }
        testPanel.addView(testBtn)
        testPanel.addView(Ui.stepper(c, "스피커 음량", Ui::castVolumeText, Ui::castMinus, Ui::castPlus))
        testPanel.addView(Ui.stepper(c, "스탠바이미 음량", { "${Prefs.dlnaVolume}" }, Ui::dlnaMinus, Ui::dlnaPlus))
        testPanel.addView(Ui.stepper(c, "블루투스 음량", { "${Prefs.btVolume} / ${Player.btMaxVolume()}" },
            { Prefs.btVolume = Prefs.btVolume - 1; Player.applyLiveBtVolume() },
            { Prefs.btVolume = (Prefs.btVolume + 1).coerceAtMost(Player.btMaxVolume()); Player.applyLiveBtVolume() }))
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

    /** 대사 버튼 + 오른쪽에 분리된 켜짐/꺼짐 버튼 */
    private fun lineRow(cmd: Command, title: String, color: Int, onPlay: () -> Unit): LinearLayout {
        val on = Prefs.isEnabled(cmd.id)
        val r = Ui.row(this).apply { setPadding(0, 0, 0, Ui.dp(this@MainActivity, 10)) }
        val b = Ui.bigButton(this, title, if (on) color else 0xFF2E2E36.toInt()) {
            if (!Prefs.isEnabled(cmd.id)) android.widget.Toast.makeText(this, "꺼진 대사예요. 오른쪽 버튼으로 켜 주세요", android.widget.Toast.LENGTH_SHORT).show()
            else onPlay()
        }
        if (!on) b.setTextColor(0xFF77778A.toInt())
        b.isEnabled = !generating
        r.addView(b, LinearLayout.LayoutParams(0, Ui.dp(this, 68), 1f))
        val tg = Ui.smallButton(this, if (on) "켜짐" else "꺼짐", if (on) Ui.GREEN else 0xFF55555F.toInt()) {
            Prefs.setEnabled(cmd.id, !Prefs.isEnabled(cmd.id)); rebuildTab()
        }
        tg.textSize = 13f
        r.addView(tg, LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 44)).apply { leftMargin = Ui.dp(this@MainActivity, 10) })
        return r
    }

    private fun rebuildTab() {
        tabRow.removeAllViews()
        // 탭이 많으면 한 줄에 3개씩
        for (chunk in Scenario.tabs.chunked(3)) {
            val r = Ui.row(this)
            for (t in chunk) {
                r.addView(Ui.smallButton(this, t.label, if (t.id == tab) Ui.RED else 0xFF3A3A46.toInt()) {
                    tab = t.id; rebuildTab()
                }, LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1f).apply {
                    rightMargin = Ui.dp(this@MainActivity, 6); bottomMargin = Ui.dp(this@MainActivity, 6)
                })
            }
            repeat(3 - chunk.size) { r.addView(android.view.View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            tabRow.addView(r)
        }
        endBox.removeAllViews()
        for (cmd in Scenario.commands.filter { it.category == "end" }.reversed()) {
            endBox.addView(lineRow(cmd, cmd.label, Ui.RED_DARK) { play(cmd.id, Who.NONE, Who.NONE) })
        }
        tabBody.removeAllViews()
        val cat = Scenario.tabs.firstOrNull { it.id == tab } ?: return
        val cmds = cat.order.mapNotNull { Scenario.byId(it) }
        if (cmds.any { it.names == "pair" }) {
            tabBody.addView(chipRow("누가?", listOf(Who.N1, Who.N2, Who.NONE), actor) { w ->
                actor = w
                if (target == w || (w == Who.NONE && target != Who.FRIEND && target != Who.OTHER)) target = Who.FRIEND
            })
            val choices = when (actor) {
                Who.N1 -> listOf(Who.N2, Who.FRIEND, Who.OTHER)
                Who.N2 -> listOf(Who.N1, Who.FRIEND, Who.OTHER)
                else -> listOf(Who.FRIEND, Who.OTHER)
            }
            if (target !in choices) target = choices.first()
            tabBody.addView(chipRow("누구를?", choices, target) { w -> target = w })
        } else if (cmds.any { it.names == "single" }) {
            tabBody.addView(chipRow("누구?", listOf(Who.N1, Who.N2, Who.BOTH, Who.NONE), single) { w -> single = w })
        }
        for (cmd in cmds) {
            val (a, t) = when (cmd.names) {
                "single" -> single to Who.NONE
                "pair" -> actor to target
                else -> Who.NONE to Who.NONE
            }
            tabBody.addView(lineRow(cmd, Scenario.title(cmd, a, t), Ui.RED) { play(cmd.id, a, t) })
        }
    }

    private fun refresh() {
        updatingSwitches = true
        listenSwitch.isChecked = ScareService.listening
        listenSwitch.setCompoundDrawablesWithIntrinsicBounds(
            if (ScareService.listening) R.drawable.ic_mic_on else R.drawable.ic_mic_off, 0, 0, 0)
        prepareSwitch.isChecked = ScareService.devicesOn
        testSwitch.isChecked = Prefs.testMode
        updatingSwitches = false
        testPanel.visibility = if (Prefs.testMode) View.VISIBLE else View.GONE
        testBtn.text = "▶ 테스트 재생 (${Prefs.testSound.label})"
        val out = if (Prefs.outputBt) "블루투스 스피커" else "Wi-Fi · ${Prefs.target.label}"
        val lines = mutableListOf("사용자: ${Prefs.role.label}  ·  출력: $out")
        if (ScareService.listening) lines += "● 듣는 중" + (if (Prefs.testMode) " (테스트 모드)" else "") + " · ${Prefs.autoOffMin}분 동안 명령 없으면 꺼짐"
        if (ScareService.currentTitle.isNotEmpty()) { lines += "▶ ${ScareService.currentTitle}"; requested = "" }
        else if (requested.isNotEmpty()) lines += "⏳ $requested 재생 준비 중…"
        if (generating) lines += "음성 준비 중…"
        status.text = lines.joinToString("\n")
        if (ScareService.lastError.isNotEmpty()) {
            val msg = ScareService.lastError
            ScareService.lastError = ""
            requested = ""
            if (!isFinishing) AlertDialog.Builder(this).setTitle("재생하지 않았어요").setMessage(msg)
                .setPositiveButton("확인", null)
                .apply { if (msg.contains("블루투스")) setNeutralButton("블루투스 설정") { _, _ -> startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) } }
                .show()
        }
        deviceText.text = when {
            ScareService.preparing -> "기기 확인 중…"
            Player.deviceStatus.isNotEmpty() -> Player.deviceStatus
            else -> "🔍 상태 확인을 누르면 스피커와 스탠바이미 상태를 보여줘요"
        }

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
        val c = this
        val role = Prefs.role
        val blue = 0xFF64B5F6.toInt()
        val box = Ui.column(c, 4)

        fun card(title: String, color: Int = 0xFFE8665C.toInt()): LinearLayout {
            val k = Ui.card(c)
            k.addView(Ui.text(c, title, 16f, color).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
            box.addView(k, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = Ui.dp(c, 10) })
            return k
        }
        fun item(k: LinearLayout, keyword: String, line: String) {
            k.addView(Ui.text(c, "🗣 $keyword", 15f, blue).apply {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, Ui.dp(c, 10), 0, Ui.dp(c, 2))
            })
            k.addView(Ui.text(c, "→ $line", 14f, 0xFFDDDDE6.toInt()))
        }

        val intro = card("이렇게 말해요", Ui.TEXT)
        intro.addView(Ui.text(c, "\"이놈아저씨\" + 아래 파란 글씨처럼 말하면 거실 스피커/TV에서 대사가 나와요.", 14f, 0xFFDDDDE6.toInt()))
        intro.addView(Ui.text(c, "• [이름]: ${Prefs.name1} / ${Prefs.name2} / ${Scenario.whoLabel(Who.BOTH)}\n• [호칭]: 지금 사용자 → \"${role.call}\"\n• 이름을 못 알아들으면 이름 없이 나와요.", 13f).apply {
            setPadding(0, Ui.dp(c, 6), 0, 0)
        })

        val cats = Scenario.tabs.map { it.id } + "end"
        for (cat in cats) {
            val k = card(Scenario.categoryLabel(cat))
            for (cmd in Scenario.commands.filter { it.category == cat }) {
                val (a, t) = when (cmd.names) {
                    "single" -> Who.N1 to Who.NONE
                    "pair" -> Who.N1 to Who.N2
                    else -> Who.NONE to Who.NONE
                }
                item(k, cmd.keyword, Scenario.displayText(cmd, role, a, t))
            }
            if (cat == "bully") k.addView(Ui.text(c, "먼저 말한 이름이 [누가], 뒤 이름이 [누구]. [누구]를 못 알아들으면 \"친구\", \"다른 사람\"이라고 하면 \"다른 사람\"으로 나와요.", 12f).apply {
                setPadding(0, Ui.dp(c, 8), 0, 0)
            })
        }
        val t = card("🧪 테스트")
        item(t, "마이크 테스트 (테스트 모드에서만)", "숫자 세기 또는 잔잔한 음악")

        val tips = card("📌 알아두기", Ui.TEXT)
        tips.addView(Ui.text(c, listOf(
            "문장에 \"아저씨\"와 키워드만 들어가면 돼요. 띄어쓰기·앞뒤 말은 상관없어요.",
            "소리가 나오는 동안엔 새 명령을 무시해요. (마무리 명령은 언제든)",
            "음성 인식은 ${Prefs.autoOffMin}분 동안 명령이 없으면 자동으로 꺼져요.",
            "여러 폰에서 동시에 말해도 한 번만 재생돼요.",
            "앱을 열면 음성 인식이 켜져요. 빅스비로 \"이놈아저씨 열어줘\"도 돼요.",
            "집 Wi-Fi가 아니면 재생하지 않아요.",
        ).joinToString("\n") { "• $it" }, 13f, 0xFFDDDDE6.toInt()).apply { setPadding(0, Ui.dp(c, 6), 0, 0) })

        AlertDialog.Builder(c).setTitle("❓ 도움말")
            .setView(ScrollView(c).apply { setBackgroundColor(Ui.BG); setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), 0); addView(box) })
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

    private fun openGoogleTts() {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$GOOGLE_TTS"))) }
        catch (_: Exception) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$GOOGLE_TTS"))) }
    }

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
            } catch (e: VoiceMissing) {
                runOnUiThread {
                    generating = false
                    try { dlg.dismiss() } catch (_: Exception) {}
                    refresh(); rebuildTab()
                    AlertDialog.Builder(this).setTitle("구글 음성 엔진이 필요해요")
                        .setMessage("${e.message}\n\n가족 모두 같은 목소리로 들으려면 'Google 음성 인식 및 합성'을 설치하거나 업데이트해 주세요. 설치 후 앱으로 돌아오면 다시 만들어요.")
                        .setPositiveButton("설치하러 가기") { _, _ -> openGoogleTts() }
                        .setNegativeButton("이 폰 기본 목소리로 만들기") { _, _ -> Prefs.ttsVoice = ""; ensureClips() }
                        .show()
                }
                return@Thread
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
