package com.rogeliorivera.amanecer

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.provider.CalendarContract
import android.util.Xml
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

val ES: Locale = Locale("es", "MX")

private fun httpGet(url: String): String {
    val con = URL(url).openConnection() as HttpURLConnection
    con.connectTimeout = 8000
    con.readTimeout = 8000
    con.setRequestProperty("User-Agent", "Amanecer/1.0 (Android)")
    return try {
        con.inputStream.bufferedReader().use { it.readText() }
    } finally {
        con.disconnect()
    }
}

private fun <T> retry(times: Int, block: () -> T): T {
    var last: Exception? = null
    repeat(times) { i ->
        try {
            return block()
        } catch (e: Exception) {
            last = e
            if (i < times - 1) Thread.sleep(2000)
        }
    }
    throw last ?: IllegalStateException()
}

fun unir(items: List<String>, sep: String = ", "): String = when (items.size) {
    0 -> ""
    1 -> items[0]
    else -> items.dropLast(1).joinToString(sep) + " y " + items.last()
}

// ======================= Frases =======================

object Frases {
    data class Frase(val texto: String, val cita: String?)

    private var cache: Map<String, List<Frase>>? = null

    private fun load(c: Context): Map<String, List<Frase>> {
        cache?.let { return it }
        val json = JSONObject(c.assets.open("frases.json").bufferedReader().use { it.readText() })
        val m = mutableMapOf<String, List<Frase>>()
        for (k in json.keys()) {
            val a = json.getJSONArray(k)
            m[k] = (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Frase(o.getString("texto"), if (o.has("cita")) o.getString("cita") else null)
            }.shuffled(Random(2026 + k.length))
        }
        cache = m
        return m
    }

    /** Tipo efectivo del día (la mezcla rota entre los tres). */
    fun tipoDelDia(c: Context, d: LocalDate): String {
        val t = Prefs.phraseType(c)
        if (t != "mezcla") return t
        return listOf("motivadora", "entusiasta", "versiculo")[(d.toEpochDay() % 3).toInt().let { if (it < 0) it + 3 else it }]
    }

    /** Una frase distinta cada día; no se repite hasta recorrer toda la lista. */
    fun delDia(c: Context, d: LocalDate): Pair<String, Frase> {
        val tipo = tipoDelDia(c, d)
        val lista = load(c)[tipo] ?: load(c).values.first()
        val n = lista.size
        val idx = (((d.toEpochDay() % n) + n) % n).toInt()
        return tipo to lista[idx]
    }
}

// ======================= Ubicación =======================

object Ubicacion {
    fun hasPermission(c: Context) =
        c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Actualiza la ubicación guardada. Llamar solo con la app visible. */
    fun refresh(c: Context) {
        if (!hasPermission(c)) return
        val lm = c.getSystemService(LocationManager::class.java) ?: return
        try {
            val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            providers.mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (e: Exception) { null } }
                .maxByOrNull { it.time }?.let { save(c, it) }
            val prov = if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) LocationManager.NETWORK_PROVIDER
            else if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER
            else return
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(prov, null, c.mainExecutor) { loc -> if (loc != null) save(c, loc) }
            }
        } catch (e: SecurityException) {
        } catch (e: Exception) {
        }
    }

    private fun save(c: Context, loc: Location) {
        Prefs.setLocation(c, loc.latitude, loc.longitude)
        val app = c.applicationContext
        Thread {
            try {
                @Suppress("DEPRECATION")
                val a = Geocoder(app, ES).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
                val city = a?.locality ?: a?.subAdminArea ?: a?.adminArea
                if (!city.isNullOrBlank()) Prefs.setCity(app, city)
            } catch (e: Exception) {
            }
        }.start()
    }
}

// ======================= Clima (Open-Meteo) =======================

object Clima {
    fun texto(c: Context): String {
        val ciudad = Prefs.city(c)
        return try {
            retry(2) { online(c, ciudad) }
        } catch (e: Exception) {
            "No pude consultar el clima de $ciudad; revisa tu conexión a internet."
        }
    }

