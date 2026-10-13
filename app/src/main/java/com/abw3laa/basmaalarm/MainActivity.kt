package com.abw3laa.basmaalarm

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.app.AlertDialog
import androidx.core.content.FileProvider
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import org.json.JSONObject
import kotlin.concurrent.thread

private val Navy = Color(0xFF0F2537)
private val Teal = Color(0xFF006A61)
private val Mint = Color(0xFFE0F7F3)
private val Canvas = Color(0xFFF7F9FB)
private val Danger = Color(0xFFBA1A1A)

class MainActivity : ComponentActivity() {
    private var pendingApk: File? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(247, 249, 251)
        window.navigationBarColor = android.graphics.Color.rgb(247, 249, 251)
        if (Build.VERSION.SDK_INT >= 23) {
            window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        ensureAlarmChannel(this)
        if (AlarmPrefs.sp(this).contains("start")) AlarmScheduler.schedule(this)
        setContent { BasmaApp() }
        checkForUpdate()
    }

    private fun checkForUpdate() {
        thread {
            try {
                val connection = URL("https://raw.githubusercontent.com/abw3laa/basma-alarm-updates/main/update.json").openConnection()
                connection.connectTimeout = 7000; connection.readTimeout = 7000
                val json = JSONObject(connection.getInputStream().bufferedReader().use { it.readText() })
                val installedInfo = packageManager.getPackageInfo(packageName, 0)
                val current = if (Build.VERSION.SDK_INT >= 28) installedInfo.longVersionCode.toInt() else @Suppress("DEPRECATION") installedInfo.versionCode
                if (json.optInt("versionCode", 0) > current) Handler(Looper.getMainLooper()).post {
                    AlertDialog.Builder(this).setTitle("تحديث متوفر")
                        .setMessage("يتوفر الإصدار ${json.optString("versionName")}. هل تريد تنزيله وتثبيته الآن؟")
                        .setNegativeButton("لاحقاً", null)
                        .setPositiveButton("تثبيت الآن") { _, _ -> downloadUpdate(json.optString("apkUrl")) }.show()
                }
            } catch (_: Exception) { /* No manifest yet or offline; continue working offline. */ }
        }
    }

    private fun downloadUpdate(url: String) {
        if (!url.startsWith("https://")) { Toast.makeText(this, "رابط التحديث غير جاهز بعد", Toast.LENGTH_LONG).show(); return }
        Toast.makeText(this, "جارٍ تنزيل التحديث…", Toast.LENGTH_SHORT).show()
        thread {
            try {
                val connection = URL(url).openConnection(); connection.connectTimeout = 15000; connection.readTimeout = 30000
                val file = File(cacheDir, "basma-update.apk")
                connection.getInputStream().use { input -> FileOutputStream(file).use { output -> input.copyTo(output) } }
                if (file.length() < 100_000) throw IllegalStateException("Downloaded package is too small")
                Handler(Looper.getMainLooper()).post { pendingApk = file; installOrRequestPermission(file) }
            } catch (_: Exception) { Handler(Looper.getMainLooper()).post { Toast.makeText(this, "تعذر تنزيل التحديث. تحقق من الرابط والاتصال.", Toast.LENGTH_LONG).show() } }
        }
    }

    private fun installOrRequestPermission(file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            startActivityForResult(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")), 731)
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    @Deprecated("Android package installer permission flow")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 731) pendingApk?.let { if (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()) installOrRequestPermission(it) }
    }
}

