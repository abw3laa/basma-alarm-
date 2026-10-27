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
    fun sp(c: Context) = AlarmSettings.preferences(c)
    fun schedules(c: Context) = ScheduleRepository.all(c)
    fun schedule(c: Context, id: String? = null) = if (id == null) ScheduleRepository.active(c) else ScheduleRepository.get(c, id)
    fun hasSchedule(c: Context) = schedule(c) != null
    fun hasSchedule(c: Context, id: String) = schedule(c, id) != null
    fun start(c: Context, id: String? = null): LocalDate = schedule(c, id)?.start ?: LocalDate.now()
    fun hour(c: Context, id: String? = null) = schedule(c, id)?.hour ?: defaultHour(c)
    fun minute(c: Context, id: String? = null) = schedule(c, id)?.minute ?: defaultMinute(c)
    fun defaultHour(c: Context) = AlarmSettings.defaultHour(c)
    fun defaultMinute(c: Context) = AlarmSettings.defaultMinute(c)
    fun interval(c: Context, id: String? = null) = schedule(c, id)?.intervalDays ?: 28
    fun count(c: Context, id: String? = null) = schedule(c, id)?.appointmentCount ?: 0
    fun language(c: Context) = AlarmSettings.language(c)
    fun theme(c: Context) = AlarmSettings.theme(c)
    fun vibrate(c: Context) = AlarmSettings.vibrate(c)
    fun sound(c: Context) = AlarmSettings.sound(c)
    fun soundUri(c: Context) = AlarmSettings.soundUri(c)
    fun autoStopMinutes(c: Context) = AlarmSettings.autoStopMinutes(c)
    fun snoozeMinutes(c: Context) = AlarmSettings.snoozeMinutes(c)
    fun highPriority(c: Context) = AlarmSettings.highPriority(c)
    fun remindDayBefore(c: Context) = AlarmSettings.remindDayBefore(c)
    fun repeatOnFingerprintDay(c: Context) = AlarmSettings.repeatOnFingerprintDay(c)
    fun remindSecondDay(c: Context) = AlarmSettings.remindSecondDay(c)
    fun completed(c: Context, id: String? = null): Set<Long> = schedule(c, id)?.completedEpochDays ?: emptySet()
    fun markDone(c: Context, baseDay: Long, method: String = "home", scheduleId: String? = null): Boolean {
        val target = schedule(c, scheduleId) ?: return false
        val occurrence = AppointmentOccurrence(target.id, target.name, LocalDate.ofEpochDay(baseDay), 0)
        if (!AppointmentRules.canConfirm(occurrence, LocalDate.now())) return false
        return ScheduleRepository.markCompleted(c, target.id, baseDay, method)
    }
    fun clearSchedule(c: Context, id: String? = null) {
        val target = schedule(c, id) ?: return
        ScheduleRepository.delete(c, target.id)
    }
}

object AlarmScheduler {
    private fun requestCode(schedule: FingerprintSchedule, baseDay: Long, suffix: Int): Int =
        ((schedule.notificationId * 31 + (baseDay xor (baseDay ushr 32)).toInt()) * 7 + suffix) and 0x7fffffff

    private fun alarmIntent(c: Context, schedule: FingerprintSchedule, baseDay: Long, epochDay: Long, windowDay: Int, action: String, repeated: Boolean = false, snooze: Boolean = false): Intent =
        Intent(c, AlarmReceiver::class.java).setAction(action)
            .setData(Uri.parse("basmaalarm://alarm/${schedule.id}/$baseDay/$epochDay/$windowDay/$action/${if (repeated) 1 else 0}/${if (snooze) 1 else 0}"))
            .putExtra(EXTRA_SCHEDULE_ID, schedule.id).putExtra(EXTRA_BASE_DAY, baseDay)
            .putExtra(EXTRA_EPOCH_DAY, epochDay).putExtra(EXTRA_WINDOW, windowDay)
            .putExtra(EXTRA_REPEAT, repeated).putExtra(EXTRA_SNOOZE, snooze)

    private fun pending(c: Context, schedule: FingerprintSchedule, baseDay: Long, epochDay: Long, windowDay: Int, action: String = ACTION_FIRE, repeated: Boolean = false, snooze: Boolean = false, noCreate: Boolean = false): PendingIntent? {
        val intent = alarmIntent(c, schedule, baseDay, epochDay, windowDay, action, repeated, snooze)
        val flags = PendingIntent.FLAG_IMMUTABLE or if (noCreate) PendingIntent.FLAG_NO_CREATE else PendingIntent.FLAG_UPDATE_CURRENT
        val id = requestCode(schedule, baseDay, windowDay * 11 + action.hashCode())
        return PendingIntent.getBroadcast(c, id, intent, flags)
    }

