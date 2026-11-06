package com.example.screenai

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.InputFilter
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.util.Locale

private data class Preset(val name: String, val url: String, val model: String, val vision: Boolean)

class MainActivity : ComponentActivity() {
    private lateinit var keyField: EditText
    private lateinit var modelField: EditText
    private lateinit var urlField: EditText
    private lateinit var tavilyField: EditText
    private lateinit var btnTextField: EditText
    private lateinit var soundSwitch: Switch
    private lateinit var ttsSwitch: Switch
    private lateinit var visionSwitch: Switch
    private lateinit var fastSwitch: Switch
    private lateinit var sizeBar: SeekBar
    private lateinit var agentStepsBar: SeekBar
    private lateinit var agentConfirmSwitch: Switch
    private lateinit var rateBar: SeekBar
    private lateinit var volBar: SeekBar
    private lateinit var voiceChip: TextView
    private var selectedColor = 0xFF7C5CFF.toInt()
    private var currentVoice = ""
    private var voiceNames: List<String> = emptyList()
    private var tmpTts: TextToSpeech? = null

    private fun startOverlay(code: Int, data: Intent?) {
        val i = Intent(this, OverlayService::class.java).putExtra("fast", data != null)
        if (data != null) i.putExtra("code", code).putExtra("data", data)
        startForegroundService(i)
        moveTaskToBack(true)
    }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val data = r.data
            if (r.resultCode == RESULT_OK && data != null) startOverlay(r.resultCode, data)
        }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (fastSwitch.isChecked) {
                val mpm = getSystemService(MediaProjectionManager::class.java)
                projectionLauncher.launch(mpm.createScreenCaptureIntent())
            } else {
                startOverlay(0, null)
            }
        }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                val ok = saveAvatar(uri)
                Toast.makeText(this, if (ok) "Аватар сохранён" else "Не удалось загрузить фото", Toast.LENGTH_SHORT).show()
            }
        }

    private fun saveAvatar(uri: Uri): Boolean {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val src = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return false
            val side = minOf(src.width, src.height)
            val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
            val scaled = Bitmap.createScaledBitmap(square, 256, 256, true)
            val circle = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(circle)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            canvas.drawCircle(128f, 128f, 128f, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(scaled, 0f, 0f, paint)
            File(filesDir, "avatar.png").outputStream().use { circle.compress(Bitmap.CompressFormat.PNG, 100, it) }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, r: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
    }

    private fun label(t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun field(hintText: String, value: String) = EditText(this).apply {
        hint = hintText
        setText(value)
        setTextColor(Color.WHITE)
        setHintTextColor(0xFF77778A.toInt())
        background = rounded(0xFF1C1C28.toInt(), 14)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        setSingleLine(true)
    }

    private fun chip(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t
        setTextColor(0xFFCFC8FF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dp(14), dp(9), dp(14), dp(9))
        background = rounded(0xFF26234A.toInt(), 18)
        setOnClickListener { onClick() }
    }

    private fun toggle(t: String, on: Boolean) = Switch(this).apply {
        text = t
        isChecked = on
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    }

    private fun section(t: String) = label(t, 18f, Color.WHITE, true)
    private fun hintLabel(t: String) = label(t, 13f, 0xFF9A9AB0.toInt())

    private fun lp(top: Int) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun voiceLabel() = "🗣 Голос: " + (if (currentVoice.isEmpty()) "авто" else currentVoice)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("s", 0)
        selectedColor = prefs.getInt("color", 0xFF7C5CFF.toInt())
        currentVoice = prefs.getString("voiceName", "") ?: ""

        tmpTts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                voiceNames = tmpTts?.voices
                    ?.filter { it.locale.language == "ru" || it.locale.language == "en" }
                    ?.map { it.name }?.sorted() ?: emptyList()
            }
        }

        urlField = field("https://api.deepseek.com", prefs.getString("url", "https://openrouter.ai/api/v1") ?: "")
        keyField = field("API-ключ", prefs.getString("key", "") ?: "")
        keyField.transformationMethod = PasswordTransformationMethod.getInstance()
        tavilyField = field("tvly-...", prefs.getString("tavily", "") ?: "")
        tavilyField.transformationMethod = PasswordTransformationMethod.getInstance()
        modelField = field("Модель", prefs.getString("model", "google/gemma-4-31b-it:free") ?: "")
        btnTextField = field("AI", prefs.getString("btnText", "AI") ?: "AI").apply {
            filters = arrayOf(InputFilter.LengthFilter(4))
        }
        soundSwitch = toggle("Звуки", prefs.getBoolean("sound", true))
        ttsSwitch = toggle("Озвучивать ответы голосом", prefs.getBoolean("tts", false))
        visionSwitch = toggle("Модель понимает изображения", prefs.getBoolean("vision", true))
        fastSwitch = toggle("Быстрый режим экрана (разрешение один раз)", prefs.getBoolean("fast", false))
        agentStepsBar = SeekBar(this).apply { max = 12; progress = prefs.getInt("agentSteps", 8) - 3 }
        agentConfirmSwitch = toggle("Подтверждать каждое действие агента", prefs.getBoolean("agentConfirm", false))
        sizeBar = SeekBar(this).apply { max = 60; progress = prefs.getInt("size", 56) - 36 }
        rateBar = SeekBar(this).apply { max = 15; progress = ((prefs.getFloat("ttsRate", 1.0f) - 0.5f) * 10).toInt() }
        volBar = SeekBar(this).apply { max = 10; progress = (prefs.getFloat("ttsVol", 1.0f) * 10).toInt() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(64), dp(24), dp(24))
        }
        root.addView(label("Screen AI", 32f, Color.WHITE, true), lp(0))
        root.addView(label("ИИ-помощник, который видит экран, помнит диалог и отвечает голосом", 14f, 0xFF9A9AB0.toInt()), lp(6))

        // ===== ИИ-модели =====
        root.addView(section("ИИ-модель"), lp(28))
        root.addView(hintLabel("Провайдер (🆓 — есть бесплатный лимит)"), lp(8))
        val groq = "https://api.groq.com/openai/v1"
        val presets = listOf(
            Preset("🆓 OpenRouter", "https://openrouter.ai/api/v1", "google/gemma-4-31b-it:free", true),
            Preset("🆓 Groq", groq, "openai/gpt-oss-120b", false),
            Preset("🆓 Groq Llama 3.3", groq, "llama-3.3-70b-versatile", false),
            Preset("🆓 Groq 🌐 compound-mini", groq, "groq/compound-mini", false),
            Preset("🆓 Groq 🌐 compound", groq, "groq/compound", false),
            Preset("🆓 Groq 👁 Scout", groq, "meta-llama/llama-4-scout-17b-16e-instruct", true),
            Preset("🆓 Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-3.8-flash", true),
            Preset("DeepSeek", "https://api.deepseek.com", "deepseek-flash", false)
        )
        val providerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        presets.forEach { pr ->
            providerRow.addView(
                chip(pr.name) {
                    urlField.setText(pr.url)
                    modelField.setText(pr.model)
                    visionSwitch.isChecked = pr.vision
                },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(8) }
            )
        }
        val providerScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(providerRow)
        }
        root.addView(providerScroll, lp(8))
        root.addView(hintLabel("Адрес API"), lp(16))
        root.addView(urlField, lp(8))
        root.addView(hintLabel("API-ключ"), lp(16))
        root.addView(keyField, lp(8))
        root.addView(hintLabel("Модель"), lp(16))
        root.addView(modelField, lp(8))
        root.addView(visionSwitch, lp(14))
        root.addView(hintLabel("Если модель не понимает изображения, скриншот уходит ей как распознанный текст."), lp(4))

        // ===== Интернет =====
        root.addView(section("Интернет-поиск"), lp(32))
        root.addView(
            hintLabel("Сами модели Groq в интернет не ходят: они только генерируют текст. Есть два способа. 1) Выбери 🌐 compound или compound-mini: у них встроенный поиск Groq. 2) Вставь ключ Tavily (бесплатно, 1000 поисков в месяц, tavily.com) и включи кнопку «🌐 Интернет» в чате: поиск пойдёт с любой моделью, ответ будет со ссылками на источники."),
            lp(8)
        )
        root.addView(hintLabel("Ключ Tavily"), lp(14))
        root.addView(tavilyField, lp(8))

        // ===== Агент =====
        root.addView(section("Агент"), lp(32))
        root.addView(
            hintLabel("Агент сам составляет план, ищет в интернете, читает страницы и собирает итог. Все его действия видны в чате, остановить можно кнопкой «⏹». Он только читает информацию и ничего не меняет на телефоне."),
            lp(8)
        )
        root.addView(agentConfirmSwitch, lp(12))
        root.addView(hintLabel("Лимит действий агента за одну задачу (поиск и чтение страниц): от 3 до 15. Общий лимит времени 4 минуты."), lp(10))
        root.addView(agentStepsBar, lp(4))

        // ===== Экран =====
        root.addView(section("Распознавание экрана"), lp(32))
        root.addView(fastSwitch, lp(10))
        root.addView(
            hintLabel("Выключено: на каждый снимок Android спрашивает разрешение, ты видишь предпросмотр и сам нажимаешь «Отправить ИИ». Включено: разрешение один раз, быстрые кнопки сами читают экран."),
            lp(4)
        )

        // ===== Голос =====
        root.addView(section("Голос"), lp(32))
        root.addView(soundSwitch, lp(10))
        root.addView(ttsSwitch, lp(10))
        voiceChip = chip(voiceLabel()) {
            val list = listOf("") + voiceNames
            val idx = (list.indexOf(currentVoice) + 1) % list.size
            currentVoice = list[idx]
            voiceChip.text = voiceLabel()
        }
        val voiceRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        voiceRow.addView(voiceChip, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(8) })
        voiceRow.addView(chip("▶ Проверить") {
            val t = tmpTts
            if (t != null) {
                val v = t.voices?.firstOrNull { it.name == currentVoice }
                if (v != null) t.setVoice(v) else t.setLanguage(Locale("ru", "RU"))
                t.setSpeechRate(0.5f + rateBar.progress / 10f)
                val b = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volBar.progress / 10f) }
                t.speak("Привет! Я ScreenAI.", TextToSpeech.QUEUE_FLUSH, b, "test")
            }
        })
        root.addView(voiceRow, lp(10))
        root.addView(hintLabel("Скорость озвучки"), lp(14))
        root.addView(rateBar, lp(4))
        root.addView(hintLabel("Громкость озвучки"), lp(10))
        root.addView(volBar, lp(4))

        // ===== Внешний вид =====
        root.addView(section("Внешний вид кнопки"), lp(32))
        val avatarRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        avatarRow.addView(
            chip("🖼 Выбрать фото") { pickImage.launch("image/*") },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(8) }
        )
        avatarRow.addView(chip("↺ Убрать фото") {
            File(filesDir, "avatar.png").delete()
            Toast.makeText(this, "Фото убрано", Toast.LENGTH_SHORT).show()
        })
        root.addView(avatarRow, lp(10))
        root.addView(hintLabel("Надпись или эмодзи на кнопке (если без фото)"), lp(14))
        root.addView(btnTextField, lp(8))
        root.addView(hintLabel("Цвет кнопки"), lp(14))
        val colors = listOf(
            0xFF7C5CFF.toInt(), 0xFF2F80ED.toInt(), 0xFF27AE60.toInt(),
            0xFFEB5757.toInt(), 0xFFF2994A.toInt(), 0xFFE056A0.toInt()
        )
        val dots = mutableListOf<TextView>()
        val colorRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        colors.forEach { col ->
            val d = TextView(this).apply {
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                background = GradientDrawable().apply {
                    setShape(GradientDrawable.OVAL)
                    setColor(col)
                }
                text = if (col == selectedColor) "✓" else ""
            }
            d.setOnClickListener {
                selectedColor = col
                dots.forEachIndexed { i, v -> v.text = if (colors[i] == selectedColor) "✓" else "" }
            }
            dots.add(d)
            colorRow.addView(d, LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(10) })
        }
        root.addView(colorRow, lp(8))
        root.addView(hintLabel("Размер кнопки"), lp(16))
        root.addView(sizeBar, lp(4))

        val start = TextView(this).apply {
            text = "Запустить"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFF7C5CFF.toInt(), 0xFF4F8CFF.toInt())
            ).apply { cornerRadius = dp(16).toFloat() }
            setPadding(0, dp(16), 0, dp(16))
        }
        root.addView(start, lp(36))
        root.addView(
            label("После запуска появится кнопка поверх экрана. Нажми её, чтобы открыть чат. Кнопку можно перетаскивать.", 13f, 0xFF77778A.toInt()),
            lp(20)
        )

        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF0E0E16.toInt())
            isFillViewport = true
            addView(root)
        }
        setContentView(scroll)

        start.setOnClickListener {
            prefs.edit()
                .putString("url", urlField.text.toString().trim())
                .putString("key", keyField.text.toString().trim())
                .putString("tavily", tavilyField.text.toString().trim())
                .putInt("agentSteps", agentStepsBar.progress + 3)
                .putBoolean("agentConfirm", agentConfirmSwitch.isChecked)
                .putString("model", modelField.text.toString().trim())
                .putString("btnText", btnTextField.text.toString().trim().ifEmpty { "AI" })
                .putInt("color", selectedColor)
                .putInt("size", sizeBar.progress + 36)
                .putBoolean("sound", soundSwitch.isChecked)
                .putBoolean("tts", ttsSwitch.isChecked)
                .putBoolean("vision", visionSwitch.isChecked)
                .putBoolean("fast", fastSwitch.isChecked)
                .putFloat("ttsRate", 0.5f + rateBar.progress / 10f)
                .putFloat("ttsVol", volBar.progress / 10f)
                .putString("voiceName", currentVoice)
                .apply()
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Разреши показ поверх других окон и нажми снова", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
            }
        }
    }

    override fun onDestroy() {
        tmpTts?.shutdown()
        super.onDestroy()
    }
}
