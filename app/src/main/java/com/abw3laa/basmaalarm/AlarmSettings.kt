package com.abw3laa.basmaalarm

import android.content.Context

/** App-wide alarm behavior preferences, kept separate from schedule data and AlarmManager logic. */
object AlarmSettings {
    private const val FILE = "basma_alarm_prefs"
    fun preferences(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun defaultHour(c: Context) = preferences(c).getInt("default_hour", 10).coerceIn(0, 23)
    fun defaultMinute(c: Context) = preferences(c).getInt("default_minute", 0).coerceIn(0, 59)
    fun language(c: Context) = preferences(c).getString("language", "ar") ?: "ar"
    fun theme(c: Context) = preferences(c).getString("theme", "dark") ?: "dark"
    fun vibrate(c: Context) = preferences(c).getBoolean("vibrate", true)
    fun sound(c: Context) = preferences(c).getBoolean("sound", true)
    fun soundUri(c: Context): String? = preferences(c).getString("sound_uri", null)
    fun autoStopMinutes(c: Context) = preferences(c).getInt("auto_stop_minutes", 10).coerceIn(0, 120)
    fun snoozeMinutes(c: Context) = preferences(c).getInt("snooze_minutes", 60).coerceIn(1, 240)
    fun highPriority(c: Context) = preferences(c).getBoolean("high_priority", true)
    fun remindDayBefore(c: Context) = preferences(c).getBoolean("remind_day_before", true)
    fun repeatOnFingerprintDay(c: Context) = preferences(c).getBoolean("repeat_on_fingerprint_day", true)
    fun remindSecondDay(c: Context) = preferences(c).getBoolean("remind_second_day", true)
}
