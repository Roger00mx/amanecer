package com.rogeliorivera.amanecer

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

data class VoicePreset(val label: String, val voiceName: String?, val pitch: Float, val rate: Float)

object Voices {

    private fun country(l: Locale): String = when (l.country.uppercase()) {
        "MX" -> "México"
        "US" -> "Latinoamérica"
        "ES" -> "España"
        "AR" -> "Argentina"
        "CO" -> "Colombia"
        "CL" -> "Chile"
        "PE" -> "Perú"
        "" -> "Español"
        else -> l.displayCountry
    }

    private fun rank(v: Voice): Int = when (v.locale.country.uppercase()) {
        "MX" -> 0
        "US" -> 1
        "419" -> 2
        "ES" -> 4
        else -> 3
    }

    /** Siempre devuelve 4 opciones: voces distintas del teléfono y, si faltan, variantes de tono. */
    fun presets(tts: TextToSpeech?): List<VoicePreset> {
        val voices: List<Voice> = try {
            tts?.voices?.filter {
                it.locale.language == "es" &&
                    !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            }?.sortedWith(compareBy<Voice>({ rank(it) }, { it.isNetworkConnectionRequired }, { it.name }))
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        val list = mutableListOf<VoicePreset>()
        voices.take(4).forEachIndexed { i, v ->
            list += VoicePreset("Voz ${i + 1} · ${country(v.locale)}", v.name, 1.0f, 0.95f)
        }
        val base = voices.firstOrNull()?.name
        val variantes = listOf(
            VoicePreset("Grave y tranquila", base, 0.82f, 0.9f),
            VoicePreset("Aguda y alegre", base, 1.2f, 1.0f),
            VoicePreset("Enérgica", base, 1.05f, 1.12f),
            VoicePreset("Pausada", base, 0.95f, 0.82f)
        )
        var k = 0
        while (list.size < 4 && k < variantes.size) {
            list += variantes[k]
            k++
        }
        if (list.size < 4) {
            while (list.size < 4) list += VoicePreset("Voz ${list.size + 1}", null, 1.0f, 0.95f)
        }
        return list
    }

    /** Aplica la voz guardada al motor de voz. */
    fun apply(c: Context, tts: TextToSpeech) {
        val r = tts.setLanguage(Locale("es", "MX"))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale("es"))
        }
        val name = Prefs.voiceName(c)
        if (name != null) {
            try {
                tts.voices?.firstOrNull { it.name == name }?.let { tts.voice = it }
            } catch (e: Exception) {
            }
        }
        tts.setPitch(Prefs.voicePitch(c))
        tts.setSpeechRate(Prefs.voiceRate(c))
    }

    fun applyPreset(tts: TextToSpeech, p: VoicePreset) {
        tts.setLanguage(Locale("es", "MX"))
        if (p.voiceName != null) {
            try {
                tts.voices?.firstOrNull { it.name == p.voiceName }?.let { tts.voice = it }
            } catch (e: Exception) {
            }
        }
        tts.setPitch(p.pitch)
        tts.setSpeechRate(p.rate)
    }
}
