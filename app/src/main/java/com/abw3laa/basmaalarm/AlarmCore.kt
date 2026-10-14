package com.abw3laa.basmaalarm

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

object AlarmPrefs {
    private const val FILE = "basma_alarm_prefs"
    fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun hasSchedule(c: Context) = sp(c).contains("start")
    fun start(c: Context): LocalDate = runCatching { LocalDate.parse(sp(c).getString("start", null) ?: LocalDate.now().toString()) }.getOrElse { LocalDate.now() }
    fun hour(c: Context) = sp(c).getInt("hour", defaultHour(c)).coerceIn(0, 23)
    fun minute(c: Context) = sp(c).getInt("minute", defaultMinute(c)).coerceIn(0, 59)
    fun defaultHour(c: Context) = sp(c).getInt("default_hour", 10).coerceIn(0, 23)
    fun defaultMinute(c: Context) = sp(c).getInt("default_minute", 0).coerceIn(0, 59)
    fun interval(c: Context) = sp(c).getInt("interval", 28).coerceIn(1, 365)
    fun count(c: Context) = sp(c).getInt("count", 14).coerceIn(1, 60)
    fun language(c: Context) = sp(c).getString("language", "ar") ?: "ar"
    fun theme(c: Context) = sp(c).getString("theme", "system") ?: "system"
    fun vibrate(c: Context) = sp(c).getBoolean("vibrate", true)
    fun sound(c: Context) = sp(c).getBoolean("sound", true)
    fun soundUri(c: Context): String? = sp(c).getString("sound_uri", null)
    fun autoStopMinutes(c: Context) = sp(c).getInt("auto_stop_minutes", 10).coerceIn(0, 120)
    fun snoozeMinutes(c: Context) = sp(c).getInt("snooze_minutes", 60).coerceIn(1, 240)
    fun highPriority(c: Context) = sp(c).getBoolean("high_priority", true)
    fun remindDayBefore(c: Context) = sp(c).getBoolean("remind_day_before", true)
    fun repeatOnFingerprintDay(c: Context) = sp(c).getBoolean("repeat_on_fingerprint_day", true)
    fun remindSecondDay(c: Context) = sp(c).getBoolean("remind_second_day", true)
    fun completed(c: Context): Set<Long> = sp(c).getStringSet("completed", emptySet())?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
    fun markDone(c: Context, baseDay: Long) { sp(c).edit().putStringSet("completed", (completed(c) + baseDay).map { it.toString() }.toSet()).apply() }
    fun clearSchedule(c: Context) { sp(c).edit().remove("start").remove("count").remove("interval").remove("hour").remove("minute").remove("completed").apply() }
}

