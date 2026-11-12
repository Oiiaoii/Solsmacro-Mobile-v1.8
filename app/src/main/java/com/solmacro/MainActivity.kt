package com.solmacro

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.graphics.drawable.GradientDrawable
import android.view.animation.OvershootInterpolator
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewOutlineProvider
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

private const val FILL = ViewGroup.LayoutParams.MATCH_PARENT
private const val HUG = ViewGroup.LayoutParams.WRAP_CONTENT

class MainActivity : Activity() {
    private val cBg = 0xFF060608.toInt()
    private val cCard = 0xFF121216.toInt()
    private val cBd = 0xFF2A2A31.toInt()
    private val cTx = 0xFFE8EEFC.toInt()
    private val cMut = 0xFF9A9AA5.toInt()
    private val cAcc = 0xFFEDEDF0.toInt()
    private val cOk = 0xFF38D39F.toInt()
    private val cBad = 0xFFFF6B6B.toInt()

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var pill: TextView
    private lateinit var stateTv: TextView
    private lateinit var statsTv: TextView
    private lateinit var posTv: TextView
    private lateinit var perfTv: TextView
    private lateinit var logo: ImageView
    private lateinit var prevIcon: ImageView
    private var cardN = 0
    private var autoReturn = false
    private var logoAnim: ObjectAnimator? = null
    private lateinit var logTv: TextView

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 250)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun shape(color: Int, r: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun tv(t: String, size: Float, color: Int = cTx, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun add(p: LinearLayout, v: View, top: Int = 8) {
        p.addView(v, LinearLayout.LayoutParams(FILL, HUG).apply { topMargin = dp(top) })
    }

    private fun card(title: String?): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(cCard, 16, cBd)
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        add(root, c, 12)
        cardN++
        if (title != null) c.addView(tv(title, 16f, cTx, true))
        return c
    }

    private fun btn(t: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setTextColor(if (color == cOk) 0xFF06251C.toInt() else if (color == cAcc) 0xFF0A0A0C.toInt() else Color.WHITE)
        background = shape(color, 12)
        setOnClickListener { onClick() }
        setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }
    }

    private fun row(p: LinearLayout, vararg b: Button) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, x) in b.withIndex()) {
            r.addView(x, LinearLayout.LayoutParams(0, HUG, 1f).apply { if (i > 0) leftMargin = dp(8) })
        }
        add(p, r, 10)
    }

    private fun field(p: LinearLayout, label: String, key: String) {
        add(p, tv(label, 12f, cMut), 10)
        val e = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Cfg.get(this@MainActivity, key).toString())
            setTextColor(cTx)
            background = shape(cBg, 10, cBd)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    Cfg.put(this@MainActivity, key, s.toString().toIntOrNull() ?: 0)
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        add(p, e, 4)
    }

    private fun bump(t: TextView, text: String) {
        if (t.text.toString() == text) return
        t.text = text
        t.scaleX = 1.12f
        t.scaleY = 1.12f
        t.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(2f)).start()
    }

    // text fields that edit the same saved value (webhook links, private server link) stay in step with each other,
    // and a small line under the field says whether the text looks right
    private val linked = HashMap<String, MutableList<EditText>>()

    private fun webhookCheck(s: String): String {
        val parts = s.split(Regex("[\\s,;]+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return ""
        val bad = parts.count { !Hook.looksValid(it) }
        return if (bad == 0) "OK: " + parts.size + " webhook link" + (if (parts.size > 1) "s" else "") + " saved"
        else "$bad link(s) do not look like a Discord webhook (https://discord.com/api/webhooks/ID/TOKEN)"
    }

    private fun serverLinkCheck(s: String): String {
        if (s.isEmpty()) return ""
        return if (s.startsWith("https://www.roblox.com/") || s.startsWith("https://roblox.com/") || s.startsWith("https://ro.blox.com/"))
            "OK: link saved"
        else "This does not look like a Roblox private server link (it starts with https://www.roblox.com/)"
    }

    private fun linkField(p: LinearLayout, label: String, key: String, hintText: String, multi: Boolean, check: (String) -> String) {
        add(p, tv(label, 12f, cMut), 10)
        val status = tv("", 11f, cMut)
        val e = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or (if (multi) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
            setText(Cfg.gets(this@MainActivity, key))
            hint = hintText
            setHintTextColor(cMut)
            setTextColor(cTx)
            background = shape(cBg, 10, cBd)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val list = linked.getOrPut(key) { ArrayList() }
        list.add(e)
        fun refresh(t: String) {
            val m = check(t)
            status.text = m
            status.setTextColor(if (m.startsWith("OK")) cOk else if (m.isEmpty()) cMut else cBad)
        }
        refresh(e.text.toString().trim())
        e.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val t = s.toString().trim()
                Cfg.puts(this@MainActivity, key, t)
                refresh(t)
                for (o in list) if (o !== e && o.text.toString().trim() != t) o.setText(t)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        // when you leave the webhook field, look up its channel now so the first biome alert is not delayed
        e.setOnFocusChangeListener { _, has -> if (!has && key == "webhook") Hook.prefetch(this@MainActivity) }
        add(p, e, 4)
        add(p, status, 2)
    }

    private fun textField(p: LinearLayout, label: String, key: String, hintText: String = "") {
        add(p, tv(label, 12f, cMut), 10)
        val e = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(Cfg.gets(this@MainActivity, key))
            hint = hintText
            setHintTextColor(cMut)
            setTextColor(cTx)
            background = shape(cBg, 10, cBd)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { Cfg.puts(this@MainActivity, key, s.toString().trim()) }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        add(p, e, 4)
    }

    private fun sw(p: LinearLayout, label: String, key: String) {
        val s = Switch(this).apply {
            text = label
            setTextColor(cTx)
            isChecked = Cfg.get(this@MainActivity, key) == 1
            setOnCheckedChangeListener { _, on -> Cfg.put(this@MainActivity, key, if (on) 1 else 0) }
        }
        add(p, s, 10)
    }

    private fun roundIcon(sizeDp: Int): ImageView = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = GradientDrawable().apply { setShape(GradientDrawable.OVAL); setColor(0xFF000000.toInt()) }
        clipToOutline = true
        outlineProvider = ViewOutlineProvider.BACKGROUND
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { topMargin = dp(10) }
    }

    private fun applyIcon(v: ImageView) {
        val f = File(filesDir, "float_icon.png")
        if (Cfg.get(this, "icon_custom") == 1 && f.exists()) {
            val b = BitmapFactory.decodeFile(f.absolutePath)
            if (b != null) { v.setImageBitmap(b); return }
        }
        v.setImageResource(R.drawable.brry_icon)
    }

    private fun pickIcon() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(i, 101)
    }

    private fun resetIcon() {
        Cfg.put(this, "icon_custom", 0)
        applyIcon(prevIcon)
        applyIcon(logo)
        MacroService.inst?.refreshIcon()
    }

    private fun saveIcon(uri: android.net.Uri) {
        try {
            val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opt) }
            val o2 = BitmapFactory.Options().apply { inSampleSize = maxOf(1, maxOf(opt.outWidth, opt.outHeight) / 1024) }
            val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
                ?: throw Exception("no image")
            val side = minOf(bmp.width, bmp.height)
            val sq = android.graphics.Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
            val out = android.graphics.Bitmap.createScaledBitmap(sq, 192, 192, true)
            FileOutputStream(File(filesDir, "float_icon.png")).use { out.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            Cfg.put(this, "icon_custom", 1)
            applyIcon(prevIcon)
            applyIcon(logo)
            MacroService.inst?.refreshIcon()
            Toast.makeText(this, "Icon updated", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not use that image", Toast.LENGTH_SHORT).show()
        }
    }

    private fun script(p: LinearLayout, label: String, key: String) {
        add(p, tv(label, 12f, cMut), 10)
        val e = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = Gravity.TOP
            typeface = Typeface.MONOSPACE
            setText(Cfg.gets(this@MainActivity, key))
            setTextColor(cTx)
            background = shape(cBg, 10, cBd)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { Cfg.puts(this@MainActivity, key, s.toString()) }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        add(p, e, 4)
    }

    private fun requestCapture() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), 100)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 101 && res == RESULT_OK && data?.data != null) saveIcon(data.data!!)
        if (req == 100) {
            if (res == RESULT_OK && data != null) {
                val i = Intent(this, CaptureService::class.java).putExtra("code", res).putExtra("data", data)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
                Toast.makeText(this, "Fast capture starting", Toast.LENGTH_SHORT).show()
                if (autoReturn) {
                    autoReturn = false
                    // go back to Roblox so you can press Start right away
                    handler.postDelayed({
                        val i2 = packageManager.getLaunchIntentForPackage(Cfg.ROBLOX)
                        if (i2 != null) startActivity(i2)
                    }, 900L)
                }
            } else {
                autoReturn = false
                Toast.makeText(this, "Permission not given", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun preset(cast: Int, wait: Int, taps: Int, gap: Int) {
        Cfg.put(this, "cast", cast); Cfg.put(this, "wait", wait); Cfg.put(this, "taps", taps); Cfg.put(this, "gap", gap)
        recreate()
    }

    // ---------- left menu ----------
    private val secNames = listOf("Home", "Fishing", "Detection", "Scripts", "Watchers", "Discord", "Rejoin", "Look", "Log")
    private val secIcons = listOf("\uD83C\uDFE0", "\uD83C\uDFA3", "\uD83E\uDD16", "\uD83E\uDDED", "\uD83D\uDC41", "\uD83D\uDCAC", "\uD83D\uDD01", "\uD83C\uDFA8", "\uD83D\uDCDC")
    private val pages = ArrayList<LinearLayout>()
    private val scrolls = ArrayList<ScrollView>()
    private val menuRows = ArrayList<LinearLayout>()
    private val menuLabels = ArrayList<TextView>()
    private var curSec = -1
    private var expanded = true
    private lateinit var menuScroll: ScrollView
    private lateinit var indicator: View
    private lateinit var burger: TextView
    private lateinit var secTv: TextView
    private lateinit var monTv: TextView
    private lateinit var meter: View

    private lateinit var bioTv: TextView

    // one tappable chip per biome: off -> message -> message and ping
    private fun biomeChips(p: LinearLayout) {
        var rowL: LinearLayout? = null
        for ((i, b) in Biome.all.withIndex()) {
            if (i % 2 == 0) {
                rowL = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                add(p, rowL, 6)
            }
            val chip = TextView(this).apply {
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            fun paint() {
                val md = Biome.mode(this, b.key)
                chip.text = Biome.nice(b) + "\n" + (if (md == 0) "off" else if (md == 1) "message" else "message + ping")
                if (md == 0) { chip.background = shape(cBg, 10, cBd); chip.setTextColor(cMut) }
                else if (md == 1) { chip.background = shape(cBg, 10, cOk); chip.setTextColor(cTx) }
                else { chip.background = shape(cAcc, 10); chip.setTextColor(0xFF0A0A0C.toInt()) }
            }
            paint()
            chip.setOnClickListener {
                Biome.setMode(this, b.key, (Biome.mode(this, b.key) + 1) % 3)
                paint()
            }
            press(chip)
            rowL!!.addView(chip, LinearLayout.LayoutParams(0, HUG, 1f).apply { rightMargin = dp(6) })
        }
    }

    private val rowH get() = dp(46)
    private val rowGap get() = dp(4)

    private fun rowTop(i: Int) = dp(4) + rowGap + i * (rowH + rowGap)

    private fun useSection(i: Int) { root = pages[i] }

    private fun press(v: View) {
        v.setOnTouchListener { x, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> x.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> x.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(3f)).start()
            }
            false
        }
    }

    private fun buildShell(): View {
        expanded = Cfg.get(this, "menu_open") == 1
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(cBg) }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(36), dp(12), dp(8))
        }
        burger = tv("\u2630", 24f, cTx, true).apply {
            setPadding(dp(12), dp(4), dp(12), dp(4))
            rotation = if (expanded) 0f else 90f
            setOnClickListener { toggleMenu() }
        }
        press(burger)
        head.addView(burger)
        logo = roundIcon(42)
        logo.layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
        applyIcon(logo)
        head.addView(logo)
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(tv("Brry", 24f, cTx, true))
        secTv = tv("", 12f, cMut)
        titles.addView(secTv)
        head.addView(titles, LinearLayout.LayoutParams(HUG, HUG).apply { leftMargin = dp(10) })
        outer.addView(head, LinearLayout.LayoutParams(FILL, HUG))

        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        // menu
        val box = FrameLayout(this).apply { setBackgroundColor(0xFF09090C.toInt()) }
        indicator = View(this).apply { background = shape(cAcc, 14) }
        box.addView(indicator, FrameLayout.LayoutParams(FILL, rowH).apply { leftMargin = dp(6); rightMargin = dp(6) })
        val menu = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(4), dp(6), dp(8)) }
        for (i in secNames.indices) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(6), 0)
                setOnClickListener { showSection(i) }
            }
            row.addView(tv(secIcons[i], 18f, cTx), LinearLayout.LayoutParams(dp(26), HUG))
            val lab = tv(secNames[i], 13f, cTx, true).apply {
                maxLines = 1
                visibility = if (expanded) View.VISIBLE else View.GONE
            }
            row.addView(lab, LinearLayout.LayoutParams(HUG, HUG).apply { leftMargin = dp(8) })
            press(row)
            menu.addView(row, LinearLayout.LayoutParams(FILL, rowH).apply { topMargin = rowGap })
            menuRows.add(row)
            menuLabels.add(lab)
        }
        box.addView(menu, FrameLayout.LayoutParams(FILL, HUG))
        menuScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(box, FrameLayout.LayoutParams(FILL, HUG)) }
        body.addView(menuScroll, LinearLayout.LayoutParams(dp(if (expanded) 132 else 58), FILL))
        // pages
        val content = FrameLayout(this)
        for (i in secNames.indices) {
            val page = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(0), dp(14), dp(40))
            }
            val sv = ScrollView(this).apply { visibility = View.GONE; addView(page) }
            pages.add(page)
            scrolls.add(sv)
            content.addView(sv, FrameLayout.LayoutParams(FILL, FILL))
        }
        body.addView(content, LinearLayout.LayoutParams(0, FILL, 1f))
        outer.addView(body, LinearLayout.LayoutParams(FILL, 0, 1f))
        return outer
    }

    private fun toggleMenu() {
        expanded = !expanded
        Cfg.put(this, "menu_open", if (expanded) 1 else 0)
        val from = menuScroll.width
        val to = dp(if (expanded) 132 else 58)
        if (expanded) {
            for (l in menuLabels) { l.visibility = View.VISIBLE; l.alpha = 0f; l.animate().alpha(1f).setStartDelay(120).setDuration(220).start() }
        } else {
            for (l in menuLabels) l.animate().alpha(0f).setStartDelay(0).setDuration(120).withEndAction { if (!expanded) l.visibility = View.GONE }.start()
        }
        ValueAnimator.ofInt(from, to).apply {
            duration = 280
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val lp = menuScroll.layoutParams
                lp.width = it.animatedValue as Int
                menuScroll.layoutParams = lp
            }
            start()
        }
        burger.animate().rotation(if (expanded) 0f else 90f).setDuration(280).setInterpolator(OvershootInterpolator(1.6f)).start()
    }

    private fun moveIndicator(i: Int, animate: Boolean) {
        val y = rowTop(i).toFloat()
        indicator.animate().cancel()
        if (animate) indicator.animate().translationY(y).setDuration(340).setInterpolator(OvershootInterpolator(1.5f)).start()
        else indicator.translationY = y
    }

    private fun stagger(page: LinearLayout) {
        for (j in 0 until page.childCount) {
            val v = page.getChildAt(j)
            v.animate().cancel()
            v.alpha = 0f
            v.translationY = dp(18).toFloat()
            v.animate().alpha(1f).translationY(0f).setStartDelay(j * 55L).setDuration(320).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun showSection(i: Int) {
        if (i == curSec) return
        val prev = curSec
        curSec = i
        for (k in scrolls.indices) {
            val sv = scrolls[k]
            sv.animate().cancel()
            if (k == i) {
                sv.visibility = View.VISIBLE
                sv.scrollTo(0, 0)
                if (prev >= 0) {
                    sv.alpha = 0f
                    sv.translationX = dp(if (i > prev) 30 else -30).toFloat()
                    sv.animate().alpha(1f).translationX(0f).setDuration(280).setInterpolator(DecelerateInterpolator()).start()
                } else {
                    sv.alpha = 1f
                    sv.translationX = 0f
                }
                stagger(pages[k])
            } else {
                sv.visibility = View.GONE
                sv.alpha = 1f
                sv.translationX = 0f
            }
        }
        for (k in menuLabels.indices) {
            val to = if (k == i) 0xFF0A0A0C.toInt() else cTx
            val l = menuLabels[k]
            val from = l.currentTextColor
            if (from != to) ObjectAnimator.ofArgb(l, "textColor", from, to).apply { duration = 240; start() }
            menuRows[k].animate().translationX(if (k == i) dp(2).toFloat() else 0f).setDuration(240).start()
        }
        moveIndicator(i, prev >= 0)
        bump(secTv, secNames[i])
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val shell = buildShell()
        useSection(0)
        root.addView(tv("Fishing and selling helper for Sol’s RNG. Not affiliated with Roblox. Macros can break game rules and get accounts banned.", 12f, cMut))

        val st = card(null)
        pill = tv("", 12f, Color.WHITE, true).apply { setPadding(dp(14), dp(6), dp(14), dp(6)) }
        st.addView(pill, LinearLayout.LayoutParams(HUG, HUG))
        stateTv = tv("", 15f)
        add(st, stateTv, 10)
        statsTv = tv("", 13f, cMut)
        add(st, statsTv, 4)
        perfTv = tv("", 12f, cMut)
        add(st, perfTv, 4)
        posTv = tv("", 11f, cMut)
        add(st, posTv, 10)
        row(st,
            btn("Turn on service", cAcc) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            btn("Open Roblox", cBd) {
                val i = packageManager.getLaunchIntentForPackage(Cfg.ROBLOX)
                if (i != null) startActivity(i) else Toast.makeText(this, "Roblox is not installed", Toast.LENGTH_SHORT).show()
            })

        val how = card("How to use")
        add(how, tv("1. Turn on the service, then open Roblox. A round Brry button appears.\n2. Tap it to open the panel and tap it again to close it.\n3. Panel > Settings > Set button positions: tap the 9 spots it asks for (the last one is the X on the caught or failed panel).\n4. Optional: start fast capture below for the best speed (Android asks for permission). You can also press Start without it.\n5. Press the big play button or the Toggle to start switch under it. If Start is refused, the panel shows exactly what is missing and you can tap the red row to fix it. Use Test detection while the reeling bar shows.", 12f, cMut), 6)

        useSection(1)
        val fish = card("Fishing")
        field(fish, "Cast hold (ms)", "cast")
        field(fish, "Stop after N catches (0 = never)", "maxcatch")

        useSection(2)
        val mon = card("Live detection")
        monTv = tv("", 12f, cTx).apply { typeface = Typeface.MONOSPACE }
        add(mon, monTv, 8)
        add(mon, tv("Zone match", 11f, cMut), 10)
        val track = FrameLayout(this).apply { background = shape(cBg, 6, cBd) }
        meter = View(this).apply { background = shape(cOk, 6); pivotX = 0f; scaleX = 0f }
        track.addView(meter, FrameLayout.LayoutParams(FILL, dp(12)))
        add(mon, track, 4)
        add(mon, tv("Updates live while the macro runs. To test: open Roblox with the reeling bar showing and use Test detection in the Brry panel (Settings).", 11f, cMut), 8)
        val det = card("Detection (whole screen)")
        sw(det, "AI Autopilot (learns the best aim, recovers when stuck)", "ai")
        add(det, btn("Reset what the AI learned", cBd) {
            Ai.reset(this)
            Toast.makeText(this, "AI memory cleared", Toast.LENGTH_SHORT).show()
        }, 8)
        field(det, "AI: seconds without progress before it recovers", "stuck")
        field(det, "Tap press length (ms)", "taphold")
        sw(det, "Debug overlay (green, yellow, red box)", "debug")
        field(det, "Minimum bar size (pixels)", "minpx")
        field(det, "Max seconds to wait for the bar", "maxwait")
        field(det, "Time between reel taps (ms)", "tapms")
        field(det, "Close button X (caught or failed panel)", "dismiss_x")
        field(det, "Close button Y (caught or failed panel)", "dismiss_y")
        sw(det, "Learn the real drain speed while reeling", "learn")
        sw(det, "Widen the color match after a missed bar", "widen")
        add(det, tv("Timing", 14f, cTx, true), 14)
        field(det, "Result panel wait (ms)", "resultms")
        field(det, "Wait after closing the result (ms)", "dismisswait")
        sw(det, "Adaptive recast time (learns how long bites take)", "adapt")
        field(det, "Wait after casting before looking (ms)", "castdelay")
        field(det, "Bar counts as finished after (ms)", "gonems")
        field(det, "Pause after a catch (ms)", "postcatch")

        useSection(7)
        val fl = card("Floating button")
        add(fl, tv("A round button stays on screen over Roblox. Tap it to open the control bar and tap it again to close it. Drag it to move it.", 12f, cMut), 6)
        prevIcon = roundIcon(72)
        applyIcon(prevIcon)
        fl.addView(prevIcon)
        row(fl, btn("Use This Icon", cAcc) { pickIcon() }, btn("Use default icon", cBd) { resetIcon() })

        useSection(2)
        val cap = card("Fast screen capture (optional)")
        add(cap, tv("Optional. Without it Brry uses screenshots (about 3 a second) and predicts the marker between them. With it the screen is read about 30 times a second. Android asks for permission each time you start it.", 12f, cMut), 6)
        add(cap, btn("Start fast capture", cOk) { requestCapture() }, 10)
        add(cap, btn("Stop fast capture", cBd) { Cap.release(); stopService(Intent(this, CaptureService::class.java)) }, 8)

        useSection(3)
        val mv = card("Movement, camera and Shift Lock")
        add(mv, tv("Scripts run with swipes on Roblox's on-screen joystick and camera area. One command per line:\nmove up 1.5   (also down, left, right, w, a, s, d)\ncamera left 250\nshiftlock\ntap inv\nwait 1\n# comment", 12f, cMut), 6)
        field(mv, "Joystick push distance (pixels)", "joyr")
        script(mv, "Run once when you press START", "script_start")
        script(mv, "Run before selling (walk to the seller)", "script_pre")
        script(mv, "Run after selling (walk back)", "script_post")

        useSection(1)
        val sell = card("Selling")
        sw(sell, "Auto sell", "sell")
        field(sell, "Sell every N catches", "every")
        field(sell, "Menu delay (ms)", "mdelay")

        useSection(5)
        val wh = card("Discord webhook")
        sw(wh, "Send webhook messages", "whon")
        linkField(wh, "Webhook URLs (one per line, you can add several channels)", "webhook",
            "https://discord.com/api/webhooks/...", true) { webhookCheck(it) }
        field(wh, "Progress message every N catches (0 = off)", "whevery")
        add(wh, btn("Send test message", cAcc) {
            Hook.send(this, "Test", "Sol Macro webhook works", 0x38D39F)
            Toast.makeText(this, "Sent, check the log below", Toast.LENGTH_SHORT).show()
        }, 12)

        val ba = card("Biome alerts")
        add(ba, tv("Pick which biomes send a Discord message. The message copies the SolsScope biome alert: a heading, your private server link (Rejoin settings), then an embed with the biome icon, Channel, Time, Jump to Message and a small footer.", 12f, cMut), 4)
        linkField(ba, "Webhook URL for biome alerts (same list as the Discord webhook card)", "webhook",
            "https://discord.com/api/webhooks/...", true) { webhookCheck(it) }
        linkField(ba, "Private server link (shown under the biome heading)", "pslink",
            "https://www.roblox.com/share?code=...&type=Server", false) { serverLinkCheck(it) }
        biomeChips(ba)
        sw(ba, "Also send a message when a biome ends", "bioend")
        textField(ba, "Footer text (empty = Message <@your ID> for any bugs)", "whfooter", "")
        add(ba, btn("Send test biome message", cAcc) {
            Hook.biomeTest(this)
            Toast.makeText(this, "Sent a sample RAINY message, check your channel", Toast.LENGTH_SHORT).show()
        }, 12)
        sw(ba, "Status report to the webhooks", "statuson")
        field(ba, "Status report every N minutes (minimum 5)", "statusmin")

        useSection(4)
        val ex = card("Anti-kick and screenshots")
        sw(ex, "Anti-kick (small camera move when nothing was tapped)", "afk")
        field(ex, "Anti-kick after N minutes without taps", "afkmin")
        sw(ex, "Send a screenshot to the webhooks (needs fast capture)", "shoton")
        field(ex, "Screenshot every N minutes", "shotmin")

        useSection(4)
        val bw = card("Biome watch (reads the screen text)")
        add(bw, tv("Reads the biome name from the screen and sends the alert you set up in Discord > Biome alerts when it changes. Works with or without fast capture (without it Android 11 or newer is needed). Set it up in the Brry panel while Roblox is open: Settings > Set biome area, tap the top-left and bottom-right corner of the biome name, then Test biome read to see what it reads.", 12f, cMut), 4)
        sw(bw, "Biome watch", "bio")
        field(bw, "Check every N seconds (minimum 1, lower = faster alerts)", "biosec")
        bioTv = tv("", 12f, cTx)
        add(bw, bioTv, 8)

        useSection(6)
        val rj = card("Auto rejoin")
        add(rj, tv("If Roblox closes, crashes or shows a disconnect screen, the macro opens Roblox again (and your private server link if you add one) and carries on. Needs fast capture for the disconnect screen check.", 12f, cMut), 6)
        sw(rj, "Auto rejoin", "rejoin")
        linkField(rj, "Private server link (optional)", "pslink",
            "https://www.roblox.com/games/...?privateServerLinkCode=...", false) { serverLinkCheck(it) }
        field(rj, "Seconds to wait for Roblox to load", "rejoinsec")

        useSection(4)
        val ww = card("Word watch (merchant, auras, anything)")
        add(ww, tv("Reads the screen text and sends a webhook message with a picture when one of your words shows up, for example Mari, Jester or an aura name. Set the area in the panel: Settings > Set watch area (leave it unset to read the whole screen, slower). Reading can be wrong. The same word is only reported once every 5 minutes.", 12f, cMut), 6)
        sw(ww, "Word watch", "watch")
        textField(ww, "Words (separated by commas)", "watchwords", "mari, jester")
        field(ww, "Check every N seconds (minimum 3)", "watchsec")
        sw(ww, "Ping me when a word is seen", "pingwatch")
        sw(ww, "Ping me on rare biomes (needs biome watch)", "pingrare")
        textField(ww, "Discord user ID to ping (type & before a role ID)", "pingid", "123456789012345678")

        useSection(3)
        val tk = card("Timed tasks (auto craft, buy, biome items)")
        add(tk, tv("Runs your script every N minutes between casts. Use the same commands as above, plus: tap p1 (extra points P1 to P6, set in the panel: Settings > Set extra points) or tap 540 1200 (screen pixels). Example for a craft: tap p1, wait 1, tap p2, wait 1.", 12f, cMut), 6)
        for (n in 1..3) {
            sw(tk, "Task $n on", "t" + n + "on")
            field(tk, "Task $n every N minutes", "t" + n + "min")
            script(tk, "Task $n script", "t" + n + "s")
        }

        useSection(8)
        val lg = card("Log")
        logTv = tv("", 11f, cMut).apply { typeface = Typeface.MONOSPACE }
        add(lg, logTv, 8)
        add(lg, btn("Copy log", cBd) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Sol Macro log", Dbg.tail(150)))
            Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
        }, 10)

        if (intent.getBooleanExtra("capture", false)) {
            autoReturn = true
            requestCapture()
        }
        setContentView(shell)
        showSection(0)
    }

    // the panel asked for fast capture while this screen was already open
    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        if (i.getBooleanExtra("capture", false)) {
            autoReturn = true
            requestCapture()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
        logoAnim?.cancel()
        logoAnim = ObjectAnimator.ofFloat(logo, "rotation", -4f, 4f).apply {
            duration = 2400
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            start()
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
        logoAnim?.cancel()
        logoAnim = null
    }

    private fun refresh() {
        val on = (Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .contains("$packageName/")
        bump(pill, if (on) "Service ON" else "Service OFF")
        pill.background = shape(if (on) cOk else cBad, 20)
        pill.setTextColor(if (on) 0xFF06251C.toInt() else Color.WHITE)
        bump(stateTv, "State: " + Dbg.state)
        val run = if (Dbg.startMs > 0 && Dbg.state != "Idle") (SystemClock.uptimeMillis() - Dbg.startMs) / 1000 else 0L
        statsTv.text = "Catches ${Dbg.catches}    Failed ${Dbg.fails}    Sells ${Dbg.sells}    Run ${run / 60}:${(run % 60).toString().padStart(2, '0')}"
        perfTv.text = "Capture: " + (if (Cap.active) "fast" else "screenshots") + "    taps ${Dbg.tapOk} ok / ${Dbg.tapCancel} cancelled    ${Dbg.fps} checks/s    ${Dbg.latency} ms    bite ${Dbg.biteMs} ms"
        posTv.text = Cfg.keys.joinToString("   ") { k -> "${Cfg.label[k]}: ${Cfg.get(this, k + "_x")},${Cfg.get(this, k + "_y")}" }
        logTv.text = Dbg.tail(25).ifEmpty { "No log yet" }
        if (curSec == 4) {
            bioTv.text = "Now: " + (Biome.cur?.title ?: "not read yet") + "\n" + Biome.summary()
        }
        if (curSec == 2) {
            monTv.text = "Capture   " + (if (Cap.active) "fast" else "screenshots") + "\nChecks/s  ${Dbg.fps}\nLatency   ${Dbg.latency} ms\nBar       " +
                (if (Dbg.barFound) "found" else "not seen") + "\nZone px   ${Dbg.zonePx}\nMarker px ${Dbg.markerPx}\nIn zone   ${Dbg.inZone}%\nTaps      ${Dbg.tapOk} ok, ${Dbg.tapCancel} cancelled\nAI brain  ${Dbg.aiInfo}"
            val pct = minOf(100, Dbg.zonePx * 100 / maxOf(Cfg.get(this, "minpx"), 1))
            meter.animate().scaleX(pct / 100f).setDuration(160).start()
            (meter.background as? GradientDrawable)?.setColor(if (pct >= 100) cOk else if (pct >= 50) 0xFFFFB84D.toInt() else cBad)
        }
    }
}