@Composable private fun BasmaApp() {
    val context = LocalContext.current
    var lang by remember { mutableStateOf(AlarmPrefs.language(context)) }
    var theme by remember { mutableStateOf(AlarmPrefs.theme(context)) }
    var page by remember { mutableStateOf(if (AlarmPrefs.sp(context).getBoolean("intro_shown", false)) "home" else "welcome") }
    var refresh by remember { mutableIntStateOf(0) }
    val dark = when (theme) { "dark" -> true; "light" -> false; else -> androidx.compose.foundation.isSystemInDarkTheme() }
    val bg = if (dark) Color(0xFF111B24) else Canvas
    val surface = if (dark) Color(0xFF1B2934) else Color.White
    val ink = if (dark) Color(0xFFE8EDF1) else Color(0xFF191C1E)
    val muted = if (dark) Color(0xFFB7C3CC) else Color(0xFF626B70)
    val direction = if (lang == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xFF78D9CD), onPrimary = Navy, secondary = Color(0xFF78D9CD), background = bg, surface = surface, onSurface = ink, onBackground = ink, error = Color(0xFFFFB4AB)) else lightColorScheme(primary = Teal, onPrimary = Color.White, secondary = Teal, background = bg, surface = surface, onSurface = ink, onBackground = ink, error = Danger)) {
            Scaffold(containerColor = bg, bottomBar = {
                if (page != "new" && page != "welcome") Row(Modifier.fillMaxWidth().background(surface).navigationBarsPadding().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
                    NavItem("home", "home", Icons.Filled.Home, page, { page = it }, ink, muted)
                    NavItem("appointments", "appointments", Icons.Filled.CalendarMonth, page, { page = it }, ink, muted)
                    NavItem("history", "history", Icons.Filled.History, page, { page = it }, ink, muted)
                    NavItem("settings", "settings", Icons.Filled.Settings, page, { page = it }, ink, muted)
                }
            }) { pad ->
                when (page) {
                    "welcome" -> WelcomeScreen(lang, surface, ink, muted, bg) { AlarmPrefs.sp(context).edit().putBoolean("intro_shown", true).apply(); page = "home" }
                    "new" -> NewAppointment(lang, surface, ink, muted, bg, onCancel = { page = "home" }, onSaved = { refresh++; page = "home" })
                    "appointments", "history" -> HistoryScreen(lang, surface, ink, muted, bg, refresh) { page = it }
                    "settings" -> SettingsScreen(lang, theme, dark, surface, ink, muted, bg,
                        setLang = { lang = it; AlarmPrefs.sp(context).edit().putString("language", it).apply() },
                        setTheme = { theme = it; AlarmPrefs.sp(context).edit().putString("theme", it).apply() })
                    else -> HomeScreen(lang, surface, ink, muted, bg, refresh, goNew = { page = "new" }, goSettings = { page = "settings" }, onRefresh = { refresh++ })
                }
            }
        }
    }
}

@Composable private fun androidx.compose.foundation.layout.RowScope.NavItem(pageKey: String, labelKey: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: String, onSelect: (String) -> Unit, ink: Color, muted: Color) {
    val color = if (selected == pageKey) Teal else muted
    Column(Modifier.weight(1f).clickable { onSelect(pageKey) }.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
        Text(tr(labelKey), color = color, fontSize = 11.sp, textAlign = TextAlign.Center)
    }
}

