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
        if (intent?.action == ACTION_SCREEN_VISIBLE) { removeAlarmOverlay(); return START_STICKY }
        if (intent?.action == ACTION_ACTIVITY_HIDDEN) { showAlarmOverlay(base, day); return START_STICKY }
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
        if (AlarmPrefs.sp(this).getBoolean("ring_activity_visible", false)) { removeAlarmOverlay(); return }
        runCatching {
            removeAlarmOverlay()
            val density = resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()
            val lang = AlarmPrefs.language(this)
            val isArabic = lang == "ar"
            fun text(ar: String, tr: String, en: String) = when (lang) { "tr" -> tr; "en" -> en; else -> ar }
            val dark = when (AlarmPrefs.theme(this)) { "dark" -> true; "light" -> false; else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES }
            val bg = if (dark) android.graphics.Color.rgb(17, 27, 36) else android.graphics.Color.rgb(247, 249, 251)
            val card = if (dark) android.graphics.Color.rgb(27, 41, 52) else android.graphics.Color.WHITE
            val panel = if (dark) android.graphics.Color.rgb(32, 45, 56) else android.graphics.Color.rgb(241, 243, 245)
            val fg = if (dark) android.graphics.Color.rgb(232, 237, 241) else android.graphics.Color.rgb(16, 29, 40)
            val muted = if (dark) android.graphics.Color.rgb(186, 197, 205) else android.graphics.Color.rgb(76, 83, 87)
            val teal = if (dark) android.graphics.Color.rgb(134, 242, 228) else android.graphics.Color.rgb(0, 106, 97)
            val red = if (dark) android.graphics.Color.rgb(255, 163, 155) else android.graphics.Color.rgb(180, 35, 44)
            val redSurface = if (dark) android.graphics.Color.rgb(73, 37, 42) else android.graphics.Color.rgb(255, 232, 231)
            fun rounded(color: Int, radius: Int, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
                setColor(color); cornerRadius = dp(radius).toFloat()
                if (stroke != null) setStroke(dp(1), stroke)
            }
            fun label(value: String, size: Float, color: Int, bold: Boolean = false, gravityValue: Int = Gravity.CENTER): TextView = TextView(this).apply {
                text = value; textSize = size; setTextColor(color); gravity = gravityValue
                if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
                setPadding(dp(4), dp(3), dp(4), dp(3))
            }
            val baseDate = LocalDate.ofEpochDay(base)
            val activeDate = baseDate.plusDays((day - 1).coerceAtLeast(0).toLong())
            val locale = java.util.Locale.forLanguageTag(lang)
            val datePattern = if (isArabic) "EEEE، d MMMM yyyy" else "EEEE, d MMMM yyyy"
            val dateFormat = java.time.format.DateTimeFormatter.ofPattern(datePattern, locale)
            val shortFormat = java.time.format.DateTimeFormatter.ofPattern(if (isArabic) "d MMMM" else "d MMM", locale)
            val alarmTime = LocalTime.of(AlarmPrefs.hour(this), AlarmPrefs.minute(this))
            val time = alarmTime.format(java.time.format.DateTimeFormatter.ofPattern("h:mm", java.util.Locale.US))
            val period = when (lang) { "ar" -> if (alarmTime.hour < 12) "صباحًا" else "مساءً"; "tr" -> if (alarmTime.hour < 12) "ÖÖ" else "ÖS"; else -> if (alarmTime.hour < 12) "AM" else "PM" }
            val minutesLeft = (24 * 60 - LocalTime.now().hour * 60 - LocalTime.now().minute).coerceAtLeast(0)
            val hoursLeft = minutesLeft / 60
            val remaining = when (lang) { "ar" -> "$hoursLeft ساعة"; "tr" -> "$hoursLeft saat"; else -> "$hoursLeft hours" }
            val soundState = when {
                AlarmPrefs.sound(this) && AlarmPrefs.vibrate(this) -> text("صوت + اهتزاز مستمر", "Ses + titreşim", "Sound + vibration")
                AlarmPrefs.sound(this) -> text("صوت التنبيه", "Alarm sesi", "Alarm sound")
                AlarmPrefs.vibrate(this) -> text("اهتزاز فقط", "Yalnızca titreşim", "Vibration only")
                else -> text("التنبيه صامت", "Sessiz alarm", "Silent alarm")
            }
            val scroll = android.widget.ScrollView(this).apply {
                isFillViewport = true; isVerticalScrollBarEnabled = false; setBackgroundColor(bg)
                layoutDirection = if (isArabic) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            }
            val page = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(17), dp(12), dp(17), dp(18)); setBackgroundColor(bg)
                layoutDirection = if (isArabic) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            }
            scroll.addView(page, android.view.ViewGroup.LayoutParams(-1, -2))
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val status = TextView(this).apply {
                text = "●  ${text("منبه البصمة نشط الآن", "Parmak izi alarmı etkin", "Fingerprint alarm active")}"; textSize = 10f; typeface = Typeface.DEFAULT_BOLD; setTextColor(red)
                gravity = Gravity.CENTER; setPadding(dp(9), dp(5), dp(9), dp(5)); background = rounded(redSurface, 24, redSurface)
            }
            top.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(label(soundState, 9f, muted), LinearLayout.LayoutParams(0, -2, 1f))
            page.addView(top, LinearLayout.LayoutParams(-1, -2))

            val iconFrame = android.widget.FrameLayout(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(redSurface); setStroke(dp(4), red) }
                elevation = dp(4).toFloat()
            }
            val appIcon = android.widget.ImageView(this).apply { setImageResource(R.drawable.app_icon_zoomed); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER; setPadding(dp(17), dp(17), dp(17), dp(17)) }
            iconFrame.addView(appIcon, android.widget.FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            page.addView(iconFrame, LinearLayout.LayoutParams(dp(128), dp(128)).apply { topMargin = dp(9); bottomMargin = dp(4) })

            val clockRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            clockRow.addView(label(time, 48f, fg, true), LinearLayout.LayoutParams(-2, -2))
            clockRow.addView(label(period, 13f, teal, true), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(5); topMargin = dp(18) })
            page.addView(clockRow, LinearLayout.LayoutParams(-1, -2))
            page.addView(label(activeDate.format(dateFormat), 11f, muted), LinearLayout.LayoutParams(-1, -2))
            page.addView(label(text("حان موعد البصمة", "Parmak izi zamanı", "Fingerprint appointment"), 21f, fg, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(11) })

            val dayCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(13), dp(10), dp(13), dp(9)); background = rounded(card, 18, if (dark) panel else android.graphics.Color.rgb(226, 230, 233)) }
            val cardTitleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            cardTitleRow.addView(label(text("نافذة الـ 3 أيام المتاحة", "3 günlük randevu aralığı", "3-day appointment window"), 12f, fg, true), LinearLayout.LayoutParams(0, -2, 1f))
            val dayBadge = TextView(this).apply {
                text = text("اليوم $day من 3", "$day / 3. gün", "Day $day of 3"); textSize = 9f; setTextColor(red); gravity = Gravity.CENTER
                setPadding(dp(7), dp(4), dp(7), dp(4)); background = rounded(redSurface, 18)
            }
            cardTitleRow.addView(dayBadge)
            dayCard.addView(cardTitleRow)
            val timeline = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(2)) }
            for (index in 0..2) {
                val dayNumber = index + 1
                val selected = dayNumber == day
                val barColor = if (selected) red else if (dayNumber < day) teal else if (dark) android.graphics.Color.rgb(62, 76, 86) else android.graphics.Color.rgb(225, 228, 231)
                val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(3), 0, dp(3), 0) }
                val bar = View(this).apply { background = rounded(barColor, 9) }
                cell.addView(bar, LinearLayout.LayoutParams(-1, dp(7)))
                val dayTitle = when (lang) { "tr" -> "$dayNumber. gün"; "en" -> "Day $dayNumber"; else -> "اليوم $dayNumber" }
                cell.addView(label(dayTitle, 9f, if (selected) fg else muted, selected), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                cell.addView(label(baseDate.plusDays(index.toLong()).format(shortFormat), 8f, muted), LinearLayout.LayoutParams(-1, -2))
                timeline.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
            }
            dayCard.addView(timeline, LinearLayout.LayoutParams(-1, -2))
            val divider = View(this).apply { setBackgroundColor(if (dark) android.graphics.Color.rgb(50, 65, 76) else android.graphics.Color.rgb(228, 231, 233)) }
            dayCard.addView(divider, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(7); bottomMargin = dp(6) })
            dayCard.addView(label(text("متبقي على إغلاق اليوم $day: $remaining", "$day. gün kapanana: $remaining", "Time left today: $remaining"), 9f, muted, false, Gravity.START))
            page.addView(dayCard, LinearLayout.LayoutParams(-1, -2))

            val caution = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(6))
                background = rounded(if (dark) android.graphics.Color.rgb(57, 39, 43) else android.graphics.Color.rgb(255, 240, 239), 13, if (dark) android.graphics.Color.rgb(87, 52, 55) else android.graphics.Color.rgb(242, 206, 203))
            }
            caution.addView(label("ⓘ", 17f, red, true), LinearLayout.LayoutParams(dp(28), -2))
            caution.addView(label(text(
                "تنبيه: إيقاف صوت المنبه لا يعتبر تأكيدًا لحضور الموعد. لإثبات حضورك اضغط «تمت البصمة»."
                , "Not: Alarmı susturmak randevuyu onaylamaz. Tamamlamak için «Parmak izi tamamlandı» düğmesine basın."
                , "Note: Silencing the alarm does not confirm attendance. Tap “Fingerprint completed” to confirm."
            ), 9f, fg, false, Gravity.START), LinearLayout.LayoutParams(0, -2, 1f))
            page.addView(caution, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) })

            fun actionButton(title: String, action: String, color: Int, content: Int, outlined: Boolean = false, height: Int = 50) {
                val button = TextView(this).apply {
                    text = title; textSize = 13f; setTextColor(content); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(8), dp(4), dp(8), dp(4)); isClickable = true; isFocusable = true
                    background = rounded(color, 15, if (outlined) (if (dark) android.graphics.Color.rgb(68, 83, 93) else android.graphics.Color.rgb(196, 202, 207)) else null)
                    setOnClickListener {
                        if (action == ACTION_MUTE) startService(Intent(this@AlarmPlaybackService, AlarmPlaybackService::class.java).setAction(ACTION_MUTE).putExtra(EXTRA_BASE_DAY, base).putExtra(EXTRA_WINDOW, day))
                        else sendBroadcast(Intent(this@AlarmPlaybackService, AlarmActionReceiver::class.java).setAction(action).putExtra(EXTRA_BASE_DAY, base).putExtra(EXTRA_WINDOW, day))
                    }
                }
                page.addView(button, LinearLayout.LayoutParams(-1, dp(height)).apply { topMargin = dp(7) })
            }
            actionButton("✓  ${text("تمت البصمة", "Parmak izi tamamlandı", "Fingerprint completed")}", ACTION_DONE, teal, if (dark) android.graphics.Color.rgb(13, 41, 37) else android.graphics.Color.WHITE, height = 52)
            actionButton("◷  ${text("ذكرني لاحقًا", "Daha sonra hatırlat", "Remind me later")}", ACTION_SNOOZE, card, fg, outlined = true)
            actionButton("◌  ${text("إيقاف الصوت فقط مع إبقاء التذكير على الشاشة", "Sesi durdur, hatırlatıcı ekranda kalsın", "Silence only; keep reminder on screen")}", ACTION_MUTE, bg, muted, height = 40)
            page.addView(label(text(
                "في حال التأجيل، سيعاد تشغيل الرنين تلقائيًا بعد ${AlarmPrefs.snoozeMinutes(this)} دقيقة."
                , "Erteleme sonrası alarm ${AlarmPrefs.snoozeMinutes(this)} dakika sonra yeniden çalar."
                , "If snoozed, the alarm rings again after ${AlarmPrefs.snoozeMinutes(this)} minutes."
            ), 9f, muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })

            val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            val params = WindowManager.LayoutParams(
                -1, -1, type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.CENTER; dimAmount = .82f }
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.addView(scroll, params)
            alarmOverlay = scroll; overlayManager = wm
        }
    }
    private fun removeAlarmOverlay() {
        runCatching { alarmOverlay?.let { overlayManager?.removeView(it) } }
        alarmOverlay = null
        overlayManager = null
    }
    override fun onDestroy() {
        stopPlayback()
        removeAlarmOverlay()
        super.onDestroy()
    }
    companion object {
        const val START = "START_ALARM"
        const val ACTION_SCREEN_VISIBLE = "RINGING_SCREEN_VISIBLE"
        const val ACTION_ACTIVITY_HIDDEN = "RINGING_SCREEN_HIDDEN"
    }
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