    private fun online(c: Context, ciudad: String): String {
        val lat = String.format(Locale.US, "%.4f", Prefs.lat(c))
        val lon = String.format(Locale.US, "%.4f", Prefs.lon(c))
        val tz = java.net.URLEncoder.encode(ZoneId.systemDefault().id, "UTF-8")
        val json = JSONObject(
            httpGet(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code" +
                    "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                    "&timezone=$tz&forecast_days=1"
            )
        )
        val cur = json.getJSONObject("current")
        val t = cur.getDouble("temperature_2m").roundToInt()
        val code = cur.getInt("weather_code")
        val d = json.getJSONObject("daily")
        val max = d.getJSONArray("temperature_2m_max").getDouble(0).roundToInt()
        val min = d.getJSONArray("temperature_2m_min").getDouble(0).roundToInt()
        val lluvia = d.getJSONArray("precipitation_probability_max").optInt(0, -1)

        val sb = StringBuilder("El clima en $ciudad: ahorita hace ${grados(t)}, ${desc(code)}. ")
        sb.append("La máxima de hoy será de ${grados(max)} y la mínima de ${grados(min)}")
        if (lluvia >= 0) {
            sb.append(", con $lluvia por ciento de probabilidad de lluvia")
            if (lluvia >= 40) sb.append("; no olvides el paraguas")
        }
        sb.append(".")
        if (t <= 8) sb.append(" Hace frío, abrígate bien.")
        if (max >= 30) sb.append(" Va a hacer calor, mantente hidratado.")
        return sb.toString()
    }

    private fun grados(n: Int) = if (n == 1 || n == -1) "$n grado" else "$n grados"

    private fun desc(code: Int) = when (code) {
        0 -> "con cielo despejado"
        1 -> "con cielo mayormente despejado"
        2 -> "parcialmente nublado"
        3 -> "nublado"
        45, 48 -> "con neblina"
        51, 53, 55 -> "con llovizna"
        56, 57 -> "con llovizna helada"
        61 -> "con lluvia ligera"
        63 -> "con lluvia"
        65 -> "con lluvia fuerte"
        66, 67 -> "con lluvia helada"
        71, 73, 75, 77 -> "con nieve"
        80, 81 -> "con chubascos"
        82 -> "con chubascos fuertes"
        95 -> "con tormenta eléctrica"
        96, 99 -> "con tormenta y granizo"
        else -> "con clima variable"
    }
}

// ======================= Noticias (Google Noticias México) =======================

object Noticias {
    data class Nota(val titulo: String, val fuente: String?)

    fun top(n: Int): List<Nota> {
        val xml = retry(2) { httpGet("https://news.google.com/rss?hl=es-419&gl=MX&ceid=MX:es-419") }
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(xml.reader())
        val notas = mutableListOf<Nota>()
        var inItem = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT && notas.size < n) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "item" -> inItem = true
                    "title" -> if (inItem) {
                        val raw = p.nextText().trim()
                        val cut = raw.lastIndexOf(" - ")
                        notas += if (cut > 0) Nota(raw.substring(0, cut).trim(), raw.substring(cut + 3).trim())
                        else Nota(raw, null)
                    }
                }
            } else if (ev == XmlPullParser.END_TAG && p.name == "item") {
                inItem = false
            }
            ev = p.next()
        }
        return notas
    }

    fun texto(): String = try {
        val notas = top(3)
        if (notas.isEmpty()) "Hoy no pude obtener las noticias."
        else {
            val ord = listOf("Primera", "Segunda", "Tercera")
            val partes = notas.mapIndexed { i, nt ->
                "${ord[i]}: ${nt.titulo}" + (nt.fuente?.let { ", según $it" } ?: "") + "."
            }
            "Ahora, las noticias más sonadas de hoy. " + partes.joinToString(" ")
        }
    } catch (e: Exception) {
        "Hoy no pude obtener las noticias; revisa tu conexión."
    }
}

// ======================= Agenda (calendario del teléfono) =======================

object Agenda {
    private data class Evento(val titulo: String, val inicio: Long, val todoElDia: Boolean)

