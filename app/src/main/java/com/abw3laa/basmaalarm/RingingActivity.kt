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
import androidx.compose.ui.text.style.TextAlign
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
    val date = LocalDate.ofEpochDay(baseDay)
    Column(Modifier.fillMaxSize().background(Color(0xFFF8FAFB)).padding(horizontal = 20.dp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("● ${if (currentLanguage() == "ar") "منبه البصمة نشط الآن" else "Fingerprint alarm active"}", color = Color(0xFF8B2930), fontSize = 12.sp)
            Text("${if (currentLanguage() == "ar") "صوت + اهتزاز" else "Sound + vibration"}", color = Color(0xFF4C5357), fontSize = 12.sp)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(color = Color(0xFFFFE8E7), shape = RoundedCornerShape(100.dp), modifier = Modifier.size(132.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Fingerprint, null, tint = Color(0xFFB4232C), modifier = Modifier.size(84.dp)); Icon(Icons.Filled.NotificationsActive, null, tint = Color(0xFFB4232C), modifier = Modifier.size(30.dp).align(Alignment.BottomCenter)) }
            }
            Text(LocalTime.of(AlarmPrefs.hour(context), AlarmPrefs.minute(context)).format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())), color = Color(0xFF0F2537), fontSize = 52.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
            Text(date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.forLanguageTag(AlarmPrefs.language(context)))), color = Color(0xFF4C5357))
            Text(if (AlarmPrefs.language(context) == "ar") "حان موعد البصمة" else if (AlarmPrefs.language(context) == "tr") "Parmak izi zamanı" else "Fingerprint appointment", color = Color(0xFF101D28), fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 22.dp))
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(if (AlarmPrefs.language(context) == "ar") "نافذة الأيام الثلاثة المتاحة" else "Three-day appointment window", color = Color(0xFF152332), fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    (0..2).forEach { i -> Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(if (AlarmPrefs.language(context) == "ar") "اليوم ${i + 1}" else "Day ${i + 1}", color = if (i + 1 == windowDay) Color(0xFFB4232C) else Color.Gray); Text(date.plusDays(i.toLong()).format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())), color = Color(0xFF20272C), fontWeight = FontWeight.SemiBold) } }
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Button(onClick = { action(ACTION_DONE) }, Modifier.fillMaxWidth().height(54.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF006A61)), shape = RoundedCornerShape(14.dp)) { Icon(Icons.Filled.Fingerprint, null); Spacer(Modifier.width(8.dp)); Text(if (AlarmPrefs.language(context) == "ar") "تمت البصمة" else "Fingerprint completed", fontWeight = FontWeight.Bold) }
            OutlinedButton(onClick = { action(ACTION_SNOOZE) }, Modifier.fillMaxWidth().padding(top = 10.dp).height(50.dp), shape = RoundedCornerShape(14.dp)) { Text(if (AlarmPrefs.language(context) == "ar") "ذكرني لاحقاً (60 دقيقة)" else "Snooze for 60 minutes") }
            TextButton(onClick = { action(ACTION_STOP) }) { Text(if (AlarmPrefs.language(context) == "ar") "إيقاف الصوت فقط" else "Stop sound only", color = Color(0xFF5D6368)) }
        }
    }
}
