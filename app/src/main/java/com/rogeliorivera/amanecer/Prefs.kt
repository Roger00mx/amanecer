package com.rogeliorivera.amanecer

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class Song(val uri: String, val name: String)

/** Todas las preferencias de la app en un solo lugar. */
object Prefs {
    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences("amanecer", Context.MODE_PRIVATE)

    // ---- Alarma ----
    fun hour(c: Context) = sp(c).getInt("hour", 6)
    fun minute(c: Context) = sp(c).getInt("minute", 0)
    fun enabled(c: Context) = sp(c).getBoolean("enabled", false)

    /** Días activos: 1 = lunes … 7 = domingo. */
    fun days(c: Context): Set<Int> =
        (sp(c).getString("days", "1,2,3,4,5,6,7") ?: "")
            .split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()

    fun setTime(c: Context, h: Int, m: Int) = sp(c).edit().putInt("hour", h).putInt("minute", m).apply()
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()
    fun setDays(c: Context, d: Set<Int>) = sp(c).edit().putString("days", d.sorted().joinToString(",")).apply()

    // ---- Persona ----
    fun name(c: Context): String = sp(c).getString("name", "Rogelio") ?: "Rogelio"
    fun setName(c: Context, v: String) = sp(c).edit().putString("name", v.trim()).apply()

    // ---- Sonido de alarma: gallo | beep | voz | sistema | custom ----
    fun alarmSound(c: Context): String = sp(c).getString("alarmSound", "gallo") ?: "gallo"
    fun setAlarmSound(c: Context, v: String) = sp(c).edit().putString("alarmSound", v).apply()
    fun customSound(c: Context): Song? = songFrom(sp(c).getString("customSound", null))
    fun setCustomSound(c: Context, s: Song) = sp(c).edit().putString("customSound", songTo(s).toString()).apply()

    // ---- Voz ----
    fun voiceIndex(c: Context) = sp(c).getInt("voiceIndex", 0)
    fun voiceName(c: Context): String? = sp(c).getString("voiceName", null)
    fun voicePitch(c: Context) = sp(c).getFloat("voicePitch", 1.0f)
    fun voiceRate(c: Context) = sp(c).getFloat("voiceRate", 0.95f)
    fun setVoice(c: Context, index: Int, p: VoicePreset) = sp(c).edit()
        .putInt("voiceIndex", index)
        .putString("voiceName", p.voiceName)
        .putFloat("voicePitch", p.pitch)
        .putFloat("voiceRate", p.rate)
        .apply()

    // ---- Frase: motivadora | entusiasta | versiculo | mezcla ----
    fun phraseType(c: Context): String = sp(c).getString("phraseType", "motivadora") ?: "motivadora"
    fun setPhraseType(c: Context, v: String) = sp(c).edit().putString("phraseType", v).apply()

    // ---- Secciones del resumen ----
    fun section(c: Context, key: String): Boolean = sp(c).getBoolean("sec_$key", true)
    fun setSection(c: Context, key: String, v: Boolean) = sp(c).edit().putBoolean("sec_$key", v).apply()

    // ---- Ubicación (última conocida) ----
    fun lat(c: Context): Double = sp(c).getString("lat", null)?.toDoubleOrNull() ?: 19.0633
    fun lon(c: Context): Double = sp(c).getString("lon", null)?.toDoubleOrNull() ?: -98.3064
    fun city(c: Context): String = sp(c).getString("city", null) ?: "San Pedro Cholula"
    fun hasLocation(c: Context) = sp(c).contains("lat")
    fun setLocation(c: Context, lat: Double, lon: Double) =
        sp(c).edit().putString("lat", lat.toString()).putString("lon", lon.toString()).apply()
    fun setCity(c: Context, v: String) = sp(c).edit().putString("city", v).apply()

    // ---- Canciones finales ----
    fun songs(c: Context): List<Song> {
        val raw = sp(c).getString("songs", "[]") ?: "[]"
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { songFrom(a.getJSONObject(it).toString()) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setSongs(c: Context, list: List<Song>) {
        val a = JSONArray()
        list.forEach { a.put(songTo(it)) }
        sp(c).edit().putString("songs", a.toString()).apply()
    }

    private fun songTo(s: Song) = JSONObject().put("uri", s.uri).put("name", s.name)
    private fun songFrom(raw: String?): Song? = try {
        if (raw == null) null else JSONObject(raw).let { Song(it.getString("uri"), it.getString("name")) }
    } catch (e: Exception) {
        null
    }

    // ---- Spotify ----
    /** local | spotify */
    fun songSource(c: Context): String = sp(c).getString("songSource", "local") ?: "local"
    fun setSongSource(c: Context, v: String) = sp(c).edit().putString("songSource", v).apply()
    fun spotifyUri(c: Context): String? = sp(c).getString("spotifyUri", null)
    fun setSpotifyUri(c: Context, v: String?) = sp(c).edit().putString("spotifyUri", v).apply()
    fun spotifyConnected(c: Context) = sp(c).getBoolean("spotifyConnected", false)
    fun setSpotifyConnected(c: Context, v: Boolean) = sp(c).edit().putBoolean("spotifyConnected", v).apply()

    // ---- Primera vez ----
    fun onboarded(c: Context) = sp(c).getBoolean("onboarded", false)
    fun setOnboarded(c: Context) = sp(c).edit().putBoolean("onboarded", true).apply()
}
