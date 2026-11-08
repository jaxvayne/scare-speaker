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
    private lateinit var dlnaLabel: TextView
    private val steppers = mutableListOf<LinearLayout>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = this
        val root = Ui.column(c, 16)
        root.addView(Ui.title(c, "설정"))

        // 사용자
        root.addView(Ui.section(c, "사용자"))
        root.addView(Ui.radio(c, Role.values().toList(), { it.label }, Prefs.role) { Prefs.role = it })
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
        root.addView(Ui.section(c, "재생할 기기"))
        root.addView(Ui.radio(c, Target.values().toList(), { it.label }, Prefs.target) { Prefs.target = it })

        castLabel = Ui.text(c, "", 14f, Ui.TEXT)
        dlnaLabel = Ui.text(c, "", 14f, Ui.TEXT)
        val castRow = Ui.row(c)
        castRow.addView(castLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        castRow.addView(Ui.smallButton(c, "찾기") { pickCast() })
        castRow.addView(Ui.smallButton(c, "IP") { enterCastIp() })
        val dlnaRow = Ui.row(c)
        dlnaRow.addView(dlnaLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        dlnaRow.addView(Ui.smallButton(c, "찾기") { pickDlna() })
        root.addView(castRow)
        root.addView(Ui.spacer(c, 6))
        root.addView(dlnaRow)
        updateDeviceLabels()

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
        root.addView(Ui.switch(c, "효과음 (뚜벅뚜벅 발소리)", Prefs.effects) { Prefs.effects = it })
        root.addView(Ui.text(c, "바꾸면 메인 화면에서 음성을 다시 만들어요", 12f))
        root.addView(Ui.switch(c, "음성 인식 시작음 끄기 (폰 미디어 소리 음소거)", Prefs.muteBeep) { Prefs.muteBeep = it })

        // 여러 폰
        root.addView(Ui.section(c, "여러 폰에서 겹쳐 재생 막기"))
        root.addView(Ui.radio(c, DedupMode.values().toList(), { it.label }, Prefs.dedupMode, horizontal = false) { Prefs.dedupMode = it })
        root.addView(Ui.text(c, "막는 시간은 재생 중인 소리 길이만큼이에요. '오지 마세요/안녕히 가세요'는 막지 않아요.", 12f))

        // 테스트
        root.addView(Ui.section(c, "테스트 소리"))
        root.addView(Ui.radio(c, TestSound.values().toList(), { it.label }, Prefs.testSound) { Prefs.testSound = it })

        // 기타
        root.addView(Ui.section(c, "기타"))
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

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
