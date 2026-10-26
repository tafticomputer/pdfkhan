package com.pdfkhan.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.pdfkhan.app.databinding.ActivityMainBinding
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), TtsReader.Callback {

    private enum class State { IDLE, READING, PAUSED }

    private lateinit var b: ActivityMainBinding
    private lateinit var systemReader: TtsReader   // صداهای نصب‌شده روی گوشی
    private lateinit var builtinReader: PiperReader // صداهای داخل برنامه
    private lateinit var onlineReader: OnlineReader  // صداهای آنلاین هوش مصنوعی
    private val ui = Handler(Looper.getMainLooper())

    private var state = State.IDLE
    private var loading = false
    private var buildToken = 0

    private var currentUri: Uri? = null
    private var rawPages: List<String> = emptyList()
    private var ocrPageCount = 0
    private var ocrModeIndex = 1      // 0 سریع، 1 متعادل، 2 دقیق
    private var ocrEngineIndex = 0    // 0 خودکار، 1..4 تعداد موتور
    private var ocrStartMs = 0L
    private var ocrMillis = 0L
    private var ocrWorkers = 0
    private var ocrUnavailable = false
    private var autoReversed = false
    private var ignoreReverseEvent = false
    private var pages: List<String> = emptyList()
    private var chunks: List<Chunk> = emptyList()

    private var systemReady = false
    private var voices: List<VoiceOption> = emptyList()
    private var selectedVoice: VoiceOption? = null
    private var noVoiceDialogShown = false

    private var resumeIndex = 0

    private lateinit var downloader: VoiceDownloader
    private var downloading = false
    private var saving = false
    private val downloadCancel = AtomicBoolean(false)

    private var currentName = "PDF"

    private val createPdf =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            if (uri != null) savePdf(uri)
        }

    private val pickPdf =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) loadPdf(uri)
        }

    // ───────────────────────── چرخه‌ی عمر ─────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        downloader = VoiceDownloader(this)
        builtinReader = PiperReader(this, this)
        onlineReader = OnlineReader(this, this)
        systemReader = TtsReader(this, this)

        b.btnPick.setOnClickListener { pickPdf.launch(arrayOf("application/pdf")) }
        b.btnVoiceSettings.setOnClickListener { openTtsSettings() }
        b.btnOnline.setOnClickListener { showOnlineDialog() }
        b.btnSavePdf.setOnClickListener {
            if (pages.none { it.isNotBlank() }) {
                Toast.makeText(this, R.string.pdf_nothing, Toast.LENGTH_SHORT).show()
            } else {
                val base = currentName.removeSuffix(".pdf").removeSuffix(".PDF")
                createPdf.launch(getString(R.string.pdf_file_suffix, base))
            }
        }
        b.btnPlay.setOnClickListener { onPlayClicked() }
        b.btnStop.setOnClickListener {
            if (downloading) {
                downloadCancel.set(true)
            } else {
                stopReading(getString(R.string.stopped))
            }
        }

        b.sbSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                updateSpeedLabel()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {
                systemReader.setRate(currentRate())
                builtinReader.setRate(currentRate())
                onlineReader.setRate(currentRate())
            }
        })

        b.spVoice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                val voice = voices.getOrNull(position) ?: return
                if (voice.key == selectedVoice?.key) return
                selectedVoice = voice
                if (state == State.READING) startReading(resumeIndex)
                updateButtons()
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        b.btnPron.setOnClickListener { showPronDialog() }

        b.cbReverse.setOnCheckedChangeListener { _, _ ->
            if (ignoreReverseEvent) return@setOnCheckedChangeListener
            autoReversed = false
            if (rawPages.isNotEmpty()) {
                stopReading(null)
                rebuildAsync()
            }
        }

        // تغییر OCR یعنی باید فایل دوباره خوانده شود
        b.cbOcr.setOnCheckedChangeListener { _, _ ->
            val uri = currentUri
            if (uri != null && !loading) loadPdf(uri)
        }

        setupOcrSpinners()

        updateSpeedLabel()
        reloadVoices()
        updateButtons()
    }

    override fun onResume() {
        super.onResume()
        // کاربر ممکن است از تنظیمات، صدای فارسی نصب کرده باشد
        if (systemReady && voices.none { it is VoiceOption.System }) reloadVoices()
    }

    override fun onDestroy() {
        systemReader.shutdown()
        builtinReader.shutdown()
        onlineReader.shutdown()
        super.onDestroy()
    }

    // ───────────────────────── PlaybackCallback / TtsReader.Callback ─────────────────────────

    override fun onInit(ok: Boolean) {
        systemReady = ok
        if (!ok && voices.isEmpty()) {
            b.tvStatus.text = getString(R.string.tts_init_failed)
        }
        reloadVoices()
    }

    override fun onChunkStarted(index: Int) {
        val chunk = chunks.getOrNull(index) ?: return
        resumeIndex = index
        b.progressBar.progress = index + 1
        b.tvStatus.text = getString(R.string.reading_format, chunk.page + 1, pages.size)
        showChunk(chunk)
    }

    override fun onFinished() {
        state = State.IDLE
        resumeIndex = 0
        allowScreenOff()
        b.tvStatus.text = getString(R.string.finished)
        updateButtons()
    }

    override fun onError(message: String) {
        state = State.IDLE
        allowScreenOff()
        b.tvStatus.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        updateButtons()
    }

    // ───────────────────────── صداها ─────────────────────────

    private fun reloadVoices() {
        val list = ArrayList<VoiceOption>()

        // ۱) صداهای داخلی برنامه (اگر دانلود نشده باشند، با اولین پخش دانلود می‌شوند)
        for (entry in VoiceCatalog.entries) {
            val installed = downloader.isInstalled(entry.dir)
            val label = if (installed) {
                getString(R.string.voice_builtin_format, entry.displayName)
            } else {
                getString(R.string.voice_builtin_download_format, entry.displayName, entry.sizeMb)
            }
            list.add(VoiceOption.Builtin(entry, installed, label))
        }

        // ۲) صداهای فارسی نصب‌شده روی گوشی
        if (systemReady) {
            systemReader.persianVoices().forEachIndexed { i, v ->
                var label = getString(R.string.voice_system_format, i + 1, v.name)
                if (v.isNetworkConnectionRequired) label += getString(R.string.voice_online_suffix)
                list.add(VoiceOption.System(v, label))
            }
        }

        // ۳) صداهای آنلاین هوش مصنوعی
        for (v in OnlineCatalog.voices) {
            list.add(
                VoiceOption.Online(
                    v,
                    getString(R.string.voice_online_ai_format, v.displayName, v.service.title)
                )
            )
        }

        voices = list

        if (list.isEmpty()) {
            selectedVoice = null
            b.spVoice.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                listOf(getString(R.string.voice_none))
            )
            if (systemReady && !noVoiceDialogShown) {
                noVoiceDialogShown = true
                showNoVoiceDialog()
            }
            updateButtons()
            return
        }

        // پیش‌فرض: اولین صدای آماده (داخلی نصب‌شده، وگرنه صدای گوشی)
        val previous = selectedVoice?.key
        val index = list.indexOfFirst { it.key == previous }.let { found ->
            when {
                found >= 0 -> found
                else -> list.indexOfFirst { it is VoiceOption.Builtin && it.installed }
                    .takeIf { it >= 0 }
                    ?: list.indexOfFirst { it is VoiceOption.System }.takeIf { it >= 0 }
                    ?: 0
            }
        }
        selectedVoice = list[index]

        b.spVoice.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            list.map { it.label }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        b.spVoice.setSelection(index)
        updateButtons()
    }

    private fun showNoVoiceDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.no_voice_title)
            .setMessage(R.string.no_voice_message)
            .setPositiveButton(R.string.open_settings) { _, _ -> openTtsSettings() }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun openTtsSettings() {
        val candidates = listOf(
            Intent("com.android.settings.TTS_SETTINGS"),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in candidates) {
            try {
                startActivity(intent)
                return
            } catch (e: Exception) {
                // سراغ گزینه‌ی بعدی می‌رویم
            }
        }
    }

    // ───────────────────────── تنظیمات OCR ─────────────────────────

    private fun setupOcrSpinners() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        ocrModeIndex = prefs.getInt(PREF_OCR_MODE, 1).coerceIn(0, 2)
        ocrEngineIndex = prefs.getInt(PREF_OCR_ENGINES, 0).coerceIn(0, 4)

        val modeLabels = listOf(R.string.ocr_mode_fast, R.string.ocr_mode_balanced, R.string.ocr_mode_accurate)
            .map { getString(it) }
        b.spOcrMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modeLabels)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        b.spOcrMode.setSelection(ocrModeIndex)
        b.spOcrMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (position == ocrModeIndex) return
                ocrModeIndex = position
                prefs.edit().putInt(PREF_OCR_MODE, position).apply()
                // کیفیت فقط وقتی اثر دارد که فایلی با OCR خوانده شده باشد
                val uri = currentUri
                if (uri != null && !loading && ocrPageCount > 0) loadPdf(uri)
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val engineLabels = listOf(getString(R.string.ocr_engines_auto)) +
            (1..4).map { getString(R.string.ocr_engines_n, it) }
        b.spOcrEngines.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, engineLabels)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        b.spOcrEngines.setSelection(ocrEngineIndex)
        b.spOcrEngines.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (position == ocrEngineIndex) return
                ocrEngineIndex = position
                prefs.edit().putInt(PREF_OCR_ENGINES, position).apply() // فقط روی سرعت اثر دارد، نه روی نتیجه
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun formatDuration(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0)
        return if (s < 60) {
            getString(R.string.dur_seconds, s.toInt())
        } else {
            getString(R.string.dur_minutes, (s / 60).toInt(), (s % 60).toInt())
        }
    }

    // ───────────────────────── بارگذاری PDF ─────────────────────────

    private fun loadPdf(uri: Uri) {
        stopReading(null)
        currentUri = uri
        rawPages = emptyList()
        pages = emptyList()
        chunks = emptyList()
        ocrPageCount = 0
        ocrMillis = 0L
        ocrWorkers = 0
        ocrStartMs = 0L
        ocrUnavailable = false
        autoReversed = false
        loading = true
        currentName = displayName(uri)
        b.tvFileName.text = currentName
        b.tvContent.text = ""
        b.progressBar.progress = 0
        b.tvStatus.text = getString(R.string.loading)
        updateButtons()

        val forceOcr = b.cbOcr.isChecked
        val quality = OcrQuality.values()[ocrModeIndex]
        val engineCount = ocrEngineIndex

        thread {
            try {
                val result = PdfExtractor.extract(applicationContext, uri, forceOcr, quality, engineCount) { done, total, ocr ->
                    ui.post {
                        b.tvStatus.text = if (ocr) {
                            if (ocrStartMs == 0L) ocrStartMs = System.currentTimeMillis()
                            if (done > 0 && done < total) {
                                val elapsed = (System.currentTimeMillis() - ocrStartMs) / 1000.0
                                val remaining = (elapsed / done * (total - done)).toLong()
                                getString(R.string.ocr_progress_eta, done, total, formatDuration(remaining))
                            } else {
                                getString(R.string.ocr_progress, done, total)
                            }
                        } else {
                            getString(R.string.loading_progress, done, total)
                        }
                    }
                }
                ui.post {
                    rawPages = result.pages
                    ocrPageCount = result.ocrPages
                    ocrMillis = result.ocrMillis
                    ocrWorkers = result.workers
                    ocrUnavailable = result.ocrUnavailable

                    // اگر حروف هر خط برعکس استخراج شده باشد، خودکار اصلاح می‌شود
                    val reversed = TextPrep.looksReversed(result.pages)
                    autoReversed = reversed
                    ignoreReverseEvent = true
                    b.cbReverse.isChecked = reversed
                    ignoreReverseEvent = false

                    rebuildAsync()
                }
            } catch (t: Throwable) {
                ui.post {
                    loading = false
                    b.tvStatus.text = getString(R.string.load_error)
                    updateButtons()
                }
            }
        }
    }

    /** پاک‌سازی متن و تقسیم به بخش‌ها (روی نخ پس‌زمینه). */
    private fun rebuildAsync() {
        val token = ++buildToken
        val raw = rawPages
        val reverse = b.cbReverse.isChecked
        val maxLen = minOf(TextToSpeech.getMaxSpeechInputLength() - 50, 400).coerceAtLeast(50)
        val rules = TextPrep.compileRules(
            getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_PRON, "") ?: ""
        )

        loading = true
        updateButtons()

        thread {
            val cleaned = raw.map { TextPrep.clean(it, reverse) }
            val list = ArrayList<Chunk>()
            cleaned.forEachIndexed { index, text -> list.addAll(TextPrep.split(index, text, maxLen, rules)) }

            ui.post {
                if (token != buildToken) return@post
                pages = cleaned
                chunks = list
                resumeIndex = 0
                loading = false

                b.tvTotalPages.text = getString(R.string.total_pages_format, pages.size)
                b.etStartPage.setText("1")
                b.progressBar.max = maxOf(chunks.size, 1)
                b.progressBar.progress = 0
                b.tvContent.text = pages.firstOrNull().orEmpty()

                var status = when {
                    chunks.isEmpty() -> getString(R.string.no_text)
                    ocrUnavailable -> getString(R.string.ocr_unavailable)
                    ocrPageCount > 0 -> {
                        val perPage = String.format(Locale.US, "%.1f", ocrMillis / 1000.0 / ocrPageCount)
                        getString(R.string.loaded_ocr_format, pages.size, chunks.size, ocrPageCount) + "\n" +
                            getString(
                                R.string.ocr_timing,
                                formatDuration(ocrMillis / 1000),
                                perPage,
                                ocrWorkers
                            )
                    }
                    else -> getString(R.string.loaded_format, pages.size, chunks.size)
                }
                if (autoReversed && reverse && chunks.isNotEmpty()) {
                    status += "\n" + getString(R.string.auto_reversed_note)
                }
                b.tvStatus.text = status
                updateButtons()
            }
        }
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val name = cursor.getString(0)
                        if (!name.isNullOrBlank()) return name
                    }
                }
        } catch (e: Exception) {
            // نام فایل مهم نیست
        }
        return uri.lastPathSegment ?: "PDF"
    }

    // ───────────────────────── پخش ─────────────────────────

    private fun onPlayClicked() {
        when (state) {
            State.READING -> pauseReading()
            State.PAUSED -> startReading(resumeIndex)
            State.IDLE -> {
                if (chunks.isEmpty()) {
                    Toast.makeText(this, R.string.choose_file_first, Toast.LENGTH_SHORT).show()
                    return
                }
                val page = (b.etStartPage.text.toString().toIntOrNull() ?: 1)
                    .coerceIn(1, maxOf(pages.size, 1))
                val index = chunks.indexOfFirst { it.page >= page - 1 }.let { if (it < 0) 0 else it }
                startReading(index)
            }
        }
    }

    private fun startReading(index: Int) {
        val voice = selectedVoice
        if (voice == null) {
            showNoVoiceDialog()
            return
        }

        if (voice is VoiceOption.Builtin && !voice.installed) {
            confirmDownload(voice.entry)
            return
        }

        // هر بار فقط یکی از دو موتور فعال است
        systemReader.stop()
        builtinReader.stop()
        onlineReader.stop()

        val started = when (voice) {
            is VoiceOption.Builtin -> {
                b.tvStatus.text = getString(R.string.preparing_voice)
                builtinReader.start(chunks, index, voice.entry.dir, currentRate())
            }
            is VoiceOption.Online -> {
                val creds = OnlineCredentials.load(getSharedPreferences(PREFS, MODE_PRIVATE))
                if (!creds.hasKey(voice.voice.service)) {
                    showOnlineDialog()
                    false
                } else {
                    b.tvStatus.text = getString(R.string.online_connecting)
                    onlineReader.start(chunks, index, voice.voice, creds, currentRate())
                }
            }
            is VoiceOption.System -> systemReader.start(chunks, index, voice.voice, currentRate())
        }

        if (started) {
            state = State.READING
            keepScreenOn()
        } else {
            state = State.IDLE
            allowScreenOff()
        }
        updateButtons()
    }

    private fun pauseReading() {
        systemReader.pause()
        builtinReader.stop()
        onlineReader.stop()
        state = State.PAUSED
        allowScreenOff()
        val page = chunks.getOrNull(resumeIndex)?.page ?: 0
        b.tvStatus.text = getString(R.string.paused_format, page + 1, pages.size)
        updateButtons()
    }

    private fun stopReading(statusText: String?) {
        systemReader.stop()
        builtinReader.stop()
        onlineReader.stop()
        state = State.IDLE
        resumeIndex = 0
        allowScreenOff()
        b.progressBar.progress = 0
        if (statusText != null) b.tvStatus.text = statusText
        updateButtons()
    }

    // ───────────────────────── ذخیره‌ی متن به صورت PDF ─────────────────────────

    private fun savePdf(uri: Uri) {
        if (saving) return
        saving = true
        b.tvStatus.text = getString(R.string.pdf_saving)
        updateButtons()
        val snapshot = pages

        thread {
            try {
                val count = PdfWriter.write(applicationContext, uri, snapshot)
                ui.post {
                    saving = false
                    b.tvStatus.text = getString(R.string.pdf_saved, count)
                    updateButtons()
                }
            } catch (t: Throwable) {
                ui.post {
                    saving = false
                    b.tvStatus.text = getString(R.string.pdf_save_failed)
                    updateButtons()
                }
            }
        }
    }

    // ───────────────────────── کلید صدای آنلاین ─────────────────────────

    private fun showOnlineDialog() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val creds = OnlineCredentials.load(prefs)
        val pad = (16 * resources.displayMetrics.density).toInt()

        fun field(hintRes: Int, value: String) = EditText(this).apply {
            hint = getString(hintRes)
            setText(value)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.START
        }

        val azureKey = field(R.string.hint_azure_key, creds.azureKey)
        val azureRegion = field(R.string.hint_azure_region, creds.azureRegion)
        val geminiKey = field(R.string.hint_gemini_key, creds.geminiKey)
        val geminiModel = field(R.string.hint_gemini_model, creds.geminiModel)
        val openaiKey = field(R.string.hint_openai_key, creds.openaiKey)
        val openaiModel = field(R.string.hint_openai_model, creds.openaiModel)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(azureKey)
            addView(azureRegion)
            addView(geminiKey)
            addView(geminiModel)
            addView(openaiKey)
            addView(openaiModel)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.online_title)
            .setMessage(R.string.online_message)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                prefs.edit()
                    .putString(OnlineCredentials.K_AZURE_KEY, azureKey.text.toString().trim())
                    .putString(OnlineCredentials.K_AZURE_REGION, azureRegion.text.toString().trim().lowercase(Locale.US))
                    .putString(OnlineCredentials.K_GEMINI_KEY, geminiKey.text.toString().trim())
                    .putString(OnlineCredentials.K_GEMINI_MODEL, geminiModel.text.toString().trim())
                    .putString(OnlineCredentials.K_OPENAI_KEY, openaiKey.text.toString().trim())
                    .putString(OnlineCredentials.K_OPENAI_MODEL, openaiModel.text.toString().trim())
                    .apply()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ───────────────────────── اصلاح تلفظ ─────────────────────────

    private fun showPronDialog() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val edit = EditText(this).apply {
            setText(prefs.getString(PREF_PRON, ""))
            hint = getString(R.string.pron_hint)
            minLines = 6
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            textDirection = View.TEXT_DIRECTION_ANY_RTL
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(edit)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.pron_title)
            .setMessage(R.string.pron_message)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                prefs.edit().putString(PREF_PRON, edit.text.toString()).apply()
                if (rawPages.isNotEmpty()) {
                    stopReading(null)
                    rebuildAsync()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ───────────────────────── دانلود صدا ─────────────────────────

    private fun confirmDownload(entry: VoiceCatalog.Entry) {
        AlertDialog.Builder(this)
            .setTitle(R.string.download_title)
            .setMessage(getString(R.string.download_message, entry.displayName, entry.sizeMb))
            .setPositiveButton(R.string.download) { _, _ -> startDownload(entry) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startDownload(entry: VoiceCatalog.Entry) {
        if (downloading) return
        downloading = true
        downloadCancel.set(false)
        b.progressBar.max = 100
        b.progressBar.progress = 0
        b.tvStatus.text = getString(R.string.download_starting)
        updateButtons()

        thread {
            try {
                val ok = downloader.install(entry, downloadCancel) { phase, done, total ->
                    ui.post {
                        if (!downloading) return@post
                        if (phase == VoiceDownloader.Phase.DOWNLOAD) {
                            val mbDone = (done / MB).toInt()
                            if (total > 0) {
                                b.progressBar.progress = (done * 100 / total).toInt()
                                b.tvStatus.text = getString(
                                    R.string.download_progress, mbDone, (total / MB).toInt()
                                )
                            } else {
                                b.tvStatus.text = getString(R.string.download_progress_unknown, mbDone)
                            }
                        } else {
                            if (total > 0) b.progressBar.progress = (done * 100 / total).toInt()
                            b.tvStatus.text = getString(R.string.extracting_voice)
                        }
                    }
                }
                ui.post { finishDownload(ok, null) }
            } catch (t: Throwable) {
                ui.post { finishDownload(false, t) }
            }
        }
    }

    private fun finishDownload(ok: Boolean, error: Throwable?) {
        downloading = false
        b.progressBar.max = maxOf(chunks.size, 1)
        b.progressBar.progress = 0
        b.tvStatus.text = when {
            ok -> getString(R.string.voice_ready)
            error != null -> getString(R.string.download_failed)
            else -> getString(R.string.download_cancelled)
        }
        reloadVoices()
    }

    // ───────────────────────── نمایش ─────────────────────────

    private fun showChunk(chunk: Chunk) {
        val text = pages.getOrNull(chunk.page) ?: return
        val spannable = SpannableString(text)
        spannable.setSpan(
            BackgroundColorSpan(0x66FFD54F),
            chunk.start.coerceAtMost(text.length),
            chunk.end.coerceAtMost(text.length),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        b.tvContent.text = spannable
        b.tvContent.post {
            val layout = b.tvContent.layout ?: return@post
            val line = layout.getLineForOffset(chunk.start.coerceAtMost(text.length))
            val y = b.tvContent.top + layout.getLineTop(line)
            b.scrollContent.smoothScrollTo(0, (y - b.scrollContent.height / 4).coerceAtLeast(0))
        }
    }

    private fun updateButtons() {
        b.btnPick.isEnabled = !loading && !downloading
        b.cbOcr.isEnabled = !loading && !downloading
        b.spOcrMode.isEnabled = !loading && !downloading
        b.spOcrEngines.isEnabled = !loading && !downloading
        b.btnPron.isEnabled = !loading
        b.btnSavePdf.isEnabled = !loading && !downloading && !saving && pages.any { it.isNotBlank() }
        b.btnPlay.isEnabled = !loading && !downloading && chunks.isNotEmpty() && selectedVoice != null
        b.btnStop.isEnabled = state != State.IDLE || downloading
        b.btnPlay.setText(
            when (state) {
                State.READING -> R.string.pause
                State.PAUSED -> R.string.resume
                State.IDLE -> R.string.play
            }
        )
    }

    private fun currentRate(): Float = 0.5f + b.sbSpeed.progress / 100f

    private fun updateSpeedLabel() {
        b.tvSpeed.text = getString(R.string.speed_format, String.format(Locale.US, "%.1f", currentRate()))
    }

    private companion object {
        const val MB = 1024L * 1024L
        const val PREFS = "pdfkhan"
        const val PREF_PRON = "pronunciations"
        const val PREF_OCR_MODE = "ocr_mode"
        const val PREF_OCR_ENGINES = "ocr_engines"
    }

    private fun keepScreenOn() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun allowScreenOff() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