    private fun cancelOccurrence(c: Context, manager: AlarmManager, schedule: FingerprintSchedule, baseDay: Long) {
        for (day in 0..2) {
            val epoch = baseDay + day
            pending(c, schedule, baseDay, epoch, day + 1, ACTION_FIRE, noCreate = true)?.let { manager.cancel(it); it.cancel() }
            pending(c, schedule, baseDay, epoch, day + 1, ACTION_FIRE, repeated = true, noCreate = true)?.let { manager.cancel(it); it.cancel() }
            pending(c, schedule, baseDay, baseDay, day + 1, ACTION_FIRE, snooze = true, noCreate = true)?.let { manager.cancel(it); it.cancel() }
        }
        val preDay = baseDay - 1
        pending(c, schedule, baseDay, preDay, 0, ACTION_PRE_NOTICE, noCreate = true)?.let { manager.cancel(it); it.cancel() }
    }

    fun cancelSchedule(c: Context, schedule: FingerprintSchedule) {
        val manager = c.getSystemService(AlarmManager::class.java)
        for (n in 0 until schedule.appointmentCount) {
            val base = schedule.start.plusDays(n.toLong() * schedule.intervalDays).toEpochDay()
            cancelOccurrence(c, manager, schedule, base)
            if (schedule.id == "legacy") cancelLegacyPending(c, manager, base)
        }
        c.getSystemService(NotificationManager::class.java).apply {
            cancel(NOTIFICATION_ID_BASE + schedule.notificationId); cancel(NOTIFICATION_PRE_NOTICE_BASE + schedule.notificationId)
            if (schedule.id == "legacy") { cancel(NOTIFICATION_ID); cancel(NOTIFICATION_PRE_NOTICE) }
        }
    }