@Composable private fun WelcomeScreen(lang: String, surface: Color, ink: Color, muted: Color, bg: Color, begin: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(18.dp))
        Surface(color = Color.White, shape = CircleShape, shadowElevation = 8.dp, modifier = Modifier.size(86.dp)) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Fingerprint, null, tint = Color(0xFF9B222C), modifier = Modifier.size(54.dp)) } }
        Text(if (lang == "ar") "مرحباً بك في منبّه بصمة" else if (lang == "tr") "Parmak İzi Hatırlatıcısına Hoş Geldiniz" else "Welcome to Basma Alarm", color = ink, fontSize = 25.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 18.dp))
        Text(if (lang == "ar") "كل مواعيدك وتنبيهاتك في مكان واحد وبدقة متناهية لا تقبل الخطأ." else if (lang == "tr") "Randevularınız ve hatırlatmalarınız tek yerde, hassas zamanlamayla." else "Your appointments and reminders in one place, scheduled with precision.", color = muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 18.dp))
        Text(if (lang == "ar") "أبرز الميزات والتحديثات" else if (lang == "tr") "Özellikler ve güncellemeler" else "Features and updates", Modifier.fillMaxWidth().padding(bottom = 8.dp), color = ink, fontWeight = FontWeight.Bold, fontSize = 19.sp)
        val features = if (lang == "ar") listOf("تنبيهات فعلية لمواعيد البصمة" to "صوت واهتزاز مع تذكيرات مستمرة", "تكرار ذكي كل 28 يوماً أو أسبوعياً" to "اختر دورة المواعيد المناسبة", "نافذة متابعة لثلاثة أيام" to "تذكير بالموعد الأساسي والمهلة", "تذكير تلقائي بعد ساعة" to "أجّل التنبيه دون نسيان الموعد", "استعادة التنبيهات بعد إعادة التشغيل" to "إعادة جدولة المواعيد المحفوظة", "يعمل دون اتصال" to "مواعيدك محفوظة على الجهاز") else if (lang == "tr") listOf("Gerçek randevu alarmları" to "Sesli ve titreşimli hatırlatma", "28 günlük veya haftalık tekrar" to "Uygun randevu döngüsünü seçin", "Üç günlük takip aralığı" to "Ana tarih ve ek hatırlatmalar", "Bir saat sonra tekrar hatırlat" to "Alarmı ertele", "Yeniden başlatmada alarmları geri yükle" to "Kayıtlı randevular yeniden kurulur", "Çevrimdışı çalışır" to "Randevularınız cihazda saklanır") else listOf("Real appointment alarms" to "Sound and vibration reminders", "Repeat every 28 days or weekly" to "Choose the schedule that suits you", "Three-day follow-up window" to "Reminders for the appointment period", "Snooze for one hour" to "Delay without forgetting the date", "Restore alarms after reboot" to "Saved reminders are rescheduled", "Works offline" to "Your schedule stays on your device")
        features.forEachIndexed { index, pair -> Card(Modifier.fillMaxWidth().padding(bottom = 8.dp), colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(1.dp)) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { BadgeText("${index + 1}", if (index == 0) Color(0xFFFFE8E7) else Mint, if (index == 0) Danger else Teal); Column(Modifier.padding(horizontal = 12.dp)) { Text(pair.first, color = ink, fontWeight = FontWeight.SemiBold); Text(pair.second, color = muted, fontSize = 12.sp) } } } }
        Button(onClick = begin, Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 24.dp).height(54.dp), colors = ButtonDefaults.buttonColors(containerColor = Navy), shape = RoundedCornerShape(14.dp)) { Text(if (lang == "ar") "ابدأ الاستخدام الآن" else if (lang == "tr") "Şimdi başlayın" else "Get started", fontWeight = FontWeight.Bold) }
    }
}

