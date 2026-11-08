package com.scarespeaker

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat

/** 코드로 화면을 만드는 작은 도우미 */
object Ui {
    const val BG = 0xFF14141A.toInt()
    const val CARD = 0xFF22222B.toInt()
    const val RED = 0xFFB3261E.toInt()
    const val RED_DARK = 0xFF6E1A15.toInt()
    const val GREEN = 0xFF2E7D32.toInt()
    const val TEXT = Color.WHITE
    const val SUB = 0xFFB8B8C4.toInt()

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun rounded(color: Int, radiusDp: Int, c: Context) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(c, radiusDp).toFloat()
    }

    fun column(c: Context, padDp: Int = 0) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(c, padDp), dp(c, padDp), dp(c, padDp), dp(c, padDp))
    }

    fun row(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun card(c: Context) = column(c, 14).apply { background = rounded(CARD, 16, c) }

    fun title(c: Context, s: String, size: Float = 22f) = TextView(c).apply {
        text = s; textSize = size; setTextColor(TEXT); setTypeface(typeface, Typeface.BOLD)
    }

    fun text(c: Context, s: String, size: Float = 15f, color: Int = SUB) = TextView(c).apply {
        text = s; textSize = size; setTextColor(color)
    }

    fun section(c: Context, s: String) = TextView(c).apply {
        text = s; textSize = 14f; setTextColor(0xFFE8665C.toInt()); setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(c, 18), 0, dp(c, 6))
    }

    fun bigButton(c: Context, s: String, color: Int = RED, heightDp: Int = 72, onClick: () -> Unit) = Button(c).apply {
        text = s; textSize = 18f; setTextColor(TEXT); isAllCaps = false
        background = rounded(color, 18, c)
        setOnClickListener { pressFx(it); onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, heightDp)).apply {
            bottomMargin = dp(c, 10)
        }
    }

    fun smallButton(c: Context, s: String, color: Int = 0xFF3A3A46.toInt(), onClick: () -> Unit) = Button(c).apply {
        text = s; textSize = 15f; setTextColor(TEXT); isAllCaps = false
        background = rounded(color, 12, c)
        minWidth = dp(c, 48); minimumWidth = dp(c, 48)
        setPadding(dp(c, 12), 0, dp(c, 12), 0)
        setOnClickListener { pressFx(it); onClick() }
    }

    /** 눌렸다는 걸 알 수 있게: 살짝 작아졌다 커지며 반짝 + 짧은 진동 */
    fun pressFx(v: View) {
        v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        v.animate().cancel()
        v.animate().scaleX(0.92f).scaleY(0.92f).alpha(0.6f).setDuration(80).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(160).start()
        }.start()
    }

    fun switch(c: Context, label: String, checked: Boolean, onChange: (Boolean) -> Unit) = SwitchCompat(c).apply {
        text = label; textSize = 16f; setTextColor(TEXT); isChecked = checked
        setPadding(0, dp(c, 8), 0, dp(c, 8))
        thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(0xFFE8665C.toInt(), 0xFFAAAAAA.toInt()))
        setOnCheckedChangeListener { _, b -> onChange(b) }
    }

    fun <T> radio(c: Context, items: List<T>, label: (T) -> String, selected: T, horizontal: Boolean = true, onPick: (T) -> Unit): RadioGroup {
        val g = RadioGroup(c).apply { orientation = if (horizontal) RadioGroup.HORIZONTAL else RadioGroup.VERTICAL }
        items.forEachIndexed { i, item ->
            g.addView(RadioButton(c).apply {
                id = View.generateViewId()
                text = label(item); textSize = 15f; setTextColor(TEXT)
                buttonTintList = ColorStateList.valueOf(0xFFE8665C.toInt())
                isChecked = item == selected
                setOnCheckedChangeListener { _, b -> if (b) onPick(item) }
                setPadding(0, 0, dp(c, 12), 0)
            })
        }
        return g
    }

    /** [−] 값 [+] 형태의 조절 줄 */
    fun stepper(c: Context, label: String, valueText: () -> String, onMinus: () -> Unit, onPlus: () -> Unit): LinearLayout {
        val r = row(c).apply { setPadding(0, dp(c, 4), 0, dp(c, 4)) }
        r.addView(text(c, label, 15f, TEXT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val v = text(c, valueText(), 16f, TEXT).apply { gravity = Gravity.CENTER; minWidth = dp(c, 92) }
        r.addView(smallButton(c, "−") { onMinus(); v.text = valueText() })
        r.addView(v)
        r.addView(smallButton(c, "+") { onPlus(); v.text = valueText() })
        r.tag = Runnable { v.text = valueText() }
        return r
    }

    fun spacer(c: Context, hDp: Int) = View(c).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(c, hDp))
    }

    // ----- 음량 표시 (최소 단위 기준) -----

    /** 사운드바 화면에 뜨는 숫자와 같게 표시 (0~100) */
    fun castVolumeText(): String = "${Math.round(Prefs.castVolume * 100)}"

    private fun castMove(dir: Int) {
        Prefs.castVolume = (Math.round(Prefs.castVolume * 100) + dir) / 100.0
        Player.applyLiveVolume()
    }
    fun castPlus() = castMove(1)
    fun castMinus() = castMove(-1)
    fun dlnaPlus() { Prefs.dlnaVolume = Prefs.dlnaVolume + 1; Player.applyLiveVolume() }
    fun dlnaMinus() { Prefs.dlnaVolume = Prefs.dlnaVolume - 1; Player.applyLiveVolume() }
    fun delayText() = "${Prefs.dlnaDelayMs} ms"
}
