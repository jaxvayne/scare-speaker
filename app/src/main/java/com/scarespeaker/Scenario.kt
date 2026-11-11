package com.scarespeaker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 누가/누구 (이름 선택) */
enum class Who { NONE, N1, N2, BOTH, FRIEND, OTHER }

/** assets/scenario.json 의 명령 하나 */
data class Command(
    val id: String,
    val category: String,
    val names: String,          // none | single | pair
    val role: Boolean,          // 대사에 호칭이 들어가는지
    val stops: Boolean,         // 지금 소리를 멈추고 재생 (마무리)
    val keyword: String,
    val match: List<String>,
    val display: String,
    val call: String,           // 길게 끄는 첫 마디 ("이노오오옴")
    val tts: List<String>,
    val ttsNoName: List<String>?,
    val steps: String?,         // approach | recede
) {
    val label: String get() = keyword.removePrefix("이놈아저씨 ")
}

data class Category(val id: String, val label: String, val order: List<String>)

object Scenario {
    const val TEST = "TEST"

    private var loaded: Pair<List<Command>, List<Category>>? = null

    @Synchronized
    private fun load(): Pair<List<Command>, List<Category>> {
        loaded?.let { return it }
        val text = App.instance.assets.open("scenario.json").bufferedReader().readText()
        val root = JSONObject(text)
        fun strs(a: JSONArray?): List<String> = if (a == null) emptyList() else List(a.length()) { a.getString(it) }
        val cmds = mutableListOf<Command>()
        val arr = root.getJSONArray("commands")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            cmds += Command(
                id = o.getString("id"),
                category = o.getString("category"),
                names = o.optString("names", "none"),
                role = o.optBoolean("role", false),
                stops = o.optBoolean("stops", false),
                keyword = o.getString("keyword"),
                match = strs(o.optJSONArray("match")),
                display = o.getString("display"),
                call = o.optString("call", ""),
                tts = strs(o.optJSONArray("tts")),
                ttsNoName = o.optJSONArray("tts_noname")?.let { strs(it) },
                steps = o.optString("steps", "").ifEmpty { null },
            )
        }
        val labels = HashMap<String, String>()
        root.getJSONArray("categories").let { a ->
            for (i in 0 until a.length()) labels[a.getJSONObject(i).getString("id")] = a.getJSONObject(i).getString("label")
        }
        val cats = mutableListOf<Category>()
        root.getJSONArray("tabs").let { a ->
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val id = o.getString("category")
                cats += Category(id, labels[id] ?: id, strs(o.getJSONArray("order")))
            }
        }
        return (cmds.toList() to cats.toList()).also { loaded = it }
    }

    val commands: List<Command> get() = load().first
    val tabs: List<Category> get() = load().second
    val version: Int get() = 5

    fun byId(id: String): Command? = commands.firstOrNull { it.id == id }
    fun categoryLabel(id: String): String = when (id) {
        "end" -> "🚪 마무리"
        else -> tabs.firstOrNull { it.id == id }?.label ?: id
    }

    // ---------------- 이름 ----------------

    fun nameOf(w: Who): String? = when (w) {
        Who.N1 -> Prefs.name1.ifBlank { null }
        Who.N2 -> Prefs.name2.ifBlank { null }
        Who.BOTH -> if (Prefs.name1.isNotBlank() && Prefs.name2.isNotBlank())
            "${Prefs.name1}${josa(Prefs.name1, "이랑", "랑")} ${Prefs.name2}" else null
        Who.FRIEND -> "친구"
        Who.OTHER -> "다른 사람"
        Who.NONE -> null
    }

    fun whoLabel(w: Who): String = nameOf(w) ?: "이름 없이"

    /** 받침 있으면 a, 없으면 b */
    fun josa(name: String, a: String, b: String): String {
        val c = name.lastOrNull() ?: return b
        if (c !in '가'..'힣') return b
        return if ((c - '가') % 28 != 0) a else b
    }

    /** 목적격: 연우를 / 민준이를 / 친구를 / 다른 사람을 */
    private fun obj(w: Who, name: String): String = when (w) {
        Who.FRIEND, Who.OTHER -> name + josa(name, "을", "를")
        else -> if (josa(name, "a", "b") == "a") "${name}이를" else "${name}를"
    }

    /** 명령마다 만들어 둘 이름 조합 */
    fun variants(c: Command): List<Pair<Who, Who>> = when (c.names) {
        "single" -> listOf(Who.NONE, Who.N1, Who.N2, Who.BOTH).map { it to Who.NONE }
        "pair" -> listOf(Who.N1 to Who.N2, Who.N2 to Who.N1, Who.N1 to Who.FRIEND, Who.N2 to Who.FRIEND, Who.NONE to Who.FRIEND,
            Who.N1 to Who.OTHER, Who.N2 to Who.OTHER, Who.NONE to Who.OTHER)
        else -> listOf(Who.NONE to Who.NONE)
    }

    fun roles(c: Command): List<Role> = if (c.role) Role.values().toList() else listOf(Role.DAD)

    fun clipKey(c: Command, role: Role, actor: Who, target: Who): String {
        val r = if (c.role) role.name else "X"
        return "${c.id}_${r}_${actor}_${target}".lowercase()
    }

    fun testKey(): String = "test_${Prefs.testSound.name.lowercase()}"

    private fun fill(s: String, c: Command, role: Role, actor: Who, target: Who): String {
        val name = nameOf(actor) ?: ""
        val tw = if (target == Who.NONE) Who.FRIEND else target
        val tgt = nameOf(tw) ?: "친구"
        return s.replace("{role}", role.call)
            .replace("{name}", name)
            .replace("{actor}", name)
            .replace("{target_obj}", obj(tw, tgt))
            .replace("{target}", tgt)
    }

    /** TTS로 읽을 문장들 (첫 마디는 따로) */
    fun ttsParts(c: Command, role: Role, actor: Who, target: Who): List<String> {
        val named = nameOf(actor) != null
        val src = when {
            c.names == "pair" && !named -> c.ttsNoName ?: c.tts
            c.names == "single" && !named -> c.tts.filterNot { it.contains("{name}") }
            else -> c.tts
        }
        return src.map { fill(it, c, role, actor, target) }.filter { it.isNotBlank() }
    }

    /** 화면/도움말에 보여 줄 대사 */
    fun displayText(c: Command, role: Role, actor: Who, target: Who): String {
        val named = nameOf(actor) != null
        if (!named && c.names == "pair") {
            return "이노옴~!! " + (c.ttsNoName ?: c.tts).joinToString(" ") { fill(it, c, role, actor, target) }
        }
        var s = c.display
        if (!named && c.names == "single") s = s.replace("{name}! ", "").replace(" {name}!", "")
        return fill(s, c, role, actor, target)
    }

    fun title(c: Command, actor: Who, target: Who): String {
        val a = nameOf(actor)
        return when (c.names) {
            "single" -> c.label.replace("[이름]", a ?: "").replace("  ", " ").trim()
            "pair" -> if (a == null) c.label.replace("[누가]가 [누구]", "누가 " + (nameOf(target) ?: "친구")).trim()
            else c.label.replace("[누가]가", a + josa(a, "이가", "가")).replace("[누구]", nameOf(target) ?: "친구")
            else -> c.label
        }
    }

    fun clipsDir(c: Context): File = File(c.filesDir, "clips").apply { mkdirs() }
    fun clipFile(c: Context, key: String): File = File(clipsDir(c), "$key.wav")

    // ---------------- 음성 인식 결과 → 명령 ----------------

    data class Match(val cmd: String, val actor: Who, val target: Who)

    private fun norm(s: String): String = s.lowercase().filter { it in '가'..'힣' || it.isLetterOrDigit() }

    fun match(text: String, testMode: Boolean): Match? {
        val t = norm(text)
        if (t.isEmpty()) return null
        if (testMode && t.contains("마이크테스트")) return Match(TEST, Who.NONE, Who.NONE)
        // 음성 인식이 "이놈아 씨", "이노마 저씨"처럼 적는 경우도 받음
        if (listOf("아저씨", "놈아씨", "노마저씨", "노마씨", "놈아저").none { t.contains(it) }) return null
        // '밥+장난감' 처럼 '+'로 이은 조각은 모두 들어 있어야 함
        val c = commands.firstOrNull { cmd -> cmd.match.any { m -> m.split('+').all { t.contains(it) } } } ?: return null
        if (!Prefs.isEnabled(c.id)) { AppLog.d("꺼진 대사라 무시: ${c.id}"); return null }

        val n1 = norm(Prefs.name1).takeIf { it.isNotEmpty() }
        val n2 = norm(Prefs.name2).takeIf { it.isNotEmpty() }
        val i1 = n1?.let { t.indexOf(it) } ?: -1
        val i2 = n2?.let { t.indexOf(it) } ?: -1
        return when (c.names) {
            "single" -> Match(c.id, when {
                i1 >= 0 && i2 >= 0 -> Who.BOTH
                i1 >= 0 -> Who.N1
                i2 >= 0 -> Who.N2
                else -> Who.NONE
            }, Who.NONE)
            "pair" -> {
                val other = if (t.contains("다른사람") || t.contains("다른분") || t.contains("다른어른")) Who.OTHER else Who.FRIEND
                when {
                    i1 >= 0 && i2 >= 0 -> if (i1 < i2) Match(c.id, Who.N1, Who.N2) else Match(c.id, Who.N2, Who.N1)
                    i1 >= 0 -> Match(c.id, Who.N1, other)
                    i2 >= 0 -> Match(c.id, Who.N2, other)
                    else -> Match(c.id, Who.NONE, other)
                }
            }
            else -> Match(c.id, Who.NONE, Who.NONE)
        }
    }
}