@Composable private fun HomeScreen(lang: String, surface: Color, ink: Color, muted: Color, bg: Color, refresh: Int, goNew: () -> Unit, goSettings: () -> Unit, onRefresh: () -> Unit) {
    val context = LocalContext.current
    val first = AlarmPrefs.start(context)
    val next = AlarmScheduler.nextBase(context)
    val time = LocalTime.of(AlarmPrefs.hour(context), AlarmPrefs.minute(context))
    val interval = AlarmPrefs.interval(context)
    val days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), next).coerceAtLeast(0).toInt()
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(bottom = 4.dp).verticalScroll(rememberScrollState())) {
        Header(tr("home"), Icons.Filled.Home, ink)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("◉", color = Teal, fontSize = 24.sp)
            Text(LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US)), color = muted, fontSize = 13.sp)
        }
        Card(Modifier.fillMaxWidth().padding(top = 12.dp), colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(2.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Shield, null, tint = Teal, modifier = Modifier.size(28.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(tr("secure_alarm"), color = ink, fontWeight = FontWeight.Bold); Text(tr("secure_sub"), color = muted, fontSize = 12.sp) }
                Icon(Icons.Filled.Check, null, tint = Teal)
            }
        }
        Button(onClick = goNew, Modifier.fillMaxWidth().padding(top = 14.dp).height(58.dp), colors = ButtonDefaults.buttonColors(containerColor = surface, contentColor = ink), shape = RoundedCornerShape(14.dp), elevation = ButtonDefaults.buttonElevation(2.dp)) {
            Icon(Icons.Filled.Add, null, tint = Teal); Spacer(Modifier.width(8.dp)); Text(tr("new_fingerprint"), fontWeight = FontWeight.Bold)
        }
        Card(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 20.dp), colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(20.dp), elevation = CardDefaults.cardElevation(2.dp)) {
            Column(Modifier.padding(18.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { BadgeText(tr("soon"), Mint, Teal); BadgeText(time.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US)), Color(0xFFF1F2F3), ink) }
                Spacer(Modifier.height(16.dp))
                Text(tr("next_appointment"), color = muted, fontSize = 13.sp)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(next.format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US)), color = ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    BadgeText(tr("days_left", days), Mint, Teal)
                }
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(tr("allowed_days"), color = ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp); Text("${next.format(DateTimeFormatter.ofPattern("dd/MM", Locale.US))} – ${next.plusDays(2).format(DateTimeFormatter.ofPattern("dd/MM", Locale.US))}", color = muted, fontSize = 12.sp) }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (0..2).forEach { d -> val c = listOf(Teal, Color(0xFFF0F1F2), Color(0xFFFFEDEE))[d]; val fg = if (d == 0) Color.White else if (d == 2) Danger else ink
                        Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = c), shape = RoundedCornerShape(12.dp)) { Column(Modifier.padding(9.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(tr("day_num", d + 1), color = fg, fontSize = 11.sp); Text(next.plusDays(d.toLong()).format(DateTimeFormatter.ofPattern("dd", Locale.US)), color = fg, fontSize = 22.sp, fontWeight = FontWeight.Bold); Text(next.plusDays(d.toLong()).format(DateTimeFormatter.ofPattern("MMM", Locale.US)), color = fg, fontSize = 11.sp); Text(listOf(tr("main_day"), tr("reminder"), tr("last_chance"))[d], color = fg, fontSize = 10.sp, textAlign = TextAlign.Center) } }
                    }
                }
                Button(onClick = { AlarmPrefs.markDone(context, next.toEpochDay()); AlarmScheduler.schedule(context); onRefresh() }, Modifier.fillMaxWidth().padding(top = 18.dp).height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = Teal), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Filled.Fingerprint, null); Spacer(Modifier.width(8.dp)); Text(tr("confirm_fingerprint"), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable private fun NewAppointment(lang: String, surface: Color, ink: Color, muted: Color, bg: Color, onCancel: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    var date by remember { mutableStateOf(if (AlarmPrefs.sp(context).contains("start")) AlarmPrefs.start(context) else LocalDate.now()) }
    var hour by remember { mutableIntStateOf(AlarmPrefs.hour(context)) }
    var minute by remember { mutableIntStateOf(AlarmPrefs.minute(context)) }
    var interval by remember { mutableIntStateOf(AlarmPrefs.interval(context)) }
    var count by remember { mutableIntStateOf(AlarmPrefs.count(context)) }
    var vibrate by remember { mutableStateOf(AlarmPrefs.vibrate(context)) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        Header(tr("new_fingerprint"), Icons.Filled.Fingerprint, ink, onCancel)
        Text(tr("set_start"), color = ink, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
        SettingCard(surface, ink, muted, Icons.Filled.CalendarMonth, date.format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.forLanguageTag(lang)))) {
            DatePickerDialog(context, { _, y, m, d -> date = LocalDate.of(y, m + 1, d) }, date.year, date.monthValue - 1, date.dayOfMonth).show()
        }
        Text(tr("alarm_time"), color = ink, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
        SettingCard(surface, ink, muted, Icons.Filled.Schedule, LocalTime.of(hour, minute).format(DateTimeFormatter.ofPattern("h:mm a", Locale.forLanguageTag(lang)))) {
            TimePickerDialog(context, { _, h, m -> hour = h; minute = m }, hour, minute, false).show()
        }
        Text(tr("repeat_cycle"), color = ink, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CycleCard(tr("every_28"), tr("official_cycle"), interval == 28, surface, ink, Modifier.weight(1f)) { interval = 28 }
            CycleCard(tr("weekly"), tr("every_week"), interval == 7, surface, ink, Modifier.weight(1f)) { interval = 7 }
        }
        Text(tr("cycles_count"), color = ink, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
        Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(14.dp)) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(onClick = { count = (count - 1).coerceAtLeast(1) }) { Text("−") }
                Text("$count  ${tr("appointments")}", color = ink, fontWeight = FontWeight.Bold)
                Button(onClick = { count = (count + 1).coerceAtMost(60) }) { Text("＋") }
            }
        }
        Text(tr("sound_settings"), color = ink, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
        Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(14.dp)) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Vibration, null, tint = Teal); Column(Modifier.weight(1f).padding(horizontal = 10.dp)) { Text(tr("vibration"), color = ink, fontWeight = FontWeight.SemiBold); Text(tr("vibration_sub"), color = muted, fontSize = 12.sp) }; Switch(vibrate, { vibrate = it })
            }
        }
        Button(onClick = {
            AlarmScheduler.cancelAll(context)
            AlarmPrefs.sp(context).edit().putString("start", date.toString()).putInt("hour", hour).putInt("minute", minute).putInt("interval", interval).putInt("count", count).putBoolean("vibrate", vibrate).apply()
            AlarmScheduler.schedule(context)
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 31 && !context.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()) context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
            onSaved()
        }, Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 28.dp).height(54.dp), colors = ButtonDefaults.buttonColors(containerColor = Teal), shape = RoundedCornerShape(14.dp)) { Text(tr("save"), fontWeight = FontWeight.Bold, fontSize = 16.sp) }
    }
}

