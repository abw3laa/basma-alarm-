package com.abw3laa.basmaalarm

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
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
        windowDay = intent.getIntExtra(EXTRA_WINDOW, 1).coerceIn(1, 3)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
            window.insetsController?.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
        setContent { RingingContent(baseDay, windowDay, ::handle) }
    }

    override fun onResume() {
        super.onResume()
        AlarmPrefs.sp(this).edit().putBoolean("ring_activity_visible", true).apply()
        runCatching { startService(Intent(this, AlarmPlaybackService::class.java).setAction(AlarmPlaybackService.ACTION_SCREEN_VISIBLE)) }
    }

    override fun onPause() {
        AlarmPrefs.sp(this).edit().putBoolean("ring_activity_visible", false).apply()
        if (!isFinishing) runCatching {
            startService(Intent(this, AlarmPlaybackService::class.java)
                .setAction(AlarmPlaybackService.ACTION_ACTIVITY_HIDDEN)
                .putExtra(EXTRA_BASE_DAY, baseDay)
                .putExtra(EXTRA_WINDOW, windowDay))
        }
        super.onPause()
    }

    private fun handle(action: String) {
        if (action == ACTION_STOP) {
            startService(Intent(this, AlarmPlaybackService::class.java)
                .setAction(ACTION_MUTE)
                .putExtra(EXTRA_BASE_DAY, baseDay)
                .putExtra(EXTRA_WINDOW, windowDay))
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

@Composable
private fun RingingContent(baseDay: Long, windowDay: Int, action: (String) -> Unit) {
    val context = LocalContext.current
    val language = currentLanguage()
    val isArabic = language == "ar"
    val direction = if (isArabic) LayoutDirection.Rtl else LayoutDirection.Ltr
    val dark = when (AlarmPrefs.theme(context)) {
        "dark" -> true
        "light" -> false
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    val bg = if (dark) Color(0xFF111B24) else Color(0xFFF7F9FB)
    val card = if (dark) Color(0xFF1B2934) else Color.White
    val panel = if (dark) Color(0xFF202D38) else Color(0xFFF1F3F5)
    val ink = if (dark) Color(0xFFE8EDF1) else Color(0xFF101D28)
    val muted = if (dark) Color(0xFFBAC5CD) else Color(0xFF4C5357)
    val accent = if (dark) Color(0xFF86F2E4) else Color(0xFF006A61)
    val red = if (dark) Color(0xFFFFA39B) else Color(0xFFB4232C)
    val redSurface = if (dark) Color(0xFF49252A) else Color(0xFFFFE8E7)
    val baseDate = LocalDate.ofEpochDay(baseDay)
    val activeDate = baseDate.plusDays((windowDay - 1).toLong())
    val alarmTime = LocalTime.of(AlarmPrefs.hour(context), AlarmPrefs.minute(context))
    val timeText = alarmTime.format(DateTimeFormatter.ofPattern("h:mm", Locale.US))
    val period = when (language) {
        "ar" -> if (alarmTime.hour < 12) "صباحًا" else "مساءً"
        "tr" -> if (alarmTime.hour < 12) "ÖÖ" else "ÖS"
        else -> if (alarmTime.hour < 12) "AM" else "PM"
    }
    val dateLocale = Locale.forLanguageTag(language)
    val datePattern = if (isArabic) "EEEE، d MMMM yyyy" else "EEEE, d MMMM yyyy"
    val dateFormatter = DateTimeFormatter.ofPattern(datePattern, dateLocale)
    val shortDateFormatter = DateTimeFormatter.ofPattern(if (isArabic) "d MMMM" else "d MMM", dateLocale)
    val snoozeMinutes = AlarmPrefs.snoozeMinutes(context)
    val remainingMinutes = (24 * 60 - LocalTime.now().hour * 60 - LocalTime.now().minute).coerceAtLeast(0)
    val remainingHours = remainingMinutes / 60
    val remainingText = when (language) {
        "ar" -> "$remainingHours ساعة"
        "tr" -> "$remainingHours saat"
        else -> "$remainingHours hours"
    }
    val soundState = when {
        AlarmPrefs.sound(context) && AlarmPrefs.vibrate(context) -> when (language) { "ar" -> "صوت + اهتزاز مستمر"; "tr" -> "Ses + titreşim"; else -> "Sound + vibration" }
        AlarmPrefs.sound(context) -> when (language) { "ar" -> "صوت التنبيه"; "tr" -> "Alarm sesi"; else -> "Alarm sound" }
        AlarmPrefs.vibrate(context) -> when (language) { "ar" -> "اهتزاز فقط"; "tr" -> "Yalnızca titreşim"; else -> "Vibration only" }
        else -> when (language) { "ar" -> "التنبيه صامت"; "tr" -> "Sessiz alarm"; else -> "Silent alarm" }
    }

    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        Column(
            Modifier.fillMaxSize()
                .background(bg)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Surface(color = redSurface, shape = CircleShape, border = BorderStroke(1.dp, red.copy(alpha = .18f))) {
                    Row(Modifier.padding(horizontal = 11.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.NotificationsActive, null, tint = red, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(when (language) { "ar" -> "منبه البصمة نشط الآن"; "tr" -> "Parmak izi alarmı etkin"; else -> "Fingerprint alarm active" }, color = red, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(soundState, color = muted, fontSize = 10.sp, maxLines = 1)
                }
            }

            Box(Modifier.padding(top = 9.dp).size(166.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(166.dp).clip(CircleShape).background(red.copy(alpha = .06f)))
                Box(Modifier.size(142.dp).border(2.dp, red.copy(alpha = .18f), CircleShape))
                Surface(color = redSurface, shape = CircleShape, border = BorderStroke(4.dp, red), shadowElevation = 8.dp, modifier = Modifier.size(118.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Fingerprint, null, tint = red, modifier = Modifier.size(76.dp))
                        Surface(color = red, shape = CircleShape, modifier = Modifier.size(34.dp).align(Alignment.BottomCenter).offset(y = 5.dp)) {
                            Icon(Icons.Filled.NotificationsActive, null, tint = Color.White, modifier = Modifier.padding(7.dp))
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
                Text(timeText, color = ink, fontSize = 58.sp, lineHeight = 62.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.width(7.dp))
                Text(period, color = accent, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(activeDate.format(dateFormatter), color = muted, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
            Text(
                when (language) { "ar" -> "حان موعد البصمة"; "tr" -> "Parmak izi zamanı"; else -> "Fingerprint appointment" },
                color = ink, fontSize = 23.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 15.dp, bottom = 13.dp)
            )

            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = card),
                shape = RoundedCornerShape(19.dp),
                border = BorderStroke(1.dp, muted.copy(alpha = .13f)),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(when (language) { "ar" -> "نافذة الـ 3 أيام المتاحة"; "tr" -> "3 günlük randevu aralığı"; else -> "3-day appointment window" }, color = ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Surface(color = redSurface, shape = CircleShape) {
                            Text(when (language) { "ar" -> "اليوم $windowDay من 3"; "tr" -> "$windowDay / 3. gün"; else -> "Day $windowDay of 3" }, Modifier.padding(horizontal = 9.dp, vertical = 4.dp), color = red, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (0..2).forEach { index ->
                            val dayNumber = index + 1
                            val selected = dayNumber == windowDay
                            val doneBefore = dayNumber < windowDay
                            val barColor = when { selected -> red; doneBefore -> accent.copy(alpha = .55f); else -> muted.copy(alpha = .18f) }
                            Column(Modifier.weight(1f)) {
                                Box(Modifier.fillMaxWidth().height(7.dp).clip(CircleShape).background(barColor))
                                Text(
                                    when (language) {
                                        "ar" -> "اليوم $dayNumber${if (selected) " (نشط)" else if (dayNumber == 3) " (الأخير)" else " (تذكير)"}"
                                        "tr" -> "$dayNumber. gün${if (selected) " (etkin)" else if (dayNumber == 3) " (son)" else " (hatırlatma)"}"
                                        else -> "Day $dayNumber${if (selected) " (active)" else if (dayNumber == 3) " (last)" else " (reminder)"}"
                                    },
                                    color = if (selected) ink else muted,
                                    fontSize = 9.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    modifier = Modifier.padding(top = 5.dp)
                                )
                                Text(baseDate.plusDays(index.toLong()).format(shortDateFormatter), color = muted, fontSize = 9.sp, maxLines = 1)
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(top = 10.dp), color = muted.copy(alpha = .15f))
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Schedule, null, tint = accent, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                when (language) { "ar" -> "متبقي على إغلاق اليوم $windowDay: $remainingText"; "tr" -> "$windowDay. gün kapanana: $remainingText"; else -> "Time left today: $remainingText" },
                                color = muted, fontSize = 9.sp, maxLines = 2
                            )
                        }
                        Text("${baseDate.format(shortDateFormatter)} – ${baseDate.plusDays(2).format(shortDateFormatter)}", color = accent, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp)
                    .background(if (dark) Color(0xFF39272B) else Color(0xFFFFF0EF), RoundedCornerShape(13.dp))
                    .border(1.dp, red.copy(alpha = .20f), RoundedCornerShape(13.dp))
                    .padding(horizontal = 11.dp, vertical = 9.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(Icons.Filled.Info, null, tint = red, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    when (language) {
                        "ar" -> "تنبيه: إيقاف صوت المنبه لا يعتبر تأكيدًا لحضور الموعد. لإثبات حضورك وانتقال المعاملة اضغط زر «تمت البصمة»."
                        "tr" -> "Not: Alarm sesini kapatmak randevuyu onaylamaz. İşlemi tamamlamak için «Parmak izi tamamlandı» düğmesine basın."
                        else -> "Note: Silencing the alarm does not confirm attendance. To confirm the appointment, tap “Fingerprint completed”."
                    },
                    color = ink, fontSize = 10.sp, lineHeight = 15.sp, modifier = Modifier.weight(1f)
                )
            }

            Button(
                onClick = { action(ACTION_DONE) },
                modifier = Modifier.fillMaxWidth().padding(top = 13.dp).height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = if (dark) Color(0xFF0D2925) else Color.White),
                shape = RoundedCornerShape(16.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
            ) {
                Icon(Icons.Filled.Check, null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(when (language) { "ar" -> "تمت البصمة"; "tr" -> "Parmak izi tamamlandı"; else -> "Fingerprint completed" }, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = { action(ACTION_SNOOZE) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(50.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = ink),
                border = BorderStroke(1.dp, muted.copy(alpha = .38f)),
                shape = RoundedCornerShape(15.dp)
            ) {
                Icon(Icons.Filled.Schedule, null, tint = accent, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(7.dp))
                Text(when (language) { "ar" -> "ذكرني لاحقًا"; "tr" -> "Daha sonra hatırlat"; else -> "Remind me later" }, fontSize = 14.sp)
            }
            TextButton(onClick = { action(ACTION_STOP) }, colors = ButtonDefaults.textButtonColors(contentColor = muted), modifier = Modifier.height(42.dp)) {
                Icon(Icons.Filled.VolumeOff, null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(5.dp))
                Text(when (language) { "ar" -> "إيقاف الصوت فقط مع إبقاء التذكير على الشاشة"; "tr" -> "Sesi durdur, hatırlatıcı ekranda kalsın"; else -> "Silence only; keep reminder on screen" }, fontSize = 10.sp)
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.History, null, tint = muted, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    when (language) { "ar" -> "في حال التأجيل، سيعاد تشغيل الرنين تلقائيًا بعد $snoozeMinutes دقيقة."; "tr" -> "Erteleme sonrası alarm $snoozeMinutes dakika sonra yeniden çalar."; else -> "If snoozed, the alarm rings again after $snoozeMinutes minutes." },
                    color = muted, fontSize = 9.sp, textAlign = TextAlign.Center
                )
            }
        }
    }
}
