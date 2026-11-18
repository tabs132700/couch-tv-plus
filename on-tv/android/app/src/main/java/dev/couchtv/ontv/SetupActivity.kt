package dev.couchtv.ontv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The setup screen on the TV (the "Couch TV" tile). Shows the address to open on a phone, the
 * pairing code, and the state of ADB. Everything with typing (wireless debugging code, API key,
 * names) is done on the phone at /setup, because typing with a TV remote is miserable.
 */
class SetupActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var address: TextView
    private lateinit var code: TextView
    private lateinit var adb: TextView
    private lateinit var homeBtn: Button
    private lateinit var qr: ImageView
    private lateinit var qrNote: TextView
    private var qrFor: String? = null
    private val tick = object : Runnable { override fun run() { refresh(); main.postDelayed(this, 1500) } }
    private val pinListener: (String?) -> Unit = { main.post { refresh() } }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        App.startService(this)
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(56), px(36), px(56), px(36)) }
        fun text(t: String, size: Float, colour: Int = CREAM, bold: Boolean = false) = TextView(this).apply {
            text = t; textSize = size; setTextColor(colour); if (bold) typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            setPadding(0, px(4), 0, px(4))
        }
        // the texts on the left, a QR on the right that opens the phone remote already paired (one scan)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(text("Couch TV", 34f, CREAM, true))
        left.addView(text(t("The phone remote runs on this TV. No computer needed.", "De telefoonbediening draait op deze tv. Geen computer nodig."), 16f, CREAM_2))
        address = text("", 22f, AMBER, true); left.addView(address)
        code = text("", 44f, CREAM, true); left.addView(code)
        top.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        qr = ImageView(this).apply { setBackgroundColor(Color.WHITE); setPadding(px(8), px(8), px(8), px(8)) }
        right.addView(qr, LinearLayout.LayoutParams(px(190), px(190)))
        qrNote = text(t("Scan to use your phone\nas the remote", "Scan om je telefoon als\nafstandsbediening te gebruiken"), 15f, CREAM, true).apply { gravity = Gravity.CENTER }
        right.addView(qrNote)
        top.addView(right, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(24) })
        col.addView(top)
        adb = text("", 16f, CREAM_2); col.addView(adb)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, px(16), 0, 0) }
        fun button(t: String, on: () -> Unit) = Button(this).apply {
            text = t; isAllCaps = false; textSize = 15f; setTextColor(CREAM)
            background = GradientDrawable().apply { setColor(FLAP); cornerRadius = px(6).toFloat() }
            setOnFocusChangeListener { v, has -> (v.background as GradientDrawable).setStroke(if (has) px(3) else 0, AMBER) }
            setOnClickListener { on() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = px(12) }
        }
        val connect = button(t("Connect ADB", "ADB verbinden")) { adb.text = t("Connecting. If the TV asks \"Allow debugging?\", tick Always allow and press OK.", "Verbinden. Vraagt de tv \"Foutopsporing toestaan?\", vink Altijd toestaan aan en druk op OK."); Thread { Adb.ensure(interactive = true); main.post { refresh() } }.start() }
        row.addView(connect)
        row.addView(button(t("New phone code", "Nieuwe telefooncode")) { Auth.newPin(); refresh() })
        row.addView(button(t("Developer options", "Opties voor ontwikkelaars")) { runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) } })
        homeBtn = button("") { App.setHomeEnabled(this, !App.homeEnabled(this)); FireHome.poke(); refresh() }
        row.addView(homeBtn)
        row.addView(button(t("Forget phones", "Telefoons vergeten")) { Auth.forgetAll(); refresh() })
        col.addView(row)
        if (nl) col.addView(text((if (android.os.Build.VERSION.SDK_INT >= 33) "Deze tv draait Android 13 of nieuwer, dus koppel via draadloze foutopsporing (ADB verbinden krijgt misschien nooit antwoord). " else "") +
            "Draadloze foutopsporing (Android 11 en nieuwer, tv op wifi): Opties voor ontwikkelaars > Draadloze foutopsporing > aan, sta dit netwerk toe, dan " +
            "Apparaat koppelen met koppelingscode, en typ die code op je telefoon op de instellingenpagina zolang de code op het scherm staat. " +
            "Let op: USB-foutopsporingsautorisaties intrekken staat vlak naast Draadloze foutopsporing; druk daar nooit op OK.", 14f, CREAM_2).apply { setPadding(0, px(18), 0, 0) })
        else col.addView(text((if (android.os.Build.VERSION.SDK_INT >= 33) "This TV runs Android 13 or newer, so use wireless debugging pairing (Connect ADB may never get an answer). " else "") +
            "Wireless debugging (Android 11 and newer, TV on Wi-Fi): Developer options > Wireless debugging > on, allow this network, then " +
            "Pair device with pairing code, and type that code on your phone at the setup page while the code stays on screen. " +
            "Careful: Revoke USB debugging authorizations sits right next to Wireless debugging; never press OK on it.", 14f, CREAM_2).apply { setPadding(0, px(18), 0, 0) })
        col.addView(text(t("Turning the TV on is not possible from the TV itself: once it sleeps, this app sleeps too. Use the TV's own remote.", "De tv aanzetten kan niet vanaf de tv zelf: slaapt hij, dan slaapt deze app ook. Gebruik de eigen afstandsbediening van de tv."), 14f, CREAM_2))
        setContentView(ScrollView(this).apply { setBackgroundColor(GROUND); addView(col) })
        connect.requestFocus()
        if (Auth.currentPin() == null) Auth.newPin()
    }

    override fun onResume() { super.onResume(); Auth.onPin(pinListener); main.post(tick) }
    override fun onPause() { super.onPause(); Auth.offPin(pinListener); main.removeCallbacks(tick) }
    override fun onNewIntent(i: Intent) { super.onNewIntent(i); refresh() }

    // Dutch when the TV home's language (Home settings) is Dutch, or, with none picked there, the TV's own
    private val nl: Boolean by lazy {
        val picked = runCatching { (Store.readJson("home-settings.json") as? org.json.JSONObject)?.optString("lang") }.getOrNull().orEmpty()
        picked.ifEmpty { java.util.Locale.getDefault().language }.lowercase().startsWith("nl")
    }
    private fun t(en: String, nlText: String) = if (nl) nlText else en

    private fun refresh() {
        val ip = App.lanAddress()
        address.text = if (ip != null) t("On your phone open", "Open op je telefoon") + "  http://$ip:${Store.port}\n" + t("Setup page", "Instellingenpagina") + "  http://$ip:${Store.port}/setup" else t("Not on a network yet", "Nog niet verbonden met een netwerk")
        val p = Auth.currentPin()
        code.text = if (p != null) t("Phone code", "Telefooncode") + "  ${p.take(3)} ${p.drop(3)}" else t("Phone code: press New phone code", "Telefooncode: druk op Nieuwe telefooncode")
        code.visibility = View.VISIBLE
        val link = if (ip != null) "http://$ip:${Store.port}/?pair=" + Auth.pairLink() else null
        if (link != qrFor) {
            qrFor = link
            qr.setImageBitmap(link?.let { qrBitmap(it, 360) })
            qr.visibility = if (link != null) View.VISIBLE else View.GONE
            qrNote.visibility = qr.visibility
        }
        val s = Adb.status()
        adb.text = "ADB: " + when {
            Adb.connected -> t("connected (${Adb.route}). Keys, typing and \"what's playing\" work.", "verbonden (${Adb.route}). Toetsen, typen en \"wat speelt er\" werken.")
            s["state"] == "waiting_for_allow" || s["state"] == "connecting" -> t("waiting. Accept \"Allow debugging?\" on the TV.", "wachten. Accepteer \"Foutopsporing toestaan?\" op de tv.")
            s["state"] == "needs_pairing" -> t("not set up. Pair wireless debugging from the phone setup page.", "niet ingesteld. Koppel draadloze foutopsporing via de instellingenpagina op je telefoon.")
            else -> t("off. Opening apps and volume still work; keys need ADB.", "uit. Apps openen en volume werken nog; toetsen hebben ADB nodig.") + (Adb.lastError?.let { "\n($it)" } ?: "")
        } + t("   Phones paired: ", "   Gekoppelde telefoons: ") + Auth.phoneCount()
        homeBtn.text = if (App.homeEnabled(this)) t("Stop being the home screen", "Niet meer als startscherm") else t("Use as home screen", "Als startscherm gebruiken")
    }

    private fun qrBitmap(text: String, size: Int): android.graphics.Bitmap? = runCatching {
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 1))
        val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) Color.BLACK else Color.WHITE }
        android.graphics.Bitmap.createBitmap(px, size, size, android.graphics.Bitmap.Config.ARGB_8888)
    }.getOrNull()

    companion object {
        private val GROUND = Color.parseColor("#0D1E19")
        private val FLAP = Color.parseColor("#131614")
        private val CREAM = Color.parseColor("#F2EAD8")
        private val CREAM_2 = Color.parseColor("#BDB6A7")
        private val AMBER = Color.parseColor("#FFB52E")
    }
}
