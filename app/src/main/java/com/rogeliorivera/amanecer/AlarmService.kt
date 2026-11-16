package com.rogeliorivera.amanecer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File

class AlarmService : Service() {

    companion object {
        const val ACTION_RING = "ring"
        const val ACTION_DISMISS = "dismiss"
        const val ACTION_SNOOZE = "snooze"
        const val ACTION_STOP = "stop"
        const val ACTION_TEST = "test"
        const val ACTION_SKIP = "skip"

        private const val CHANNEL = "alarma"
        private const val NOTIF_ID = 7

        /** idle | ringing | preparing | running */
        @Volatile var state: String = "idle"
        @Volatile var briefing: String = ""
        @Volatile var nowPlaying: String = ""

        val listeners = mutableSetOf<() -> Unit>()

        fun intent(c: Context, action: String): Intent =
            Intent(c, AlarmService::class.java).setAction(action)

        fun wakeVoiceFile(c: Context) = File(c.filesDir, "levantate.wav")
    }

    private val main = Handler(Looper.getMainLooper())
    private var ringer: MediaPlayer? = null
    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsFailed = false
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var steps: ArrayDeque<Step>? = null
    private var ringVolume = 0.3f

    private val ringTimeout = Runnable { if (state == "ringing") snooze() }
    private val volumeRamp = object : Runnable {
        override fun run() {
            ringVolume = (ringVolume + 0.035f).coerceAtMost(1f)
            try { ringer?.setVolume(ringVolume, ringVolume) } catch (e: Exception) {}
            if (ringVolume < 1f && state == "ringing") main.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val ch = NotificationChannel(CHANNEL, "Alarma", NotificationManager.IMPORTANCE_HIGH)
        ch.setSound(null, null)
        ch.enableVibration(false)
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RING -> ring()
            ACTION_DISMISS, ACTION_TEST -> startRoutine()
            ACTION_SNOOZE -> snooze()
            ACTION_SKIP -> skipCurrent()
            else -> finish()
        }
        return START_NOT_STICKY
    }

    // ================= Notificación =================

