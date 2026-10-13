package com.abw3laa.basmaalarm

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.media.RingtoneManager
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
import androidx.compose.ui.graphics.luminance
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
            SideEffect {
                val window = (context as? ComponentActivity)?.window
                window?.statusBarColor = if (dark) android.graphics.Color.rgb(17, 27, 36) else android.graphics.Color.rgb(247, 249, 251)
                window?.navigationBarColor = if (dark) android.graphics.Color.rgb(17, 27, 36) else android.graphics.Color.rgb(247, 249, 251)
                window?.decorView?.systemUiVisibility = if (dark) 0 else android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
            Scaffold(containerColor = bg, bottomBar = {
                if (page != "new" && page != "welcome") Row(Modifier.fillMaxWidth().background(surface).navigationBarsPadding().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
                    NavItem("home", "home", Icons.Filled.Home, page, { page = it }, ink, muted)
                    NavItem("appointments", "appointments", Icons.Filled.CalendarMonth, page, { page = it }, ink, muted)
                    NavItem("history", "history", Icons.Filled.History, page, { page = it }, ink, muted)
                    NavItem("settings", "settings", Icons.Filled.Settings, page, { page = it }, ink, muted)
                }
            }) { pad ->
                Box(Modifier.fillMaxSize().padding(pad)) {
                when (page) {
                    "welcome" -> WelcomeScreen(lang, surface, ink, muted, bg) { AlarmPrefs.sp(context).edit().putBoolean("intro_shown", true).apply(); page = "home" }
                    "new" -> NewAppointment(lang, surface, ink, muted, bg, onCancel = { page = "home" }, onSaved = { refresh++; page = "home" })
                    "appointments", "history" -> HistoryScreen(lang, surface, ink, muted, bg, refresh, page == "history") { page = it }
                    "settings" -> SettingsScreen(lang, theme, dark, surface, ink, muted, bg,
                        setLang = { lang = it; AlarmPrefs.sp(context).edit().putString("language", it).apply() },
                        setTheme = { theme = it; AlarmPrefs.sp(context).edit().putString("theme", it).apply() })
                    else -> HomeScreen(lang, surface, ink, muted, bg, refresh, goNew = { page = "new" }, goSettings = { page = "settings" }, onRefresh = { refresh++ })
                }
                }
            }
        }
    }
}

