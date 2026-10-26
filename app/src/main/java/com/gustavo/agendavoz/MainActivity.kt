package com.gustavo.agendavoz

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.TextStyle

class MainActivity : Activity() {

    // colores
    private val cPrimary = Color.parseColor("#1E3A5F")
    private val cAccent = Color.parseColor("#2E7D32")
    private val cBg = Color.parseColor("#F3F5F8")
    private val cCard = Color.WHITE
    private val cText = Color.parseColor("#1B1B1B")
    private val cMuted = Color.parseColor("#8A8F98")

    private lateinit var content: LinearLayout
    private val tabs = mutableListOf<TextView>()
    private var tab = 0
    private var month: YearMonth = YearMonth.now()
    private var selected: LocalDate = LocalDate.now()
    private var settingsTts: TextToSpeech? = null

    private val reminderValues = listOf(0, 5, 10, 15, 30, 60, 120)
    private val reminderLabels = listOf(
        "Sin aviso previo", "5 minutos antes", "10 minutos antes", "15 minutos antes",
        "30 minutos antes", "1 hora antes", "2 horas antes"
    )
    private val repeatLabels = listOf("No se repite", "Todos los días", "Algunos días de la semana", "Todos los meses")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = cPrimary
        Notifs.ensureChannel(this)
        buildUi()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        Scheduler.rescheduleAll(this)
    }

    override fun onResume() {
        super.onResume()
        if (tab == 0) selected = LocalDate.now()
        render()
        WidgetProvider.updateAll(this)
    }

    override fun onDestroy() {
        settingsTts?.shutdown()
        super.onDestroy()
    }

    // ---------------- helpers de UI ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int = 14): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    private fun label(text: String, size: Float = 16f, color: Int = cText, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun bigButton(text: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 17f
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = rounded(color)
        setPadding(dp(12), dp(14), dp(12), dp(14))
        setOnClickListener { onClick() }
    }

    private fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight)

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
        }

        // barra superior
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(cPrimary)
            setPadding(dp(16), dp(12), dp(8), dp(12))
        }
        top.addView(label("Agenda Voz", 22f, Color.WHITE, true), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val mic = label("🎤", 26f).apply { setPadding(dp(12), 0, dp(12), 0); setOnClickListener { startDictation() } }
        val gear = label("⚙️", 24f).apply { setPadding(dp(12), 0, dp(8), 0); setOnClickListener { showSettings() } }
        top.addView(mic)
        top.addView(gear)
        root.addView(top, lp())

        // contenido
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(24))
        }
        scroll.addView(content)
        root.addView(scroll, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // pestañas
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            elevation = dp(8).toFloat()
        }
        listOf("Hoy", "Semana", "Mes").forEachIndexed { i, name ->
            val t = label(name, 16f, cMuted, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, dp(16))
                setOnClickListener {
                    tab = i
                    if (i == 0) selected = LocalDate.now()
                    if (i == 2) { month = YearMonth.from(selected) }
                    render()
                }
            }
            tabs.add(t)
            bar.addView(t, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(bar, lp())
        setContentView(root)
    }

    private fun render() {
        tabs.forEachIndexed { i, t -> t.setTextColor(if (i == tab) cPrimary else cMuted) }
        content.removeAllViews()
        val all = TaskStore.load(this)
        when (tab) {
            0 -> renderToday(all)
            1 -> renderWeek(all)
            else -> renderMonth(all)
        }
    }

    // ---------------- vistas ----------------

    private fun renderToday(all: List<Task>) {
        val today = LocalDate.now()
        content.addView(label(Speech.dayName(today), 20f, cText, true), lp())
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(bigButton("🔊  Escuchar mi día", cAccent) { SpeakService.readDay(this) },
            lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f).apply { rightMargin = dp(8) })
        row.addView(bigButton("+  Agregar", cPrimary) { showTaskDialog(null, today) },
            lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(row, lp().apply { topMargin = dp(12); bottomMargin = dp(12) })
        addDayList(all, today, showEmpty = true)
    }

    private fun renderWeek(all: List<Task>) {
        val today = LocalDate.now()
        for (i in 0..6) {
            val d = today.plusDays(i.toLong())
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val name = if (i == 0) "Hoy" else if (i == 1) "Mañana" else Speech.dayName(d)
            header.addView(label(name, 17f, cPrimary, true), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            header.addView(label("+ Agregar", 15f, cAccent, true).apply {
                setPadding(dp(10), dp(6), dp(4), dp(6))
                setOnClickListener { showTaskDialog(null, d) }
            })
            content.addView(header, lp().apply { topMargin = dp(if (i == 0) 0 else 14) })
            addDayList(all, d, showEmpty = false)
        }
    }

    private fun renderMonth(all: List<Task>) {
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nav.addView(label("◀", 22f, cPrimary, true).apply {
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setOnClickListener { month = month.minusMonths(1); render() }
        })
        val title = month.month.getDisplayName(TextStyle.FULL, Speech.ES).replaceFirstChar { it.uppercase() } + " " + month.year
        nav.addView(label(title, 19f, cText, true).apply { gravity = Gravity.CENTER }, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        nav.addView(label("▶", 22f, cPrimary, true).apply {
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setOnClickListener { month = month.plusMonths(1); render() }
        })
        content.addView(nav, lp())

        val grid = GridLayout(this).apply { columnCount = 7 }
        val cellW = (resources.displayMetrics.widthPixels - dp(28)) / 7
        for (h in listOf("L", "M", "M", "J", "V", "S", "D")) {
            grid.addView(label(h, 13f, cMuted, true).apply {
                gravity = Gravity.CENTER
                layoutParams = GridLayout.LayoutParams().apply { width = cellW; height = dp(28) }
            })
        }
        val first = month.atDay(1)
        val offset = first.dayOfWeek.value - 1
        repeat(offset) {
            grid.addView(View(this).apply { layoutParams = GridLayout.LayoutParams().apply { width = cellW; height = dp(48) } })
        }
        val today = LocalDate.now()
        for (day in 1..month.lengthOfMonth()) {
            val d = month.atDay(day)
            val tasks = all.filter { it.occursOn(d) }
            val pending = tasks.any { !it.isDone(d) }
            val mark = if (tasks.isEmpty()) "" else if (pending) "\n●" else "\n✓"
            val cell = TextView(this).apply {
                text = "$day$mark"
                textSize = 15f
                gravity = Gravity.CENTER
                setLineSpacing(0f, 0.85f)
                val isSel = d == selected
                setTextColor(if (isSel) Color.WHITE else if (d == today) cAccent else cText)
                if (d == today && !isSel) typeface = Typeface.DEFAULT_BOLD
                if (isSel) background = rounded(cPrimary, 24)
                setOnClickListener { selected = d; render() }
                layoutParams = GridLayout.LayoutParams().apply { width = cellW; height = dp(48) }
            }
            grid.addView(cell)
        }
        content.addView(grid, lp().apply { topMargin = dp(8) })

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(label(Speech.dayName(selected), 17f, cPrimary, true), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(label("+ Agregar", 15f, cAccent, true).apply {
            setPadding(dp(10), dp(6), dp(4), dp(6))
            setOnClickListener { showTaskDialog(null, selected) }
        })
        content.addView(header, lp().apply { topMargin = dp(16) })
        addDayList(all, selected, showEmpty = true)
    }

    private fun addDayList(all: List<Task>, d: LocalDate, showEmpty: Boolean) {
        val list = TaskStore.tasksOn(this, d, all)
        if (list.isEmpty()) {
            if (showEmpty) content.addView(label("No hay tareas. Tocá \"+ Agregar\" o el micrófono 🎤 para cargar una.", 15f, cMuted).apply {
                setPadding(dp(4), dp(10), dp(4), dp(10))
            }, lp())
            else content.addView(label("Sin tareas", 14f, cMuted).apply { setPadding(dp(4), dp(2), 0, dp(2)) }, lp())
            return
        }
        for (t in list) content.addView(taskRow(t, d), lp().apply { topMargin = dp(6) })
    }

    private fun taskRow(t: Task, d: LocalDate): View {
        val done = t.isDone(d)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(cCard)
            setPadding(dp(6), dp(10), dp(12), dp(10))
            setOnClickListener { showTaskDialog(t, d) }
        }
        val cb = CheckBox(this).apply {
            isChecked = done
            scaleX = 1.25f; scaleY = 1.25f
            setOnClickListener {
                val nowDone = isChecked
                TaskStore.setDone(this@MainActivity, t.id, d, nowDone)
                if (nowDone && d == LocalDate.now()) SpeakService.say(this@MainActivity, Speech.afterDone(this@MainActivity, d))
                render()
            }
        }
        row.addView(cb)
        val time = label("%02d:%02d".format(t.time.hour, t.time.minute), 17f, if (done) cMuted else cPrimary, true).apply {
            setPadding(dp(6), 0, dp(12), 0)
        }
        row.addView(time)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = label(t.title, 17f, if (done) cMuted else cText).apply {
            if (done) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        }
        col.addView(title)
        val extras = mutableListOf<String>()
        if (t.reminderMin > 0) extras.add("🔔 " + reminderLabels[reminderValues.indexOf(t.reminderMin).coerceAtLeast(0)])
        if (t.repeat != Repeat.NONE) extras.add("🔁 " + repeatText(t))
        if (extras.isNotEmpty()) col.addView(label(extras.joinToString("   "), 12f, cMuted))
        row.addView(col, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun repeatText(t: Task): String = when (t.repeat) {
        Repeat.NONE -> ""
        Repeat.DAILY -> "todos los días"
        Repeat.MONTHLY -> "el ${t.date.dayOfMonth} de cada mes"
        Repeat.WEEKLY -> t.weekDays.sorted().joinToString(", ") { dayShort[it - 1] }
    }

    private val dayShort = listOf("lun", "mar", "mié", "jue", "vie", "sáb", "dom")

    // ---------------- cargar / editar ----------------

    private fun showTaskDialog(existing: Task?, defaultDate: LocalDate, preTitle: String? = null, preTime: LocalTime? = null) {
        var date = existing?.date ?: defaultDate
        var time = existing?.time ?: preTime ?: LocalTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0)
        val defaultReminder = Speech.prefs(this).getInt("aviso_def", 15)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val et = EditText(this).apply {
            hint = "¿Qué tenés que hacer?"
            setText(existing?.title ?: preTitle ?: "")
            textSize = 18f
        }
        titleRow.addView(et, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(titleRow, lp())

        val dateBtn = Button(this)
        val timeBtn = Button(this)
        fun refreshButtons() {
            dateBtn.text = "📅  " + Speech.dayName(date)
            timeBtn.text = "🕒  %02d:%02d".format(time.hour, time.minute)
        }
        refreshButtons()
        dateBtn.setOnClickListener {
            DatePickerDialog(this, { _, y, m, dd -> date = LocalDate.of(y, m + 1, dd); refreshButtons() },
                date.year, date.monthValue - 1, date.dayOfMonth).show()
        }
        timeBtn.setOnClickListener {
            TimePickerDialog(this, { _, h, mi -> time = LocalTime.of(h, mi); refreshButtons() },
                time.hour, time.minute, true).show()
        }
        box.addView(dateBtn, lp().apply { topMargin = dp(8) })
        box.addView(timeBtn, lp())

        box.addView(label("Aviso previo", 14f, cMuted), lp().apply { topMargin = dp(10) })
        val remSp = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, reminderLabels)
            setSelection(reminderValues.indexOf(existing?.reminderMin ?: defaultReminder).coerceAtLeast(0))
        }
        box.addView(remSp, lp())

        box.addView(label("Repetir", 14f, cMuted), lp().apply { topMargin = dp(10) })
        val repSp = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, repeatLabels)
            setSelection(existing?.repeat?.ordinal ?: 0)
        }
        box.addView(repSp, lp())

        val daysRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val dayChecks = (1..7).map { n ->
            CheckBox(this).apply {
                text = listOf("L", "M", "X", "J", "V", "S", "D")[n - 1]
                isChecked = existing?.weekDays?.contains(n) ?: (n == date.dayOfWeek.value)
            }
        }
        dayChecks.forEach { daysRow.addView(it, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        box.addView(daysRow, lp())
        daysRow.visibility = if (repSp.selectedItemPosition == 2) View.VISIBLE else View.GONE
        repSp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                daysRow.visibility = if (pos == 2) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val scroll = ScrollView(this).apply { addView(box) }
        val b = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Nueva tarea" else "Editar tarea")
            .setView(scroll)
            .setPositiveButton("Guardar", null)
            .setNegativeButton("Cancelar", null)
        if (existing != null) {
            b.setNeutralButton("Borrar") { _, _ ->
                AlertDialog.Builder(this)
                    .setMessage(if (existing.repeat == Repeat.NONE) "¿Borrar esta tarea?" else "¿Borrar esta tarea y todas sus repeticiones?")
                    .setPositiveButton("Borrar") { _, _ ->
                        val all = TaskStore.load(this)
                        all.removeAll { it.id == existing.id }
                        TaskStore.save(this, all)
                        render()
                    }
                    .setNegativeButton("No", null)
                    .show()
            }
        }
        val dlg = b.create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = et.text.toString().trim()
                if (title.isEmpty()) {
                    Toast.makeText(this, "Escribí qué tenés que hacer", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val repeat = Repeat.values()[repSp.selectedItemPosition]
                val days = dayChecks.mapIndexedNotNull { i, c -> if (c.isChecked) i + 1 else null }.toMutableSet()
                if (repeat == Repeat.WEEKLY && days.isEmpty()) {
                    Toast.makeText(this, "Elegí al menos un día", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val all = TaskStore.load(this)
                val t = all.find { it.id == existing?.id }
                if (t != null) {
                    t.title = title; t.date = date; t.time = time
                    t.reminderMin = reminderValues[remSp.selectedItemPosition]
                    t.repeat = repeat; t.weekDays = days
                } else {
                    all.add(Task(TaskStore.nextId(all), title, date, time, reminderValues[remSp.selectedItemPosition], repeat, days))
                }
                TaskStore.save(this, all)
                if (tab == 2) { selected = date; month = YearMonth.from(date) }
                dlg.dismiss()
                render()
                Toast.makeText(this, "Guardada ✔", Toast.LENGTH_SHORT).show()
            }
        }
        dlg.show()
    }

    // ---------------- dictado ----------------

    private fun startDictation() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-AR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Decí la tarea. Ej: mañana a las 10 llamar al distribuidor")
        }
        try {
            startActivityForResult(i, REQ_SPEECH)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Este teléfono no tiene dictado por voz disponible", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SPEECH || resultCode != RESULT_OK) return
        val said = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
        val r = VoiceParser.parse(said)
        showTaskDialog(null, r.date, r.title, r.time)
        if (r.time == null) Toast.makeText(this, "No entendí la hora, elegila abajo", Toast.LENGTH_LONG).show()
    }

    // ---------------- ajustes ----------------

    private fun showSettings() {
        val p = Speech.prefs(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        val status = label("Cargando voces del teléfono…", 14f, cMuted)
        box.addView(label("Voz", 14f, cMuted))
        val voiceSp = Spinner(this)
        box.addView(voiceSp, lp())
        box.addView(status, lp())

        var speed = p.getInt("velocidad", 100)
        var pitch = p.getInt("tono", 95)
        val speedLbl = label("Velocidad: $speed%", 14f, cMuted)
        box.addView(speedLbl, lp().apply { topMargin = dp(12) })
        box.addView(SeekBar(this).apply {
            max = 100; progress = speed - 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, f: Boolean) { speed = v + 50; speedLbl.text = "Velocidad: $speed%" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }, lp())
        val pitchLbl = label("Tono (más grave ← → más agudo): $pitch%", 14f, cMuted)
        box.addView(pitchLbl, lp().apply { topMargin = dp(8) })
        box.addView(SeekBar(this).apply {
            max = 80; progress = pitch - 60
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, f: Boolean) { pitch = v + 60; pitchLbl.text = "Tono (más grave ← → más agudo): $pitch%" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }, lp())

        box.addView(label("Aviso previo por defecto", 14f, cMuted), lp().apply { topMargin = dp(12) })
        val defSp = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, reminderLabels)
            setSelection(reminderValues.indexOf(p.getInt("aviso_def", 15)).coerceAtLeast(0))
        }
        box.addView(defSp, lp())

        var voices: List<Voice> = emptyList()
        val testBtn = Button(this).apply { text = "▶  Probar voz" }
        box.addView(testBtn, lp().apply { topMargin = dp(12) })

        settingsTts?.shutdown()
        var engine: TextToSpeech? = null
        engine = TextToSpeech(this) init@{ st ->
            val e = engine ?: return@init
            if (st != TextToSpeech.SUCCESS) { status.text = "No se pudo iniciar la voz del teléfono"; return@init }
            voices = Speech.spanishVoices(e)
            if (voices.isEmpty()) {
                status.text = "No encontré voces en español. Instalá \"Servicios de voz de Google\" desde Play Store."
                return@init
            }
            val names = voices.mapIndexed { i, v ->
                val net = if (v.isNetworkConnectionRequired) " (necesita internet)" else ""
                "Voz ${i + 1} · ${v.locale.displayCountry}$net"
            }
            voiceSp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
            val saved = p.getString("voz", null)
            val idx = voices.indexOfFirst { it.name == saved }
            if (idx >= 0) voiceSp.setSelection(idx)
            status.text = "Probá varias y quedate con la que más te guste."
        }
        settingsTts = engine
        testBtn.setOnClickListener {
            val e = settingsTts ?: return@setOnClickListener
            voices.getOrNull(voiceSp.selectedItemPosition)?.let { try { e.voice = it } catch (x: Exception) { } }
            e.setSpeechRate(speed / 100f)
            e.setPitch(pitch / 100f)
            e.speak("Hola Gustavo. Hoy tenés tres tareas. Primero, a las 9, abrir el kiosco.", TextToSpeech.QUEUE_FLUSH, null, "prueba")
        }

        AlertDialog.Builder(this)
            .setTitle("Ajustes de voz")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Guardar") { _, _ ->
                val ed = p.edit()
                voices.getOrNull(voiceSp.selectedItemPosition)?.let { ed.putString("voz", it.name) }
                ed.putInt("velocidad", speed).putInt("tono", pitch)
                ed.putInt("aviso_def", reminderValues[defSp.selectedItemPosition])
                ed.apply()
                Toast.makeText(this, "Ajustes guardados", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cerrar", null)
            .setOnDismissListener { settingsTts?.stop() }
            .show()
    }

    companion object {
        private const val REQ_SPEECH = 10
    }
}
