package com.scarespeaker

import android.content.Context
import java.io.File

/** 버전 1 시나리오: 잠잘 때 상황 + 상황 종료 + 테스트 */
enum class Cmd(val label: String, val usesName: Boolean, val usesRole: Boolean, val stopsCurrent: Boolean) {
    COME("이놈아저씨 오세요", false, true, false),
    TAKE("이놈아저씨 ○○ 데려가세요", true, false, false),
    NOSLEEP("이놈아저씨 ○○ 안 자요", true, false, false),
    GOAWAY("이놈아저씨 오지 마세요", false, true, true),
    END("이놈아저씨 안녕히 가세요", false, true, true),
    TEST("마이크 테스트", false, false, false),
}

enum class NameOpt { NONE, N1, N2, BOTH }

object Scenario {

    fun nameText(opt: NameOpt): String? = when (opt) {
        NameOpt.NONE -> null
        NameOpt.N1 -> Prefs.name1
        NameOpt.N2 -> Prefs.name2
        NameOpt.BOTH -> "${Prefs.name1}${withRang(Prefs.name1)} ${Prefs.name2}"
    }

    fun nameLabel(opt: NameOpt): String = nameText(opt) ?: "이름 없이"

    /** 받침이 있으면 '이랑', 없으면 '랑' */
    private fun withRang(name: String): String {
        val c = name.lastOrNull() ?: return "랑"
        if (c !in '가'..'힣') return "랑"
        return if ((c - '가') % 28 != 0) "이랑" else "랑"
    }

    /** TTS가 읽을 문장 */
    fun ttsText(cmd: Cmd, role: Role, opt: NameOpt): String {
        val r = role.label
        val n = nameText(opt)
        return when (cmd) {
            Cmd.COME -> "이노오옴! 누가 $r 말씀 안 듣니!"
            Cmd.TAKE -> if (n != null) "이노오옴! $n! 이놈아저씨가 잡아가야겠다!"
            else "이노오옴! 이놈아저씨가 잡아가야겠다!"
            Cmd.NOSLEEP -> if (n != null) "이노오옴! 누가 안 자고 장난치니! $n! 얼른 안 자면 아저씨가 잡아간다!"
            else "이노오옴! 누가 안 자고 장난치니! 얼른 안 자면 아저씨가 잡아간다!"
            Cmd.GOAWAY -> "알았다. 계속 $r 말씀 안 들으면, 아저씨 또 온다!"
            Cmd.END -> "알았다. $r 말씀 안 들으면, 아저씨 또 온다!"
            Cmd.TEST -> ""
        }
    }

    fun clipKey(cmd: Cmd, role: Role, opt: NameOpt): String = when (cmd) {
        Cmd.COME, Cmd.GOAWAY, Cmd.END -> "${cmd.name.lowercase()}_${role.name.lowercase()}"
        Cmd.TAKE, Cmd.NOSLEEP -> "${cmd.name.lowercase()}_${opt.name.lowercase()}"
        Cmd.TEST -> "test_${Prefs.testSound.name.lowercase()}"
    }

    fun clipsDir(c: Context): File = File(c.filesDir, "clips").apply { mkdirs() }

    fun clipFile(c: Context, key: String): File = File(clipsDir(c), "$key.wav")

    // ---------------- 음성 인식 결과 → 명령 ----------------

    data class Match(val cmd: Cmd, val opt: NameOpt)

    private fun norm(s: String): String =
        s.lowercase().filter { it in '가'..'힣' || it.isLetterOrDigit() }

    fun match(text: String, testMode: Boolean): Match? {
        val t = norm(text)
        if (t.isEmpty()) return null
        if (testMode && t.contains("마이크테스트")) return Match(Cmd.TEST, NameOpt.NONE)
        if (!t.contains("아저씨")) return null

        val cmd = when {
            t.contains("안녕히") || t.contains("잘가세요") -> Cmd.END
            t.contains("오지마") || t.contains("오지말") -> Cmd.GOAWAY
            t.contains("데려가") || t.contains("대려가") || t.contains("잡아가") || t.contains("대리고가") || t.contains("데리고가") -> Cmd.TAKE
            t.contains("안자") || t.contains("안잔") || t.contains("안주무") -> Cmd.NOSLEEP
            t.contains("오세요") || t.contains("와주세요") || t.contains("오셔") || t.contains("와요") || t.contains("빨리와") -> Cmd.COME
            else -> null
        } ?: return null

        var opt = NameOpt.NONE
        if (cmd.usesName) {
            val has1 = Prefs.name1.isNotBlank() && t.contains(norm(Prefs.name1))
            val has2 = Prefs.name2.isNotBlank() && t.contains(norm(Prefs.name2))
            opt = when {
                has1 && has2 -> NameOpt.BOTH
                has1 -> NameOpt.N1
                has2 -> NameOpt.N2
                else -> NameOpt.NONE
            }
        }
        return Match(cmd, opt)
    }
}