    fun hasPermission(c: Context) =
        c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun texto(c: Context, today: LocalDate): String {
        if (!hasPermission(c)) return "No tengo permiso para leer tu calendario; actívalo desde la app."
        val eventos = try {
            leer(c, today)
        } catch (e: Exception) {
            return "No pude leer tu calendario hoy."
        }
        if (eventos.isEmpty()) return "Hoy tu calendario está libre; aprovecha para avanzar en tus pendientes."

        val conHora = eventos.filter { !it.todoElDia }
        val todoDia = eventos.filter { it.todoElDia }
        val sb = StringBuilder()
        if (conHora.isNotEmpty()) {
            var juntas = 0
            var cursos = 0
            var otros = 0
            val reCurso = Regex("curso|capacitaci|clase|taller|training|webinar|diplomado|seminario")
            val reJunta = Regex("junta|reuni|meeting|llamada|call|visita|cita|zoom|meet|teams|revisi|entrevista")
            for (e in conHora) {
                val t = e.titulo.lowercase(ES)
                when {
                    reCurso.containsMatchIn(t) -> cursos++
                    reJunta.containsMatchIn(t) -> juntas++
                    else -> otros++
                }
            }
            val piezas = mutableListOf<String>()
            if (juntas > 0) piezas += cant(juntas, "una junta", "juntas")
            if (cursos > 0) piezas += cant(cursos, "un curso", "cursos")
            if (otros > 0) piezas += if (piezas.isEmpty()) cant(otros, "un pendiente", "pendientes")
            else cant(otros, "un pendiente más", "pendientes más")
            sb.append("Ahora, tus pendientes del día. En tu calendario tienes ${unir(piezas)}. ")
            val primero = conHora.first()
            sb.append("Empiezas ${hora(primero.inicio)} con ${primero.titulo}.")
            val resto = conHora.drop(1).take(6)
            if (resto.isNotEmpty()) {
                sb.append(" Después tienes: ")
                sb.append(unir(resto.map { "${hora(it.inicio)}, ${it.titulo}" }, "; "))
                sb.append(".")
            }
            if (conHora.size > 7) sb.append(" Y ${conHora.size - 7} más.")
        } else {
            sb.append("Ahora, tus pendientes del día. No tienes juntas con horario.")
        }
        if (todoDia.isNotEmpty()) {
            sb.append(" Para todo el día: ").append(unir(todoDia.map { it.titulo })).append(".")
        }
        return sb.toString().trim()
    }

    private fun leer(c: Context, today: LocalDate): List<Evento> {
        val zone = ZoneId.systemDefault()
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, start)
        ContentUris.appendId(b, end)
        val proj = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS
        )
        val lista = mutableListOf<Evento>()
        c.contentResolver.query(b.build(), proj, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { cur ->
            while (cur.moveToNext()) {
                val titulo = (cur.getString(0) ?: "").trim().ifEmpty { "un evento sin título" }
                val inicio = cur.getLong(1)
                val allDay = cur.getInt(2) == 1
                if (cur.getInt(3) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                if (allDay) {
                    if (Instant.ofEpochMilli(inicio).atZone(ZoneOffset.UTC).toLocalDate() != today) continue
                } else if (inicio < start || inicio >= end) continue
                lista += Evento(titulo, inicio, allDay)
            }
        }
        return lista.distinctBy { it.titulo.lowercase(ES) + "|" + it.inicio }
    }

    private fun cant(n: Int, uno: String, varios: String) = if (n == 1) uno else "$n $varios"

    private fun hora(ms: Long): String {
        val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime()
        val h12 = if (t.hour % 12 == 0) 12 else t.hour % 12
        val min = when (t.minute) {
            0 -> ""
            15 -> " y cuarto"
            30 -> " y media"
            else -> " y ${t.minute}"
        }
        val periodo = when {
            t.hour < 12 -> "de la mañana"
            t.hour < 13 -> "del día"
            t.hour < 19 -> "de la tarde"
            else -> "de la noche"
        }
        return "${if (h12 == 1) "a la" else "a las"} $h12$min $periodo"
    }
}
