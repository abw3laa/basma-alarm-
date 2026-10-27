package com.abw3laa.basmaalarm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

data class FingerprintSchedule(
    val id: String,
    val name: String,
    val start: LocalDate,
    val intervalDays: Int,
    val appointmentCount: Int,
    val hour: Int,
    val minute: Int,
    val completedEpochDays: Set<Long> = emptySet(),
    val notificationId: Int = 0,
)

/** Versioned SharedPreferences repository; legacy single-schedule keys are copied, never removed. */
object ScheduleRepository {
    private const val FILE = "basma_alarm_prefs"
    private const val KEY_SCHEDULES = "schedules_v1"
    private const val KEY_ACTIVE = "active_schedule_id"
    private const val KEY_EVENTS = "appointment_events_v1"

    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun all(c: Context): List<FingerprintSchedule> {
        val prefs = prefs(c)
        if (!prefs.contains(KEY_SCHEDULES)) {
            if (!prefs.contains("start")) return emptyList()
            val legacy = FingerprintSchedule(
                id = "legacy",
                name = "جدول البصمة",
                start = runCatching { LocalDate.parse(prefs.getString("start", null)) }.getOrDefault(LocalDate.now()),
                intervalDays = prefs.getInt("interval", 28).coerceIn(1, 365),
                appointmentCount = prefs.getInt("count", 14).coerceIn(1, 60),
                hour = prefs.getInt("hour", 10).coerceIn(0, 23),
                minute = prefs.getInt("minute", 0).coerceIn(0, 59),
                completedEpochDays = prefs.getStringSet("completed", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet(),
                notificationId = 1,
            )
            save(c, legacy)
            prefs.edit().putString(KEY_ACTIVE, legacy.id).apply()
            return listOf(legacy)
        }
        val raw = prefs.getString(KEY_SCHEDULES, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let(::fromJson) }
        }.getOrDefault(emptyList())
    }

    fun get(c: Context, id: String?): FingerprintSchedule? = all(c).firstOrNull { it.id == id }

    fun active(c: Context): FingerprintSchedule? {
        val schedules = all(c)
        val id = prefs(c).getString(KEY_ACTIVE, null)
        return schedules.firstOrNull { it.id == id } ?: schedules.firstOrNull()
    }

    fun setActive(c: Context, id: String) {
        if (all(c).any { it.id == id }) {
            prefs(c).edit().putString(KEY_ACTIVE, id).apply()
            runCatching { AppointmentWidgetProvider.updateAll(c) }
        }
    }

    fun create(c: Context, name: String, start: LocalDate = LocalDate.now(), intervalDays: Int = 28, count: Int = 14, hour: Int = AlarmPrefs.defaultHour(c), minute: Int = AlarmPrefs.defaultMinute(c)): FingerprintSchedule {
        val nextId = prefs(c).getInt("next_schedule_notification_id", 2).coerceAtLeast(2)
        prefs(c).edit().putInt("next_schedule_notification_id", nextId + 1).apply()
        val schedule = FingerprintSchedule(UUID.randomUUID().toString(), name, start, intervalDays.coerceIn(1,365), count.coerceIn(1,60), hour.coerceIn(0,23), minute.coerceIn(0,59), notificationId = nextId)
        save(c, schedule)
        setActive(c, schedule.id)
        return schedule
    }

    fun save(c: Context, schedule: FingerprintSchedule) {
        val schedules = allWithoutMigration(c).toMutableList()
        val index = schedules.indexOfFirst { it.id == schedule.id }
        if (index >= 0) schedules[index] = schedule else schedules.add(schedule)
        persist(c, schedules)
        runCatching { AppointmentWidgetProvider.updateAll(c) }
    }

    fun delete(c: Context, id: String) {
        val remaining = all(c).filterNot { it.id == id }
        persist(c, remaining)
        val prefs = prefs(c)
        if (prefs.getString(KEY_ACTIVE, null) == id) prefs.edit().putString(KEY_ACTIVE, remaining.firstOrNull()?.id).apply()
        runCatching { AppointmentWidgetProvider.updateAll(c) }
    }