@Composable private fun HistoryScreen(lang: String, surface: Color, ink: Color, muted: Color, bg: Color, refresh: Int, navigate: (String) -> Unit) {
    val context = LocalContext.current
    val next = AlarmScheduler.nextBase(context)
    val interval = AlarmPrefs.interval(context)
    val count = AlarmPrefs.count(context)
    val done = AlarmPrefs.completed(context)
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Header(tr("appointments"), Icons.Filled.CalendarMonth, ink)
        Text(tr("history_title"), color = ink, fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
        Text(tr("history_sub"), color = muted, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BadgeText("${tr("upcoming")}  ${count - done.size}", Mint, Teal); BadgeText("${tr("previous")}  ${done.size}", Color(0xFFEFF1F3), muted)
            Spacer(Modifier.weight(1f)); TextButton(onClick = { navigate("new") }) { Icon(Icons.Filled.Add, null); Text(tr("new_short")) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(tr("upcoming_list"), color = muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
            val dates = (0 until count).map { AlarmPrefs.start(context).plusDays(it.toLong() * interval) }.filter { it.toEpochDay() !in done && it.plusDays(2) >= LocalDate.now() }.take(20)
            dates.forEachIndexed { index, d ->
                Card(Modifier.fillMaxWidth().padding(bottom = 10.dp), colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(1.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column { Text(tr("appointment_num", index + 1), color = muted, fontSize = 12.sp); Text(d.format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US)), color = ink, fontSize = 21.sp, fontWeight = FontWeight.Bold) }
                            BadgeText(if (index == 0) tr("soon") else tr("scheduled"), if (index == 0) Mint else Color(0xFFF0F1F2), if (index == 0) Teal else muted)
                        }
                        HorizontalDivider(Modifier.padding(vertical = 10.dp), color = bg)
                        Text("${tr("grace_window")}: ${d.format(DateTimeFormatter.ofPattern("dd/MM", Locale.US))} – ${d.plusDays(2).format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US))}", color = muted, fontSize = 12.sp)
                    }
                }
            }
            Text(tr("previous"), color = ink, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
            done.sortedDescending().take(30).forEachIndexed { index, epoch -> Card(Modifier.fillMaxWidth().padding(bottom = 8.dp), colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(14.dp)) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Check, null, tint = Teal); Text("${tr("completed_label")} ${LocalDate.ofEpochDay(epoch).format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US))}", Modifier.padding(start = 10.dp), color = ink) } } }
        }
    }
}