@Composable private fun androidx.compose.foundation.layout.RowScope.NavItem(pageKey: String, labelKey: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: String, onSelect: (String) -> Unit, ink: Color, muted: Color) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .45f
    val color = if (selected == pageKey) (if (dark) Color(0xFF86F2E4) else Teal) else muted
    Column(Modifier.weight(1f).clickable { onSelect(pageKey) }.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
        Text(tr(labelKey), color = color, fontSize = 12.sp, textAlign = TextAlign.Center)
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
        val dark = MaterialTheme.colorScheme.background.luminance() < .45f
        Button(onClick = begin, Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 24.dp).height(54.dp), colors = ButtonDefaults.buttonColors(containerColor = if (dark) Color(0xFF78D9CD) else Navy, contentColor = if (dark) Navy else Color.White), shape = RoundedCornerShape(14.dp)) { Text(if (lang == "ar") "ابدأ الاستخدام الآن" else if (lang == "tr") "Şimdi başlayın" else "Get started", fontWeight = FontWeight.Bold) }
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
                Icon(Icons.Filled.Shield, null, tint = if (MaterialTheme.colorScheme.background.luminance() < .45f) Color(0xFF86F2E4) else Teal, modifier = Modifier.size(36.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(tr("secure_alarm"), color = ink, fontWeight = FontWeight.Bold); Text(tr("secure_sub"), color = muted, fontSize = 12.sp) }
                Icon(Icons.Filled.Check, null, tint = if (MaterialTheme.colorScheme.background.luminance() < .45f) Color(0xFF86F2E4) else Teal, modifier = Modifier.size(28.dp))
            }
        }
        Button(onClick = goNew, Modifier.fillMaxWidth().padding(top = 14.dp).height(58.dp), colors = ButtonDefaults.buttonColors(containerColor = surface, contentColor = ink), shape = RoundedCornerShape(14.dp), elevation = ButtonDefaults.buttonElevation(2.dp)) {
            Icon(Icons.Filled.Add, null, tint = if (MaterialTheme.colorScheme.background.luminance() < .45f) Color(0xFF86F2E4) else Teal, modifier = Modifier.size(30.dp)); Spacer(Modifier.width(8.dp)); Text(tr("new_fingerprint"), fontWeight = FontWeight.Bold)
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
                    (0..2).forEach { d -> val dark = MaterialTheme.colorScheme.background.luminance() < .45f; val c = if (dark) listOf(Color(0xFF075348), Color(0xFF293741), Color(0xFF49252A))[d] else listOf(Teal, Color(0xFFF0F1F2), Color(0xFFFFEDEE))[d]; val fg = if (d == 0) Color.White else if (d == 2) (if (dark) Color(0xFFFFA39B) else Danger) else ink
                        Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = c), shape = RoundedCornerShape(12.dp)) { Column(Modifier.padding(9.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(tr("day_num", d + 1), color = fg, fontSize = 11.sp); Text(next.plusDays(d.toLong()).format(DateTimeFormatter.ofPattern("dd", Locale.US)), color = fg, fontSize = 22.sp, fontWeight = FontWeight.Bold); Text(next.plusDays(d.toLong()).format(DateTimeFormatter.ofPattern("MMM", Locale.US)), color = fg, fontSize = 11.sp); Text(listOf(tr("main_day"), tr("reminder"), tr("last_chance"))[d], color = fg, fontSize = 10.sp, textAlign = TextAlign.Center) } }
                    }
                }
                val dark = MaterialTheme.colorScheme.background.luminance() < .45f
                Button(onClick = { AlarmPrefs.markDone(context, next.toEpochDay()); AlarmScheduler.schedule(context); onRefresh() }, Modifier.fillMaxWidth().padding(top = 18.dp).height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = if (dark) Color(0xFF78D9CD) else Teal, contentColor = if (dark) Navy else Color.White), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Filled.Fingerprint, null); Spacer(Modifier.width(8.dp)); Text(tr("confirm_fingerprint"), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable private fun NewAppointment(lang: String, surface: Color, ink: Color, muted: Color, bg: Color, onCancel: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .45f
    var date by remember { mutableStateOf(if (AlarmPrefs.sp(context).contains("start")) AlarmPrefs.start(context) else LocalDate.now()) }
    var hour by remember { mutableIntStateOf(AlarmPrefs.hour(context)) }
    var minute by remember { mutableIntStateOf(AlarmPrefs.minute(context)) }
    var interval by remember { mutableIntStateOf(AlarmPrefs.interval(context)) }
    var count by remember { mutableIntStateOf(AlarmPrefs.count(context)) }
    var vibrate by remember { mutableStateOf(AlarmPrefs.vibrate(context)) }
    var soundEnabled by remember { mutableStateOf(AlarmPrefs.sound(context)) }
    var toneLabel by remember {
        mutableStateOf(AlarmPrefs.soundUri(context)?.let { saved -> runCatching { RingtoneManager.getRingtone(context, Uri.parse(saved))?.getTitle(context) }.getOrNull() }
            ?: if (lang == "ar") "نغمة التنبيه الافتراضية" else if (lang == "tr") "Varsayılan alarm sesi" else "Default alarm ringtone")
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val ringtoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            @Suppress("DEPRECATION") val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            AlarmPrefs.sp(context).edit().putString("sound_uri", uri?.toString()).putBoolean("sound", uri != null).apply()
            soundEnabled = uri != null
            val fallbackName = if (lang == "ar") "نغمة التنبيه الافتراضية" else if (lang == "tr") "Varsayılan alarm sesi" else "Default alarm ringtone"
            toneLabel = if (uri == null) (if (lang == "ar") "بدون صوت" else if (lang == "tr") "Sessiz" else "Silent") else runCatching { RingtoneManager.getRingtone(context, uri)?.getTitle(context) }.getOrNull() ?: fallbackName
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        Header(tr("new_fingerprint"), Icons.Filled.Fingerprint, ink, onCancel)
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InputSummaryCard(Modifier.weight(1f), surface, ink, muted, tr("set_start"), Icons.Filled.CalendarMonth, date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US))) {
                val pickerTheme = if (dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert
                DatePickerDialog(android.view.ContextThemeWrapper(context, pickerTheme), { _, y, m, d -> date = LocalDate.of(y, m + 1, d) }, date.year, date.monthValue - 1, date.dayOfMonth).show()
            }
            InputSummaryCard(Modifier.weight(1f), surface, ink, muted, tr("alarm_time"), Icons.Filled.Schedule, LocalTime.of(hour, minute).format(DateTimeFormatter.ofPattern("h:mm a", Locale.forLanguageTag(lang)))) {
                val pickerTheme = if (dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert
                TimePickerDialog(android.view.ContextThemeWrapper(context, pickerTheme), { _, h, m -> hour = h; minute = m }, hour, minute, false).show()
            }
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
                Text("$count  ${tr("appointments_unit")}", color = ink, fontWeight = FontWeight.Bold)
                Button(onClick = { count = (count + 1).coerceAtMost(60) }) { Text("＋") }
            }
        }
        Text(tr("sound_settings"), color = ink, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
        val chooseToneLabel = tr("choose_tone")
        Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Alarm, null, tint = Teal, modifier = Modifier.size(28.dp))
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) { Text(tr("alarm_tone"), color = ink, fontWeight = FontWeight.SemiBold); Text(toneLabel, color = muted, fontSize = 12.sp, maxLines = 1) }
                OutlinedButton(onClick = {
                    val pick = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, chooseToneLabel)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, AlarmPrefs.soundUri(context)?.let { Uri.parse(it) } ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                    }
                    ringtoneLauncher.launch(pick)
                }, colors = ButtonDefaults.outlinedButtonColors(contentColor = if (dark) Color(0xFF86F2E4) else Teal)) { Text(tr("choose_tone")) }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(14.dp)) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Vibration, null, tint = Teal); Column(Modifier.weight(1f).padding(horizontal = 10.dp)) { Text(tr("vibration"), color = ink, fontWeight = FontWeight.SemiBold); Text(tr("vibration_sub"), color = muted, fontSize = 12.sp) }; Switch(vibrate, { vibrate = it })
            }
        }
        Button(onClick = {
            AlarmScheduler.cancelAll(context)
            AlarmPrefs.sp(context).edit().putString("start", date.toString()).putInt("hour", hour).putInt("minute", minute).putInt("interval", interval).putInt("count", count).putBoolean("vibrate", vibrate).putBoolean("sound", soundEnabled).apply()
            AlarmScheduler.schedule(context)
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 31 && !context.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()) context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
            onSaved()
        }, Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 28.dp).height(54.dp), colors = ButtonDefaults.buttonColors(containerColor = if (dark) Color(0xFF78D9CD) else Teal, contentColor = if (dark) Navy else Color.White), shape = RoundedCornerShape(14.dp)) { Text(tr("save"), fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        TextButton(onClick = onCancel, Modifier.fillMaxWidth().padding(bottom = 22.dp)) { Text(tr("cancel"), color = muted) }
    }
}

