package com.scarespeaker

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var castLabel: TextView
    private lateinit var wifiCard: LinearLayout
    private lateinit var btCard: LinearLayout
    private lateinit var btStatus: TextView
    private val askBt = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) pickBt() else toast("블루투스 권한이 필요해요")
    }
    private val cdm = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == RESULT_OK) {
            @Suppress("DEPRECATION")
            val d = r.data?.getParcelableExtra<android.bluetooth.BluetoothDevice>(android.companion.CompanionDeviceManager.EXTRA_DEVICE)
            if (d != null) {
                try { @Suppress("MissingPermission") d.createBond() } catch (_: Exception) {}
                toast("페어링 중이에요. 완료되면 '연결하기'를 눌러 주세요")
            }
        }
    }
    private lateinit var dlnaLabel: TextView
    private val steppers = mutableListOf<LinearLayout>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = this
        val root = Ui.column(c, 16)
        root.addView(Ui.title(c, "설정"))

        // 사용자
        root.addView(Ui.section(c, "사용자"))
        root.addView(Ui.radio(c, Role.values().toList(), { it.label }, Prefs.role, horizontal = false) { Prefs.role = it })
        root.addView(Ui.text(c, "대사 속 호칭이 바뀌어요 (예: \"누가 엄마 말씀 안 듣니\")", 12f))

        // 아이 이름
        root.addView(Ui.section(c, "아이 이름"))
        val n1 = edit(Prefs.name1, "이름 1")
        val n2 = edit(Prefs.name2, "이름 2")
        root.addView(n1); root.addView(n2)
        root.addView(Ui.smallButton(c, "이름 저장 (음성 다시 만들기)") {
            Prefs.name1 = n1.text.toString()
            Prefs.name2 = n2.text.toString()
            Toast.makeText(c, "저장했어요. 메인 화면에서 음성을 다시 만들어요", Toast.LENGTH_SHORT).show()
            finish()
        })

        // 재생 기기
        root.addView(Ui.section(c, "출력 방식 (둘 중 하나만 사용)"))
        wifiCard = Ui.card(c)
        btCard = Ui.card(c)
        wifiCard.setOnClickListener { setOutput(false) }
        btCard.setOnClickListener { setOutput(true) }
        val wTitle = Ui.row(c)
        wTitle.addView(android.widget.ImageView(c).apply { setImageResource(R.drawable.ic_wifi) }, LinearLayout.LayoutParams(Ui.dp(c, 28), Ui.dp(c, 28)))
        wTitle.addView(Ui.text(c, "  Wi-Fi 기기", 16f, Ui.TEXT))
        wifiCard.addView(wTitle)
        wifiCard.addView(Ui.text(c, "같은 Wi-Fi에서만 사용 가능", 12f))
        wifiCard.addView(Ui.radio(c, Target.values().toList(), { it.label }, Prefs.target) { Prefs.target = it; setOutput(false) })

        castLabel = Ui.text(c, "", 14f, Ui.TEXT)
        dlnaLabel = Ui.text(c, "", 14f, Ui.TEXT)
        val castRow = Ui.row(c)
        castRow.addView(castLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        castRow.addView(Ui.smallButton(c, "찾기") { pickCast() })
        castRow.addView(Ui.smallButton(c, "IP") { enterCastIp() })
        val dlnaRow = Ui.row(c)
        dlnaRow.addView(dlnaLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        dlnaRow.addView(Ui.smallButton(c, "찾기") { pickDlna() })
        wifiCard.addView(castRow)
        wifiCard.addView(Ui.spacer(c, 6))
        wifiCard.addView(dlnaRow)
        updateDeviceLabels()
        root.addView(wifiCard)
        root.addView(Ui.spacer(c, 10))

        val bTitle = Ui.row(c)
        bTitle.addView(android.widget.ImageView(c).apply { setImageResource(R.drawable.ic_bluetooth) }, LinearLayout.LayoutParams(Ui.dp(c, 28), Ui.dp(c, 28)))
        bTitle.addView(Ui.text(c, "  블루투스 스피커", 16f, Ui.TEXT))
        btCard.addView(bTitle)
        btStatus = Ui.text(c, "", 13f)
        btCard.addView(btStatus)
        val br = Ui.row(c).apply { setPadding(0, Ui.dp(c, 8), 0, 0) }
        br.addView(Ui.smallButton(c, "연결하기") { pickBt() }, LinearLayout.LayoutParams(0, Ui.dp(c, 46), 1f).apply { rightMargin = Ui.dp(c, 6) })
        br.addView(Ui.smallButton(c, "새 스피커 추가") { addBt() }, LinearLayout.LayoutParams(0, Ui.dp(c, 46), 1f))
        btCard.addView(br)
        btCard.addView(Ui.stepper(c, "블루투스 음량", { "${Prefs.btVolume} / ${Player.btMaxVolume()}" },
            { Prefs.btVolume = Prefs.btVolume - 1 }, { Prefs.btVolume = (Prefs.btVolume + 1).coerceAtMost(Player.btMaxVolume()) }))
        btCard.addView(Ui.text(c, "연결이 안 되어 있으면 폰 스피커로 새지 않게 재생하지 않아요.", 12f))
        root.addView(btCard)
        drawOutput()

        // 음량
        root.addView(Ui.section(c, "재생 음량 (기기 최소 단위)"))
        steppers += Ui.stepper(c, "LG 스피커", Ui::castVolumeText, Ui::castMinus, Ui::castPlus)
        steppers += Ui.stepper(c, "스탠바이미", { "${Prefs.dlnaVolume}" }, Ui::dlnaMinus, Ui::dlnaPlus)
        steppers.forEach { root.addView(it) }
        root.addView(Ui.smallButton(c, "기기의 지금 음량 가져오기") { readVolumes() })
        root.addView(Ui.switch(c, "재생 끝나면 원래 음량으로 되돌리기", Prefs.restoreVolume) { Prefs.restoreVolume = it })

        // 둘 다 재생할 때
        root.addView(Ui.section(c, "둘 다 재생할 때 맞추기"))
        val delay = Ui.stepper(c, "스탠바이미 지연", Ui::delayText, { Prefs.dlnaDelayMs -= 10 }, { Prefs.dlnaDelayMs += 10 })
        root.addView(delay)
        root.addView(Ui.text(c, "스탠바이미가 늦게 들리면 −, 빨리 들리면 +", 12f))

        // 소리
        root.addView(Ui.section(c, "소리"))
        root.addView(Ui.smallButton(c, "🔊 목소리 고르기 · 대사 미리듣기") {
            startActivity(android.content.Intent(c, PreviewActivity::class.java))
        })
        root.addView(Ui.spacer(c, 6))
        root.addView(Ui.switch(c, "효과음 (뚜벅뚜벅 발소리)", Prefs.effects) { Prefs.effects = it })
        root.addView(Ui.text(c, "바꾸면 메인 화면에서 음성을 다시 만들어요", 12f))
        root.addView(Ui.switch(c, "음성 인식 시작음 끄기 (폰 미디어 소리 음소거)", Prefs.muteBeep) { Prefs.muteBeep = it })
        root.addView(Ui.text(c, "유튜브 등 다른 소리가 나오고 있으면 음소거하지 않아요.", 12f))
        root.addView(Ui.switch(c, "기기 안 음성 인식 (유튜브 멈춤 줄이기)", Prefs.onDeviceStt) { Prefs.onDeviceStt = it })
        root.addView(Ui.text(c, "다음에 음성 인식을 켤 때부터 적용돼요. 이 폰에서 지원하지 않으면 자동으로 기본 방식을 써요.", 12f))

        // 음성 인식 켜기
        root.addView(Ui.section(c, "음성 인식 켜기 · 빅스비 · 예약"))
        root.addView(Ui.switch(c, "앱을 열면 음성 인식 자동 켜기", Prefs.autoListenOnOpen) { Prefs.autoListenOnOpen = it })
        root.addView(Ui.text(c, "빅스비: \"하이 빅스비, 이놈아저씨 열어줘\" → 앱이 열리며 음성 인식이 켜져요. 빅스비 빠른 명령어에 \"이놈아저씨 켜줘\" = \"이놈아저씨 열어줘\"로 등록하면 더 짧게 부를 수 있어요.", 12f))
        root.addView(Ui.text(c, "음성 인식은 ${Prefs.autoOffMin}분 동안 명령이 없으면 자동으로 꺼져요.", 12f))
        root.addView(Ui.switch(c, "음성 인식 켤 때 기기 자동 준비", Prefs.autoPrepare) { Prefs.autoPrepare = it })
        root.addView(Ui.text(c, "끄면 메인 화면의 '🔌 기기 준비' 버튼으로만 준비해요. (재생할 때는 항상 필요한 기기를 깨워요)", 12f))
        root.addView(Ui.spacer(c, 8))
        root.addView(Ui.switch(c, "예약 알림 켜기", Prefs.scheduleOn) { Prefs.scheduleOn = it; Schedule.apply(c) })
        val timeBtn = Ui.smallButton(c, "") {}
        fun timeText() = "알림 시각: %02d:%02d".format(Prefs.scheduleMinute / 60, Prefs.scheduleMinute % 60)
        timeBtn.text = timeText()
        timeBtn.setOnClickListener {
            android.app.TimePickerDialog(c, { _, h, m ->
                Prefs.scheduleMinute = h * 60 + m; timeBtn.text = timeText(); Schedule.apply(c)
            }, Prefs.scheduleMinute / 60, Prefs.scheduleMinute % 60, false).show()
        }
        root.addView(timeBtn)
        val days = Ui.row(c).apply { setPadding(0, Ui.dp(c, 6), 0, 0) }
        val dayNames = listOf("월", "화", "수", "목", "금", "토", "일")
        fun drawDays() {
            days.removeAllViews()
            dayNames.forEachIndexed { i, d ->
                val bit = 1 shl i
                val on = Prefs.scheduleDays and bit != 0
                days.addView(Ui.smallButton(c, d, if (on) Ui.RED else 0xFF3A3A46.toInt()) {
                    Prefs.scheduleDays = Prefs.scheduleDays xor bit; drawDays(); Schedule.apply(c)
                }, LinearLayout.LayoutParams(0, Ui.dp(c, 44), 1f).apply { rightMargin = Ui.dp(c, 4) })
            }
        }
        drawDays()
        root.addView(days)
        root.addView(Ui.text(c, "정한 시각에 알림이 와요. 알림의 [켜기]를 누르면 음성 인식이 켜져요. (안드로이드 정책상 앱이 혼자서는 마이크를 켤 수 없어 한 번 탭이 필요해요)", 12f))

        // 여러 폰
        root.addView(Ui.section(c, "여러 폰에서 겹쳐 재생 막기"))
        root.addView(Ui.radio(c, DedupMode.values().toList(), { it.label }, Prefs.dedupMode, horizontal = false) { Prefs.dedupMode = it })
        root.addView(Ui.text(c, "막는 시간은 재생 중인 소리 길이만큼이에요. '오지 마세요/안녕히 가세요'는 막지 않아요.", 12f))

        // 테스트
        root.addView(Ui.section(c, "테스트 소리"))
        root.addView(Ui.radio(c, TestSound.values().toList(), { it.label }, Prefs.testSound) { Prefs.testSound = it })

        // 설정 공유
        root.addView(Ui.section(c, "설정 공유 (가족끼리 같은 설정 쓰기)"))
        root.addView(Ui.smallButton(c, "📤 내 설정 공유하기") { shareSettings() })
        root.addView(Ui.spacer(c, 6))
        root.addView(Ui.smallButton(c, "📥 설정 코드 붙여넣기") { pasteSettings() })
        root.addView(Ui.spacer(c, 6))
        root.addView(Ui.smallButton(c, "⭐ 기본 설정으로 되돌리기") { resetRecommended() })
        root.addView(Ui.text(c, "사용자(아빠/엄마/할머니)는 공유되지 않아요. 각자 고르세요.", 12f))

        // 대사 켜기/끄기
        root.addView(Ui.section(c, "대사 켜기/끄기"))
        root.addView(Ui.text(c, "꺼진 대사는 버튼을 눌러도, 말해도 재생되지 않아요.", 12f))
        for (cat in Scenario.tabs.map { it.id } + "end") {
            root.addView(Ui.text(c, Scenario.categoryLabel(cat), 14f, Ui.TEXT).apply { setPadding(0, Ui.dp(c, 10), 0, 0) })
            for (cmd in Scenario.commands.filter { it.category == cat }) {
                root.addView(Ui.switch(c, cmd.label.replace("[이름]", "○○").replace("[누가]가 [누구]", "○○가 ○○"), Prefs.isEnabled(cmd.id)) { on -> Prefs.setEnabled(cmd.id, on) }
                    .apply { textSize = 14f })
            }
        }

        // 기타
        root.addView(Ui.section(c, "기타"))
        root.addView(Ui.smallButton(c, "📝 업데이트 내역") { showChangelog() })
        root.addView(Ui.spacer(c, 8))
        root.addView(Ui.smallButton(c, "진단 기록 보기") { showLog() })
        root.addView(Ui.spacer(c, 8))
        root.addView(Ui.smallButton(c, "음성 다시 만들기") {
            Prefs.clipsSignature = ""
            Toast.makeText(c, "메인 화면에서 음성을 다시 만들어요", Toast.LENGTH_SHORT).show()
            finish()
        })
        root.addView(Ui.text(c, "\n버전 ${packageManager.getPackageInfo(packageName, 0).versionName}", 12f))

        setContentView(ScrollView(c).apply { setBackgroundColor(Ui.BG); addView(root) })
    }

    private fun edit(value: String, hint: String) = EditText(this).apply {
        setText(value); this.hint = hint; inputType = InputType.TYPE_CLASS_TEXT
        setTextColor(Ui.TEXT); setHintTextColor(Ui.SUB)
    }

    private fun updateDeviceLabels() {
        castLabel.text = "스피커: " + Prefs.castName.ifEmpty { "자동 (S95QR 찾기)" }
        dlnaLabel.text = "스탠바이미: " + Prefs.dlnaName.ifEmpty { "자동 (LG TV 찾기)" }
    }

    private fun refreshSteppers() = steppers.forEach { (it.tag as? Runnable)?.run() }

    private fun busy(msg: String): AlertDialog =
        AlertDialog.Builder(this).setMessage(msg).setCancelable(false).show()

    private fun pickCast() {
        val dlg = busy("스피커 찾는 중… (몇 초 걸려요)")
        Thread {
            val list = try { Discovery.findCast(applicationContext) } catch (e: Exception) { emptyList() }
            runOnUiThread {
                dlg.dismiss()
                if (list.isEmpty()) { toast("찾지 못했어요. 같은 Wi-Fi인지 확인해 주세요"); return@runOnUiThread }
                AlertDialog.Builder(this).setTitle("스피커 고르기")
                    .setItems(list.map { "${it.name} (${it.host})" }.toTypedArray()) { _, i ->
                        val d = list[i]
                        Prefs.castName = d.name; Prefs.castHost = d.host; Prefs.castPort = d.port
                        updateDeviceLabels()
                    }.show()
            }
        }.start()
    }

    private fun enterCastIp() {
        val e = edit(Prefs.castHost, "예: 192.168.1.50").apply { inputType = InputType.TYPE_CLASS_PHONE }
        AlertDialog.Builder(this).setTitle("스피커 IP 직접 입력")
            .setMessage("Google Home 앱 > 스피커 > 설정 > 기기 정보에서 IP 주소를 확인할 수 있어요.")
            .setView(e)
            .setPositiveButton("연결 확인") { _, _ ->
                val ip = e.text.toString().trim()
                if (ip.isEmpty()) return@setPositiveButton
                if (Player.isTvHost(ip)) { toast("$ip 는 스탠바이미 주소예요. 사운드바 IP를 넣어 주세요"); return@setPositiveButton }
                val dlg = busy("연결 확인 중…")
                Thread {
                    val ok = try { CastClient.connect(ip, 8009).also { it.getVolume(); it.close() }; true }
                    catch (ex: Exception) { AppLog.d("IP 연결 실패 $ip: ${ex.message}"); false }
                    runOnUiThread {
                        dlg.dismiss()
                        if (ok) {
                            Prefs.castHost = ip; Prefs.castPort = 8009; Prefs.castName = "직접 입력 $ip"
                            updateDeviceLabels(); toast("연결돼요. 저장했어요")
                        } else toast("연결하지 못했어요. IP와 스피커 전원을 확인해 주세요")
                    }
                }.start()
            }
            .setNegativeButton("취소", null).show()
    }

    private fun pickDlna() {
        val dlg = busy("스탠바이미 찾는 중… (TV가 켜져 있어야 해요)")
        Thread {
            val list = try { Discovery.findDlna(applicationContext) } catch (e: Exception) { emptyList() }
            runOnUiThread {
                dlg.dismiss()
                if (list.isEmpty()) { toast("찾지 못했어요. TV를 켜고 같은 Wi-Fi인지 확인해 주세요"); return@runOnUiThread }
                AlertDialog.Builder(this).setTitle("TV 고르기")
                    .setItems(list.map { it.name }.toTypedArray()) { _, i ->
                        Prefs.dlnaName = list[i].name; Prefs.dlnaLocation = list[i].location
                        updateDeviceLabels()
                    }.show()
            }
        }.start()
    }

    private fun readVolumes() {
        val dlg = busy("기기 음량 읽는 중…")
        Thread {
            val (cv, dv) = Player.readDeviceVolumes(applicationContext)
            runOnUiThread {
                dlg.dismiss()
                cv?.let { Prefs.castVolume = it.level }
                dv?.let { Prefs.dlnaVolume = it }
                refreshSteppers()
                updateDeviceLabels()
                toast("스피커: ${if (cv != null) "가져옴" else "실패"}, 스탠바이미: ${if (dv != null) "가져옴" else "실패"}")
            }
        }.start()
    }

    private fun showLog() {
        val log = AppLog.dump().ifEmpty { "(기록 없음)" }
        val tv = Ui.text(this, log, 11f, Ui.TEXT).apply { setTextIsSelectable(true); setPadding(24, 24, 24, 24) }
        AlertDialog.Builder(this).setTitle("진단 기록")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("복사") { _, _ ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("log", log))
                toast("복사했어요")
            }
            .setNegativeButton("닫기", null).show()
    }

    private fun shareSettings() {
        val code = Prefs.exportCode()
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("settings", code))
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, "이놈아저씨 앱 설정 코드예요. 앱 설정 > 설정 코드 붙여넣기에 넣어 주세요.\n\n$code")
        }
        toast("설정 코드를 복사했어요")
        startActivity(android.content.Intent.createChooser(send, "설정 보내기"))
    }

    private fun pasteSettings() {
        val clip = try {
            getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        } catch (_: Exception) { null } ?: ""
        val e = edit(if (clip.contains("이놈아저씨설정:")) clip.substring(clip.indexOf("이놈아저씨설정:")) else "", "설정 코드")
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        AlertDialog.Builder(this).setTitle("설정 코드 붙여넣기")
            .setMessage("가족에게 받은 설정 코드를 넣어 주세요.")
            .setView(e)
            .setPositiveButton("적용") { _, _ ->
                if (Prefs.importCode(e.text.toString())) {
                    Schedule.apply(this)
                    toast("적용했어요. 메인 화면에서 필요하면 음성을 다시 만들어요")
                    recreate()
                } else toast("코드를 읽지 못했어요")
            }
            .setNegativeButton("취소", null).show()
    }

    private fun resetRecommended() {
        AlertDialog.Builder(this).setTitle("기본 설정으로 되돌릴까요?")
            .setMessage("음량·기기·목소리·대사 켜기/끄기 등이 기본(추천) 설정으로 돌아가요. (사용자는 그대로)")
            .setPositiveButton("맞추기") { _, _ ->
                Prefs.resetToRecommended()
                Schedule.apply(this)
                toast("기본 설정으로 되돌렸어요")
                recreate()
            }
            .setNegativeButton("취소", null).show()
    }

    override fun onResume() {
        super.onResume()
        if (::btStatus.isInitialized) drawOutput()
    }

    // ---------------- 출력 방식 ----------------

    private fun setOutput(bt: Boolean) {
        Prefs.outputBt = bt
        drawOutput()
    }

    private fun drawOutput() {
        val bt = Prefs.outputBt
        wifiCard.background = Ui.rounded(if (!bt) 0xFF23324A.toInt() else Ui.CARD, 16, this)
        btCard.background = Ui.rounded(if (bt) 0xFF23324A.toInt() else Ui.CARD, 16, this)
        wifiCard.alpha = if (bt) 0.5f else 1f
        btCard.alpha = if (bt) 1f else 0.5f
        val n = Bt.connectedSpeaker(this)
        btStatus.text = (if (bt) "✓ 사용 중 · " else "눌러서 사용 · ") + (if (n != null) "$n 연결됨" else "연결 안 됨")
    }

    private fun pickBt() {
        if (!Bt.hasPermission(this)) { askBt.launch(android.Manifest.permission.BLUETOOTH_CONNECT); return }
        if (!Bt.isOn(this)) { toast("블루투스를 켜 주세요"); Bt.openSettings(this); return }
        val list = Bt.pairedAudio(this)
        if (list.isEmpty()) { toast("페어링된 스피커가 없어요. '새 스피커 추가'를 눌러 주세요"); return }
        val now = Bt.connectedSpeaker(this)
        AlertDialog.Builder(this).setTitle("블루투스 스피커 연결")
            .setItems(list.map { val nm = Bt.nameOf(it); if (nm == now) "$nm (연결됨)" else nm }.toTypedArray()) { _, i ->
                val d = list[i]
                Prefs.btName = Bt.nameOf(d)
                toast("${Bt.nameOf(d)} 연결 중…")
                Bt.connect(this, d) { ok ->
                    runOnUiThread {
                        if (ok) {
                            setOutput(true)
                            btStatus.postDelayed({ drawOutput() }, 3000)
                        } else {
                            AlertDialog.Builder(this).setTitle("폰 설정에서 연결해 주세요")
                                .setMessage("이 폰은 앱에서 바로 연결하는 걸 막아 두었어요. 블루투스 설정에서 '${Bt.nameOf(d)}'를 눌러 연결한 뒤 돌아와 주세요.")
                                .setPositiveButton("블루투스 설정 열기") { _, _ -> setOutput(true); Bt.openSettings(this) }
                                .setNegativeButton("취소", null).show()
                        }
                    }
                }
            }.show()
    }

    /** 새 스피커 찾기 + 페어링 (안드로이드 기기 선택 창) */
    private fun addBt() {
        try {
            val mgr = getSystemService(android.companion.CompanionDeviceManager::class.java)
            val req = android.companion.AssociationRequest.Builder()
                .addDeviceFilter(android.companion.BluetoothDeviceFilter.Builder().build())
                .setSingleDevice(false).build()
            @Suppress("DEPRECATION")
            mgr.associate(req, object : android.companion.CompanionDeviceManager.Callback() {
                override fun onDeviceFound(chooserLauncher: android.content.IntentSender) {
                    cdm.launch(androidx.activity.result.IntentSenderRequest.Builder(chooserLauncher).build())
                }
                override fun onFailure(error: CharSequence?) { runOnUiThread { toast("기기를 찾지 못했어요. 스피커를 페어링 모드로 켜 주세요") } }
            }, null)
        } catch (e: Exception) {
            AppLog.d("새 스피커 추가 실패: ${e.message}")
            Bt.openSettings(this)
        }
    }

    private fun showChangelog() {
        val text = Changelog.all(this).joinToString("\n\n") { e -> "■ ${e.name}  (${e.date})\n" + e.items.joinToString("\n") { "• $it" } }
        val tv = Ui.text(this, text, 14f, Ui.TEXT).apply { setPadding(48, 24, 48, 24) }
        AlertDialog.Builder(this).setTitle("📝 업데이트 내역")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("닫기", null).show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
