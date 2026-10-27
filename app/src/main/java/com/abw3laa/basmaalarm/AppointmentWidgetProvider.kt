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
import java.time.temporal.ChronoUnit
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
            views.setTextViewText(R.id.widget_home, when (lang) { "tr" -> "Ana sayfa"; "en" -> "Home"; else -> "الرئيسية" })
            views.setTextViewText(R.id.widget_history, when (lang) { "tr" -> "Geçmiş"; "en" -> "History"; else -> "السجل" })
            if (schedule == null) {
                views.setTextViewText(R.id.widget_schedule, when (lang) { "tr" -> "Henüz program yok"; "en" -> "No schedule yet"; else -> "لا يوجد جدول بعد" })
                views.setTextViewText(R.id.widget_next, when (lang) { "tr" -> "Yeni program eklemek için dokunun"; "en" -> "Tap to add a schedule"; else -> "اضغط لإضافة جدول" })
            } else {
                views.setTextViewText(R.id.widget_schedule, schedule.name)
                views.setTextViewText(R.id.widget_next, if (next == null) {
                    val allConfirmed = schedule.completedEpochDays.size >= schedule.appointmentCount
                    when (lang) {
                        "tr" -> if (allConfirmed) "Tüm randevular onaylandı" else "Program sona erdi; onaylanmayan randevular var"
                        "en" -> if (allConfirmed) "All appointments confirmed" else "Schedule ended; some appointments were not confirmed"
                        else -> if (allConfirmed) "تم تأكيد جميع المواعيد" else "انتهى الجدول مع مواعيد غير مؤكدة"
                    }
                } else {
                    val date = next.format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.forLanguageTag(lang)))
                    val days = ChronoUnit.DAYS.between(LocalDate.now(), next).coerceAtLeast(0)
                    val end = next.plusDays(2).format(DateTimeFormatter.ofPattern("dd MMM", Locale.forLanguageTag(lang)))
                    when (lang) {
                        "tr" -> "Sonraki randevu: $date • $days gün kaldı\nUygun süre: $end tarihine kadar"
                        "en" -> "Next appointment: $date • $days days left\nAvailable through $end"
                        else -> "الموعد القادم: $date • متبقٍ $days يوم\nالإتاحة حتى $end"
                    }
                })
            }
            views.setInt(R.id.widget_root, "setLayoutDirection", if (lang == "ar") android.view.View.LAYOUT_DIRECTION_RTL else android.view.View.LAYOUT_DIRECTION_LTR)
            fun openPage(page: String, request: Int): PendingIntent = PendingIntent.getActivity(
                context, id * 10 + request,
                Intent(context, MainActivity::class.java).putExtra("initial_page", page)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .setData(android.net.Uri.parse("basmaalarm://widget/$id/$page")),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_root, openPage("home", 0))
            views.setOnClickPendingIntent(R.id.widget_home, openPage("home", 1))
            views.setOnClickPendingIntent(R.id.widget_history, openPage("history", 2))
            manager.updateAppWidget(id, views)
        }
    }
}
