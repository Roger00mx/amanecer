package com.rogeliorivera.amanecer

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse

/** Conexión con la app de Spotify (App Remote SDK). Requiere Spotify Premium. */
object SpotifyLink {
    const val CLIENT_ID = "8c36b6a415b04abf9c03c4f8c6151d35"
    const val REDIRECT_URI = "amanecer://spotify-callback"

    private val main = Handler(Looper.getMainLooper())

    /** Pantalla de Spotify para que el usuario autorice a Amanecer. */
    fun loginIntent(a: android.app.Activity): android.content.Intent {
        val req = AuthorizationRequest.Builder(CLIENT_ID, AuthorizationResponse.Type.TOKEN, REDIRECT_URI)
            .setScopes(arrayOf("app-remote-control", "user-modify-playback-state"))
            .build()
        return AuthorizationClient.createLoginActivityIntent(a, req)
    }

    /** null si salió bien; si no, el mensaje de error. */
    fun loginResult(resultCode: Int, data: android.content.Intent?): String? {
        val r = AuthorizationClient.getResponse(resultCode, data)
        return when (r.type) {
            AuthorizationResponse.Type.TOKEN -> null
            AuthorizationResponse.Type.ERROR -> r.error ?: "error"
            else -> "cancelado"
        }
    }

    fun isInstalled(c: Context): Boolean = try {
        SpotifyAppRemote.isSpotifyInstalled(c)
    } catch (e: Exception) {
        false
    }

    /** Convierte un enlace de Spotify (https://open.spotify.com/...) a URI spotify:tipo:id */
    fun toUri(input: String): String? {
        val s = input.trim()
        if (Regex("^spotify:(track|album|playlist|artist|show|episode):[A-Za-z0-9]+$").matches(s)) return s
        val m = Regex("open\\.spotify\\.com/(?:intl-[a-zA-Z-]+/)?(track|album|playlist|artist|show|episode)/([A-Za-z0-9]+)")
            .find(s) ?: return null
        return "spotify:${m.groupValues[1]}:${m.groupValues[2]}"
    }

    fun describe(uri: String?): String {
        if (uri == null) return "Nada elegido"
        return when (uri.split(":").getOrNull(1)) {
            "playlist" -> "Playlist de Spotify"
            "album" -> "Álbum de Spotify"
            "track" -> "Canción de Spotify"
            "artist" -> "Artista de Spotify"
            "show", "episode" -> "Podcast de Spotify"
            else -> "Spotify"
        }
    }

    fun connect(c: Context, showAuth: Boolean, done: (SpotifyAppRemote?, String?) -> Unit) {
        if (!isInstalled(c)) {
            done(null, "Spotify no está instalado en este celular.")
            return
        }
        val params = ConnectionParams.Builder(CLIENT_ID)
            .setRedirectUri(REDIRECT_URI)
            .showAuthView(showAuth)
            .build()
        var finished = false
        val timeout = Runnable {
            if (!finished) {
                finished = true
                done(null, "Spotify no respondió a tiempo.")
            }
        }
        main.postDelayed(timeout, 15_000)
        try {
            SpotifyAppRemote.connect(c, params, object : Connector.ConnectionListener {
                override fun onConnected(remote: SpotifyAppRemote) {
                    main.removeCallbacks(timeout)
                    if (!finished) {
                        finished = true
                        done(remote, null)
                    } else {
                        SpotifyAppRemote.disconnect(remote)
                    }
                }

                override fun onFailure(error: Throwable) {
                    main.removeCallbacks(timeout)
                    if (!finished) {
                        finished = true
                        done(null, error.message ?: error.javaClass.simpleName)
                    }
                }
            })
        } catch (e: Exception) {
            main.removeCallbacks(timeout)
            if (!finished) {
                finished = true
                done(null, e.message ?: "Error al conectar con Spotify")
            }
        }
    }

    /** Pone en Spotify el contenido elegido, en modo aleatorio. */
    fun play(c: Context, uri: String, done: (Boolean, String?) -> Unit) {
        connect(c, false) { remote, err ->
            if (remote == null) {
                done(false, err)
                return@connect
            }
            try {
                val player = remote.playerApi
                val isTrack = uri.startsWith("spotify:track:")
                player.setShuffle(!isTrack)
                val call = player.play(uri)
                call.setResultCallback {
                    done(true, null)
                    main.postDelayed({ SpotifyAppRemote.disconnect(remote) }, 3000)
                }
                call.setErrorCallback { e ->
                    done(false, e.message)
                    SpotifyAppRemote.disconnect(remote)
                }
            } catch (e: Exception) {
                done(false, e.message)
                SpotifyAppRemote.disconnect(remote)
            }
        }
    }
}