    private fun cancelLegacyPending(c: Context, manager: AlarmManager, baseDay: Long) {
        fun cancel(code: Int, action: String) {
            val intent = Intent(c, AlarmReceiver::class.java).setAction(action)
            PendingIntent.getBroadcast(c, code, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let { manager.cancel(it); it.cancel() }
        }
        for (day in 0..2) cancel((baseDay + day).toInt(), ACTION_FIRE)
        cancel(2_000_000 + (baseDay % 500_000).toInt(), ACTION_FIRE)
        cancel(1_000_000 + (baseDay % 500_000).toInt(), ACTION_FIRE)
        val pre = baseDay - 1
        cancel(3_000_000 + (pre % 500_000).toInt(), ACTION_PRE_NOTICE)
    }

    fun cancelAll(c: Context) { AlarmPrefs.schedules(c).forEach { cancelSchedule(c, it) } }

    fun schedule(c: Context) {
        cancelAll(c)
        AlarmPrefs.schedules(c).forEach { schedule(c, it.id) }
        AlarmPrefs.sp(c).edit().putLong("last_schedule_success_ms", System.currentTimeMillis()).apply()
        runCatching { AppointmentWidgetProvider.updateAll(c) }
    }

    fun schedule(c: Context, scheduleId: String) {
        val schedule = AlarmPrefs.schedule(c, scheduleId) ?: return
        cancelSchedule(c, schedule)
        scheduleAppointments(c, schedule)
    }

    private fun scheduleAppointments(c: Context, schedule: FingerprintSchedule) {
        val manager = c.getSystemService(AlarmManager::class.java)
        val now = System.currentTimeMillis()
        for (appointment in AppointmentRules.occurrences(schedule.id, schedule.name, schedule.start, schedule.intervalDays, schedule.appointmentCount, schedule.completedEpochDays)) {
            val base = appointment.epochDay
            if (AlarmPrefs.remindDayBefore(c)) {
                val preDay = appointment.baseDate.minusDays(1)
                val at = preDay.atTime(schedule.hour, schedule.minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                if (at > now) pending(c, schedule, base, preDay.toEpochDay(), 0, ACTION_PRE_NOTICE)?.let { setAlarm(c, manager, at, it) }
            }
            for (day in 0..2) {
                if (day == 1 && !AlarmPrefs.remindSecondDay(c)) continue
                val date = appointment.baseDate.plusDays(day.toLong())
                val at = date.atTime(schedule.hour, schedule.minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                if (at > now) pending(c, schedule, base, date.toEpochDay(), day + 1)?.let { setAlarm(c, manager, at, it) }
            }
        }
    }

    private fun setAlarm(c: Context, manager: AlarmManager, at: Long, pi: PendingIntent) {
        if (Build.VERSION.SDK_INT >= 31 && !manager.canScheduleExactAlarms()) manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    fun scheduleFingerprintRepeat(c: Context, baseDay: Long, scheduleId: String? = null) {
        val schedule = AlarmPrefs.schedule(c, scheduleId) ?: return
        if (!AlarmPrefs.repeatOnFingerprintDay(c) || baseDay in schedule.completedEpochDays) return
        val baseDate = LocalDate.ofEpochDay(baseDay)
        val now = java.time.ZonedDateTime.now()
        if (now.toLocalDate() != baseDate) return
        val at = now.plusMinutes(AlarmPrefs.snoozeMinutes(c).toLong()).withSecond(0).withNano(0)
        if (at.toLocalDate() != baseDate) return
        val pi = pending(c, schedule, baseDay, baseDay, 1, ACTION_FIRE, repeated = true) ?: return
        setAlarm(c, c.getSystemService(AlarmManager::class.java), at.toInstant().toEpochMilli(), pi)
    }

    fun snooze(c: Context, baseDay: Long, windowDay: Int, scheduleId: String? = null) {
        val schedule = AlarmPrefs.schedule(c, scheduleId) ?: return
        pending(c, schedule, baseDay, baseDay, 1, ACTION_FIRE, repeated = true, noCreate = true)?.let {
            c.getSystemService(AlarmManager::class.java).cancel(it)
            it.cancel()
        }
        val at = System.currentTimeMillis() + AlarmPrefs.snoozeMinutes(c) * 60_000L
        val pi = pending(c, schedule, baseDay, LocalDate.now().toEpochDay(), windowDay, ACTION_FIRE, snooze = true) ?: return
        setAlarm(c, c.getSystemService(AlarmManager::class.java), at, pi)
    }

    fun nextBase(c: Context, scheduleId: String? = null): LocalDate? {
        val schedule = AlarmPrefs.schedule(c, scheduleId) ?: return null
        return AppointmentRules.nextOccurrence(schedule.id, schedule.name, schedule.start, schedule.intervalDays, schedule.appointmentCount, schedule.completedEpochDays, LocalDate.now())?.baseDate
    }
}

const val ACTION_FIRE = "com.abw3laa.basmaalarm.FIRE"
const val ACTION_PRE_NOTICE = "com.abw3laa.basmaalarm.PRE_NOTICE"
const val EXTRA_SCHEDULE_ID = "schedule_id"
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
        val scheduleId = intent.getStringExtra(EXTRA_SCHEDULE_ID) ?: AlarmPrefs.schedule(context)?.id ?: return
        val schedule = AlarmPrefs.schedule(context, scheduleId) ?: return
        val baseDay = intent.getLongExtra(EXTRA_BASE_DAY, LocalDate.now().toEpochDay())
        val windowDay = intent.getIntExtra(EXTRA_WINDOW, 1)
        if (intent.action == ACTION_PRE_NOTICE) {
            ensureAlarmChannel(context)
            val lang = AlarmPrefs.language(context)
            val title = when (lang) { "tr" -> "Parmak izi randevusu yarın"; "en" -> "Fingerprint appointment tomorrow"; else -> "موعد البصمة غداً" }
            val body = when (lang) { "tr" -> "Yarın için hazırlanın"; "en" -> "Please prepare for tomorrow's appointment"; else -> "استعد لموعد البصمة القادم" }
            val open = PendingIntent.getActivity(context, 9111, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP).setData(Uri.parse("basmaalarm://pre-notice/${schedule.id}/$baseDay")), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(context, CHANNEL_REMINDER).setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("${schedule.name}: $title").setContentText(body).setContentIntent(open).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_REMINDER).setPriority(NotificationCompat.PRIORITY_DEFAULT).build()
            context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_PRE_NOTICE_BASE + schedule.notificationId, notification)
            return
        }
        val svc = Intent(context, AlarmPlaybackService::class.java).setAction(AlarmPlaybackService.START).putExtra(EXTRA_SCHEDULE_ID, scheduleId).putExtra(EXTRA_BASE_DAY, baseDay).putExtra(EXTRA_WINDOW, windowDay)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc) else context.startService(svc)
        if (windowDay == 1 && !intent.getBooleanExtra(EXTRA_SNOOZE, false)) AlarmScheduler.scheduleFingerprintRepeat(context, baseDay, scheduleId)
        val open = Intent(context, RingingActivity::class.java).putExtra(EXTRA_SCHEDULE_ID, scheduleId).putExtra(EXTRA_BASE_DAY, baseDay).putExtra(EXTRA_WINDOW, windowDay).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .setData(Uri.parse("basmaalarm://ring/$scheduleId/$baseDay/$windowDay"))
        val full = PendingIntent.getActivity(context, 8000 + schedule.notificationId * 4 + windowDay, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        ensureAlarmChannel(context)
        val notification = NotificationCompat.Builder(context, alarmChannelId(context)).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(schedule.name).setContentText(alarmBody(context, windowDay))
            .setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(if (AlarmPrefs.highPriority(context)) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true).setAutoCancel(false).setFullScreenIntent(full, AlarmPrefs.highPriority(context)).setContentIntent(full).build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID_BASE + schedule.notificationId, notification)
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
        val scheduleId = intent?.getStringExtra(EXTRA_SCHEDULE_ID) ?: AlarmPrefs.schedule(this)?.id ?: return START_NOT_STICKY
        val schedule = AlarmPrefs.schedule(this, scheduleId) ?: return START_NOT_STICKY
        val command = intent?.action
        val base = intent?.getLongExtra(EXTRA_BASE_DAY, LocalDate.now().toEpochDay()) ?: LocalDate.now().toEpochDay()
        val day = intent?.getIntExtra(EXTRA_WINDOW, 1) ?: 1
        if (command == ACTION_SCREEN_VISIBLE) { removeAlarmOverlay(); return START_STICKY }
        if (command == ACTION_ACTIVITY_HIDDEN) { showAlarmOverlay(base, day, scheduleId); return START_STICKY }
        ensureAlarmChannel(this)
        startForeground(NOTIFICATION_ID_BASE + schedule.notificationId, buildNotification(this, base, day, scheduleId))
        handler.removeCallbacks(autoStop)
        stopPlayback()
        if (command != ACTION_MUTE && AlarmPrefs.sound(this)) {
            runCatching {
                val uri = AlarmPrefs.soundUri(this)?.let { Uri.parse(it) } ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                    audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                    if (Build.VERSION.SDK_INT >= 28) isLooping = true
                    play()
                }
            }
        }
        if (command != ACTION_MUTE && AlarmPrefs.vibrate(this)) runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator else getSystemService(Vibrator::class.java)
            if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 450), 0)) else @Suppress("DEPRECATION") vibrator.vibrate(longArrayOf(0, 700, 450), 0)
        }
        val autoStopMinutes = AlarmPrefs.autoStopMinutes(this)
        if (command != ACTION_MUTE && autoStopMinutes > 0) handler.postDelayed(autoStop, autoStopMinutes * 60_000L)
        showAlarmOverlay(base, day, scheduleId)
        return START_STICKY
    }
    private fun stopPlayback() {
        handler.removeCallbacks(autoStop)
        ringtone?.stop(); ringtone = null
        runCatching { val v = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator else getSystemService(Vibrator::class.java); v.cancel() }
    }
    private fun showAlarmOverlay(base: Long, day: Int, scheduleId: String) {
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
            val alarmTime = LocalTime.of(AlarmPrefs.hour(this, scheduleId), AlarmPrefs.minute(this, scheduleId))
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
                        if (action == ACTION_MUTE) startService(Intent(this@AlarmPlaybackService, AlarmPlaybackService::class.java).setAction(ACTION_MUTE).putExtra(EXTRA_SCHEDULE_ID, scheduleId).putExtra(EXTRA_BASE_DAY, base).putExtra(EXTRA_WINDOW, day))
                        else sendBroadcast(Intent(this@AlarmPlaybackService, AlarmActionReceiver::class.java).setAction(action).putExtra(EXTRA_SCHEDULE_ID, scheduleId).putExtra(EXTRA_BASE_DAY, base).putExtra(EXTRA_WINDOW, day))
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
        val scheduleId = intent.getStringExtra(EXTRA_SCHEDULE_ID) ?: AlarmPrefs.schedule(context)?.id ?: return
        val schedule = AlarmPrefs.schedule(context, scheduleId) ?: return
        val base = intent.getLongExtra(EXTRA_BASE_DAY, LocalDate.now().toEpochDay())
        val day = intent.getIntExtra(EXTRA_WINDOW, 1)
        if (intent.action == ACTION_STOP) {
            context.startService(Intent(context, AlarmPlaybackService::class.java).setAction(ACTION_MUTE).putExtra(EXTRA_SCHEDULE_ID, scheduleId).putExtra(EXTRA_BASE_DAY, base).putExtra(EXTRA_WINDOW, day))
            context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID_BASE + schedule.notificationId, buildNotification(context, base, day, scheduleId))
            return
        }
        when (intent.action) {
            ACTION_SNOOZE -> AlarmScheduler.snooze(context, base, day, scheduleId)
            ACTION_DONE -> { AlarmPrefs.markDone(context, base, "notification", scheduleId); AlarmScheduler.schedule(context) }
        }
        context.stopService(Intent(context, AlarmPlaybackService::class.java))
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID_BASE + schedule.notificationId)
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
fun buildNotification(c: Context, base: Long, day: Int, scheduleId: String? = null): Notification {
    val schedule = AlarmPrefs.schedule(c, scheduleId) ?: return NotificationCompat.Builder(c, alarmChannelId(c)).setSmallIcon(android.R.drawable.ic_lock_idle_alarm).build()
    val fullI = Intent(c, RingingActivity::class.java).putExtra(EXTRA_SCHEDULE_ID, schedule.id).putExtra(EXTRA_BASE_DAY, base).putExtra(EXTRA_WINDOW, day).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).setData(Uri.parse("basmaalarm://ring/${schedule.id}/$base/$day"))
    val full = PendingIntent.getActivity(c, 8100 + schedule.notificationId * 4 + day, fullI, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun action(label: String, act: String, code: Int): NotificationCompat.Action {
        val i = Intent(c, AlarmActionReceiver::class.java).setAction(act).putExtra(EXTRA_SCHEDULE_ID, schedule.id).putExtra(EXTRA_BASE_DAY, base).putExtra(EXTRA_WINDOW, day).setData(Uri.parse("basmaalarm://action/${schedule.id}/$base/$day/$act"))
        val p = PendingIntent.getBroadcast(c, schedule.notificationId * 10_000 + code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Action.Builder(0, label, p).build()
    }
    val lang = AlarmPrefs.language(c)
    val done = when (lang) { "tr" -> "Tamamlandı"; "en" -> "Completed"; else -> "تمت البصمة" }
    val snooze = when (lang) { "tr" -> "${AlarmPrefs.snoozeMinutes(c)} dk ertele"; "en" -> "Snooze ${AlarmPrefs.snoozeMinutes(c)} min"; else -> "غفوة ${AlarmPrefs.snoozeMinutes(c)} د" }
    val stop = when (lang) { "tr" -> "Sesi durdur"; "en" -> "Stop sound"; else -> "إيقاف الصوت" }
    return NotificationCompat.Builder(c, alarmChannelId(c)).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
        .setContentTitle(alarmTitle(c)).setContentText("${schedule.name}: ${alarmBody(c, day)}")
        .setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(if (AlarmPrefs.highPriority(c)) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT)
        .setOngoing(true).setFullScreenIntent(full, AlarmPrefs.highPriority(c)).setContentIntent(full)
        .addAction(action(done, ACTION_DONE, 8200 + day)).addAction(action(snooze, ACTION_SNOOZE, 8300 + day)).addAction(action(stop, ACTION_STOP, 8400 + day)).build()
}
const val CHANNEL_ALARM = "basma_alarm_channel"
const val CHANNEL_ALARM_HIGH = "basma_alarm_channel_high"
const val CHANNEL_ALARM_NORMAL = "basma_alarm_channel_normal"
const val CHANNEL_REMINDER = "basma_reminder_channel"
const val NOTIFICATION_ID_BASE = 6210
const val NOTIFICATION_PRE_NOTICE_BASE = 6211
const val NOTIFICATION_ID = NOTIFICATION_ID_BASE + 1
const val NOTIFICATION_PRE_NOTICE = NOTIFICATION_PRE_NOTICE_BASE + 1
