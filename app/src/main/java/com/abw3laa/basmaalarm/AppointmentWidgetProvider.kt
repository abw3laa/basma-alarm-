package com.abw3laa.basmaalarm

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class AppointmentWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { updateWidget(context, manager, it) }
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, AppointmentWidgetProvider::class.java))
            ids.forEach { updateWidget(context, manager, it) }
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.appointment_widget)
            val lang = AlarmPrefs.language(context)
            val schedule = ScheduleRepository.active(context)
            val next = schedule?.let { AlarmScheduler.nextBase(context, it.id) }
            val title = when (lang) { "tr" -> "Parmak İzi Alarmı"; "en" -> "Basma Alarm"; else -> "منبه بصمة" }
            views.setTextViewText(R.id.widget_title, title)
            if (schedule == null) {
                views.setTextViewText(R.id.widget_schedule, when (lang) { "tr" -> "Henüz program yok"; "en" -> "No schedule yet"; else -> "لا يوجد جدول بعد" })
                views.setTextViewText(R.id.widget_next, when (lang) { "tr" -> "Yeni program eklemek için dokunun"; "en" -> "Tap to add a schedule"; else -> "اضغط لإضافة جدول" })
            } else {
                views.setTextViewText(R.id.widget_schedule, schedule.name)
                views.setTextViewText(R.id.widget_next, if (next == null) {
                    when (lang) { "tr" -> "Program tamamlandı"; "en" -> "Schedule completed"; else -> "انتهت مواعيد الجدول" }
                } else {
                    val date = next.format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.forLanguageTag(lang)))
                    when (lang) { "tr" -> "Sonraki randevu: $date"; "en" -> "Next appointment: $date"; else -> "الموعد القادم: $date" }
                })
            }
            val open = PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).setData(android.net.Uri.parse("basmaalarm://widget/$id")), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_root, open)
            manager.updateAppWidget(id, views)
        }
    }
}