@Composable private fun SettingsScreen(lang: String, theme: String, dark: Boolean, surface: Color, ink: Color, muted: Color, bg: Color, setLang: (String) -> Unit, setTheme: (String) -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        Header(tr("settings"), Icons.Filled.Settings, ink)
        Text(tr("language"), color = ink, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("ar" to "العربية", "tr" to "Türkçe", "en" to "English").forEach { (code, name) -> FilterChip(selected = lang == code, onClick = { setLang(code) }, label = { Text(name) }) } }
        Text(tr("appearance"), color = ink, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("system" to tr("system"), "light" to tr("light"), "dark" to tr("dark")).forEach { (code, name) -> FilterChip(selected = theme == code, onClick = { setTheme(code) }, label = { Text(name) }) } }
        Text(tr("alarm_options"), color = ink, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
        PermissionCard(surface, ink, muted, tr("exact_alarm"), tr("exact_alarm_sub")) { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }
        PermissionCard(surface, ink, muted, tr("overlay"), tr("overlay_sub")) { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) }
        PermissionCard(surface, ink, muted, tr("battery"), tr("battery_sub")) { context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))) }
        PermissionCard(surface, ink, muted, tr("notifications"), tr("notifications_sub")) { if (Build.VERSION.SDK_INT >= 33) context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }
        Text(tr("sound_settings"), color = ink, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
        var sound by remember { mutableStateOf(AlarmPrefs.sound(context)) }
        var vibration by remember { mutableStateOf(AlarmPrefs.vibrate(context)) }
        ToggleRow(surface, ink, Icons.Filled.Alarm, tr("alarm_sound"), sound) { sound = it; AlarmPrefs.sp(context).edit().putBoolean("sound", it).apply() }
        ToggleRow(surface, ink, Icons.Filled.Vibration, tr("vibration"), vibration) { vibration = it; AlarmPrefs.sp(context).edit().putBoolean("vibrate", it).apply() }
        Text(tr("about_developer"), color = ink, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
        DeveloperLink(surface, ink, "WhatsApp · +905354883886", "https://wa.me/905354883886")
        DeveloperLink(surface, ink, "Telegram · @abw3laa", "https://t.me/abw3laa")
        DeveloperLink(surface, ink, "Facebook", "https://www.facebook.com/share/1EykCQDkjB/")
        Text("منبه بصمة · Android Kotlin", color = muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 18.dp).align(Alignment.CenterHorizontally))
    }
}

@Composable private fun Header(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, ink: Color, back: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        if (back != null) TextButton(onClick = back) { Text("‹", fontSize = 24.sp, color = ink) } else Icon(Icons.Filled.Fingerprint, null, tint = Teal, modifier = Modifier.size(28.dp))
        Text(title, color = ink, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Icon(icon, null, tint = Teal, modifier = Modifier.size(25.dp))
    }
}
@Composable private fun BadgeText(text: String, color: Color, fg: Color) { Surface(color = color, shape = CircleShape) { Text(text, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = fg, fontSize = 11.sp, fontWeight = FontWeight.Medium) } }
@Composable private fun SettingCard(surface: Color, ink: Color, muted: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, click: () -> Unit) { Card(Modifier.fillMaxWidth().clickable(onClick = click), colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(14.dp)) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = Teal); Text(value, Modifier.weight(1f).padding(horizontal = 12.dp), color = ink); Text("›", color = muted, fontSize = 22.sp) } } }
@Composable private fun CycleCard(title: String, sub: String, selected: Boolean, surface: Color, ink: Color, modifier: Modifier, click: () -> Unit) { Card(modifier.clickable(onClick = click), colors = CardDefaults.cardColors(containerColor = if (selected) Mint else surface), shape = RoundedCornerShape(14.dp), border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, Teal) else null) { Column(Modifier.padding(14.dp)) { Text(title, color = ink, fontWeight = FontWeight.Bold); Text(sub, color = ink.copy(alpha = .7f), fontSize = 12.sp); if (selected) Text("✓", color = Teal) } } }
@Composable private fun PermissionCard(surface: Color, ink: Color, muted: Color, title: String, sub: String, click: () -> Unit) { Card(Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable(onClick = click), colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(14.dp)) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Shield, null, tint = Teal); Column(Modifier.weight(1f).padding(horizontal = 10.dp)) { Text(title, color = ink, fontWeight = FontWeight.SemiBold); Text(sub, color = muted, fontSize = 12.sp) }; Text(tr("enable"), color = Teal, fontWeight = FontWeight.Bold) } } }
@Composable private fun ToggleRow(surface: Color, ink: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, checked: Boolean, change: (Boolean) -> Unit) { Card(Modifier.fillMaxWidth().padding(bottom = 8.dp), colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(14.dp)) { Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = Teal); Text(title, Modifier.weight(1f).padding(horizontal = 10.dp), color = ink); Switch(checked, change) } } }
@Composable private fun DeveloperLink(surface: Color, ink: Color, label: String, url: String) { val c = LocalContext.current; Card(Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable { runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }, colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(12.dp)) { Text(label, Modifier.fillMaxWidth().padding(14.dp), color = ink) } }