object AlarmScheduler {
    private fun pending(c: Context, requestCode: Int, baseDay: Long, epochDay: Long, windowDay: Int, action: String = ACTION_FIRE, repeated: Boolean = false): PendingIntent {
        val i = Intent(c, AlarmReceiver::class.java).setAction(action)
            .putExtra(EXTRA_BASE_DAY, baseDay).putExtra(EXTRA_EPOCH_DAY, epochDay).putExtra(EXTRA_WINDOW, windowDay)
            .putExtra(EXTRA_REPEAT, repeated)
        return PendingIntent.getBroadcast(c, requestCode, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    private fun repeatId(baseDay: Long) = 2_000_000 + (baseDay % 500_000).toInt()
    private fun snoozeId(baseDay: Long) = 1_000_000 + (baseDay % 500_000).toInt()
    private fun preNoticeId(epochDay: Long) = 3_000_000 + (epochDay % 500_000).toInt()

    fun cancelAll(c: Context) {
        if (!AlarmPrefs.hasSchedule(c)) return
        val am = c.getSystemService(AlarmManager::class.java)
        val s = AlarmPrefs.start(c); val interval = AlarmPrefs.interval(c); val count = AlarmPrefs.count(c)
        for (n in 0 until count) {
            val base = s.plusDays(n.toLong() * interval).toEpochDay()
            listOf(repeatId(base), snoozeId(base)).forEach { id ->
                PendingIntent.getBroadcast(c, id, Intent(c, AlarmReceiver::class.java).setAction(ACTION_FIRE), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let { am.cancel(it); it.cancel() }
            }
            for (d in 0..2) {
                val day = base + d
                PendingIntent.getBroadcast(c, day.toInt(), Intent(c, AlarmReceiver::class.java).setAction(ACTION_FIRE), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let { am.cancel(it); it.cancel() }
            }
            val before = base - 1
            PendingIntent.getBroadcast(c, preNoticeId(before), Intent(c, AlarmReceiver::class.java).setAction(ACTION_PRE_NOTICE), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let { am.cancel(it); it.cancel() }
        }
    }
    fun schedule(c: Context) {
        cancelAll(c)
        if (!AlarmPrefs.hasSchedule(c)) return
        val am = c.getSystemService(AlarmManager::class.java)
        val start = AlarmPrefs.start(c); val interval = AlarmPrefs.interval(c); val count = AlarmPrefs.count(c)
        val done = AlarmPrefs.completed(c)
        val now = System.currentTimeMillis()
        for (n in 0 until count) {
            val base = start.plusDays(n.toLong() * interval)
            val baseEpoch = base.toEpochDay()
            if (baseEpoch in done) continue
            if (AlarmPrefs.remindDayBefore(c)) {
                val day = base.minusDays(1)
                val at = day.atTime(AlarmPrefs.hour(c), AlarmPrefs.minute(c)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                if (at > now) setAlarm(c, am, at, pending(c, preNoticeId(day.toEpochDay()), baseEpoch, day.toEpochDay(), 0, ACTION_PRE_NOTICE))
            }
            for (d in 0..2) {
                if (d == 1 && !AlarmPrefs.remindSecondDay(c)) continue
                val date = base.plusDays(d.toLong())
                val at = date.atTime(AlarmPrefs.hour(c), AlarmPrefs.minute(c)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                if (at > now) setAlarm(c, am, at, pending(c, date.toEpochDay().toInt(), baseEpoch, date.toEpochDay(), d + 1))
            }
        }
    }
    private fun setAlarm(c: Context, am: AlarmManager, at: Long, pi: PendingIntent) {
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }
    fun scheduleFingerprintRepeat(c: Context, baseDay: Long) {
        if (!AlarmPrefs.hasSchedule(c) || !AlarmPrefs.repeatOnFingerprintDay(c) || baseDay in AlarmPrefs.completed(c)) return
        val baseDate = LocalDate.ofEpochDay(baseDay)
        val now = java.time.ZonedDateTime.now()
        if (now.toLocalDate() != baseDate) return
        val at = now.plusMinutes(AlarmPrefs.snoozeMinutes(c).toLong()).withSecond(0).withNano(0)
        if (at.toLocalDate() != baseDate) return
        val pi = pending(c, repeatId(baseDay), baseDay, baseDate.toEpochDay(), 1, ACTION_FIRE, repeated = true)
        setAlarm(c, c.getSystemService(AlarmManager::class.java), at.toInstant().toEpochMilli(), pi)
    }
    fun snooze(c: Context, baseDay: Long, windowDay: Int) {
        val at = System.currentTimeMillis() + AlarmPrefs.snoozeMinutes(c) * 60_000L
        val i = Intent(c, AlarmReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_BASE_DAY, baseDay)
            .putExtra(EXTRA_EPOCH_DAY, LocalDate.now().toEpochDay()).putExtra(EXTRA_WINDOW, windowDay).putExtra(EXTRA_SNOOZE, true)
        val pi = PendingIntent.getBroadcast(c, snoozeId(baseDay), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        setAlarm(c, c.getSystemService(AlarmManager::class.java), at, pi)
    }
    fun nextBase(c: Context): LocalDate {
        if (!AlarmPrefs.hasSchedule(c)) return LocalDate.now()
        val s = AlarmPrefs.start(c); val interval = AlarmPrefs.interval(c); val done = AlarmPrefs.completed(c); val today = LocalDate.now()
        return (0 until AlarmPrefs.count(c)).asSequence().map { s.plusDays(it.toLong() * interval) }
            .firstOrNull { it.plusDays(2) >= today && it.toEpochDay() !in done } ?: s.plusDays(AlarmPrefs.count(c).toLong() * interval)
    }
}

const val ACTION_FIRE = "com.abw3laa.basmaalarm.FIRE"
const val ACTION_PRE_NOTICE = "com.abw3laa.basmaalarm.PRE_NOTICE"
const val EXTRA_BASE_DAY = "base_day"
const val EXTRA_EPOCH_DAY = "epoch_day"
const val EXTRA_WINDOW = "window_day"
const val EXTRA_SNOOZE = "is_snooze"
const val EXTRA_REPEAT = "is_repeat"
const val ACTION_STOP = "com.abw3laa.basmaalarm.STOP_SOUND"
const val ACTION_SNOOZE = "com.abw3laa.basmaalarm.SNOOZE"
const val ACTION_DONE = "com.abw3laa.basmaalarm.DONE"
const val ACTION_MUTE = "com.abw3laa.basmaalarm.MUTE"

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val baseDay = intent.getLongExtra(EXTRA_BASE_DAY, LocalDate.now().toEpochDay())
        val windowDay = intent.getIntExtra(EXTRA_WINDOW, 1)
        if (intent.action == ACTION_PRE_NOTICE) {
            ensureAlarmChannel(context)
            val lang = AlarmPrefs.language(context)
            val title = when (lang) { "tr" -> "Parmak izi randevusu yarın"; "en" -> "Fingerprint appointment tomorrow"; else -> "موعد البصمة غداً" }
            val body = when (lang) { "tr" -> "Yarın için hazırlanın"; "en" -> "Please prepare for tomorrow's appointment"; else -> "استعد لموعد البصمة القادم" }
            val open = PendingIntent.getActivity(context, 9111, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(context, CHANNEL_REMINDER).setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle(title).setContentText(body).setContentIntent(open).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_REMINDER).setPriority(NotificationCompat.PRIORITY_DEFAULT).build()
            context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_PRE_NOTICE, notification)
            return
        }
        val svc = Intent(context, AlarmPlaybackService::class.java).setAction(AlarmPlaybackService.START).putExtra(EXTRA_BASE_DAY, baseDay).putExtra(EXTRA_WINDOW, windowDay)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc) else context.startService(svc)
        if (windowDay == 1 && !intent.getBooleanExtra(EXTRA_SNOOZE, false)) AlarmScheduler.scheduleFingerprintRepeat(context, baseDay)
        val open = Intent(context, RingingActivity::class.java).putExtra(EXTRA_BASE_DAY, baseDay).putExtra(EXTRA_WINDOW, windowDay).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val full = PendingIntent.getActivity(context, 8000 + windowDay, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        ensureAlarmChannel(context)
        val notification = NotificationCompat.Builder(context, alarmChannelId(context)).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(alarmTitle(context)).setContentText(alarmBody(context, windowDay))
            .setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(if (AlarmPrefs.highPriority(context)) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true).setAutoCancel(false).setFullScreenIntent(full, AlarmPrefs.highPriority(context)).setContentIntent(full).build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { AlarmScheduler.schedule(context) }
}

class AlarmPlaybackService : Service() {
    private var ringtone: Ringtone? = null
    private var alarmOverlay: View? = null
    private var overlayManager: WindowManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stopPlayback() }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val base = intent?.getLongExtra(EXTRA_BASE_DAY, LocalDate.now().toEpochDay()) ?: LocalDate.now().toEpochDay()
        val day = intent?.getIntExtra(EXTRA_WINDOW, 1) ?: 1
        ensureAlarmChannel(this)
        startForeground(NOTIFICATION_ID, buildNotification(this, base, day))
        handler.removeCallbacks(autoStop)
        stopPlayback()
        if (intent?.action != ACTION_MUTE && AlarmPrefs.sound(this)) {
            runCatching {
                val uri = AlarmPrefs.soundUri(this)?.let { Uri.parse(it) } ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                    audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                    if (Build.VERSION.SDK_INT >= 28) isLooping = true
                    play()
                }
            }
        }
        if (intent?.action != ACTION_MUTE && AlarmPrefs.vibrate(this)) runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator else getSystemService(Vibrator::class.java)
            if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 450), 0)) else @Suppress("DEPRECATION") vibrator.vibrate(longArrayOf(0, 700, 450), 0)
        }
        val autoStopMinutes = AlarmPrefs.autoStopMinutes(this)
        if (intent?.action != ACTION_MUTE && autoStopMinutes > 0) handler.postDelayed(autoStop, autoStopMinutes * 60_000L)
        showAlarmOverlay(base, day)
        return START_STICKY
    }
    private fun stopPlayback() {
        handler.removeCallbacks(autoStop)
        ringtone?.stop(); ringtone = null
        runCatching { val v = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator else getSystemService(Vibrator::class.java); v.cancel() }
    }
    private fun showAlarmOverlay(base: Long, day: Int) {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) return
        runCatching {
            alarmOverlay?.let { overlayManager?.removeView(it) }
            val dark = when (AlarmPrefs.theme(this)) { "dark" -> true; "light" -> false; else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES }
            val bg = if (dark) android.graphics.Color.rgb(10, 23, 33) else android.graphics.Color.rgb(247, 249, 251)
            val card = if (dark) android.graphics.Color.rgb(25, 41, 53) else android.graphics.Color.WHITE
            val fg = if (dark) android.graphics.Color.rgb(239, 244, 247) else android.graphics.Color.rgb(15, 37, 55)
            val muted = if (dark) android.graphics.Color.rgb(188, 201, 210) else android.graphics.Color.rgb(82, 94, 103)
            val teal = android.graphics.Color.rgb(0, 106, 97)
            val red = android.graphics.Color.rgb(186, 26, 26)
            fun label(text: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
                this.text = text; textSize = size; setTextColor(color); gravity = Gravity.CENTER
                if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
                setPadding(8, 12, 8, 12)
            }
            val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(28, 32, 28, 24); setBackgroundColor(bg) }
            val lang = AlarmPrefs.language(this)
            fun text(ar: String, tr: String, en: String) = when (lang) { "tr" -> tr; "en" -> en; else -> ar }
            val clock = LocalTime.of(AlarmPrefs.hour(this), AlarmPrefs.minute(this)).format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.getDefault()))
            root.addView(label("◉  ${text("منبه البصمة نشط الآن", "Parmak izi alarmı etkin", "Fingerprint alarm active")}", 15f, red, true))
            root.addView(label("$clock", 54f, fg, true))
            root.addView(label(LocalDate.ofEpochDay(base).format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", java.util.Locale.forLanguageTag(lang))), 17f, muted))
            root.addView(label(text("حان موعد البصمة", "Parmak izi zamanı", "Fingerprint appointment"), 25f, fg, true))
            val dayCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(14, 8, 14, 8); background = GradientDrawable().apply { setColor(card); cornerRadius = 28f } }
            dayCard.addView(label(text("نافذة الأيام الثلاثة المتاحة", "Üç günlük randevu aralığı", "Three-day appointment window"), 16f, fg, true))
            dayCard.addView(label(text("اليوم $day من 3", "$day / 3. gün", "Day $day of 3"), 14f, teal, true))
            root.addView(dayCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 24; bottomMargin = 24 })
            fun actionButton(title: String, action: String, color: Int, content: Int) {
                val button = label(title, 16f, content, true).apply {
                    background = GradientDrawable().apply { setColor(color); cornerRadius = 30f }
                    isClickable = true; isFocusable = true
                    setOnClickListener {
                        if (action == ACTION_MUTE) startService(Intent(this@AlarmPlaybackService, AlarmPlaybackService::class.java).setAction(ACTION_MUTE).putExtra(EXTRA_BASE_DAY, base).putExtra(EXTRA_WINDOW, day))
                        else sendBroadcast(Intent(this@AlarmPlaybackService, AlarmActionReceiver::class.java).setAction(action).putExtra(EXTRA_BASE_DAY, base).putExtra(EXTRA_WINDOW, day))
                    }
                }
                root.addView(button, LinearLayout.LayoutParams(-1, 56).apply { topMargin = 8 })
            }
            actionButton(text("تمت البصمة ✓", "Parmak izi tamamlandı ✓", "Fingerprint completed ✓"), ACTION_DONE, teal, android.graphics.Color.WHITE)
            actionButton(text("ذكرني لاحقاً (${AlarmPrefs.snoozeMinutes(this)} دقيقة)", "${AlarmPrefs.snoozeMinutes(this)} dakika sonra hatırlat", "Snooze for ${AlarmPrefs.snoozeMinutes(this)} minutes"), ACTION_SNOOZE, card, fg)
            actionButton(text("إيقاف الصوت فقط", "Yalnızca sesi durdur", "Stop sound only"), ACTION_MUTE, card, muted)
            val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            val params = WindowManager.LayoutParams(-1, -1, type, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or WindowManager.LayoutParams.FLAG_DIM_BEHIND, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.CENTER; dimAmount = .82f }
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.addView(root, params)
            alarmOverlay = root; overlayManager = wm
        }
    }
    override fun onDestroy() {
        stopPlayback()
        runCatching { alarmOverlay?.let { overlayManager?.removeView(it) } }
        alarmOverlay = null
        super.onDestroy()
    }
    companion object { const val START = "START_ALARM" }
}

class AlarmActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val base = intent.getLongExtra(EXTRA_BASE_DAY, LocalDate.now().toEpochDay())
        val day = intent.getIntExtra(EXTRA_WINDOW, 1)
        if (intent.action == ACTION_STOP) {
            context.startService(Intent(context, AlarmPlaybackService::class.java).setAction(ACTION_MUTE).putExtra(EXTRA_BASE_DAY, base).putExtra(EXTRA_WINDOW, day))
            context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(context, base, day))
            return
        }
        when (intent.action) {
            ACTION_SNOOZE -> AlarmScheduler.snooze(context, base, day)
            ACTION_DONE -> { AlarmPrefs.markDone(context, base); AlarmScheduler.schedule(context) }
        }
        context.stopService(Intent(context, AlarmPlaybackService::class.java))
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}

fun alarmChannelId(c: Context) = if (AlarmPrefs.highPriority(c)) CHANNEL_ALARM_HIGH else CHANNEL_ALARM_NORMAL
fun ensureAlarmChannel(c: Context) {
    if (Build.VERSION.SDK_INT >= 26) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ALARM_HIGH) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL_ALARM_HIGH, if (AlarmPrefs.language(c) == "ar") "تنبيهات البصمة ذات الأولوية القصوى" else "High priority fingerprint alarms", NotificationManager.IMPORTANCE_HIGH).apply { description = "Alarms and time-critical fingerprint reminders"; setSound(null, null); enableVibration(false); lockscreenVisibility = Notification.VISIBILITY_PUBLIC })
        if (nm.getNotificationChannel(CHANNEL_ALARM_NORMAL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL_ALARM_NORMAL, if (AlarmPrefs.language(c) == "ar") "تنبيهات مواعيد البصمة" else "Fingerprint appointment reminders", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Fingerprint appointment alarms"; setSound(null, null); enableVibration(false); lockscreenVisibility = Notification.VISIBILITY_PUBLIC })
        if (nm.getNotificationChannel(CHANNEL_REMINDER) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL_REMINDER, if (AlarmPrefs.language(c) == "ar") "تذكير قبل موعد البصمة" else "Appointment advance reminder", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Reminder notification before the appointment" })
    }
}
fun alarmTitle(c: Context) = when (AlarmPrefs.language(c)) { "tr" -> "Parmak izi zamanı"; "en" -> "Fingerprint appointment"; else -> "حان موعد البصمة" }
fun alarmBody(c: Context, day: Int) = when (AlarmPrefs.language(c)) { "tr" -> "Üç günlük sürenin $day. günü"; "en" -> "Day $day of the three-day window"; else -> "اليوم $day من نافذة الأيام الثلاثة" }
fun buildNotification(c: Context, base: Long, day: Int): Notification {
    val fullI = Intent(c, RingingActivity::class.java).putExtra(EXTRA_BASE_DAY, base).putExtra(EXTRA_WINDOW, day).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    val full = PendingIntent.getActivity(c, 8100 + day, fullI, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun action(label: String, act: String, code: Int): NotificationCompat.Action {
        val i = Intent(c, AlarmActionReceiver::class.java).setAction(act).putExtra(EXTRA_BASE_DAY, base).putExtra(EXTRA_WINDOW, day)
        val p = PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Action.Builder(0, label, p).build()
    }
    val lang = AlarmPrefs.language(c)
    val done = when (lang) { "tr" -> "Tamamlandı"; "en" -> "Completed"; else -> "تمت البصمة" }
    val snooze = when (lang) { "tr" -> "${AlarmPrefs.snoozeMinutes(c)} dk ertele"; "en" -> "Snooze ${AlarmPrefs.snoozeMinutes(c)} min"; else -> "غفوة ${AlarmPrefs.snoozeMinutes(c)} د" }
    val stop = when (lang) { "tr" -> "Sesi durdur"; "en" -> "Stop sound"; else -> "إيقاف الصوت" }
    return NotificationCompat.Builder(c, alarmChannelId(c)).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
        .setContentTitle(alarmTitle(c)).setContentText(alarmBody(c, day))
        .setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(if (AlarmPrefs.highPriority(c)) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT)
        .setOngoing(true).setFullScreenIntent(full, AlarmPrefs.highPriority(c)).setContentIntent(full)
        .addAction(action(done, ACTION_DONE, 8200 + day)).addAction(action(snooze, ACTION_SNOOZE, 8300 + day)).addAction(action(stop, ACTION_STOP, 8400 + day)).build()
}
const val CHANNEL_ALARM = "basma_alarm_channel"
const val CHANNEL_ALARM_HIGH = "basma_alarm_channel_high"
const val CHANNEL_ALARM_NORMAL = "basma_alarm_channel_normal"
const val CHANNEL_REMINDER = "basma_reminder_channel"
const val NOTIFICATION_ID = 6210
const val NOTIFICATION_PRE_NOTICE = 6211
