package com.rogeliorivera.amanecer

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class AlarmActivity : ComponentActivity() {

    private var tick by mutableIntStateOf(0)
    private val listener: () -> Unit = {
        tick++
        if (AlarmService.state == "idle" && closeWhenIdle) finish()
    }
    private var closeWhenIdle = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Con la pantalla visible sí se puede actualizar la ubicación para el clima
        Ubicacion.refresh(this)

        setContent {
            AmanecerTheme {
                @Suppress("UNUSED_VARIABLE") val t = tick
                AlarmScreen(
                    state = AlarmService.state,
                    briefing = AlarmService.briefing,
                    nowPlaying = AlarmService.nowPlaying,
                    name = Prefs.name(this),
                    onWake = { startService(AlarmService.intent(this, AlarmService.ACTION_DISMISS)) },
                    onSnooze = {
                        startService(AlarmService.intent(this, AlarmService.ACTION_SNOOZE))
                        finish()
                    },
                    onSkip = { startService(AlarmService.intent(this, AlarmService.ACTION_SKIP)) },
                    onStop = {
                        if (AlarmService.state != "idle") startService(AlarmService.intent(this, AlarmService.ACTION_STOP))
                        finish()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AlarmService.listeners.add(listener)
        tick++
    }

    override fun onPause() {
        AlarmService.listeners.remove(listener)
        super.onPause()
    }
}

@Composable
private fun AlarmScreen(
    state: String,
    briefing: String,
    nowPlaying: String,
    name: String,
    onWake: () -> Unit,
    onSnooze: () -> Unit,
    onSkip: () -> Unit,
    onStop: () -> Unit
) {
    val ringing = state == "ringing"
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Navy)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(Modifier.height(if (ringing) 60.dp else 16.dp))
        Text("☀️", fontSize = 56.sp)
        Text(
            LocalTime.now().format(DateTimeFormatter.ofPattern("H:mm")),
            fontSize = 72.sp, fontWeight = FontWeight.Bold, color = Orange
        )
        Text(
            when (state) {
                "ringing" -> "Buenos días, $name"
                "preparing" -> "Preparando tu resumen…"
                "running" -> if (nowPlaying.isNotEmpty()) "🎵 $nowPlaying" else "Tu resumen de hoy"
                else -> "¡Que tengas un excelente día!"
            },
            fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = TextMain,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
        if (ringing) {
            Button(
                onClick = onWake,
                modifier = Modifier.fillMaxWidth().height(72.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Navy)
            ) { Text("☀️  Ya desperté", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(14.dp))
            OutlinedButton(
                onClick = onSnooze,
                modifier = Modifier.fillMaxWidth().height(60.dp)
            ) { Text("😴  5 minutos más", fontSize = 18.sp, color = TextMain) }
        } else {
            if (briefing.isNotBlank()) {
                Text(briefing, fontSize = 17.sp, color = TextMain, lineHeight = 24.sp)
                Spacer(Modifier.height(20.dp))
            }
            if (state == "running") {
                OutlinedButton(onClick = onSkip, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text("⏭  Saltar esta parte", fontSize = 17.sp, color = TextMain)
                }
                Spacer(Modifier.height(10.dp))
            }
            Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CardBg, contentColor = TextMain)
            ) { Text(if (state == "idle") "Cerrar" else "⏹  Detener", fontSize = 17.sp) }
        }
    }
}
