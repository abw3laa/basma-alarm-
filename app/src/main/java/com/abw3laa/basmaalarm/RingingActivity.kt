package com.abw3laa.basmaalarm

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class RingingActivity : ComponentActivity() {
    private var baseDay: Long = LocalDate.now().toEpochDay()
    private var windowDay: Int = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        baseDay = intent.getLongExtra(EXTRA_BASE_DAY, baseDay)
        windowDay = intent.getIntExtra(EXTRA_WINDOW, 1)
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        setContent { RingingContent(baseDay, windowDay, ::handle) }
    }

    private fun handle(action: String) {
        if (action == ACTION_STOP) {
            startService(Intent(this, AlarmPlaybackService::class.java).setAction(ACTION_MUTE).putExtra(EXTRA_BASE_DAY, baseDay).putExtra(EXTRA_WINDOW, windowDay))
            return
        }
        when (action) {
            ACTION_DONE -> { AlarmPrefs.markDone(this, baseDay); AlarmScheduler.schedule(this) }
            ACTION_SNOOZE -> AlarmScheduler.snooze(this, baseDay, windowDay)
        }
        stopService(Intent(this, AlarmPlaybackService::class.java))
        getSystemService(android.app.NotificationManager::class.java).cancel(NOTIFICATION_ID)
        finish()
    }
}

@Composable private fun RingingContent(baseDay: Long, windowDay: Int, action: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val language = currentLanguage()
    val date = LocalDate.ofEpochDay(baseDay)
    val dark = when (AlarmPrefs.theme(context)) { "dark" -> true; "light" -> false; else -> androidx.compose.foundation.isSystemInDarkTheme() }
    val bg = if (dark) Color(0xFF111B24) else Color(0xFFF8FAFB)
    val card = if (dark) Color(0xFF1B2934) else Color.White
    val ink = if (dark) Color(0xFFE8EDF1) else Color(0xFF101D28)
    val muted = if (dark) Color(0xFFB7C3CC) else Color(0xFF4C5357)
    val accent = if (dark) Color(0xFF78D9CD) else Color(0xFF006A61)
    val red = if (dark) Color(0xFFFFA39B) else Color(0xFFB4232C)
    val status = when (language) { "ar" -> "منبه البصمة نشط الآن"; "tr" -> "Parmak izi alarmı etkin"; else -> "Fingerprint alarm active" }
    val appointment = when (language) { "ar" -> "حان موعد البصمة"; "tr" -> "Parmak izi zamanı"; else -> "Fingerprint appointment" }
    Column(Modifier.fillMaxSize().background(bg).padding(horizontal = 20.dp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("● $status", color = red, fontSize = 12.sp)
            val enabled = AlarmPrefs.sound(context) || AlarmPrefs.vibrate(context)
            if (enabled) Text(when (language) { "ar" -> "صوت + اهتزاز"; "tr" -> "Ses + titreşim"; else -> "Sound + vibration" }, color = muted, fontSize = 12.sp)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(color = if (dark) Color(0xFF49252A) else Color(0xFFFFE8E7), shape = RoundedCornerShape(100.dp), modifier = Modifier.size(148.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Fingerprint, null, tint = red, modifier = Modifier.size(94.dp))
                    Icon(Icons.Filled.NotificationsActive, null, tint = red, modifier = Modifier.size(32.dp).align(Alignment.BottomCenter))
                }
            }
            Text(LocalTime.of(AlarmPrefs.hour(context), AlarmPrefs.minute(context)).format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())), color = ink, fontSize = 56.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
            Text(date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.forLanguageTag(language))), color = muted)
            Text(appointment, color = ink, fontSize = 25.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 22.dp))
        }
        Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(when (language) { "ar" -> "نافذة الأيام الثلاثة المتاحة"; "tr" -> "Üç günlük randevu aralığı"; else -> "Three-day appointment window" }, color = ink, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    (0..2).forEach { i ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(when (language) { "ar" -> "اليوم ${i + 1}"; "tr" -> "${i + 1}. gün"; else -> "Day ${i + 1}" }, color = if (i + 1 == windowDay) red else muted)
                            Text(date.plusDays(i.toLong()).format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())), color = ink, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Button(onClick = { action(ACTION_DONE) }, Modifier.fillMaxWidth().height(54.dp), colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = if (dark) Color(0xFF0D2925) else Color.White), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Filled.Fingerprint, null); Spacer(Modifier.width(8.dp))
                Text(when (language) { "ar" -> "تمت البصمة"; "tr" -> "Parmak izi tamamlandı"; else -> "Fingerprint completed" }, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = { action(ACTION_SNOOZE) }, Modifier.fillMaxWidth().padding(top = 10.dp).height(50.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = ink), shape = RoundedCornerShape(14.dp)) {
                Text(when (language) { "ar" -> "ذكرني لاحقاً (60 دقيقة)"; "tr" -> "60 dakika sonra hatırlat"; else -> "Snooze for 60 minutes" })
            }
            TextButton(onClick = { action(ACTION_STOP) }, colors = ButtonDefaults.textButtonColors(contentColor = muted)) {
                Text(when (language) { "ar" -> "إيقاف الصوت فقط"; "tr" -> "Yalnızca sesi durdur"; else -> "Stop sound only" })
            }
        }
    }
}