@Composable private fun HistoryScreen(lang: String, surface: Color, ink: Color, muted: Color, bg: Color, refresh: Int, initiallyPast: Boolean, navigate: (String) -> Unit) {
    val context = LocalContext.current
    val start = AlarmPrefs.start(context)
    val interval = AlarmPrefs.interval(context)
    val count = AlarmPrefs.count(context)
    val done = AlarmPrefs.completed(context)
    val today = LocalDate.now()
    val allDates = (0 until count).map { start.plusDays(it.toLong() * interval) }
    val upcomingDates = allDates.filter { it.toEpochDay() !in done && it.plusDays(2) >= today }
    val previousDates = (allDates.filter { it.plusDays(2) < today || it.toEpochDay() in done } + done.map { LocalDate.ofEpochDay(it) }).distinct().sortedDescending()
    var showPast by remember(refresh, initiallyPast) { mutableStateOf(initiallyPast) }
    val visibleDates = if (showPast) previousDates else upcomingDates
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Header(tr("appointments_history_title"), Icons.Filled.CalendarMonth, ink)
        Text(tr("history_title"), color = ink, fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
        Text(tr("history_sub"), color = muted, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).background(if (MaterialTheme.colorScheme.background.luminance() < .45f) Color(0xFF172630) else Color(0xFFEFF1F3), RoundedCornerShape(14.dp)).padding(4.dp)) {
                HistorySegment(tr("upcoming"), upcomingDates.size, !showPast, surface, ink) { showPast = false }
                HistorySegment(tr("previous"), previousDates.size, showPast, surface, ink) { showPast = true }
            }
            val dark = MaterialTheme.colorScheme.background.luminance() < .45f
            Button(onClick = { navigate("new") }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp), colors = ButtonDefaults.buttonColors(containerColor = if (dark) Color(0xFF78D9CD) else Teal, contentColor = if (dark) Navy else Color.White)) { Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(tr("new_short"), fontSize = 12.sp) }
        }
        Text(if (showPast) tr("previous_list") else tr("upcoming_list"), color = muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (visibleDates.isEmpty()) {
                Card(Modifier.fillMaxWidth().padding(top = 6.dp), colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(18.dp)) {
                    Text(tr(if (showPast) "no_previous" else "no_upcoming"), Modifier.fillMaxWidth().padding(24.dp), color = muted, textAlign = TextAlign.Center)
                }
            }
            visibleDates.forEachIndexed { index, date ->
                val isDone = date.toEpochDay() in done
                val isUpcoming = !showPast
                Card(Modifier.fillMaxWidth().padding(bottom = 10.dp), colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(18.dp), elevation = CardDefaults.cardElevation(1.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column { Text(tr("appointment_num", index + 1), color = muted, fontSize = 12.sp); Text(date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US)), color = ink, fontSize = 21.sp, fontWeight = FontWeight.Bold) }
                            BadgeText(if (isUpcoming && index == 0) tr("soon") else if (isUpcoming) tr("scheduled") else if (isDone) tr("completed_short") else tr("elapsed"), if (!showPast && index == 0) Mint else bg, if (!showPast && index == 0) Teal else muted)
                        }
                        HorizontalDivider(Modifier.padding(vertical = 10.dp), color = bg)
                        Text(if (showPast) tr(if (isDone) "completed_label" else "elapsed_label") else "${tr("grace_window")}: ${date.format(DateTimeFormatter.ofPattern("dd/MM", Locale.US))} – ${date.plusDays(2).format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US))}", color = muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable private fun androidx.compose.foundation.layout.RowScope.HistorySegment(title: String, count: Int, selected: Boolean, surface: Color, ink: Color, onClick: () -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .45f
    val selectedBg = if (dark) Color(0xFF123D37) else Color(0xFFFFFFFF)
    val selectedText = if (dark) Color(0xFF86F2E4) else Teal
    Row(Modifier.weight(1f).clickable(onClick = onClick).background(if (selected) selectedBg else Color.Transparent, RoundedCornerShape(11.dp)).padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        BadgeText(count.toString(), if (selected) Mint else bgForSegment(dark), if (selected) Teal else mutedForSegment(dark))
        Text(title, Modifier.padding(start = 6.dp), color = if (selected) selectedText else ink.copy(alpha = .78f), fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, fontSize = 12.sp, maxLines = 1)
    }
}
private fun bgForSegment(dark: Boolean) = if (dark) Color(0xFF2A3741) else Color(0xFFE5E8EA)
private fun mutedForSegment(dark: Boolean) = if (dark) Color(0xFFB7C3CC) else Color(0xFF626B70)

@Composable private fun SettingsScreen(lang: String, theme: String, dark: Boolean, surface: Color, ink: Color, muted: Color, bg: Color, setLang: (String) -> Unit, setTheme: (String) -> Unit) {
    val context = LocalContext.current
    var panel by remember { mutableStateOf("") }
    var sound by remember { mutableStateOf(AlarmPrefs.sound(context)) }
    var vibration by remember { mutableStateOf(AlarmPrefs.vibrate(context)) }
    fun openSystem(intent: Intent) { panel = ""; runCatching { context.startActivity(intent) } }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        Header(tr("settings"), Icons.Filled.Settings, ink)
        Spacer(Modifier.height(12.dp))
        SettingsOption(surface, ink, muted, Icons.Filled.Language, tr("language"), tr("language_sub"), when (lang) { "tr" -> "Türkçe"; "en" -> "English"; else -> "العربية" }) { panel = "language" }
        SettingsOption(surface, ink, muted, Icons.Filled.Settings, tr("appearance"), tr("appearance_sub"), when (theme) { "dark" -> tr("dark"); "light" -> tr("light"); else -> tr("system") }) { panel = "theme" }
        SettingsOption(surface, ink, muted, Icons.Filled.Alarm, tr("alarm_options"), tr("alarm_settings_sub"), tr("customized")) { panel = "alarm" }
        SettingsOption(surface, ink, muted, Icons.Filled.Shield, tr("permissions"), tr("permissions_sub"), tr("manage")) { panel = "permissions" }
        SettingsOption(surface, ink, muted, Icons.Filled.Info, tr("about_app"), tr("about_app_sub"), "v1.2.0") { panel = "about" }
        SettingsOption(surface, ink, muted, Icons.Filled.Fingerprint, tr("about_developer"), tr("developer_sub"), "") { panel = "developer" }
        SettingsOption(surface, ink, muted, Icons.Filled.Info, tr("disclaimer"), tr("disclaimer_sub"), "") { panel = "disclaimer" }
        Text("منبه بصمة · Android Kotlin", color = muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 18.dp).align(Alignment.CenterHorizontally))
    }
    if (panel.isNotEmpty()) AlertDialog(
        onDismissRequest = { panel = "" },
        title = { Text(when (panel) { "language" -> tr("language"); "theme" -> tr("appearance"); "alarm" -> tr("sound_settings"); "permissions" -> tr("permissions"); "about" -> tr("about_app"); "developer" -> tr("about_developer"); else -> tr("disclaimer") }, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when (panel) {
                    "language" -> listOf("ar" to "العربية", "tr" to "Türkçe", "en" to "English").forEach { (code, name) -> TextButton(onClick = { setLang(code); panel = "" }, modifier = Modifier.fillMaxWidth()) { Text(if (lang == code) "✓  $name" else name, color = MaterialTheme.colorScheme.onSurface) } }
                    "theme" -> listOf("system" to tr("system"), "light" to tr("light"), "dark" to tr("dark")).forEach { (code, name) -> TextButton(onClick = { setTheme(code); panel = "" }, modifier = Modifier.fillMaxWidth()) { Text(if (theme == code) "✓  $name" else name, color = MaterialTheme.colorScheme.onSurface) } }
                    "alarm" -> {
                        ToggleRow(surface, ink, Icons.Filled.Alarm, tr("alarm_sound"), sound) { sound = it; AlarmPrefs.sp(context).edit().putBoolean("sound", it).apply() }
                        ToggleRow(surface, ink, Icons.Filled.Vibration, tr("vibration"), vibration) { vibration = it; AlarmPrefs.sp(context).edit().putBoolean("vibrate", it).apply() }
                        Text("${tr("alarm_tone")}: ${AlarmPrefs.soundUri(context)?.let { runCatching { RingtoneManager.getRingtone(context, Uri.parse(it))?.getTitle(context) }.getOrNull() } ?: tr("default_sound")}", color = muted, modifier = Modifier.padding(8.dp))
                    }
                    "permissions" -> {
                        val exactOk = Build.VERSION.SDK_INT < 31 || context.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()
                        val overlayOk = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)
                        val notificationsOk = context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()
                        val fullScreenOk = Build.VERSION.SDK_INT < 34 || context.getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent()
                        PermissionEntry(tr("exact_alarm"), exactOk, { openSystem(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) })
                        PermissionEntry(tr("overlay"), overlayOk, { openSystem(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) })
                        PermissionEntry(tr("full_screen_permission"), fullScreenOk, { if (Build.VERSION.SDK_INT >= 34) openSystem(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))) else panel = "" })
                        PermissionEntry(tr("notifications"), notificationsOk, { openSystem(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) })
                        PermissionEntry(tr("battery"), true, { openSystem(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))) })
                    }
                    "about" -> Text("منبه بصمة\n${tr("version")}: 1.2.0\n${tr("package")}: com.abw3laa.basmaalarm", color = MaterialTheme.colorScheme.onSurface)
                    "developer" -> {
                        DeveloperLink(surface, ink, "WhatsApp · +905354883886", "https://wa.me/905354883886")
                        DeveloperLink(surface, ink, "Telegram · @abw3laa", "https://t.me/abw3laa")
                        DeveloperLink(surface, ink, "Facebook", "https://www.facebook.com/share/1EykCQDkjB/")
                    }
                    else -> Text(tr("disclaimer_body"), color = MaterialTheme.colorScheme.onSurface)
                }
            }
        },
        confirmButton = { TextButton(onClick = { panel = "" }) { Text(tr("close")) } },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable private fun SettingsOption(surface: Color, ink: Color, muted: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, value: String, click: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(bottom = 10.dp).clickable(onClick = click), colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(17.dp), elevation = CardDefaults.cardElevation(1.dp), border = androidx.compose.foundation.BorderStroke(1.dp, if (MaterialTheme.colorScheme.background.luminance() < .45f) Color(0xFF263845) else Color(0xFFE9ECEF))) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = Teal.copy(alpha = if (MaterialTheme.colorScheme.background.luminance() < .45f) .22f else .12f), shape = RoundedCornerShape(14.dp), modifier = Modifier.size(44.dp)) { Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = if (MaterialTheme.colorScheme.background.luminance() < .45f) Color(0xFF86F2E4) else Teal, modifier = Modifier.size(24.dp)) } }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(title, color = ink, fontWeight = FontWeight.Bold, fontSize = 15.sp); Text(subtitle, color = muted, fontSize = 12.sp, maxLines = 1) }
            if (value.isNotEmpty()) Text(value, color = if (MaterialTheme.colorScheme.background.luminance() < .45f) Color(0xFF86F2E4) else Teal, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 6.dp))
            Text(if (LocalLayoutDirection.current == LayoutDirection.Rtl) "‹" else "›", color = muted, fontSize = 22.sp)
        }
    }
}
@Composable private fun PermissionEntry(title: String, enabled: Boolean, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = click).padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (enabled) Icons.Filled.Check else Icons.Filled.Shield, null, tint = if (enabled) Teal else MaterialTheme.colorScheme.error)
        Text(title, Modifier.weight(1f).padding(horizontal = 10.dp), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
        Text(if (enabled) tr("enabled") else tr("enable"), color = if (enabled && MaterialTheme.colorScheme.background.luminance() < .45f) Color(0xFF86F2E4) else if (enabled) Teal else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
}

@Composable private fun Header(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, ink: Color, back: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        if (back != null) TextButton(onClick = back) { Text(if (LocalLayoutDirection.current == LayoutDirection.Rtl) "‹" else "›", fontSize = 24.sp, color = ink) } else Icon(Icons.Filled.Fingerprint, null, tint = if (MaterialTheme.colorScheme.background.luminance() < .45f) Color(0xFF86F2E4) else Teal, modifier = Modifier.size(30.dp))
        Text(title, color = ink, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Icon(icon, null, tint = if (MaterialTheme.colorScheme.background.luminance() < .45f) Color(0xFF86F2E4) else Teal, modifier = Modifier.size(28.dp))
    }
}
@Composable private fun BadgeText(text: String, color: Color, fg: Color) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .45f
    val bg = if (!dark) color else when { fg == Danger -> Color(0xFF49252A); color == Teal -> Color(0xFF06483F); else -> Color(0xFF2A3741) }
    val textColor = if (!dark) fg else when (fg) { Teal -> Color(0xFF86F2E4); Danger -> Color(0xFFFFA39B); Color.White -> Color.White; else -> MaterialTheme.colorScheme.onSurface }
    Surface(color = bg, shape = CircleShape) { Text(text, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = textColor, fontSize = 11.sp, fontWeight = FontWeight.Medium) }
}
@Composable private fun SettingCard(surface: Color, ink: Color, muted: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, click: () -> Unit) { Card(Modifier.fillMaxWidth().clickable(onClick = click), colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(14.dp)) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = Teal); Text(value, Modifier.weight(1f).padding(horizontal = 12.dp), color = ink); Text("›", color = muted, fontSize = 22.sp) } } }
@Composable private fun InputSummaryCard(modifier: Modifier, surface: Color, ink: Color, muted: Color, title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, click: () -> Unit) {
    Card(modifier.clickable(onClick = click), colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(1.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = muted, fontSize = 12.sp, maxLines = 1)
                Surface(color = if (MaterialTheme.colorScheme.background.luminance() < .45f) Color(0xFF20343D) else Color(0xFFF0F3F5), shape = RoundedCornerShape(9.dp), modifier = Modifier.size(30.dp)) { Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = if (MaterialTheme.colorScheme.background.luminance() < .45f) Color(0xFF86F2E4) else Teal, modifier = Modifier.size(18.dp)) } }
            }
            Text(value, color = ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 9.dp), maxLines = 1)
        }
    }
}
@Composable private fun CycleCard(title: String, sub: String, selected: Boolean, surface: Color, ink: Color, modifier: Modifier, click: () -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .45f
    val selectedSurface = if (dark) Color(0xFF163F3A) else Mint
    val accent = if (dark) Color(0xFF86F2E4) else Teal
    Card(modifier.clickable(onClick = click), colors = CardDefaults.cardColors(containerColor = if (selected) selectedSurface else surface), shape = RoundedCornerShape(14.dp), border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, accent) else null) {
        Column(Modifier.padding(14.dp)) { Text(title, color = ink, fontWeight = FontWeight.Bold); Text(sub, color = ink.copy(alpha = .78f), fontSize = 12.sp); if (selected) Text("✓", color = accent) }
    }
}
@Composable private fun PermissionCard(surface: Color, ink: Color, muted: Color, title: String, sub: String, click: () -> Unit) { Card(Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable(onClick = click), colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(14.dp)) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Shield, null, tint = Teal); Column(Modifier.weight(1f).padding(horizontal = 10.dp)) { Text(title, color = ink, fontWeight = FontWeight.SemiBold); Text(sub, color = muted, fontSize = 12.sp) }; Text(tr("enable"), color = Teal, fontWeight = FontWeight.Bold) } } }
@Composable private fun ToggleRow(surface: Color, ink: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, checked: Boolean, change: (Boolean) -> Unit) { val accent = if (MaterialTheme.colorScheme.background.luminance() < .45f) Color(0xFF86F2E4) else Teal; Card(Modifier.fillMaxWidth().padding(bottom = 8.dp), colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(14.dp)) { Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = accent, modifier = Modifier.size(24.dp)); Text(title, Modifier.weight(1f).padding(horizontal = 10.dp), color = ink); Switch(checked, change) } } }
@Composable private fun DeveloperLink(surface: Color, ink: Color, label: String, url: String) { val c = LocalContext.current; Card(Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable { runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }, colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(12.dp)) { Text(label, Modifier.fillMaxWidth().padding(14.dp), color = ink) } }

