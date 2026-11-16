@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.rogeliorivera.amanecer

import android.Manifest
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var tick by mutableIntStateOf(0)
    private var tts: TextToSpeech? = null
    private var ttsReady by mutableStateOf(false)
    private var preview: MediaPlayer? = null
    private val main = Handler(Looper.getMainLooper())
    private val listener: () -> Unit = { tick++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { st ->
            main.post {
                if (st == TextToSpeech.SUCCESS) {
                    tts?.let { Voices.apply(this, it) }
                    ttsReady = true
                    // Genera el audio "¡Levántate!" con la voz elegida
                    makeWakeVoice()
                }
            }
        }
        setContent {
            AmanecerTheme {
                @Suppress("UNUSED_VARIABLE") val t = tick
                key(tick) { SettingsScreen() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AlarmService.listeners.add(listener)
        Ubicacion.refresh(this)
        AlarmScheduler.schedule(this)
        main.postDelayed({ tick++ }, 1500) // refresca la ciudad cuando llegue la ubicación
        tick++
    }

    override fun onPause() {
        AlarmService.listeners.remove(listener)
        stopPreview()
        super.onPause()
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    private fun refresh() { tick++ }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ---------------- Audio de prueba ----------------

    private fun stopPreview() {
        val p = preview
        preview = null
        try { p?.stop() } catch (e: Exception) {}
        p?.release()
        try { tts?.stop() } catch (e: Exception) {}
    }

    private fun playPreview(uri: Uri?) {
        stopPreview()
        if (uri == null) return
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            )
            mp.setDataSource(this, uri)
            mp.prepare()
            mp.start()
            preview = mp
            main.postDelayed({ if (preview === mp) stopPreview() }, 5000)
        } catch (e: Exception) {
            toast("No se pudo reproducir ese sonido.")
        }
    }

    private fun previewSound(key: String) {
        when (key) {
            "gallo" -> playPreview(Briefing.rawUri(this, "gallo") ?: Briefing.rawUri(this, "beep"))
            "beep" -> playPreview(Briefing.rawUri(this, "beep"))
            "voz" -> {
                val f = AlarmService.wakeVoiceFile(this)
                if (f.exists()) playPreview(Uri.fromFile(f)) else speak(wakeText())
            }
            "custom" -> playPreview(Prefs.customSound(this)?.let { Uri.parse(it.uri) })
            else -> playPreview(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM))
        }
    }

    private fun speak(text: String) {
        stopPreview()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "preview")
    }

    private fun wakeText() = "¡${Prefs.name(this)}, levántate! ¡Ya es hora! ¡Buenos días!"

    private fun makeWakeVoice() {
        val t = tts ?: return
        if (!ttsReady) return
        try {
            Voices.apply(this, t)
            t.synthesizeToFile(wakeText(), Bundle(), AlarmService.wakeVoiceFile(this), "wake")
        } catch (e: Exception) {
        }
    }

    // ---------------- Archivos ----------------

    private fun displayName(uri: Uri): String {
        var name = "Audio"
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = (c.getString(0) ?: name).substringBeforeLast('.')
            }
        } catch (e: Exception) {
        }
        return name
    }

    private fun keep(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
        }
    }

    // ---------------- Permisos ----------------

    private fun has(p: String) = checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun neededPerms(): Array<String> {
        val l = mutableListOf<String>()
        if (!has(Manifest.permission.READ_CALENDAR)) l += Manifest.permission.READ_CALENDAR
        if (!has(Manifest.permission.ACCESS_COARSE_LOCATION)) l += Manifest.permission.ACCESS_COARSE_LOCATION
        if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) l += Manifest.permission.POST_NOTIFICATIONS
        return l.toTypedArray()
    }

    private fun fullScreenOk() =
        Build.VERSION.SDK_INT < 34 || getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    private fun openFullScreenSettings() {
        val pkg = Uri.parse("package:$packageName")
        try {
            if (Build.VERSION.SDK_INT >= 34) startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
        }
    }

    // ================= Pantalla =================

    @Composable
    private fun SettingsScreen() {
        val ctx = LocalContext.current
        val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            Ubicacion.refresh(ctx); refresh()
        }
        val songsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                val now = Prefs.songs(ctx).toMutableList()
                uris.forEach { u ->
                    keep(u)
                    if (now.none { it.uri == u.toString() }) now += Song(u.toString(), displayName(u))
                }
                Prefs.setSongs(ctx, now)
                refresh()
            }
        }
        val soundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
            if (u != null) {
                keep(u)
                Prefs.setCustomSound(ctx, Song(u.toString(), displayName(u)))
                Prefs.setAlarmSound(ctx, "custom")
                refresh()
            }
        }
        if (!Prefs.onboarded(ctx)) {
            Prefs.setOnboarded(ctx)
            val need = neededPerms()
            if (need.isNotEmpty()) main.post { permLauncher.launch(need) }
        }

        Column(
            Modifier
                .fillMaxSize()
                .background(Navy)
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 12.dp)
        ) {
            Text("☀️ Amanecer", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Orange)
            Text("Buenos días, ${Prefs.name(ctx)}", fontSize = 16.sp, color = Muted)

            AlarmCard(ctx)
            TestCard(ctx)
            SoundCard(ctx) { soundLauncher.launch(arrayOf("audio/*")) }
            VoiceCard(ctx)
            PhraseCard(ctx)
            SectionsCard(ctx)
            SongsCard(ctx) { songsLauncher.launch(arrayOf("audio/*")) }
            LocationCard(ctx)
            NameCard(ctx)
            PermsCard(ctx) {
                val need = neededPerms()
                when {
                    need.isNotEmpty() -> permLauncher.launch(need)
                    !fullScreenOk() -> openFullScreenSettings()
                    else -> {
                        try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e: Exception) {}
                        toast("Busca Amanecer y elige \"Sin restricciones\" para que la alarma nunca falle.")
                    }
                }
            }
            CreditsCard()
            Spacer(Modifier.height(30.dp))
        }
    }

    @Composable
    private fun Section(title: String, content: @Composable () -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(title, fontSize = 13.sp, color = Muted, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                content()
            }
        }
    }

    @Composable
    private fun Choice(label: String, selected: Boolean, onSelect: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
        Row(
            Modifier.fillMaxWidth().clickable { onSelect() }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Text(label, fontSize = 16.sp, color = TextMain, modifier = Modifier.weight(1f))
            trailing?.invoke()
        }
    }

    @Composable
    private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(
            Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 16.sp, color = TextMain, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }

    @Composable
    private fun WideButton(text: String, primary: Boolean = false, onClick: () -> Unit) {
        if (primary) {
            Button(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Navy)
            ) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        } else {
            OutlinedButton(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(50.dp)
            ) { Text(text, fontSize = 16.sp, color = TextMain) }
        }
    }

    // ---------- Alarma ----------

    @Composable
    private fun AlarmCard(ctx: Context) {
        Section("ALARMA") {
            Text(
                String.format(Locale.US, "%d:%02d", Prefs.hour(ctx), Prefs.minute(ctx)),
                fontSize = 60.sp, fontWeight = FontWeight.Bold, color = TextMain,
                modifier = Modifier.clickable { pickTime(ctx) }
            )
            Toggle("Alarma activa", Prefs.enabled(ctx)) {
                Prefs.setEnabled(ctx, it)
                applySchedule(ctx)
            }
            val names = listOf("L", "M", "M", "J", "V", "S", "D")
            val days = Prefs.days(ctx)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (d in 1..7) {
                    val on = d in days
                    FilterChip(
                        selected = on,
                        onClick = {
                            Prefs.setDays(ctx, if (on) days - d else days + d)
                            applySchedule(ctx)
                        },
                        label = { Text(names[d - 1], fontWeight = FontWeight.Bold) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Orange,
                            selectedLabelColor = Navy,
                            labelColor = TextMain
                        )
                    )
                }
            }
            Text(nextText(ctx), fontSize = 14.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
            WideButton("🕕  Cambiar hora", primary = true) { pickTime(ctx) }
        }
    }

    private fun nextText(ctx: Context): String {
        if (!Prefs.enabled(ctx)) return "La alarma está apagada."
        val ms = AlarmScheduler.nextTrigger(ctx) ?: return "Elige al menos un día."
        val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        val d = t.toLocalDate()
        val cuando = when (d) {
            LocalDate.now() -> "hoy"
            LocalDate.now().plusDays(1) -> "mañana"
            else -> d.format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", ES))
        }
        return "Próxima alarma: $cuando a las ${t.format(DateTimeFormatter.ofPattern("H:mm"))}"
    }

    private fun pickTime(ctx: Context) {
        TimePickerDialog(ctx, { _, h, m ->
            Prefs.setTime(ctx, h, m)
            Prefs.setEnabled(ctx, true)
            applySchedule(ctx)
        }, Prefs.hour(ctx), Prefs.minute(ctx), true).show()
    }

    private fun applySchedule(ctx: Context) {
        if (!AlarmScheduler.schedule(ctx)) {
            toast("Android no dio permiso para alarmas exactas. Revisa la sección Permisos.")
        } else if (Prefs.enabled(ctx)) {
            toast(nextText(ctx))
        }
        refresh()
    }

    // ---------- Probar ----------

    @Composable
    private fun TestCard(ctx: Context) {
        Section("PROBAR") {
            WideButton("▶  Escuchar mi rutina ahora", primary = true) {
                stopPreview()
                startForegroundService(AlarmService.intent(ctx, AlarmService.ACTION_TEST))
                startActivity(Intent(ctx, AlarmActivity::class.java))
            }
            WideButton("⏰  Probar la alarma en 1 minuto") {
                if (AlarmScheduler.scheduleIn(ctx, 1)) toast("Listo. Bloquea el celular y espera 1 minuto.")
                else toast("Android no dio permiso para alarmas exactas.")
            }
            if (AlarmService.state != "idle") {
                WideButton("⏹  Detener") { startService(AlarmService.intent(ctx, AlarmService.ACTION_STOP)) }
            }
        }
    }

    // ---------- Sonido ----------

    @Composable
    private fun SoundCard(ctx: Context, pickFile: () -> Unit) {
        Section("1 · SONIDO DE LA ALARMA") {
            val cur = Prefs.alarmSound(ctx)
            val custom = Prefs.customSound(ctx)
            val opts = listOf(
                "gallo" to "🐓  Gallo",
                "beep" to "📟  Beep clásico",
                "voz" to "🗣️  \"¡Levántate, ya es hora!\"",
                "sistema" to "🔔  Tono del celular",
                "custom" to "🎙️  Personalizado" + (custom?.let { ": ${it.name}" } ?: "")
            )
            for ((k, label) in opts) {
                Choice(label, cur == k, {
                    if (k == "custom" && custom == null) pickFile()
                    else {
                        Prefs.setAlarmSound(ctx, k)
                        if (k == "voz") makeWakeVoice()
                        refresh()
                    }
                }) {
                    TextButton(onClick = { previewSound(k) }) { Text("▶", fontSize = 18.sp, color = Orange) }
                }
            }
            WideButton("📁  Elegir audio personalizado") { pickFile() }
            Text(
                "Puedes usar cualquier audio, incluso una grabación de tu propia voz. El volumen sube poco a poco.",
                fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 6.dp)
            )
        }
    }

    // ---------- Voz ----------

    @Composable
    private fun VoiceCard(ctx: Context) {
        Section("2 · VOZ DEL SALUDO") {
            if (!ttsReady) {
                Text("Cargando voces del celular…", color = Muted)
            } else {
            val presets = Voices.presets(tts)
            val sel = Prefs.voiceIndex(ctx).coerceIn(0, presets.size - 1)
            presets.forEachIndexed { i, p ->
                Choice(p.label, sel == i, {
                    Prefs.setVoice(ctx, i, p)
                    tts?.let { Voices.applyPreset(it, p) }
                    speak("Buenos días, ${Prefs.name(ctx)}. Así sonaré cada mañana.")
                    makeWakeVoice()
                    refresh()
                }) {
                    TextButton(onClick = {
                        tts?.let { Voices.applyPreset(it, p) }
                        speak("Buenos días, ${Prefs.name(ctx)}. Así sonaré cada mañana.")
                    }) { Text("▶", fontSize = 18.sp, color = Orange) }
                }
            }
            TextButton(onClick = {
                try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) } catch (e: Exception) {}
            }) { Text("Instalar más voces en el celular", color = Orange) }
            }
        }
    }

    // ---------- Frase ----------

    @Composable
    private fun PhraseCard(ctx: Context) {
        Section("3 · FRASE DEL DÍA") {
            val cur = Prefs.phraseType(ctx)
            val opts = listOf(
                "motivadora" to "💪  Frase motivadora",
                "entusiasta" to "🎉  Frase entusiasta",
                "versiculo" to "📖  Versículo bíblico",
                "mezcla" to "🔀  Una distinta cada día"
            )
            for ((k, label) in opts) Choice(label, cur == k, { Prefs.setPhraseType(ctx, k); refresh() })
            val (tipo, f) = try { Frases.delDia(ctx, LocalDate.now()) } catch (e: Exception) { null to null }
            if (f != null) {
                Text(
                    "Hoy: \"${f.texto}\"" + (if (tipo == "versiculo" && f.cita != null) " — ${f.cita}" else ""),
                    fontSize = 14.sp, color = Muted, modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }

    // ---------- Secciones ----------

    @Composable
    private fun SectionsCard(ctx: Context) {
        Section("QUÉ INCLUYE TU RUTINA") {
            Text("Saludo con tu nombre y la fecha (siempre)", fontSize = 14.sp, color = Muted)
            for ((k, label) in Secciones.todas) {
                Toggle(label, Prefs.section(ctx, k)) { Prefs.setSection(ctx, k, it); refresh() }
            }
        }
    }

    // ---------- Canciones ----------

    @Composable
    private fun SongsCard(ctx: Context, add: () -> Unit) {
        Section("8 · CANCIÓN FINAL") {
            val songs = Prefs.songs(ctx)
            if (songs.isEmpty()) {
                Text("Agrega una o varias canciones de tu celular. Cada día sonará una distinta.", color = Muted, fontSize = 14.sp)
            } else {
                val today = Briefing.songOfDay(ctx, LocalDate.now())
                songs.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            (if (s == today) "▶ " else "• ") + s.name,
                            color = if (s == today) Orange else TextMain, fontSize = 15.sp,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { Prefs.setSongs(ctx, songs - s); refresh() }) {
                            Text("✕", color = Muted)
                        }
                    }
                }
            }
            WideButton("🎵  Agregar canciones del celular") { add() }
            Spacer(Modifier.height(10.dp))
            val src = Prefs.songSource(ctx)
            Choice("📱  Usar canciones del celular", src == "local", { Prefs.setSongSource(ctx, "local"); refresh() })
            Choice("🟢  Usar Spotify (Premium)", src == "spotify", { Prefs.setSongSource(ctx, "spotify"); refresh() })
            if (src == "spotify") SpotifyBox(ctx)
        }
    }

    @Composable
    private fun SpotifyBox(ctx: Context) {
        var link by remember { mutableStateOf("") }
        val cur = Prefs.spotifyUri(ctx)
        Text(
            if (Prefs.spotifyConnected(ctx)) "✅  Conectado con Spotify" else "❌  Aún no conectado",
            color = TextMain, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp)
        )
        WideButton(if (Prefs.spotifyConnected(ctx)) "🔄  Volver a conectar Spotify" else "🔗  Conectar con Spotify", primary = !Prefs.spotifyConnected(ctx)) {
            SpotifyLink.connect(this, true) { remote, err ->
                main.post {
                    if (remote != null) {
                        Prefs.setSpotifyConnected(ctx, true)
                        com.spotify.android.appremote.api.SpotifyAppRemote.disconnect(remote)
                        toast("¡Spotify conectado!")
                    } else {
                        Prefs.setSpotifyConnected(ctx, false)
                        toast("No se pudo conectar: ${err ?: "error desconocido"}")
                    }
                    refresh()
                }
            }
        }
        Text(
            "Elegido: " + SpotifyLink.describe(cur),
            color = if (cur != null) Orange else Muted, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp)
        )
        OutlinedTextField(
            value = link,
            onValueChange = { link = it },
            singleLine = true,
            label = { Text("Pega aquí el enlace de tu playlist, álbum o canción") },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
        Text(
            "En Spotify: toca ⋯ en la playlist → Compartir → Copiar enlace. Si no funciona Spotify, sonará una canción de tu celular.",
            fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 4.dp)
        )
        WideButton("Guardar enlace") {
            val uri = SpotifyLink.toUri(link)
            if (uri == null) toast("Ese enlace no parece de Spotify.")
            else {
                Prefs.setSpotifyUri(ctx, uri)
                toast("Guardado: ${SpotifyLink.describe(uri)}")
                refresh()
            }
        }
        if (cur != null) {
            WideButton("▶  Probar en Spotify") {
                SpotifyLink.play(this, cur) { ok, err ->
                    main.post { toast(if (ok) "Sonando en Spotify 🎵" else "No se pudo: ${err ?: "error"}") }
                }
            }
        }
    }

    // ---------- Ubicación ----------

    @Composable
    private fun LocationCard(ctx: Context) {
        Section("CLIMA") {
            Text(
                if (Prefs.hasLocation(ctx)) "Usando tu ubicación: ${Prefs.city(ctx)}"
                else "Aún sin ubicación; mientras tanto: San Pedro Cholula",
                color = TextMain, fontSize = 15.sp
            )
            WideButton("📍  Actualizar ubicación") {
                if (!Ubicacion.hasPermission(ctx)) toast("Activa el permiso de ubicación en Permisos.")
                Ubicacion.refresh(ctx)
                main.postDelayed({ refresh() }, 2500)
            }
        }
    }

    // ---------- Nombre ----------

    @Composable
    private fun NameCard(ctx: Context) {
        Section("¿CÓMO TE LLAMO?") {
            var name by remember { mutableStateOf(Prefs.name(ctx)) }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            WideButton("Guardar nombre") {
                if (name.isNotBlank()) {
                    Prefs.setName(ctx, name)
                    makeWakeVoice()
                    toast("¡Listo, ${name.trim()}!")
                    refresh()
                }
            }
        }
    }

    // ---------- Permisos ----------

    @Composable
    private fun PermsCard(ctx: Context, fix: () -> Unit) {
        Section("PERMISOS") {
            fun line(ok: Boolean, label: String) = (if (ok) "✅  " else "❌  ") + label
            val lines = mutableListOf(
                line(Agenda.hasPermission(ctx), "Leer tu calendario"),
                line(Ubicacion.hasPermission(ctx), "Ubicación aproximada (clima)"),
                line(AlarmScheduler.canExact(ctx), "Alarmas a la hora exacta"),
                line(fullScreenOk(), "Mostrar la alarma en pantalla completa")
            )
            if (Build.VERSION.SDK_INT >= 33) lines.add(2, line(has(Manifest.permission.POST_NOTIFICATIONS), "Notificaciones"))
            lines.forEach { Text(it, color = TextMain, fontSize = 15.sp, modifier = Modifier.padding(vertical = 2.dp)) }
            WideButton("🔐  Revisar permisos") { fix() }
        }
    }

    // ---------- Créditos ----------

    @Composable
    private fun CreditsCard() {
        Section("CRÉDITOS") {
            Text(
                "Clima: Open-Meteo.com (CC BY 4.0).\n" +
                    "Noticias: Google Noticias.\n" +
                    "Versículos: Reina-Valera 1909 (dominio público).\n" +
                    "Gallo: \"Dawn Rooster Crow\" de BudgetPixel AI (CC BY 4.0).\n" +
                    "Música: Kevin MacLeod, incompetech.com (CC BY 4.0).\n" +
                    "Spotify es una marca de Spotify AB.",
                fontSize = 12.sp, color = Muted, lineHeight = 17.sp
            )
        }
    }
}
