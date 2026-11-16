package com.rogeliorivera.amanecer

import android.content.Context
import android.net.Uri
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/** Un paso de la rutina de la mañana. */
sealed class Step {
    data class Speak(val text: String) : Step()
    data class Play(val uri: Uri, val maxMs: Long, val title: String) : Step()
}

object Secciones {
    // clave -> nombre visible, en el orden de la rutina
    val todas = listOf(
        "clima" to "Clima de tu ubicación",
        "frase" to "Frase del día",
        "musica" to "20 segundos de música motivadora",
        "agenda" to "Pendientes del calendario",
        "correos" to "Recordatorio de correos",
        "noticias" to "3 noticias del día",
        "cancion" to "Canción final"
    )
}

object Briefing {

    fun rawUri(c: Context, name: String): Uri? {
        val id = c.resources.getIdentifier(name, "raw", c.packageName)
        return if (id == 0) null else Uri.parse("android.resource://${c.packageName}/$id")
    }

    fun songOfDay(c: Context, d: LocalDate): Song? {
        val songs = Prefs.songs(c)
        if (songs.isEmpty()) return null
        if (songs.size == 1) return songs[0]
        val order = songs.indices.shuffled(Random(d.year * 1000 + d.dayOfYear))
        return songs[order[0]]
    }

    /** Construye toda la rutina. Hace llamadas de red: no llamar en el hilo principal. */
    fun build(c: Context): List<Step> {
        val today = LocalDate.now()
        val name = Prefs.name(c)
        val steps = mutableListOf<Step>()
        fun on(k: String) = Prefs.section(c, k)

        // 1. Saludo
        val fecha = today.format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'de' yyyy", ES))
        steps += Step.Speak("${saludo()}, $name. Hoy es $fecha.")

        // 2. Clima
        if (on("clima")) steps += Step.Speak(Clima.texto(c))

        // 3. Ánimo + frase
        steps += Step.Speak(animo(today))
        if (on("frase")) {
            val (tipo, f) = Frases.delDia(c, today)
            steps += Step.Speak(
                when (tipo) {
                    "versiculo" -> "El versículo de hoy, de ${f.cita}: ${f.texto}"
                    "entusiasta" -> "Y para arrancar con todo: ${f.texto}"
                    else -> "Te dejo una frase motivadora: ${f.texto}"
                }
            )
        }

        // 4. Música motivadora (20 s)
        if (on("musica")) {
            val n = (((today.toEpochDay() % 3) + 3) % 3 + 1).toInt()
            (rawUri(c, "motivacion$n") ?: rawUri(c, "motivacion1"))
                ?.let { steps += Step.Play(it, 20_000, "Música motivadora") }
        }

        // 5. Pendientes y correos
        if (on("agenda")) steps += Step.Speak(Agenda.texto(c, today))
        if (on("correos")) steps += Step.Speak("No olvides revisar y contestar los correos importantes.")

        // 6. Noticias
        if (on("noticias")) steps += Step.Speak(Noticias.texto())

        // 7. Canción final
        val song = if (on("cancion")) songOfDay(c, today) else null
        if (song != null) {
            steps += Step.Speak("Y para cerrar, aquí va tu canción. ¡Que tengas un excelente día, $name!")
            steps += Step.Play(Uri.parse(song.uri), 0, song.name)
        } else {
            steps += Step.Speak("¡Que tengas un excelente día, $name!")
        }
        return steps
    }

    private fun saludo(): String {
        val h = LocalDateTime.now().hour
        return when {
            h < 12 -> "Buenos días"
            h < 19 -> "Buenas tardes"
            else -> "Buenas noches"
        }
    }

    private fun animo(d: LocalDate): String {
        val aperturas = listOf(
            "Hoy amanecimos muy bien.",
            "Hoy amanecimos con toda la energía.",
            "Qué gusto empezar un nuevo día.",
            "Hoy es un gran día para avanzar.",
            "Amanecimos con salud y con ganas, y eso ya es ganancia."
        )
        val extra = when (d.dayOfWeek) {
            DayOfWeek.MONDAY -> " Es lunes: a arrancar la semana con todo."
            DayOfWeek.FRIDAY -> " Ya es viernes: cierra la semana con fuerza."
            DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> " Es fin de semana: disfruta y recarga energía."
            else -> ""
        }
        return aperturas[Random(d.toEpochDay()).nextInt(aperturas.size)] + extra +
            " Recuerda levantarte y empezar el día bien."
    }

    /** Texto plano para mostrar en pantalla. */
    fun asText(steps: List<Step>): String = steps.joinToString("\n\n") {
        when (it) {
            is Step.Speak -> it.text
            is Step.Play -> "🎵 ${it.title}"
        }
    }
}