    fun markCompleted(c: Context, scheduleId: String, baseEpochDay: Long, method: String, note: String = ""): Boolean {
        val schedule = get(c, scheduleId) ?: return false
        if (baseEpochDay in schedule.completedEpochDays) return false
        val updated = schedule.copy(completedEpochDays = schedule.completedEpochDays + baseEpochDay)
        save(c, updated)
        val event = JSONObject().put("scheduleId", schedule.id).put("scheduleName", schedule.name)
            .put("appointmentEpochDay", baseEpochDay).put("confirmedAtEpochMillis", System.currentTimeMillis())
            .put("method", method).put("status", AppointmentState.COMPLETED.name).put("note", note)
        val array = runCatching { JSONArray(prefs(c).getString(KEY_EVENTS, "[]")) }.getOrDefault(JSONArray())
        array.put(event)
        prefs(c).edit().putString(KEY_EVENTS, array.toString()).apply()
        return true
    }

    fun events(c: Context): List<AppointmentEvent> = runCatching {
        val array = JSONArray(prefs(c).getString(KEY_EVENTS, "[]"))
        (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            AppointmentEvent(item.optString("scheduleId"), item.optString("scheduleName"), item.optLong("appointmentEpochDay"), item.optLong("confirmedAtEpochMillis"), item.optString("method"), runCatching { AppointmentState.valueOf(item.optString("status")) }.getOrDefault(AppointmentState.COMPLETED), item.optString("note"))
        }.sortedByDescending { it.confirmedAtEpochMillis }
    }.getOrDefault(emptyList())

    fun updateEventNote(c: Context, confirmedAtEpochMillis: Long, note: String) {
        val prefs = prefs(c)
        val array = runCatching { JSONArray(prefs.getString(KEY_EVENTS, "[]")) }.getOrDefault(JSONArray())
        for (index in 0 until array.length()) {
            val event = array.optJSONObject(index) ?: continue
            if (event.optLong("confirmedAtEpochMillis") == confirmedAtEpochMillis) {
                event.put("note", note.take(280))
                break
            }
        }
        prefs.edit().putString(KEY_EVENTS, array.toString()).apply()
    }

    private fun allWithoutMigration(c: Context): List<FingerprintSchedule> {
        val prefs = prefs(c)
        if (!prefs.contains(KEY_SCHEDULES)) return emptyList()
        return runCatching {
            val array = JSONArray(prefs.getString(KEY_SCHEDULES, "[]"))
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::fromJson) }
        }.getOrDefault(emptyList())
    }

    private fun persist(c: Context, schedules: List<FingerprintSchedule>) {
        val array = JSONArray()
        schedules.forEach { array.put(toJson(it)) }
        prefs(c).edit().putString(KEY_SCHEDULES, array.toString()).apply()
    }

    private fun toJson(s: FingerprintSchedule) = JSONObject().put("id", s.id).put("name", s.name)
        .put("start", s.start.toString()).put("intervalDays", s.intervalDays).put("appointmentCount", s.appointmentCount)
        .put("hour", s.hour).put("minute", s.minute).put("completedEpochDays", JSONArray(s.completedEpochDays.map { it.toString() }))
        .put("notificationId", s.notificationId)

    private fun fromJson(o: JSONObject): FingerprintSchedule? = runCatching {
        val completed = o.optJSONArray("completedEpochDays") ?: JSONArray()
        FingerprintSchedule(
            id = o.getString("id"), name = o.optString("name", "Schedule"), start = LocalDate.parse(o.getString("start")),
            intervalDays = o.optInt("intervalDays", 28).coerceIn(1,365), appointmentCount = o.optInt("appointmentCount", 14).coerceIn(1,60),
            hour = o.optInt("hour", 10).coerceIn(0,23), minute = o.optInt("minute", 0).coerceIn(0,59),
            completedEpochDays = (0 until completed.length()).mapNotNull { completed.optString(it).toLongOrNull() }.toSet(),
            notificationId = o.optInt("notificationId", 1).coerceAtLeast(1),
        )
    }.getOrNull()
}