@Composable private fun tr(k: String, n: Int? = null): String {
    val l = currentLanguage()
    val values = when (l) {
        "en" -> mapOf("home" to "Home", "appointments" to "Appointments", "history" to "History", "settings" to "Settings", "secure_alarm" to "Smart alarm active and protected", "secure_sub" to "Three-day reminders are scheduled", "new_fingerprint" to "Add fingerprint appointment", "soon" to "Upcoming", "next_appointment" to "Next appointment date", "days_left" to "$n days left", "allowed_days" to "Allowed days", "main_day" to "Main date", "reminder" to "Reminder", "last_chance" to "Last chance", "confirm_fingerprint" to "Confirm fingerprint", "set_start" to "First appointment date", "alarm_time" to "Alarm time", "repeat_cycle" to "Repeat cycle", "every_28" to "Every 28 days", "official_cycle" to "Regular cycle", "weekly" to "Weekly", "every_week" to "Repeats every 7 days", "cycles_count" to "Number of appointments", "appointments" to "appointments", "vibration" to "Vibration", "vibration_sub" to "Vibrate during the alarm", "sound_settings" to "Sound and vibration", "save" to "Save schedule", "cancel" to "Cancel", "history_title" to "Fingerprint appointment history", "history_sub" to "Review upcoming and completed dates", "upcoming" to "Upcoming", "previous" to "Completed", "new_short" to "New", "upcoming_list" to "Scheduled appointments", "grace_window" to "3-day window", "scheduled" to "Scheduled", "appointment_num" to "Appointment #$n", "completed_label" to "Completed", "language" to "Language", "appearance" to "Appearance", "system" to "System", "light" to "Light", "dark" to "Dark", "alarm_options" to "Permissions and reliability", "exact_alarm" to "Exact alarm access", "exact_alarm_sub" to "Allow precise scheduled alarms", "overlay" to "Display over other apps", "overlay_sub" to "Optional full-screen alarm display", "battery" to "Battery optimization", "battery_sub" to "Allow background alarm delivery", "notifications" to "Notifications", "notifications_sub" to "Manage notification permission", "alarm_sound" to "Alarm sound", "about_developer" to "About developer", "enable" to "Enable", "day_num" to "Day $n")
        "tr" -> mapOf("home" to "Ana Sayfa", "appointments" to "Randevular", "history" to "Geçmiş", "settings" to "Ayarlar", "secure_alarm" to "Akıllı alarm etkin ve güvenli", "secure_sub" to "Üç günlük hatırlatıcılar ayarlandı", "new_fingerprint" to "Yeni randevu ekle", "soon" to "Yakında", "next_appointment" to "Sonraki randevu tarihi", "days_left" to "$n gün kaldı", "allowed_days" to "Uygun günler", "main_day" to "Ana tarih", "reminder" to "Hatırlatma", "last_chance" to "Son fırsat", "confirm_fingerprint" to "Parmak izini onayla", "set_start" to "İlk randevu tarihi", "alarm_time" to "Alarm saati", "repeat_cycle" to "Tekrarlama", "every_28" to "28 günde bir", "official_cycle" to "Düzenli döngü", "weekly" to "Haftalık", "every_week" to "7 günde bir", "cycles_count" to "Randevu sayısı", "appointments" to "randevu", "vibration" to "Titreşim", "vibration_sub" to "Alarm sırasında titreşim", "sound_settings" to "Ses ve titreşim", "save" to "Programı kaydet", "cancel" to "İptal", "history_title" to "Parmak izi randevu geçmişi", "history_sub" to "Yaklaşan ve tamamlanan tarihleri görüntüle", "upcoming" to "Yaklaşan", "previous" to "Tamamlandı", "new_short" to "Yeni", "upcoming_list" to "Planlanan randevular", "grace_window" to "3 günlük süre", "scheduled" to "Planlandı", "appointment_num" to "Randevu #$n", "completed_label" to "Tamamlandı", "language" to "Dil", "appearance" to "Görünüm", "system" to "Sistem", "light" to "Açık", "dark" to "Koyu", "alarm_options" to "İzinler ve güvenilirlik", "exact_alarm" to "Kesin alarm izni", "exact_alarm_sub" to "Zamanında alarm kurulumu", "overlay" to "Diğer uygulamaların üzerinde göster", "overlay_sub" to "İsteğe bağlı tam ekran alarm", "battery" to "Pil optimizasyonu", "battery_sub" to "Arka plan alarmlarına izin ver", "notifications" to "Bildirimler", "notifications_sub" to "Bildirim izinlerini yönet", "alarm_sound" to "Alarm sesi", "about_developer" to "Geliştirici hakkında", "enable" to "Aç", "day_num" to "$n. gün")
        else -> mapOf("home" to "الرئيسية", "appointments" to "المواعيد", "history" to "السجل", "settings" to "الإعدادات", "secure_alarm" to "المنبه الذكي نشط ومؤمّن", "secure_sub" to "تم ضبط تنبيهات مهلة الأيام الثلاثة بدقة", "new_fingerprint" to "تسجيل بصمة جديدة", "soon" to "قريباً", "next_appointment" to "موعد البصمة التالي المقرّر", "days_left" to "متبقي $n يوم", "allowed_days" to "الأيام المسموحة", "main_day" to "الأساسي", "reminder" to "تذكير", "last_chance" to "الفرصة الأخيرة", "confirm_fingerprint" to "تأكيد البصمة", "set_start" to "تاريخ أول بصمة", "alarm_time" to "وقت التنبيه", "repeat_cycle" to "دورة التكرار", "every_28" to "كل 28 يوماً", "official_cycle" to "الدورة المنتظمة", "weekly" to "أسبوعي", "every_week" to "يتكرر كل 7 أيام", "cycles_count" to "عدد المواعيد المجدولة", "appointments" to "موعداً", "vibration" to "الاهتزاز", "vibration_sub" to "اهتزاز أثناء التنبيه", "sound_settings" to "صوت التنبيه", "save" to "حفظ المواعيد", "cancel" to "إلغاء", "history_title" to "سجل مواعيد البصمة", "history_sub" to "متابعة تواريخ البصمات السابقة والمقبلة", "upcoming" to "البصمات القادمة", "previous" to "البصمات السابقة", "new_short" to "بصمة جديدة", "upcoming_list" to "قائمة البصمات القادمة", "grace_window" to "مهلة السماح (3 أيام)", "scheduled" to "مجدول", "appointment_num" to "الموعد رقم $n", "completed_label" to "بصمة مكتملة بتاريخ", "language" to "اختر اللغة", "appearance" to "الثيم والمظهر", "system" to "النظام", "light" to "فاتح", "dark" to "داكن", "alarm_options" to "الأذونات وتفادي الإغلاق", "exact_alarm" to "السماح بالمنبهات الدقيقة", "exact_alarm_sub" to "تفعيل جدولة التنبيهات في وقتها", "overlay" to "الظهور فوق التطبيقات", "overlay_sub" to "إظهار واجهة المنبه فوق الشاشة", "battery" to "استثناء توفير البطارية", "battery_sub" to "منع نظام الهاتف من إيقاف المنبه", "notifications" to "إعدادات الإشعارات", "notifications_sub" to "السماح بعرض إشعارات المنبه", "alarm_sound" to "صوت التنبيه", "about_developer" to "حول المطور", "enable" to "تفعيل", "day_num" to "اليوم $n")
    }
    return values[k] ?: k
}

@Composable internal fun currentLanguage(): String = AlarmPrefs.language(LocalContext.current)