    private fun servicePI(action: String, rc: Int): PendingIntent =
        PendingIntent.getService(
            this, rc, intent(this, action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun action(title: String, pi: PendingIntent) =
        Notification.Action.Builder(null as Icon?, title, pi).build()

    private fun goForeground(title: String, text: String, ringing: Boolean) {
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(open)
        if (ringing) {
            b.setFullScreenIntent(open, true)
            b.addAction(action("Ya desperté", servicePI(ACTION_DISMISS, 2)))
            b.addAction(action("5 minutos más", servicePI(ACTION_SNOOZE, 3)))
        } else {
            b.addAction(action("Saltar", servicePI(ACTION_SKIP, 5)))
            b.addAction(action("Detener", servicePI(ACTION_STOP, 4)))
        }
        val n = b.build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun notifyUi() {
        main.post { listeners.toList().forEach { it() } }
    }

    private fun attrs(contentType: Int): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(contentType)
            .build()

    private fun acquireWake() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "amanecer:alarma")
            .apply { acquire(30 * 60 * 1000L) }
    }

    // ================= 1. Sonido de alarma =================

    private fun alarmSource(): Pair<Uri?, Long> {
        val sistema = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val beep = Briefing.rawUri(this, "beep")
        return when (Prefs.alarmSound(this)) {
            "gallo" -> (Briefing.rawUri(this, "gallo") ?: beep) to 1200L
            "beep" -> beep to 0L
            "voz" -> {
                val f = wakeVoiceFile(this)
                if (f.exists() && f.length() > 1000) Uri.fromFile(f) to 900L else beep to 0L
            }
            "custom" -> (Prefs.customSound(this)?.let { Uri.parse(it.uri) } ?: sistema) to 800L
            else -> sistema to 0L
        }
    }

    private fun ring() {
        state = "ringing"
        briefing = ""
        nowPlaying = ""
        goForeground("⏰ Hora de levantarse", "Buenos días, ${Prefs.name(this)}", true)
        acquireWake()
        try {
            startActivity(Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
        }
        stopRinging()
        val (uri, gap) = alarmSource()
        ringVolume = 0.3f
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(attrs(AudioAttributes.CONTENT_TYPE_SONIFICATION))
            mp.setDataSource(this, uri ?: Uri.EMPTY)
            mp.setVolume(ringVolume, ringVolume)
            if (gap == 0L) {
                mp.isLooping = true
            } else {
                mp.setOnCompletionListener { p ->
                    main.postDelayed({
                        if (state == "ringing" && ringer === p) try { p.seekTo(0); p.start() } catch (e: Exception) {}
                    }, gap)
                }
            }
            mp.prepare()
            mp.start()
            ringer = mp
        } catch (e: Exception) {
            // Último recurso: tono del sistema
            try {
                val mp = MediaPlayer()
                mp.setAudioAttributes(attrs(AudioAttributes.CONTENT_TYPE_SONIFICATION))
                mp.setDataSource(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                mp.isLooping = true
                mp.prepare()
                mp.start()
                ringer = mp
            } catch (e2: Exception) {
            }
        }
        main.postDelayed(volumeRamp, 1000)
        try {
            val v = if (Build.VERSION.SDK_INT >= 31) {
                getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Vibrator::class.java)
            }
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 700), 0))
            vibrator = v
        } catch (e: Exception) {
        }
        main.removeCallbacks(ringTimeout)
        main.postDelayed(ringTimeout, 10 * 60 * 1000L)
        notifyUi()
    }

    private fun stopRinging() {
        main.removeCallbacks(ringTimeout)
        main.removeCallbacks(volumeRamp)
        val r = ringer
        ringer = null
        try { r?.stop() } catch (e: Exception) {}
        r?.release()
        vibrator?.cancel()
        vibrator = null
    }

    private fun snooze() {
        stopRinging()
        AlarmScheduler.scheduleIn(this, 5)
        finish()
    }

    // ================= 2. Rutina hablada =================

    private fun startRoutine() {
        stopRinging()
        stopPlayer()
        state = "preparing"
        briefing = "Preparando tu resumen…"
        nowPlaying = ""
        steps = null
        goForeground("☀️ Buenos días, ${Prefs.name(this)}", "Preparando tu resumen…", false)
        acquireWake()
        notifyUi()

        ttsReady = false
        ttsFailed = false
        tts?.shutdown()
        var engine: TextToSpeech? = null
        engine = TextToSpeech(this) { status ->
            main.post {
                val t = engine
                if (status == TextToSpeech.SUCCESS && t != null) {
                    Voices.apply(this, t)
                    t.setAudioAttributes(attrs(AudioAttributes.CONTENT_TYPE_SPEECH))
                    t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) {
                            main.post { if (id == currentId) next() }
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) {
                            main.post { if (id == currentId) next() }
                        }
                    })
                    ttsReady = true
                } else {
                    ttsFailed = true
                }
                if (state == "preparing" && steps != null) begin()
            }
        }
        tts = engine

        val app = applicationContext
        Thread {
            val list = try {
                Briefing.build(app)
            } catch (e: Exception) {
                listOf(Step.Speak("Buenos días, ${Prefs.name(app)}. Hoy no pude preparar tu resumen completo, pero recuerda: hoy es un gran día."))
            }
            main.post {
                if (state != "preparing") return@post
                steps = ArrayDeque(list)
                briefing = Briefing.asText(list)
                notifyUi()
                if (ttsReady || ttsFailed) begin()
                else main.postDelayed({ if (state == "preparing") begin() }, 6000)
            }
        }.start()
    }

    private var currentId: String? = null
    private var counter = 0

    private fun begin() {
        if (state != "preparing") return
        state = "running"
        goForeground("☀️ Tu resumen de hoy", "Escuchando…", false)
        notifyUi()
        next()
    }

    private fun next() {
        if (state != "running") return
        stopPlayer()
        val q = steps
        val s = q?.removeFirstOrNull()
        if (s == null) {
            finish()
            return
        }
        when (s) {
            is Step.Speak -> {
                nowPlaying = ""
                notifyUi()
                val t = tts
                if (ttsFailed || t == null) {
                    main.post { next() }
                    return
                }
                if (!ttsReady) {
                    q?.addFirst(s)
                    main.postDelayed({ next() }, 300)
                    return
                }
                counter++
                val id = "u$counter"
                currentId = id
                t.speak(s.text, TextToSpeech.QUEUE_FLUSH, null, id)
            }
            is Step.Play -> play(s)
        }
    }

    private fun play(s: Step.Play) {
        currentId = null
        nowPlaying = s.title
        notifyUi()
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(attrs(AudioAttributes.CONTENT_TYPE_MUSIC))
            mp.setDataSource(this, s.uri)
            mp.setOnCompletionListener { main.post { if (player === mp) next() } }
            mp.setOnErrorListener { _, _, _ -> main.post { if (player === mp) next() }; true }
            mp.prepare()
            mp.start()
            player = mp
            goForeground("🎵 ${s.title}", "¡Que tengas un excelente día!", false)
            if (s.maxMs > 0) fadeOutAt(mp, s.maxMs)
        } catch (e: Exception) {
            main.post { next() }
        }
    }

    /** Baja el volumen en los últimos 3 segundos y pasa al siguiente paso. */
    private fun fadeOutAt(mp: MediaPlayer, maxMs: Long) {
        val fadeMs = 3000L
        val n = 15
        for (i in 1..n) {
            main.postDelayed({
                if (player === mp) {
                    val v = 1f - i.toFloat() / n
                    try { mp.setVolume(v, v) } catch (e: Exception) {}
                    if (i == n) next()
                }
            }, maxMs - fadeMs + i * (fadeMs / n))
        }
    }

    private fun skipCurrent() {
        if (state != "running") return
        try { tts?.stop() } catch (e: Exception) {}
        next()
    }

    private fun stopPlayer() {
        val p = player
        player = null
        try { p?.stop() } catch (e: Exception) {}
        p?.release()
    }

    // ================= Fin =================

    private fun finish() {
        stopRinging()
        stopPlayer()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
        }
        tts = null
        steps = null
        currentId = null
        state = "idle"
        nowPlaying = ""
        notifyUi()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopRinging()
        stopPlayer()
        try { tts?.shutdown() } catch (e: Exception) {}
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (state != "idle") {
            state = "idle"
            notifyUi()
        }
        super.onDestroy()
    }
}