@Composable private fun tr(k: String, n: Int? = null): String {
    val l = currentLanguage()
    val values = when (l) {
        "en" -> mapOf("home" to "Home", "appointments" to "Appointments", "history" to "History", "settings" to "Settings", "secure_alarm" to "Smart alarm active and protected", "secure_sub" to "Three-day reminders are scheduled", "new_fingerprint" to "Add fingerprint appointment", "soon" to "Upcoming", "next_appointment" to "Next appointment date", "days_left" to "$n days left", "allowed_days" to "Allowed days", "main_day" to "Main date", "reminder" to "Reminder", "last_chance" to "Last chance", "confirm_fingerprint" to "Confirm fingerprint", "set_start" to "First appointment date", "alarm_time" to "Alarm time", "repeat_cycle" to "Repeat cycle", "every_28" to "Every 28 days", "official_cycle" to "Regular cycle", "weekly" to "Weekly", "every_week" to "Repeats every 7 days", "cycles_count" to "Number of appointments", "appointments_unit" to "appointments", "appointments_history_title" to "Appointments history", "previous_list" to "Past appointments", "no_previous" to "No past appointments yet", "no_upcoming" to "No upcoming appointments. Add a schedule.", "completed_short" to "Done", "elapsed" to "Elapsed", "elapsed_label" to "Appointment window passed", "choose_tone" to "Choose ringtone", "default_sound" to "Default alarm ringtone", "silent" to "Silent", "vibration" to "Vibration", "vibration_sub" to "Vibrate during the alarm", "sound_settings" to "Sound and vibration", "save" to "Save schedule", "cancel" to "Cancel", "history_title" to "Fingerprint appointment history", "history_sub" to "Review upcoming and completed dates", "upcoming" to "Upcoming", "previous" to "Completed", "new_short" to "New", "upcoming_list" to "Scheduled appointments", "grace_window" to "3-day window", "scheduled" to "Scheduled", "appointment_num" to "Appointment #$n", "completed_label" to "Completed", "language" to "Language", "appearance" to "Appearance", "system" to "System", "light" to "Light", "dark" to "Dark", "alarm_options" to "Permissions and reliability", "exact_alarm" to "Exact alarm access", "exact_alarm_sub" to "Allow precise scheduled alarms", "overlay" to "Display over other apps", "overlay_sub" to "Optional full-screen alarm display", "battery" to "Battery optimization", "battery_sub" to "Allow background alarm delivery", "notifications" to "Notifications", "notifications_sub" to "Manage notification permission", "alarm_sound" to "Alarm sound", "about_developer" to "About developer", "enable" to "Enable", "day_num" to "Day $n")
        "tr" -> mapOf("home" to "Ana Sayfa", "appointments" to "Randevular", "history" to "Geçmiş", "settings" to "Ayarlar", "secure_alarm" to "Akıllı alarm etkin ve güvenli", "secure_sub" to "Üç günlük hatırlatıcılar ayarlandı", "new_fingerprint" to "Yeni randevu ekle", "soon" to "Yakında", "next_appointment" to "Sonraki randevu tarihi", "days_left" to "$n gün kaldı", "allowed_days" to "Uygun günler", "main_day" to "Ana tarih", "reminder" to "Hatırlatma", "last_chance" to "Son fırsat", "confirm_fingerprint" to "Parmak izini onayla", "set_start" to "İlk randevu tarihi", "alarm_time" to "Alarm saati", "repeat_cycle" to "Tekrarlama", "every_28" to "28 günde bir", "official_cycle" to "Düzenli döngü", "weekly" to "Haftalık", "every_week" to "7 günde bir", "cycles_count" to "Randevu sayısı", "appointments_unit" to "randevu", "appointments_history_title" to "Randevu geçmişi", "previous_list" to "Geçmiş randevular", "no_previous" to "Henüz geçmiş randevu yok", "no_upcoming" to "Yaklaşan randevu yok. Bir program ekleyin.", "completed_short" to "Tamamlandı", "elapsed" to "Geçti", "elapsed_label" to "Randevu süresi geçti", "choose_tone" to "Alarm sesini seç", "default_sound" to "Varsayılan alarm sesi", "silent" to "Sessiz", "vibration" to "Titreşim", "vibration_sub" to "Alarm sırasında titreşim", "sound_settings" to "Ses ve titreşim", "save" to "Programı kaydet", "cancel" to "İptal", "history_title" to "Parmak izi randevu geçmişi", "history_sub" to "Yaklaşan ve tamamlanan tarihleri görüntüle", "upcoming" to "Yaklaşan", "previous" to "Tamamlandı", "new_short" to "Yeni", "upcoming_list" to "Planlanan randevular", "grace_window" to "3 günlük süre", "scheduled" to "Planlandı", "appointment_num" to "Randevu #$n", "completed_label" to "Tamamlandı", "language" to "Dil", "appearance" to "Görünüm", "system" to "Sistem", "light" to "Açık", "dark" to "Koyu", "alarm_options" to "İzinler ve güvenilirlik", "exact_alarm" to "Kesin alarm izni", "exact_alarm_sub" to "Zamanında alarm kurulumu", "overlay" to "Diğer uygulamaların üzerinde göster", "overlay_sub" to "İsteğe bağlı tam ekran alarm", "battery" to "Pil optimizasyonu", "battery_sub" to "Arka plan alarmlarına izin ver", "notifications" to "Bildirimler", "notifications_sub" to "Bildirim izinlerini yönet", "alarm_sound" to "Alarm sesi", "about_developer" to "Geliştirici hakkında", "enable" to "Aç", "day_num" to "$n. gün")
        else -> mapOf("home" to "الرئيسية", "appointments" to "المواعيد", "history" to "السجل", "settings" to "الإعدادات", "secure_alarm" to "المنبه الذكي نشط ومؤمّن", "secure_sub" to "تم ضبط تنبيهات مهلة الأيام الثلاثة بدقة", "new_fingerprint" to "تسجيل بصمة جديدة", "soon" to "قريباً", "next_appointment" to "موعد البصمة التالي المقرّر", "days_left" to "متبقي $n يوم", "allowed_days" to "الأيام المسموحة", "main_day" to "الأساسي", "reminder" to "تذكير", "last_chance" to "الفرصة الأخيرة", "confirm_fingerprint" to "تأكيد البصمة", "set_start" to "تاريخ أول بصمة", "alarm_time" to "وقت التنبيه", "repeat_cycle" to "دورة التكرار", "every_28" to "كل 28 يوماً", "official_cycle" to "الدورة المنتظمة", "weekly" to "أسبوعي", "every_week" to "يتكرر كل 7 أيام", "cycles_count" to "عدد المواعيد المجدولة", "appointments_unit" to "موعداً", "appointments_history_title" to "سجل المواعيد", "previous_list" to "المواعيد السابقة", "no_previous" to "لا توجد مواعيد سابقة بعد", "no_upcoming" to "لا توجد مواعيد قادمة. أضف جدول بصمات.", "completed_short" to "مكتمل", "elapsed" to "انتهى موعده", "elapsed_label" to "انتهت مهلة الموعد دون تأكيد", "choose_tone" to "اختر نغمة", "default_sound" to "نغمة التنبيه الافتراضية", "silent" to "بدون صوت", "vibration" to "الاهتزاز", "vibration_sub" to "اهتزاز أثناء التنبيه", "sound_settings" to "صوت التنبيه", "save" to "حفظ المواعيد", "cancel" to "إلغاء", "history_title" to "سجل مواعيد البصمة", "history_sub" to "متابعة تواريخ البصمات السابقة والمقبلة", "upcoming" to "البصمات القادمة", "previous" to "البصمات السابقة", "new_short" to "بصمة جديدة", "upcoming_list" to "قائمة البصمات القادمة", "grace_window" to "مهلة السماح (3 أيام)", "scheduled" to "مجدول", "appointment_num" to "الموعد رقم $n", "completed_label" to "بصمة مكتملة بتاريخ", "language" to "اختر اللغة", "appearance" to "الثيم والمظهر", "system" to "النظام", "light" to "فاتح", "dark" to "داكن", "alarm_options" to "الأذونات وتفادي الإغلاق", "exact_alarm" to "السماح بالمنبهات الدقيقة", "exact_alarm_sub" to "تفعيل جدولة التنبيهات في وقتها", "overlay" to "الظهور فوق التطبيقات", "overlay_sub" to "إظهار واجهة المنبه فوق الشاشة", "battery" to "استثناء توفير البطارية", "battery_sub" to "منع نظام الهاتف من إيقاف المنبه", "notifications" to "إعدادات الإشعارات", "notifications_sub" to "السماح بعرض إشعارات المنبه", "alarm_sound" to "صوت التنبيه", "about_developer" to "حول المطور", "enable" to "تفعيل", "day_num" to "اليوم $n")
    }
    val extra = when (l) {
        "en" -> mapOf("language_sub" to "Choose the app language", "appearance_sub" to "Light, dark, or system appearance", "alarm_settings_sub" to "Sound, ringtone, vibration and reminders", "customized" to "Custom", "permissions" to "Permissions and notifications", "permissions_sub" to "Overlay, full screen, notifications, battery", "manage" to "Manage", "about_app" to "About app", "about_app_sub" to "Version and app details", "developer_sub" to "Contact and social accounts", "disclaimer" to "Disclaimer", "disclaimer_sub" to "Terms and usage notes", "full_screen_permission" to "Full-screen alarm permission", "version" to "Version", "package" to "Package", "disclaimer_body" to "Alarms depend on the permissions and background-execution rules of the device. Verify alarm operation on your phone.", "close" to "Close", "enabled" to "Enabled", "alarm_tone" to "Alarm ringtone", "choose_tone" to "Choose ringtone", "default_sound" to "Default alarm ringtone", "silent" to "Silent")
        "tr" -> mapOf("language_sub" to "Uygulama dilini seçin", "appearance_sub" to "Açık, koyu veya sistem görünümü", "alarm_settings_sub" to "Ses, zil sesi, titreşim ve hatırlatmalar", "customized" to "Özel", "permissions" to "İzinler ve bildirimler", "permissions_sub" to "Kaplama, tam ekran, bildirimler, pil", "manage" to "Yönet", "about_app" to "Uygulama hakkında", "about_app_sub" to "Sürüm ve uygulama bilgileri", "developer_sub" to "İletişim ve sosyal hesaplar", "disclaimer" to "Sorumluluk reddi", "disclaimer_sub" to "Koşullar ve kullanım notları", "full_screen_permission" to "Tam ekran alarm izni", "version" to "Sürüm", "package" to "Paket", "disclaimer_body" to "Alarmlar cihaz izinlerine ve arka plan çalıştırma kurallarına bağlıdır. Alarmı kendi cihazınızda test edin.", "close" to "Kapat", "enabled" to "Etkin", "alarm_tone" to "Alarm zil sesi", "choose_tone" to "Zil sesi seç", "default_sound" to "Varsayılan alarm sesi", "silent" to "Sessiz")
        else -> mapOf("language_sub" to "تحديد لغة واجهة التطبيق", "appearance_sub" to "التبديل بين الوضع الفاتح والداكن", "alarm_settings_sub" to "الصوت، نغمة الرنين، الاهتزاز، والتذكير", "customized" to "مخصّص", "permissions" to "الأذونات والإشعارات", "permissions_sub" to "الظهور فوق الشاشة، الإشعارات والبطارية", "manage" to "إدارة", "about_app" to "حول التطبيق", "about_app_sub" to "الإصدار وتفاصيل التطبيق", "developer_sub" to "التواصل وحسابات التواصل الاجتماعي", "disclaimer" to "إخلاء المسؤولية", "disclaimer_sub" to "الشروط وملاحظات الاستخدام", "full_screen_permission" to "السماح بواجهة المنبه بملء الشاشة", "version" to "الإصدار", "package" to "معرّف الحزمة", "disclaimer_body" to "تعتمد التنبيهات على أذونات الجهاز وقواعد تشغيل الخلفية. اختبر عمل المنبه على هاتفك قبل الاعتماد عليه.", "close" to "إغلاق", "enabled" to "مفعّل", "alarm_tone" to "نغمة التنبيه", "choose_tone" to "اختر نغمة", "default_sound" to "نغمة التنبيه الافتراضية", "silent" to "بدون صوت")
    }
    return extra[k] ?: values[k] ?: k
}

@Composable internal fun currentLanguage(): String = AlarmPrefs.language(LocalContext.current)
