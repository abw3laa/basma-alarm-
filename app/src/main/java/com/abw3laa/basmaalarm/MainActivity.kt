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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Timer
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
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
    private var pendingUpdate: UpdateInfo? = null
    private var installAfterDownload = false
    private val updateHandler = Handler(Looper.getMainLooper())
    private var updatePollAttempts = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startupDark = when (AlarmPrefs.theme(this)) {
            "dark" -> true
            "light" -> false
            else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        val startupBarColor = if (startupDark) android.graphics.Color.rgb(17, 27, 36) else android.graphics.Color.rgb(247, 249, 251)
        window.statusBarColor = startupBarColor
        window.navigationBarColor = startupBarColor
        if (Build.VERSION.SDK_INT >= 23) window.decorView.systemUiVisibility = if (startupDark) 0 else android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        ensureAlarmChannel(this)
        val draftId = AlarmPrefs.sp(this).getString("draft_schedule_id", null)
        if (draftId != null) { ScheduleRepository.delete(this, draftId); AlarmPrefs.sp(this).edit().remove("draft_schedule_id").apply() }
        if (AlarmPrefs.hasSchedule(this)) AlarmScheduler.schedule(this)
        setContent { BasmaApp(intent.getStringExtra("initial_page") ?: "home") }
        checkForUpdate()
    }

    private fun checkForUpdate() {
        UpdateManager.check(this) { info ->
            if (info == null || isFinishing) return@check
            pendingUpdate = info
            UpdateManager.ensureDownloaded(this, info) { file, state ->
                if (file != null) pendingApk = file
                if (state == "download_start_failed" || state == "mediafire_link_not_found" || state == "mediafire_timeout" || state == "invalid_package") {
                    if (state == "invalid_package") { installAfterDownload = false; updateHandler.removeCallbacksAndMessages(null) }
                    val text = when (state) {
                        "invalid_package" -> updateText("ملف التحديث لا يطابق التطبيق أو مفتاح توقيعه.", "Güncelleme dosyasının uygulama veya imza anahtarı eşleşmiyor.", "The update package or signing key does not match this app.")
                        else -> updateText("تعذر بدء تنزيل التحديث. تحقق من رابط MediaFire واتصل بالإنترنت.", "Güncelleme indirilemedi. MediaFire bağlantısını ve interneti kontrol edin.", "The update could not be downloaded. Check the MediaFire link and your connection.")
                    }
                    Toast.makeText(this, text, Toast.LENGTH_LONG).show()
                }
                if (installAfterDownload && file != null) { installAfterDownload = false; installOrRequestPermission(file) }
            }
            showUpdatePrompt(info)
        }
    }

    private fun updateText(ar: String, tr: String, en: String) = when (AlarmPrefs.language(this)) { "tr" -> tr; "en" -> en; else -> ar }

    private fun showUpdatePrompt(info: UpdateInfo) {
        val title = updateText("يتوفر تحديث جديد", "Yeni güncelleme mevcut", "Update available")
        val message = updateText(
            "يتوفر الإصدار ${info.versionName}. يجري تنزيله تلقائياً في الخلفية. هل تريد تثبيته الآن؟",
            "${info.versionName} sürümü mevcut. Arka planda indiriliyor. Şimdi yüklemek ister misiniz?",
            "Version ${info.versionName} is available and is downloading in the background. Install it now?"
        )
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setNegativeButton(updateText("ذكرني لاحقاً", "Daha sonra hatırlat", "Remind me later"), null)
            .setPositiveButton(updateText("تثبيت الآن", "Şimdi yükle", "Install now")) { _, _ -> installOrWait(info) }
            .setCancelable(true).show()
    }

    private fun installOrWait(info: UpdateInfo) {
        pendingApk?.let { installOrRequestPermission(it); return }
        installAfterDownload = true
        updatePollAttempts = 0
        Toast.makeText(this, updateText("التحديث قيد التنزيل. سيبدأ التثبيت عند اكتماله.", "Güncelleme indiriliyor. Tamamlanınca kurulum başlayacak.", "The update is downloading. Installation will start when it finishes."), Toast.LENGTH_LONG).show()
        UpdateManager.ensureDownloaded(this, info) { file, state ->
            if (file != null) {
                installAfterDownload = false
                pendingApk = file
                installOrRequestPermission(file)
            } else if (state == "downloading") pollUpdate(info)
            else {
                installAfterDownload = false
                Toast.makeText(this, updateText("تعذر بدء تنزيل التحديث. تحقق من رابط MediaFire واتصل بالإنترنت.", "Güncelleme indirilemedi. MediaFire bağlantısını ve interneti kontrol edin.", "The update could not be downloaded. Check the MediaFire link and your connection."), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun pollUpdate(info: UpdateInfo) {
        if (!installAfterDownload || isFinishing) return
        updatePollAttempts++
        thread {
            val file = UpdateManager.downloadedFileIfReady(this, info)
            Handler(Looper.getMainLooper()).post {
                if (!installAfterDownload) return@post
                if (file != null) {
                    installAfterDownload = false
                    pendingApk = file
                    installOrRequestPermission(file)
                } else if (UpdateManager.hasTerminalFailure(this, info)) {
                    installAfterDownload = false
                    Toast.makeText(this, updateText("تعذر بدء تنزيل التحديث. تحقق من رابط MediaFire واتصل بالإنترنت.", "Güncelleme indirilemedi. MediaFire bağlantısını ve interneti kontrol edin.", "The update could not be downloaded. Check the MediaFire link and your connection."), Toast.LENGTH_LONG).show()
                } else if (updatePollAttempts < 240) updateHandler.postDelayed({ pollUpdate(info) }, 2500)
                else {
                    installAfterDownload = false
                    Toast.makeText(this, updateText("ما زال التنزيل غير مكتمل. افتح التطبيق لاحقاً للمتابعة.", "İndirme henüz tamamlanmadı. Devam etmek için uygulamayı daha sonra açın.", "The download is not complete yet. Reopen the app later to continue."), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun installOrRequestPermission(file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            pendingApk = file
            startActivityForResult(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")), 731)
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

    override fun onDestroy() {
        updateHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
@Composable private fun BasmaApp(initialPage: String = "home") {
    val context = LocalContext.current
    var lang by remember { mutableStateOf(AlarmPrefs.language(context)) }
    var theme by remember { mutableStateOf(AlarmPrefs.theme(context)) }
    var page by remember { mutableStateOf(if (AlarmPrefs.sp(context).getBoolean("intro_shown", false)) initialPage else "welcome") }
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
                if (page != "new" && page != "welcome" && page != "setup") Row(Modifier.fillMaxWidth().background(surface).navigationBarsPadding().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
                    NavItem("home", "home", Icons.Filled.Home, page, { page = it }, ink, muted)
                    NavItem("appointments", "appointments", Icons.Filled.CalendarMonth, page, { page = it }, ink, muted)
                    NavItem("history", "history", Icons.Filled.History, page, { page = it }, ink, muted)
                    NavItem("settings", "settings", Icons.Filled.Settings, page, { page = it }, ink, muted)
                }
            }) { pad ->
                Box(Modifier.fillMaxSize().padding(pad)) {
                    when (page) {
                        "welcome" -> WelcomeScreen(lang, surface, ink, muted, bg, beginSetup = { page = "setup" }, skip = { AlarmPrefs.sp(context).edit().putBoolean("intro_shown", true).apply(); page = "home" })
                        "setup" -> SetupAssistantScreen(lang, surface, ink, muted, done = { AlarmPrefs.sp(context).edit().putBoolean("intro_shown", true).apply(); page = "home" })
                        "new" -> NewAppointment(lang, surface, ink, muted, bg, onCancel = {
                            val draft = AlarmPrefs.sp(context).getString("draft_schedule_id", null)
                            if (draft != null) { ScheduleRepository.delete(context, draft); AlarmPrefs.sp(context).edit().remove("draft_schedule_id").apply(); AlarmScheduler.schedule(context) }
                            page = if (AlarmPrefs.hasSchedule(context)) "appointments" else "home"
                        }, onSaved = { AlarmPrefs.sp(context).edit().remove("draft_schedule_id").apply(); refresh++; page = "home" })
                        "appointments" -> AppointmentsScreen(lang, surface, ink, muted, bg, refresh, navigate = { page = it }, onRefresh = { refresh++ })
                        "history" -> HistoryScreen(lang, surface, ink, muted, bg, refresh)
                        "settings" -> SettingsScreen(lang, theme, dark, surface, ink, muted, bg,
                            setLang = { lang = it; AlarmPrefs.sp(context).edit().putString("language", it).apply() },
                            setTheme = { theme = it; AlarmPrefs.sp(context).edit().putString("theme", it).apply() },
                            openSetup = { page = "setup" })
                        else -> HomeScreen(lang, surface, ink, muted, bg, refresh, goNew = { page = "new" }, onRefresh = { refresh++ })
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

@Composable private fun WelcomeScreen(lang: String, surface: Color, ink: Color, muted: Color, bg: Color, beginSetup: () -> Unit, skip: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(18.dp))
        Surface(color = Color.White, shape = CircleShape, shadowElevation = 8.dp, modifier = Modifier.size(86.dp)) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Fingerprint, null, tint = Color(0xFF9B222C), modifier = Modifier.size(54.dp)) } }
        Text(if (lang == "ar") "مرحباً بك في منبّه بصمة" else if (lang == "tr") "Parmak İzi Hatırlatıcısına Hoş Geldiniz" else "Welcome to Basma Alarm", color = ink, fontSize = 25.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 18.dp))
        Text(if (lang == "ar") "كل مواعيدك وتنبيهاتك في مكان واحد وبدقة متناهية لا تقبل الخطأ." else if (lang == "tr") "Randevularınız ve hatırlatmalarınız tek yerde, hassas zamanlamayla." else "Your appointments and reminders in one place, scheduled with precision.", color = muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 18.dp))
        Text(if (lang == "ar") "أبرز الميزات والتحديثات" else if (lang == "tr") "Özellikler ve güncellemeler" else "Features and updates", Modifier.fillMaxWidth().padding(bottom = 8.dp), color = ink, fontWeight = FontWeight.Bold, fontSize = 19.sp)
        val features = if (lang == "ar") listOf("تنبيهات فعلية لمواعيد البصمة" to "صوت واهتزاز مع تذكيرات مستمرة", "تكرار ذكي كل 28 يوماً أو أسبوعياً" to "اختر دورة المواعيد المناسبة", "نافذة متابعة لثلاثة أيام" to "تذكير بالموعد الأساسي والمهلة", "تذكير تلقائي بعد ساعة" to "أجّل التنبيه دون نسيان الموعد", "استعادة التنبيهات بعد إعادة التشغيل" to "إعادة جدولة المواعيد المحفوظة", "يعمل دون اتصال" to "مواعيدك محفوظة على الجهاز") else if (lang == "tr") listOf("Gerçek randevu alarmları" to "Sesli ve titreşimli hatırlatma", "28 günlük veya haftalık tekrar" to "Uygun randevu döngüsünü seçin", "Üç günlük takip aralığı" to "Ana tarih ve ek hatırlatmalar", "Bir saat sonra tekrar hatırlat" to "Alarmı ertele", "Yeniden başlatmada alarmları geri yükle" to "Kayıtlı randevular yeniden kurulur", "Çevrimdışı çalışır" to "Randevularınız cihazda saklanır") else listOf("Real appointment alarms" to "Sound and vibration reminders", "Repeat every 28 days or weekly" to "Choose the schedule that suits you", "Three-day follow-up window" to "Reminders for the appointment period", "Snooze for one hour" to "Delay without forgetting the date", "Restore alarms after reboot" to "Saved reminders are rescheduled", "Works offline" to "Your schedule stays on your device")
        features.forEachIndexed { index, pair -> Card(Modifier.fillMaxWidth().padding(bottom = 8.dp), colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(1.dp)) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { BadgeText("${index + 1}", if (index == 0) Color(0xFFFFE8E7) else Mint, if (index == 0) Danger else Teal); Column(Modifier.padding(horizontal = 12.dp)) { Text(pair.first, color = ink, fontWeight = FontWeight.SemiBold); Text(pair.second, color = muted, fontSize = 12.sp) } } } }
        val dark = MaterialTheme.colorScheme.background.luminance() < .45f
        Button(onClick = beginSetup, Modifier.fillMaxWidth().padding(top = 14.dp).height(54.dp), colors = ButtonDefaults.buttonColors(containerColor = if (dark) Color(0xFF78D9CD) else Navy, contentColor = if (dark) Navy else Color.White), shape = RoundedCornerShape(14.dp)) { Text(if (lang == "ar") "إعداد التنبيهات" else if (lang == "tr") "Alarm kurulumunu yap" else "Set up reminders", fontWeight = FontWeight.Bold) }
        TextButton(onClick = skip, Modifier.fillMaxWidth().padding(bottom = 18.dp)) { Text(if (lang == "ar") "المتابعة دون إعداد الآن" else if (lang == "tr") "Şimdilik geç" else "Continue without setup", color = muted) }
    }
}

@Composable private fun SetupAssistantScreen(lang: String, surface: Color, ink: Color, muted: Color, done: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val exactOk = remember(refresh) { Build.VERSION.SDK_INT < 31 || context.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms() }
    val notificationOk = remember(refresh) { Build.VERSION.SDK_INT < 24 || context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled() }
    val notificationPermissionGranted = remember(refresh) { Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val overlayOk = remember(refresh) { Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context) }
    val fullOk = remember(refresh) { Build.VERSION.SDK_INT < 34 || context.getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent() }
    val batteryOk = remember(refresh) { Build.VERSION.SDK_INT < 23 || context.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }
    fun open(intent: Intent) { runCatching { context.startActivity(intent) } }
    fun label(ar: String, tr: String, en: String) = when (lang) { "tr" -> tr; "en" -> en; else -> ar }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Header(label("إعداد التنبيهات", "Alarm kurulumu", "Reminder setup"), Icons.Filled.NotificationsActive, ink)
        Text(label("راجع الحالة وفعّل الأذونات المطلوبة. الظهور فوق التطبيقات واستثناء البطارية اختياريان وقد يختلف أثرهما حسب الجهاز.", "Durumu kontrol edip gerekli izinleri etkinleştirin. Üstte gösterim ve pil istisnası isteğe bağlıdır.", "Review the status and enable required permissions. Overlay and battery exemption are optional and device-dependent."), color = muted, modifier = Modifier.padding(vertical = 8.dp))
        ScheduleReliabilityCard(lang, surface, ink, muted, refresh)
        PermissionEntry(label("الإشعارات", "Bildirimler", "Notifications"), notificationOk) {
            if (Build.VERSION.SDK_INT >= 33 && !notificationPermissionGranted) notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            else {
                val notificationIntent = if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                open(notificationIntent)
            }
        }
        PermissionEntry(label("المنبهات الدقيقة", "Kesin alarmlar", "Exact alarms"), exactOk) { if (Build.VERSION.SDK_INT >= 31) open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }
        PermissionEntry(label("واجهة ملء الشاشة على القفل", "Tam ekran alarm", "Full-screen alarm"), fullOk) { if (Build.VERSION.SDK_INT >= 34) open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))) }
        PermissionEntry(label("الظهور فوق التطبيقات (اختياري)", "Diğer uygulamaların üzerinde (isteğe bağlı)", "Display over other apps (optional)"), overlayOk) { open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) }
        PermissionEntry(label("استثناء تحسين البطارية (اختياري)", "Pil optimizasyonu istisnası (isteğe bağlı)", "Battery optimization exemption (optional)"), batteryOk) { if (Build.VERSION.SDK_INT >= 23) open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))) }
        OutlinedButton(onClick = { refresh++ }, Modifier.fillMaxWidth().padding(top = 14.dp)) { Text(label("تحديث حالة الأذونات", "İzin durumunu yenile", "Refresh permission status")) }
        Button(onClick = done, Modifier.fillMaxWidth().padding(top = 8.dp).height(52.dp)) { Text(label("إنهاء الإعداد والمتابعة", "Kurulumu bitir", "Finish setup")) }
        TextButton(onClick = done, Modifier.fillMaxWidth()) { Text(label("تخطي الآن", "Şimdi geç", "Skip for now"), color = muted) }
    }
}

@Composable private fun ScheduleReliabilityCard(lang: String, surface: Color, ink: Color, muted: Color, refresh: Int) {
    val context = LocalContext.current
    val schedule = remember(refresh) { ScheduleRepository.active(context) }
    val exact = remember(refresh) { Build.VERSION.SDK_INT < 31 || context.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms() }
    val notifications = remember(refresh) { Build.VERSION.SDK_INT < 24 || context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled() }
    val fullScreen = remember(refresh) { Build.VERSION.SDK_INT < 34 || context.getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent() }
    val required = listOfNotNull(
        if (!exact) tr("reliability_exact_missing") else null,
        if (!notifications) tr("reliability_notifications_missing") else null,
        if (!fullScreen) tr("reliability_fullscreen_missing") else null,
    )
    val next = remember(refresh, schedule?.id) { schedule?.let { AlarmScheduler.nextBase(context, it.id) } }
    val last = remember(refresh) { AlarmPrefs.sp(context).getLong("last_schedule_success_ms", 0L) }
    val status = if (required.isEmpty()) tr("reliability_ready") else required.joinToString(" • ")
    val optionalWarnings = buildList {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(context)) add(when (lang) { "tr" -> "Üstte gösterim kapalı (isteğe bağlı)"; "en" -> "Overlay is off (optional)"; else -> "الظهور فوق التطبيقات غير مفعّل (اختياري)" })
        if (Build.VERSION.SDK_INT >= 23 && !context.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)) add(when (lang) { "tr" -> "Pil optimizasyonu hâlâ etkin olabilir"; "en" -> "Battery optimization may restrict delivery"; else -> "قد يقيّد تحسين البطارية وصول التنبيه" })
    }
    Card(Modifier.fillMaxWidth().padding(top = 6.dp), colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, if (required.isEmpty()) Teal.copy(alpha = .4f) else MaterialTheme.colorScheme.error.copy(alpha = .45f))) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.NotificationsActive, null, tint = if (required.isEmpty()) Teal else MaterialTheme.colorScheme.error); Text(tr("reliability_title"), Modifier.weight(1f).padding(horizontal = 8.dp), color = ink, fontWeight = FontWeight.Bold) }
            Text(status, color = if (required.isEmpty()) muted else MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
            optionalWarnings.forEach { Text(it, color = muted, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp)) }
            if (next != null) Text("${tr("reliability_next")}: ${next.format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US))}", color = muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            if (last > 0L) Text("${tr("reliability_last_schedule")}: ${java.time.Instant.ofEpochMilli(last).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM HH:mm", Locale.forLanguageTag(lang)))}", color = muted, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
            Text(tr("reliability_caveat"), color = muted, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable private fun HomeScreen(lang: String, surface: Color, ink: Color, muted: Color, bg: Color, refresh: Int, goNew: () -> Unit, onRefresh: () -> Unit) {
    val context = LocalContext.current
    val schedule = ScheduleRepository.active(context)
    val hasSchedule = schedule != null
    var showDelete by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        Header(tr("home"), Icons.Filled.Home, ink)
        if (!hasSchedule) {
            Card(Modifier.fillMaxWidth().padding(top = 12.dp), colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(18.dp), elevation = CardDefaults.cardElevation(2.dp)) {
                Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Shield, null, tint = if (MaterialTheme.colorScheme.background.luminance() < .45f) Color(0xFF86F2E4) else Teal, modifier = Modifier.size(44.dp))
                    Text(tr("no_schedule_title"), color = ink, fontWeight = FontWeight.Bold, fontSize = 19.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
                    Text(tr("no_schedule_sub"), color = muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                    Button(onClick = goNew, Modifier.fillMaxWidth().padding(top = 18.dp).height(52.dp), shape = RoundedCornerShape(14.dp)) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(8.dp)); Text(tr("new_fingerprint"), fontWeight = FontWeight.Bold) }
                }
            }
        } else {
            val active = requireNotNull(schedule)
            val next = AlarmScheduler.nextBase(context, active.id)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) { Text(LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US)), color = muted, fontSize = 13.sp) }
            if (next == null) {
                Card(Modifier.fillMaxWidth().padding(top = 12.dp), colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.CalendarMonth, null, tint = Teal, modifier = Modifier.size(38.dp))
                        Text(tr("schedule_ended"), color = ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
                        Text("${active.name}: ${tr("schedule_ended_sub")}", color = muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                        Button(onClick = goNew, Modifier.fillMaxWidth().padding(top = 14.dp)) { Icon(Icons.Filled.Edit, null); Spacer(Modifier.width(6.dp)); Text(tr("extend_schedule")) }
                    }
                }
            } else {
                val interval = active.intervalDays
                val time = LocalTime.of(active.hour, active.minute)
                val days = ChronoUnit.DAYS.between(LocalDate.now(), next).coerceAtLeast(0).toInt()
                Card(Modifier.fillMaxWidth().padding(top = 12.dp), colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(2.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Shield, null, tint = if (MaterialTheme.colorScheme.background.luminance() < .45f) Color(0xFF86F2E4) else Teal, modifier = Modifier.size(34.dp))
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(active.name, color = ink, fontWeight = FontWeight.Bold); Text(tr("secure_sub"), color = muted, fontSize = 12.sp) }
                            Icon(Icons.Filled.Check, null, tint = Teal, modifier = Modifier.size(25.dp))
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = goNew) { Icon(Icons.Filled.Edit, null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text(tr("edit_schedule")) }
                            TextButton(onClick = { showDelete = true }) { Icon(Icons.Filled.Delete, null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text(tr("delete_schedule"), color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
                Card(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 20.dp), colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(20.dp), elevation = CardDefaults.cardElevation(2.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { BadgeText(tr("soon"), Mint, Teal); BadgeText(time.format(DateTimeFormatter.ofPattern("h:mm a", Locale.forLanguageTag(lang))), Color(0xFFF1F2F3), ink) }
                        Spacer(Modifier.height(16.dp))
                        Text(tr("next_appointment"), color = muted, fontSize = 13.sp)
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(next.format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US)), color = ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            BadgeText(tr("days_left", days), Mint, Teal)
                        }
                        Spacer(Modifier.height(18.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(tr("allowed_days"), color = ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp); Text("${next.format(DateTimeFormatter.ofPattern("dd/MM", Locale.US))} – ${next.plusDays(2).format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US))}", color = muted, fontSize = 12.sp) }
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (0..2).forEach { d -> val dark = MaterialTheme.colorScheme.background.luminance() < .45f; val c = if (dark) listOf(Color(0xFF075348), Color(0xFF293741), Color(0xFF49252A))[d] else listOf(Teal, Color(0xFFF0F1F2), Color(0xFFFFEDEE))[d]; val fg = if (d == 0) Color.White else if (d == 2) (if (dark) Color(0xFFFFA39B) else Danger) else ink
                                Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = c), shape = RoundedCornerShape(12.dp)) { Column(Modifier.padding(9.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(tr("day_num", d + 1), color = fg, fontSize = 11.sp); Text(next.plusDays(d.toLong()).format(DateTimeFormatter.ofPattern("dd", Locale.US)), color = fg, fontSize = 22.sp, fontWeight = FontWeight.Bold); Text(next.plusDays(d.toLong()).format(DateTimeFormatter.ofPattern("MMM", Locale.forLanguageTag(lang))), color = fg, fontSize = 11.sp); Text(listOf(tr("main_day"), tr("reminder"), tr("last_chance"))[d], color = fg, fontSize = 10.sp, textAlign = TextAlign.Center) } }
                            }
                        }
                        val canConfirm = AppointmentRules.canConfirm(AppointmentOccurrence(active.id, active.name, next, 0), LocalDate.now())
                        Button(onClick = { if (AlarmPrefs.markDone(context, next.toEpochDay(), "home", active.id)) { AlarmScheduler.schedule(context); onRefresh() } }, enabled = canConfirm, modifier = Modifier.fillMaxWidth().padding(top = 18.dp).height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = if (MaterialTheme.colorScheme.background.luminance() < .45f) Color(0xFF78D9CD) else Teal, contentColor = if (MaterialTheme.colorScheme.background.luminance() < .45f) Navy else Color.White, disabledContainerColor = bg, disabledContentColor = muted), shape = RoundedCornerShape(14.dp)) {
                            Icon(Icons.Filled.Fingerprint, null); Spacer(Modifier.width(8.dp)); Text(tr(if (canConfirm) "confirm_fingerprint" else "not_yet"), fontWeight = FontWeight.Bold)
                        }
                        Text(tr("cycle_every", interval), color = muted, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp))
                    }
                }
            }
        }
    }
    DeleteScheduleDialog(context, showDelete, { showDelete = false }, activeScheduleId = schedule?.id) { onRefresh(); showDelete = false }
}

@Composable private fun DeleteScheduleDialog(context: Context, visible: Boolean, dismiss: () -> Unit, activeScheduleId: String? = null, deleted: () -> Unit) {
    if (visible) androidx.compose.material3.AlertDialog(onDismissRequest = dismiss, title = { Text(tr("delete_schedule")) }, text = { Text(tr("delete_schedule_confirm")) },
        confirmButton = { TextButton(onClick = { AlarmScheduler.cancelAll(context); activeScheduleId?.let { AlarmPrefs.clearSchedule(context, it) }; AlarmScheduler.schedule(context); deleted() }) { Text(tr("delete"), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(tr("cancel")) } })
}

@Composable private fun NewAppointment(lang: String, surface: Color, ink: Color, muted: Color, bg: Color, onCancel: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .45f
    val editingSchedule = remember { ScheduleRepository.active(context) }
    val editing = editingSchedule != null
    val originalStart = editingSchedule?.start
    val originalInterval = editingSchedule?.intervalDays ?: 0
    var scheduleName by remember { mutableStateOf(editingSchedule?.name ?: if (lang == "tr") "Yeni program" else if (lang == "en") "New schedule" else "جدول جديد") }
    var date by remember { mutableStateOf(editingSchedule?.start ?: LocalDate.now()) }
    var hour by remember { mutableIntStateOf(editingSchedule?.hour ?: AlarmPrefs.defaultHour(context)) }
    var minute by remember { mutableIntStateOf(editingSchedule?.minute ?: AlarmPrefs.defaultMinute(context)) }
    var interval by remember { mutableIntStateOf(editingSchedule?.intervalDays ?: 28) }
    var count by remember { mutableIntStateOf(editingSchedule?.appointmentCount ?: 14) }
    var vibrate by remember { mutableStateOf(AlarmPrefs.vibrate(context)) }
    var soundEnabled by remember { mutableStateOf(AlarmPrefs.sound(context)) }
    val fallbackName = tr("schedule_name")
    var showHelp by remember { mutableStateOf(false) }
    val defaultSoundText = tr("default_sound")
    val silentText = tr("silent")
    val chooseToneText = tr("choose_tone")
    var toneLabel by remember(lang) {
        mutableStateOf(AlarmPrefs.soundUri(context)?.let { saved -> runCatching { RingtoneManager.getRingtone(context, Uri.parse(saved))?.getTitle(context) }.getOrNull() } ?: defaultSoundText)
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val ringtoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            @Suppress("DEPRECATION") val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            AlarmPrefs.sp(context).edit().putString("sound_uri", uri?.toString()).putBoolean("sound", uri != null).apply()
            soundEnabled = uri != null
            toneLabel = if (uri == null) silentText else runCatching { RingtoneManager.getRingtone(context, uri)?.getTitle(context) }.getOrNull() ?: defaultSoundText
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text(if (lang == "ar") "‹" else "›", color = ink, fontSize = 26.sp) }
            Text(tr("new_fingerprint"), color = ink, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = { showHelp = true }) { Icon(Icons.Filled.HelpOutline, contentDescription = tr("help"), tint = if (dark) Color(0xFF86F2E4) else Teal) }
        }
        if (editing) Text(tr("edit_schedule"), color = muted, fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 4.dp))
        OutlinedTextField(value = scheduleName, onValueChange = { scheduleName = it.take(48) }, label = { Text(tr("schedule_name")) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
        Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.MusicNote, null, tint = if (dark) Color(0xFF86F2E4) else Teal, modifier = Modifier.size(28.dp))
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) { Text(tr("alarm_tone"), color = ink, fontWeight = FontWeight.SemiBold); Text(toneLabel, color = muted, fontSize = 12.sp, maxLines = 1) }
                OutlinedButton(onClick = {
                    val pick = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true); putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, chooseToneText)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, AlarmPrefs.soundUri(context)?.let { Uri.parse(it) } ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                    }
                    ringtoneLauncher.launch(pick)
                }, colors = ButtonDefaults.outlinedButtonColors(contentColor = if (dark) Color(0xFF86F2E4) else Teal)) { Text(tr("choose_tone")) }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(14.dp)) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Vibration, null, tint = if (dark) Color(0xFF86F2E4) else Teal); Column(Modifier.weight(1f).padding(horizontal = 10.dp)) { Text(tr("vibration"), color = ink, fontWeight = FontWeight.SemiBold); Text(tr("vibration_sub"), color = muted, fontSize = 12.sp) }; Switch(vibrate, { vibrate = it })
            }
        }
        Button(onClick = {
            AlarmScheduler.cancelAll(context)
            val previous = editingSchedule
            val completed = if (previous != null && originalStart == date && originalInterval == interval) previous.completedEpochDays else emptySet()
            val updated = if (previous != null) previous.copy(name = scheduleName.ifBlank { fallbackName }, start = date, hour = hour, minute = minute, intervalDays = interval, appointmentCount = count, completedEpochDays = completed)
                else ScheduleRepository.create(context, scheduleName.ifBlank { fallbackName }, date, interval, count, hour, minute)
            ScheduleRepository.save(context, updated)
            ScheduleRepository.setActive(context, updated.id)
            AlarmPrefs.sp(context).edit().putBoolean("vibrate", vibrate).putBoolean("sound", soundEnabled).apply()
            if (!editing) AlarmPrefs.sp(context).edit().putInt("default_hour", hour).putInt("default_minute", minute).apply()
            AlarmScheduler.schedule(context)
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 31 && !context.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()) context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
            onSaved()
        }, Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp).height(54.dp), colors = ButtonDefaults.buttonColors(containerColor = if (dark) Color(0xFF78D9CD) else Teal, contentColor = if (dark) Navy else Color.White), shape = RoundedCornerShape(14.dp)) { Text(tr("save"), fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        TextButton(onClick = onCancel, Modifier.fillMaxWidth().padding(bottom = 22.dp)) { Text(tr("cancel"), color = muted) }
    }
    if (showHelp) androidx.compose.material3.AlertDialog(onDismissRequest = { showHelp = false }, icon = { Icon(Icons.Filled.HelpOutline, null, tint = Teal) }, title = { Text(tr("help_instructions_title"), color = ink) }, text = { Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(tr("help_instructions_intro"), color = ink, fontSize = 14.sp)
        listOf("help_step_date", "help_step_time", "help_step_interval", "help_step_sound", "help_step_save").forEach { Text("•  ${tr(it)}", color = muted, modifier = Modifier.padding(top = 9.dp), fontSize = 13.sp) }
    } }, confirmButton = { TextButton(onClick = { showHelp = false }) { Text(tr("understood")) } })
}

@Composable private fun AppointmentsScreen(lang: String, surface: Color, ink: Color, muted: Color, bg: Color, refresh: Int, navigate: (String) -> Unit, onRefresh: () -> Unit) {
    val context = LocalContext.current
    val schedules = ScheduleRepository.all(context)
    var selectedId by remember(refresh) { mutableStateOf(ScheduleRepository.active(context)?.id) }
    val selected = schedules.firstOrNull { it.id == selectedId } ?: schedules.firstOrNull()
    val today = LocalDate.now()
    val occurrences = selected?.let { AppointmentRules.occurrences(it.id, it.name, it.start, it.intervalDays, it.appointmentCount, it.completedEpochDays) }.orEmpty()
    val upcoming = occurrences.filter { !it.windowEnd.isBefore(today) }
    var showDelete by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Header(tr("appointments"), Icons.Filled.CalendarMonth, ink)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(tr("upcoming_list"), color = ink, fontSize = 21.sp, fontWeight = FontWeight.Bold); Text(tr("appointments_sub"), color = muted, fontSize = 13.sp) }
            IconButton(onClick = {
                val created = ScheduleRepository.create(context, when (lang) { "tr" -> "Yeni program"; "en" -> "New schedule"; else -> "جدول جديد" })
                ScheduleRepository.setActive(context, created.id)
                AlarmPrefs.sp(context).edit().putString("draft_schedule_id", created.id).apply()
                navigate("new")
            }) { Icon(Icons.Filled.Add, tr("new_schedule"), tint = Teal) }
        }
        if (schedules.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            schedules.forEach { item ->
                FilterChip(selected = item.id == selected?.id, onClick = { selectedId = item.id; ScheduleRepository.setActive(context, item.id); onRefresh() }, label = { Text(item.name) }, leadingIcon = { if (item.id == selected?.id) Icon(Icons.Filled.Check, null, modifier = Modifier.size(16.dp)) })
            }
        }
        if (selected != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { ScheduleRepository.setActive(context, selected.id); navigate("new") }) { Icon(Icons.Filled.Edit, null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text(tr("edit_schedule")) }
            TextButton(onClick = { showDelete = true }) { Icon(Icons.Filled.Delete, null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text(tr("delete_schedule"), color = MaterialTheme.colorScheme.error) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (selected == null) Card(Modifier.fillMaxWidth().padding(top = 8.dp), colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(tr("no_upcoming"), color = muted, textAlign = TextAlign.Center); Button(onClick = { navigate("new") }, Modifier.padding(top = 14.dp)) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text(tr("new_fingerprint")) } }
            } else if (upcoming.isEmpty()) Card(Modifier.fillMaxWidth().padding(top = 8.dp), colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(tr("schedule_ended"), color = ink, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center); Text(tr("schedule_ended_sub"), color = muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp)); Button(onClick = { navigate("new") }, Modifier.padding(top = 14.dp)) { Icon(Icons.Filled.Edit, null); Spacer(Modifier.width(6.dp)); Text(tr("extend_schedule")) } }
            }
            upcoming.forEachIndexed { index, occurrence ->
                val date = occurrence.baseDate
                val state = AppointmentRules.state(occurrence, today, false)
                val isAvailable = state == AppointmentState.AVAILABLE
                Card(Modifier.fillMaxWidth().padding(bottom = 10.dp), colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(17.dp), elevation = CardDefaults.cardElevation(1.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                BadgeText("${index + 1}", if (index == 0) Mint else bg, if (index == 0) Teal else muted)
                                Column(Modifier.padding(start = 10.dp)) { Text(tr("schedule_name"), color = muted, fontSize = 12.sp); Text(date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US)), color = ink, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                            }
                            BadgeText(tr(if (isAvailable) "available_now" else "scheduled"), if (isAvailable) Mint else bg, if (isAvailable) Teal else muted)
                        }
                        HorizontalDivider(Modifier.padding(vertical = 10.dp), color = bg)
                        Text("${tr("grace_window")}: ${date.format(DateTimeFormatter.ofPattern("dd/MM", Locale.US))} – ${date.plusDays(2).format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US))}", color = muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
    DeleteScheduleDialog(context, showDelete, { showDelete = false }, activeScheduleId = selected?.id) { selectedId = ScheduleRepository.active(context)?.id; onRefresh(); showDelete = false }
}

@Composable private fun HistoryScreen(lang: String, surface: Color, ink: Color, muted: Color, bg: Color, refresh: Int) {
    val context = LocalContext.current
    var redraw by remember { mutableIntStateOf(0) }
    var selectedEvent by remember { mutableStateOf<AppointmentEvent?>(null) }
    var noteText by remember { mutableStateOf("") }
    val schedules = ScheduleRepository.all(context)
    val today = LocalDate.now()
    val events = remember(refresh, redraw) {
        val recorded = ScheduleRepository.events(context)
        val eventKeys = recorded.map { it.scheduleId to it.appointmentEpochDay }.toSet()
        val legacy = schedules.flatMap { schedule -> schedule.completedEpochDays.filter { (schedule.id to it) !in eventKeys }.map { day ->
            AppointmentEvent(schedule.id, schedule.name, day, 0L, "legacy_history")
        } }
        (recorded + legacy).sortedByDescending { it.confirmedAtEpochMillis }
    }
    val missed = schedules.flatMap { schedule ->
        AppointmentRules.occurrences(schedule.id, schedule.name, schedule.start, schedule.intervalDays, schedule.appointmentCount, schedule.completedEpochDays)
            .filter { it.windowEnd.isBefore(today) }
    }.sortedByDescending { it.baseDate }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Header(tr("history"), Icons.Filled.History, ink)
        Text(tr("history_title"), color = ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
        Text(tr("history_sub"), color = muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (events.isEmpty() && missed.isEmpty()) Card(Modifier.fillMaxWidth().padding(top = 6.dp), colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(18.dp)) {
                Text(tr("no_previous"), Modifier.fillMaxWidth().padding(24.dp), color = muted, textAlign = TextAlign.Center)
            }
            events.forEach { event ->
                val date = runCatching { LocalDate.ofEpochDay(event.appointmentEpochDay) }.getOrNull() ?: return@forEach
                val completedAt = java.time.Instant.ofEpochMilli(event.confirmedAtEpochMillis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.forLanguageTag(lang)))
                Card(Modifier.fillMaxWidth().padding(bottom = 10.dp), colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(17.dp), elevation = CardDefaults.cardElevation(1.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(event.scheduleName, color = muted, fontSize = 12.sp); Text(date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US)), color = ink, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                            BadgeText(tr("completed_short"), Mint, Teal)
                        }
                        HorizontalDivider(Modifier.padding(vertical = 9.dp), color = bg)
                        if (event.confirmedAtEpochMillis > 0L) {
                            Text("${tr("confirmed_at")}: $completedAt", color = muted, fontSize = 12.sp)
                            Text("${tr("confirmation_method")}: ${methodName(event.method, lang)}", color = muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                        } else Text(tr("legacy_history_note"), color = muted, fontSize = 12.sp)

                        if (event.note.isNotBlank()) Text("${tr("note")}: ${event.note}", color = ink, fontSize = 13.sp, modifier = Modifier.padding(top = 5.dp))
                        TextButton(onClick = { selectedEvent = event; noteText = event.note }) { Text(tr(if (event.note.isBlank()) "add_note" else "edit_note")) }
                    }
                }
            }
            if (missed.isNotEmpty()) Text(tr("not_confirmed_short"), color = ink, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
            missed.forEach { occurrence ->
                Card(Modifier.fillMaxWidth().padding(bottom = 10.dp), colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(17.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Column { Text(occurrence.scheduleName, color = muted, fontSize = 12.sp); Text(occurrence.baseDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US)), color = ink, fontSize = 20.sp, fontWeight = FontWeight.Bold) }; BadgeText(tr("not_confirmed_short"), bg, muted) }
                        Text(tr("not_confirmed_label"), color = muted, fontSize = 12.sp, modifier = Modifier.padding(top = 7.dp))
                    }
                }
            }
        }
    }
    selectedEvent?.let { event ->
        androidx.compose.material3.AlertDialog(onDismissRequest = { selectedEvent = null }, title = { Text(tr("note")) }, text = {
            OutlinedTextField(value = noteText, onValueChange = { noteText = it.take(280) }, label = { Text(tr("note_optional")) }, minLines = 2, maxLines = 4)
        }, confirmButton = { TextButton(onClick = { ScheduleRepository.updateEventNote(context, event.confirmedAtEpochMillis, noteText); selectedEvent = null; redraw++ }) { Text(tr("save")) } }, dismissButton = { TextButton(onClick = { selectedEvent = null }) { Text(tr("cancel")) } })
    }
}

private fun methodName(method: String, lang: String): String = when (method) {
    "legacy_history" -> when (lang) { "tr" -> "Eski geçmiş kaydı"; "en" -> "Earlier history record"; else -> "سجل محفوظ من إصدار سابق" }
    "ringing_screen" -> when (lang) { "tr" -> "Alarm ekranı"; "en" -> "Ringing screen"; else -> "شاشة الرنين" }
    "notification" -> when (lang) { "tr" -> "Bildirim"; "en" -> "Notification"; else -> "الإشعار" }
    "widget" -> when (lang) { "tr" -> "Widget"; "en" -> "Widget"; else -> "الودجت" }
    else -> when (lang) { "tr" -> "Ana sayfa"; "en" -> "Home screen"; else -> "الشاشة الرئيسية" }
}

@Composable private fun SettingsScreen(lang: String, theme: String, dark: Boolean, surface: Color, ink: Color, muted: Color, bg: Color, setLang: (String) -> Unit, setTheme: (String) -> Unit, openSetup: () -> Unit) {
    val context = LocalContext.current
    var panel by remember { mutableStateOf("") }
    var sound by remember { mutableStateOf(AlarmPrefs.sound(context)) }
    var vibration by remember { mutableStateOf(AlarmPrefs.vibrate(context)) }
    var highPriority by remember { mutableStateOf(AlarmPrefs.highPriority(context)) }
    var remindBefore by remember { mutableStateOf(AlarmPrefs.remindDayBefore(context)) }
    var repeatDay by remember { mutableStateOf(AlarmPrefs.repeatOnFingerprintDay(context)) }
    var remindSecondDay by remember { mutableStateOf(AlarmPrefs.remindSecondDay(context)) }
    var autoStop by remember { mutableIntStateOf(AlarmPrefs.autoStopMinutes(context)) }
    var snooze by remember { mutableIntStateOf(AlarmPrefs.snoozeMinutes(context)) }
    var defaultHour by remember { mutableIntStateOf(AlarmPrefs.defaultHour(context)) }
    var defaultMinute by remember { mutableIntStateOf(AlarmPrefs.defaultMinute(context)) }
    val defaultSoundText = tr("default_sound")
    val silentText = tr("silent")
    val chooseToneText = tr("choose_tone")
    var toneLabel by remember(lang) { mutableStateOf(AlarmPrefs.soundUri(context)?.let { runCatching { RingtoneManager.getRingtone(context, Uri.parse(it))?.getTitle(context) }.getOrNull() } ?: defaultSoundText) }
    fun putBool(key: String, value: Boolean) { AlarmPrefs.sp(context).edit().putBoolean(key, value).apply() }
    fun reschedule() { if (AlarmPrefs.hasSchedule(context)) AlarmScheduler.schedule(context) }
    val ringtoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            @Suppress("DEPRECATION") val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            AlarmPrefs.sp(context).edit().putString("sound_uri", uri?.toString()).putBoolean("sound", uri != null).apply()
            sound = uri != null
            toneLabel = if (uri == null) silentText else runCatching { RingtoneManager.getRingtone(context, uri)?.getTitle(context) }.getOrNull() ?: defaultSoundText
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        Header(tr("settings"), Icons.Filled.Settings, ink)
        Spacer(Modifier.height(12.dp))
        SettingsOption(surface, ink, muted, Icons.Filled.Language, tr("language"), tr("language_sub"), when (lang) { "tr" -> "Türkçe"; "en" -> "English"; else -> "العربية" }) { panel = "language" }
        SettingsOption(surface, ink, muted, Icons.Filled.Palette, tr("appearance"), tr("appearance_sub"), when (theme) { "dark" -> tr("dark"); "light" -> tr("light"); else -> tr("system") }) { panel = "theme" }
        SettingsOption(surface, ink, muted, Icons.Filled.NotificationsActive, tr("setup_assistant"), tr("setup_assistant_sub"), tr("manage"), openSetup)
        SettingsOption(surface, ink, muted, Icons.Filled.NotificationsActive, tr("alarm_options"), tr("alarm_settings_sub"), tr("customized")) { panel = "alarm" }
        SettingsOption(surface, ink, muted, Icons.Filled.Info, tr("about_app"), tr("about_app_sub"), "v${runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "1.3.4"}") { panel = "about" }
        SettingsOption(surface, ink, muted, Icons.Filled.Fingerprint, tr("about_developer"), tr("developer_sub"), "") { panel = "developer" }
    }
    if (panel.isNotEmpty()) androidx.compose.material3.AlertDialog(
        onDismissRequest = { panel = "" },
        title = { Text(when (panel) { "language" -> tr("choose_language"); "theme" -> tr("appearance"); "alarm" -> tr("alarm_options"); "about" -> tr("about_app"); else -> tr("about_developer") }, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(rememberScrollState())) {
                when (panel) {
                    "language" -> listOf(Triple("ar", "🇸🇦", "العربية"), Triple("tr", "🇹🇷", "Türkçe"), Triple("en", "🇬🇧", "English")).forEach { (code, flag, name) ->
                        LanguageChoice(surface, ink, muted, flag, name, lang == code) { setLang(code); panel = "" }
                    }
                    "theme" -> listOf("light" to tr("light"), "dark" to tr("dark"), "system" to tr("system")).forEach { (code, name) ->
                        LanguageChoice(surface, ink, muted, if (code == "dark") "◐" else if (code == "light") "☼" else "◉", name, theme == code) { setTheme(code); panel = "" }
                    }
                    "alarm" -> {
                        SettingsValueRow(surface, ink, muted, Icons.Filled.Schedule, tr("default_alarm_time"), LocalTime.of(defaultHour, defaultMinute).format(DateTimeFormatter.ofPattern("h:mm a", Locale.forLanguageTag(lang)))) {
                            val pickerTheme = if (dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert
                            TimePickerDialog(android.view.ContextThemeWrapper(context, pickerTheme), { _, h, m -> defaultHour = h; defaultMinute = m; AlarmPrefs.sp(context).edit().putInt("default_hour", h).putInt("default_minute", m).apply() }, defaultHour, defaultMinute, false).show()
                        }
                        ToggleRow(surface, ink, Icons.Filled.MusicNote, tr("alarm_sound"), sound) { sound = it; putBool("sound", it) }
                        SettingsValueRow(surface, ink, muted, Icons.Filled.MusicNote, tr("alarm_tone"), toneLabel) {
                            val pick = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM); putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true); putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, chooseToneText); putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, AlarmPrefs.soundUri(context)?.let { Uri.parse(it) } ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                            }
                            ringtoneLauncher.launch(pick)
                        }
                        ToggleRow(surface, ink, Icons.Filled.Vibration, tr("vibration"), vibration) { vibration = it; putBool("vibrate", it) }
                        Text(tr("auto_stop_after"), color = ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 9.dp, bottom = 4.dp))
                        ChoiceChips(listOf(0, 5, 10, 15, 30, 60), autoStop, { if (it == 0) tr("off") else "$it ${tr("minutes_short")}" }) { autoStop = it; AlarmPrefs.sp(context).edit().putInt("auto_stop_minutes", it).apply() }
                        Text(tr("snooze_duration"), color = ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 9.dp, bottom = 4.dp))
                        ChoiceChips(listOf(5, 10, 15, 30, 60), snooze, { "$it ${tr("minutes_short")}" }) { snooze = it; AlarmPrefs.sp(context).edit().putInt("snooze_minutes", it).apply(); reschedule() }
                        ToggleRow(surface, ink, Icons.Filled.NotificationsActive, tr("high_priority"), highPriority) { highPriority = it; putBool("high_priority", it); ensureAlarmChannel(context) }
                        ToggleRow(surface, ink, Icons.Filled.CalendarMonth, tr("remind_day_before"), remindBefore) { remindBefore = it; putBool("remind_day_before", it); reschedule() }
                        ToggleRow(surface, ink, Icons.Filled.Alarm, tr("repeat_until_done"), repeatDay) { repeatDay = it; putBool("repeat_on_fingerprint_day", it) }
                        ToggleRow(surface, ink, Icons.Filled.Event, tr("remind_next_day"), remindSecondDay) { remindSecondDay = it; putBool("remind_second_day", it); reschedule() }
                    }
                    "about" -> {
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Image(painterResource(R.drawable.app_icon_zoomed), contentDescription = tr("about_app"), modifier = Modifier.size(92.dp))
                            Text(tr("app_name"), color = ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                            Text(tr("app_short_description"), color = muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                            Text(tr("disclaimer_body"), color = muted, fontSize = 12.sp, lineHeight = 19.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp))
                            Text("${tr("version")}: ${runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "1.3.4"}", color = muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                        }
                    }
                    "developer" -> {
                        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(color = if (dark) Color(0xFF20343D) else Color(0xFFE8F5F2), shape = CircleShape, modifier = Modifier.size(66.dp)) { Box(contentAlignment = Alignment.Center) { Text("Y", color = if (dark) Color(0xFF86F2E4) else Teal, fontSize = 32.sp, fontWeight = FontWeight.Bold) } }
                            Text(tr("developer_name"), color = ink, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(top = 8.dp))
                            Text(tr("developer_intro"), color = muted, fontSize = 13.sp, textAlign = TextAlign.Center)
                        }
                        DeveloperLink(surface, ink, R.drawable.ic_brand_whatsapp, "WhatsApp", tr("developer_whatsapp_sub"), "https://wa.me/905354883886")
                        DeveloperLink(surface, ink, R.drawable.ic_brand_telegram, "Telegram", tr("developer_telegram_sub"), "tg://resolve?domain=abw3laa", "https://t.me/abw3laa", "org.telegram.messenger")
                        DeveloperLink(surface, ink, R.drawable.ic_brand_facebook, "Facebook", tr("developer_facebook_sub"), "https://www.facebook.com/share/1EykCQDkjB/")
                    }
                    else -> Unit
                }
            }
        },
        confirmButton = { TextButton(onClick = { panel = "" }) { Text(tr("close")) } },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable private fun LanguageChoice(surface: Color, ink: Color, muted: Color, symbol: String, title: String, selected: Boolean, click: () -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .45f
    val accent = if (dark) Color(0xFF86F2E4) else Teal
    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable(onClick = click), colors = CardDefaults.cardColors(containerColor = if (selected) { if (dark) Color(0xFF163F3A) else Color(0xFFE0F7F3) } else surface), shape = RoundedCornerShape(13.dp), border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, accent) else null) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(symbol, fontSize = 22.sp)
            Text(title, Modifier.weight(1f).padding(horizontal = 12.dp), color = ink, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
            if (selected) Icon(Icons.Filled.Check, null, tint = accent)
        }
    }
}

@Composable private fun SettingsValueRow(surface: Color, ink: Color, muted: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: String, click: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable(onClick = click), colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(13.dp)) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (MaterialTheme.colorScheme.background.luminance() < .45f) Color(0xFF86F2E4) else Teal)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) { Text(title, color = ink, fontWeight = FontWeight.SemiBold); Text(value, color = muted, fontSize = 12.sp, maxLines = 1) }
            Text(if (LocalLayoutDirection.current == LayoutDirection.Rtl) "‹" else "›", color = muted, fontSize = 22.sp)
        }
    }
}

@Composable private fun ChoiceChips(values: List<Int>, selected: Int, label: @Composable (Int) -> String, select: (Int) -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .45f
    val accent = if (dark) Color(0xFF86F2E4) else Teal
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        values.forEach { value -> Surface(onClick = { select(value) }, color = if (value == selected) (if (dark) Color(0xFF163F3A) else Color(0xFFE0F7F3)) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f), shape = CircleShape, border = if (value == selected) androidx.compose.foundation.BorderStroke(1.dp, accent) else null) {
            Text(label(value), modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp), color = if (value == selected) accent else MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = if (value == selected) FontWeight.Bold else FontWeight.Medium)
        } }
    }
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
@Composable private fun DeveloperLink(surface: Color, ink: Color, iconRes: Int, label: String, sub: String, url: String, fallbackUrl: String? = null, targetPackage: String? = null) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable {
        val opened = runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            if (targetPackage != null) intent.setPackage(targetPackage)
            context.startActivity(intent)
        }.isSuccess
        if (!opened && fallbackUrl != null) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl))) }
    }, colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(iconRes), contentDescription = label, modifier = Modifier.size(28.dp))
            Column(Modifier.padding(horizontal = 10.dp)) {
                Text(label, color = ink, fontWeight = FontWeight.SemiBold)
                Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
    }
}

@Composable private fun tr(k: String, n: Int? = null): String {
    val l = currentLanguage()
    val values = when (l) {
        "en" -> mapOf("home" to "Home", "appointments" to "Appointments", "history" to "History", "settings" to "Settings", "secure_alarm" to "Smart alarm active and protected", "secure_sub" to "Three-day reminders are scheduled", "new_fingerprint" to "Add fingerprint appointment", "soon" to "Upcoming", "next_appointment" to "Next appointment date", "days_left" to "$n days left", "allowed_days" to "Allowed days", "main_day" to "Main date", "reminder" to "Reminder", "last_chance" to "Last chance", "confirm_fingerprint" to "Confirm fingerprint", "set_start" to "First appointment date", "alarm_time" to "Alarm time", "repeat_cycle" to "Repeat cycle", "every_28" to "Every 28 days", "official_cycle" to "Regular cycle", "weekly" to "Weekly", "every_week" to "Repeats every 7 days", "cycles_count" to "Number of appointments", "appointments_unit" to "appointments", "appointments_history_title" to "Appointments history", "previous_list" to "Past appointments", "no_previous" to "No past appointments yet", "no_upcoming" to "No upcoming appointments. Add a schedule.", "completed_short" to "Done", "elapsed" to "Elapsed", "elapsed_label" to "Appointment window passed", "not_confirmed_short" to "Not confirmed", "not_confirmed_label" to "Snoozing only delays the alarm; this appointment is not marked complete.", "choose_tone" to "Choose ringtone", "default_sound" to "Default alarm ringtone", "silent" to "Silent", "vibration" to "Vibration", "vibration_sub" to "Vibrate during the alarm", "sound_settings" to "Sound and vibration", "save" to "Save schedule", "cancel" to "Cancel", "history_title" to "Fingerprint appointment history", "history_sub" to "Review upcoming and completed dates", "upcoming" to "Upcoming", "previous" to "Completed", "new_short" to "New", "upcoming_list" to "Scheduled appointments", "grace_window" to "3-day window", "scheduled" to "Scheduled", "appointment_num" to "Appointment #$n", "completed_label" to "Completed", "language" to "Language", "appearance" to "Appearance", "system" to "System", "light" to "Light", "dark" to "Dark", "alarm_options" to "Permissions and reliability", "exact_alarm" to "Exact alarm access", "exact_alarm_sub" to "Allow precise scheduled alarms", "overlay" to "Display over other apps", "overlay_sub" to "Optional full-screen alarm display", "battery" to "Battery optimization", "battery_sub" to "Allow background alarm delivery", "notifications" to "Notifications", "notifications_sub" to "Manage notification permission", "alarm_sound" to "Alarm sound", "about_developer" to "About developer", "enable" to "Enable", "day_num" to "Day $n")
        "tr" -> mapOf("home" to "Ana Sayfa", "appointments" to "Randevular", "history" to "Geçmiş", "settings" to "Ayarlar", "secure_alarm" to "Akıllı alarm etkin ve güvenli", "secure_sub" to "Üç günlük hatırlatıcılar ayarlandı", "new_fingerprint" to "Yeni randevu ekle", "soon" to "Yakında", "next_appointment" to "Sonraki randevu tarihi", "days_left" to "$n gün kaldı", "allowed_days" to "Uygun günler", "main_day" to "Ana tarih", "reminder" to "Hatırlatma", "last_chance" to "Son fırsat", "confirm_fingerprint" to "Parmak izini onayla", "set_start" to "İlk randevu tarihi", "alarm_time" to "Alarm saati", "repeat_cycle" to "Tekrarlama", "every_28" to "28 günde bir", "official_cycle" to "Düzenli döngü", "weekly" to "Haftalık", "every_week" to "7 günde bir", "cycles_count" to "Randevu sayısı", "appointments_unit" to "randevu", "appointments_history_title" to "Randevu geçmişi", "previous_list" to "Geçmiş randevular", "no_previous" to "Henüz geçmiş randevu yok", "no_upcoming" to "Yaklaşan randevu yok. Bir program ekleyin.", "completed_short" to "Tamamlandı", "elapsed" to "Geçti", "elapsed_label" to "Randevu süresi geçti", "not_confirmed_short" to "Onaylanmadı", "not_confirmed_label" to "Erteleme yalnızca alarmı geciktirir; randevu tamamlandı olarak kaydedilmez.", "choose_tone" to "Alarm sesini seç", "default_sound" to "Varsayılan alarm sesi", "silent" to "Sessiz", "vibration" to "Titreşim", "vibration_sub" to "Alarm sırasında titreşim", "sound_settings" to "Ses ve titreşim", "save" to "Programı kaydet", "cancel" to "İptal", "history_title" to "Parmak izi randevu geçmişi", "history_sub" to "Yaklaşan ve tamamlanan tarihleri görüntüle", "upcoming" to "Yaklaşan", "previous" to "Tamamlandı", "new_short" to "Yeni", "upcoming_list" to "Planlanan randevular", "grace_window" to "3 günlük süre", "scheduled" to "Planlandı", "appointment_num" to "Randevu #$n", "completed_label" to "Tamamlandı", "language" to "Dil", "appearance" to "Görünüm", "system" to "Sistem", "light" to "Açık", "dark" to "Koyu", "alarm_options" to "İzinler ve güvenilirlik", "exact_alarm" to "Kesin alarm izni", "exact_alarm_sub" to "Zamanında alarm kurulumu", "overlay" to "Diğer uygulamaların üzerinde göster", "overlay_sub" to "İsteğe bağlı tam ekran alarm", "battery" to "Pil optimizasyonu", "battery_sub" to "Arka plan alarmlarına izin ver", "notifications" to "Bildirimler", "notifications_sub" to "Bildirim izinlerini yönet", "alarm_sound" to "Alarm sesi", "about_developer" to "Geliştirici hakkında", "enable" to "Aç", "day_num" to "$n. gün")
        else -> mapOf("home" to "الرئيسية", "appointments" to "المواعيد", "history" to "السجل", "settings" to "الإعدادات", "secure_alarm" to "المنبه الذكي نشط ومؤمّن", "secure_sub" to "تم ضبط تنبيهات مهلة الأيام الثلاثة بدقة", "new_fingerprint" to "تسجيل بصمة جديدة", "soon" to "قريباً", "next_appointment" to "موعد البصمة التالي المقرّر", "days_left" to "متبقي $n يوم", "allowed_days" to "الأيام المسموحة", "main_day" to "الأساسي", "reminder" to "تذكير", "last_chance" to "الفرصة الأخيرة", "confirm_fingerprint" to "تأكيد البصمة", "set_start" to "تاريخ أول بصمة", "alarm_time" to "وقت التنبيه", "repeat_cycle" to "دورة التكرار", "every_28" to "كل 28 يوماً", "official_cycle" to "الدورة المنتظمة", "weekly" to "أسبوعي", "every_week" to "يتكرر كل 7 أيام", "cycles_count" to "عدد المواعيد المجدولة", "appointments_unit" to "موعداً", "appointments_history_title" to "سجل المواعيد", "previous_list" to "المواعيد السابقة", "no_previous" to "لا توجد مواعيد سابقة بعد", "no_upcoming" to "لا توجد مواعيد قادمة. أضف جدول بصمات.", "completed_short" to "مكتمل", "elapsed" to "انتهى موعده", "elapsed_label" to "انتهت مهلة الموعد دون تأكيد", "not_confirmed_short" to "غير موثّق", "not_confirmed_label" to "لم يتم تأكيد البصمة. التأجيل يغيّر وقت التنبيه فقط ولا يسجل اكتمال الموعد.", "choose_tone" to "اختر نغمة", "default_sound" to "نغمة التنبيه الافتراضية", "silent" to "بدون صوت", "vibration" to "الاهتزاز", "vibration_sub" to "اهتزاز أثناء التنبيه", "sound_settings" to "صوت التنبيه", "save" to "حفظ المواعيد", "cancel" to "إلغاء", "history_title" to "سجل مواعيد البصمة", "history_sub" to "متابعة تواريخ البصمات السابقة والمقبلة", "upcoming" to "البصمات القادمة", "previous" to "البصمات السابقة", "new_short" to "بصمة جديدة", "upcoming_list" to "قائمة البصمات القادمة", "grace_window" to "مهلة السماح (3 أيام)", "scheduled" to "مجدول", "appointment_num" to "الموعد رقم $n", "completed_label" to "بصمة مكتملة بتاريخ", "language" to "اختر اللغة", "appearance" to "الثيم والمظهر", "system" to "النظام", "light" to "فاتح", "dark" to "داكن", "alarm_options" to "الأذونات وتفادي الإغلاق", "exact_alarm" to "السماح بالمنبهات الدقيقة", "exact_alarm_sub" to "تفعيل جدولة التنبيهات في وقتها", "overlay" to "الظهور فوق التطبيقات", "overlay_sub" to "إظهار واجهة المنبه فوق الشاشة", "battery" to "استثناء توفير البطارية", "battery_sub" to "منع نظام الهاتف من إيقاف المنبه", "notifications" to "إعدادات الإشعارات", "notifications_sub" to "السماح بعرض إشعارات المنبه", "alarm_sound" to "صوت التنبيه", "about_developer" to "حول المطور", "enable" to "تفعيل", "day_num" to "اليوم $n")
    }
    val extra = when (l) {
        "en" -> mapOf("language_sub" to "Choose the app language", "appearance_sub" to "Light, dark, or system appearance", "alarm_settings_sub" to "Sound, ringtone, vibration and reminders", "customized" to "Custom", "permissions" to "Permissions and notifications", "permissions_sub" to "Overlay, full screen, notifications, battery", "manage" to "Manage", "about_app" to "About app", "about_app_sub" to "Version and app details", "developer_sub" to "Contact and social accounts", "disclaimer" to "Disclaimer", "disclaimer_sub" to "Terms and usage notes", "full_screen_permission" to "Full-screen alarm permission", "version" to "Version", "package" to "Package", "disclaimer_body" to "Alarms depend on the permissions and background-execution rules of the device. Verify alarm operation on your phone.", "close" to "Close", "enabled" to "Enabled", "alarm_tone" to "Alarm ringtone", "choose_tone" to "Choose ringtone", "default_sound" to "Default alarm ringtone", "silent" to "Silent")
        "tr" -> mapOf("language_sub" to "Uygulama dilini seçin", "appearance_sub" to "Açık, koyu veya sistem görünümü", "alarm_settings_sub" to "Ses, zil sesi, titreşim ve hatırlatmalar", "customized" to "Özel", "permissions" to "İzinler ve bildirimler", "permissions_sub" to "Kaplama, tam ekran, bildirimler, pil", "manage" to "Yönet", "about_app" to "Uygulama hakkında", "about_app_sub" to "Sürüm ve uygulama bilgileri", "developer_sub" to "İletişim ve sosyal hesaplar", "disclaimer" to "Sorumluluk reddi", "disclaimer_sub" to "Koşullar ve kullanım notları", "full_screen_permission" to "Tam ekran alarm izni", "version" to "Sürüm", "package" to "Paket", "disclaimer_body" to "Alarmlar cihaz izinlerine ve arka plan çalıştırma kurallarına bağlıdır. Alarmı kendi cihazınızda test edin.", "close" to "Kapat", "enabled" to "Etkin", "alarm_tone" to "Alarm zil sesi", "choose_tone" to "Zil sesi seç", "default_sound" to "Varsayılan alarm sesi", "silent" to "Sessiz")
        else -> mapOf("language_sub" to "تحديد لغة واجهة التطبيق", "appearance_sub" to "التبديل بين الوضع الفاتح والداكن", "alarm_settings_sub" to "الصوت، نغمة الرنين، الاهتزاز، والتذكير", "customized" to "مخصّص", "permissions" to "الأذونات والإشعارات", "permissions_sub" to "الظهور فوق الشاشة، الإشعارات والبطارية", "manage" to "إدارة", "about_app" to "حول التطبيق", "about_app_sub" to "الإصدار وتفاصيل التطبيق", "developer_sub" to "التواصل وحسابات التواصل الاجتماعي", "disclaimer" to "إخلاء المسؤولية", "disclaimer_sub" to "الشروط وملاحظات الاستخدام", "full_screen_permission" to "السماح بواجهة المنبه بملء الشاشة", "version" to "الإصدار", "package" to "معرّف الحزمة", "disclaimer_body" to "تعتمد التنبيهات على أذونات الجهاز وقواعد تشغيل الخلفية. اختبر عمل المنبه على هاتفك قبل الاعتماد عليه.", "close" to "إغلاق", "enabled" to "مفعّل", "alarm_tone" to "نغمة التنبيه", "choose_tone" to "اختر نغمة", "default_sound" to "نغمة التنبيه الافتراضية", "silent" to "بدون صوت")
    }
    val added = when (l) {
        "en" -> mapOf(
            "no_schedule_title" to "No fingerprint schedule yet", "no_schedule_sub" to "Your appointments will appear here after you create and save a schedule.", "edit_schedule" to "Edit schedule", "delete_schedule" to "Delete schedule", "delete_schedule_confirm" to "Delete this schedule? Its pending alarms will be cancelled; already recorded history will remain.", "delete" to "Delete", "appointments_sub" to "Upcoming fingerprint dates in your saved schedule", "next_cycle" to "Next scheduled appointment", "cycle_every" to "Repeats every $n days",
            "help" to "Help", "help_instructions_title" to "Setup instructions", "help_instructions_intro" to "Enter the date of your first or next fingerprint appointment, choose the schedule interval, and set the reminder time.", "help_step_date" to "Choose the first appointment date shown in GöcBil.", "help_step_time" to "Choose when the alarm should ring.", "help_step_interval" to "Choose every 28 days or weekly.", "help_step_sound" to "Choose an alarm ringtone and vibration preference.", "help_step_save" to "Save to create the future appointment reminders.", "understood" to "Got it",
            "choose_language" to "Choose language", "alarm_options" to "Alarm settings", "alarm_settings_sub" to "Sound, vibration, timing and reminders", "permissions_sub" to "Notifications, exact alarms, overlay and battery", "alarm_sound" to "Alarm sound", "default_alarm_time" to "Default alarm time", "auto_stop_after" to "Automatically stop after", "snooze_duration" to "Snooze duration", "high_priority" to "Highest priority alert", "remind_day_before" to "Notify one day before", "repeat_until_done" to "Repeat on appointment day until confirmed", "remind_next_day" to "Reminder on the second day", "off" to "Off", "minutes_short" to "min", "customized" to "Custom",
            "app_name" to "Basma Alarm", "app_short_description" to "A personal organizer for periodic fingerprint appointments and reminders.", "developer_name" to "Yasir Abu Alaa", "developer_intro" to "App and website developer.", "developer_whatsapp_sub" to "Direct WhatsApp message", "developer_telegram_sub" to "Telegram profile and updates", "developer_facebook_sub" to "Facebook page", "permissions_note" to "Alarm behavior depends on Android permissions and device background restrictions. Enable the required items for reliable reminders.",
            "disclaimer_body" to "This app is an independent, unofficial personal organizer and reminder tool. It does not represent any government authority or official office. Always verify your actual appointment dates in the GöcBil app."
        )
        "tr" -> mapOf(
            "no_schedule_title" to "Henüz parmak izi programı yok", "no_schedule_sub" to "Randevularınız, bir program oluşturup kaydettikten sonra burada görünür.", "edit_schedule" to "Programı düzenle", "delete_schedule" to "Programı sil", "delete_schedule_confirm" to "Bu program silinsin mi? Bekleyen alarmlar iptal edilir; kaydedilmiş geçmiş korunur.", "delete" to "Sil", "appointments_sub" to "Kaydedilen programınızdaki yaklaşan parmak izi tarihleri", "next_cycle" to "Sıradaki randevu", "cycle_every" to "$n günde bir tekrarlanır",
            "help" to "Yardım", "help_instructions_title" to "Kurulum talimatları", "help_instructions_intro" to "İlk veya sıradaki parmak izi randevunuzun tarihini girin, aralığı ve alarm saatini seçin.", "help_step_date" to "GöcBil uygulamasında gösterilen ilk randevu tarihini seçin.", "help_step_time" to "Alarmın çalacağı saati seçin.", "help_step_interval" to "28 günde bir veya haftalık seçin.", "help_step_sound" to "Alarm zil sesini ve titreşim tercihini seçin.", "help_step_save" to "Gelecek randevu hatırlatmalarını oluşturmak için kaydedin.", "understood" to "Anladım",
            "choose_language" to "Dil seçin", "alarm_options" to "Alarm ayarları", "alarm_settings_sub" to "Ses, titreşim, saat ve hatırlatmalar", "permissions_sub" to "Bildirimler, kesin alarmlar, üstte gösterim ve pil", "alarm_sound" to "Alarm sesi", "default_alarm_time" to "Varsayılan alarm saati", "auto_stop_after" to "Otomatik durdurma süresi", "snooze_duration" to "Erteleme süresi", "high_priority" to "En yüksek öncelikli uyarı", "remind_day_before" to "Bir gün önceden bildir", "repeat_until_done" to "Onaylanana kadar randevu gününde tekrarla", "remind_next_day" to "İkinci gün hatırlatması", "off" to "Kapalı", "minutes_short" to "dk", "customized" to "Özel",
            "app_name" to "Parmak İzi Alarmı", "app_short_description" to "Düzenli parmak izi randevuları ve hatırlatmaları için kişisel bir düzenleyici.", "developer_name" to "Yasir Abu Alaa", "developer_intro" to "Uygulama ve web sitesi geliştiricisi.", "developer_whatsapp_sub" to "Doğrudan WhatsApp mesajı", "developer_telegram_sub" to "Telegram profili ve güncellemeler", "developer_facebook_sub" to "Facebook sayfası", "permissions_note" to "Alarmın çalışması Android izinlerine ve cihazın arka plan kısıtlamalarına bağlıdır. Hatırlatmaların güvenilir olması için gerekli izinleri açın.",
            "disclaimer_body" to "Bu uygulama bağımsız ve resmi olmayan kişisel bir düzenleme ve hatırlatma aracıdır. Herhangi bir devlet kurumunu veya resmi daireyi temsil etmez. Gerçek randevu tarihlerinizi her zaman GöcBil uygulamasından doğrulayın."
        )
        else -> mapOf(
            "no_schedule_title" to "لا يوجد جدول بصمة بعد", "no_schedule_sub" to "ستظهر مواعيدك هنا بعد إنشاء جدول وحفظه.", "edit_schedule" to "تعديل الجدول", "delete_schedule" to "حذف الجدول", "delete_schedule_confirm" to "هل تريد حذف هذا الجدول؟ ستُلغى تنبيهاته القادمة وتبقى أحداث السجل المحفوظة.", "delete" to "حذف", "appointments_sub" to "مواعيد البصمة القادمة في جدولك المحفوظ", "next_cycle" to "موعد البصمة القادم", "cycle_every" to "يتكرر كل $n يوماً",
            "help" to "مساعدة", "help_instructions_title" to "تعليمات الإعداد", "help_instructions_intro" to "أدخل تاريخ أول بصمة أو البصمة القادمة، وحدد دورة التكرار ووقت التنبيه.", "help_step_date" to "حدد تاريخ أول بصمة معتمد في تطبيق GöcBil.", "help_step_time" to "اختر وقت التنبيه.", "help_step_interval" to "اختر دورة كل 28 يوماً أو أسبوعية.", "help_step_sound" to "اختر نغمة التنبيه المناسبة وحدد الاهتزاز.", "help_step_save" to "احفظ الجدول لإنشاء المواعيد القادمة والتنبيهات تلقائياً.", "understood" to "فهمت ذلك",
            "choose_language" to "اختر اللغة", "alarm_options" to "إعدادات التنبيه", "alarm_settings_sub" to "الصوت والاهتزاز والوقت والتذكيرات", "permissions_sub" to "الإشعارات والمنبهات الدقيقة والظهور والبطارية", "alarm_sound" to "صوت التنبيه", "default_alarm_time" to "وقت التنبيه الافتراضي", "auto_stop_after" to "الإيقاف التلقائي بعد", "snooze_duration" to "مدة الغفوة", "high_priority" to "تنبيه أولوية قصوى", "remind_day_before" to "إشعار قبل بيوم", "repeat_until_done" to "تكرار تنبيه يوم البصمة حتى التوثيق", "remind_next_day" to "تذكير في اليوم الثاني", "off" to "إيقاف", "minutes_short" to "د", "customized" to "مخصّص",
            "app_name" to "منبه بصمة", "app_short_description" to "أداة شخصية لتنظيم مواعيد البصمة الدورية والتذكير بها.", "developer_name" to "ياسر أبو علاء", "developer_intro" to "مختص ببرمجة التطبيقات والمواقع الإلكترونية.", "developer_whatsapp_sub" to "مراسلة مباشرة وسريعة عبر واتساب", "developer_telegram_sub" to "حساب تلجرام والتحديثات", "developer_facebook_sub" to "صفحة المطور على فيسبوك", "permissions_note" to "يعتمد عمل التنبيه على أذونات Android وقيود تشغيل الخلفية في الجهاز. فعّل الأذونات اللازمة لضمان وصول التذكيرات.",
            "disclaimer_body" to "هذا التطبيق هو أداة تنظيم ومساعد تذكير شخصي مستقل غير رسمي، ولا يمثل أي جهة حكومية أو دائرة رسمية، يرجى التحقق دائما من المواعيد الحقيقية في تطبيق GöcBil."
        )
    }
    val approved = when (l) {
        "en" -> mapOf(
            "legacy_history_note" to "Saved as complete before detailed event logging was added; no exact confirmation time was stored.", "setup_assistant" to "Reminder setup assistant", "setup_assistant_sub" to "Review alarm permissions and settings", "schedule_name" to "Schedule name", "new_schedule" to "New schedule", "schedule_ended" to "This schedule has ended", "schedule_ended_sub" to "All configured appointments are complete. Edit the schedule to add more cycles, or create another schedule.", "extend_schedule" to "Extend schedule", "not_yet" to "Not available yet", "available_now" to "Available now",
            "confirmed_at" to "Confirmed at", "confirmation_method" to "Confirmation method", "note" to "Note", "note_optional" to "Optional note", "add_note" to "Add note", "edit_note" to "Edit note",
            "reliability_title" to "Alarm reliability status", "reliability_ready" to "Required alarm permissions are available", "reliability_no_schedule" to "No schedule saved", "reliability_exact_missing" to "Exact alarms are not allowed", "reliability_notifications_missing" to "Notifications are disabled", "reliability_fullscreen_missing" to "Full-screen alarm permission is off", "reliability_next" to "Next alarm", "reliability_last_schedule" to "Last rescheduled", "reliability_caveat" to "Device or manufacturer background limits may still affect delivery."
        )
        "tr" -> mapOf(
            "legacy_history_note" to "Ayrıntılı kayıt eklenmeden önce tamamlandı olarak saklanmış; kesin onay saati bulunmuyor.", "setup_assistant" to "Alarm kurulum yardımcısı", "setup_assistant_sub" to "Alarm izinlerini ve ayarlarını gözden geçirin", "schedule_name" to "Program adı", "new_schedule" to "Yeni program", "schedule_ended" to "Bu program sona erdi", "schedule_ended_sub" to "Belirlenen randevular tamamlandı. Döngü eklemek için programı düzenleyin veya yeni bir program oluşturun.", "extend_schedule" to "Programı uzat", "not_yet" to "Henüz uygun değil", "available_now" to "Şimdi uygun",
            "confirmed_at" to "Onay zamanı", "confirmation_method" to "Onay yöntemi", "note" to "Not", "note_optional" to "İsteğe bağlı not", "add_note" to "Not ekle", "edit_note" to "Notu düzenle",
            "reliability_title" to "Alarm güvenilirlik durumu", "reliability_ready" to "Gerekli alarm izinleri kullanılabilir", "reliability_no_schedule" to "Kayıtlı program yok", "reliability_exact_missing" to "Kesin alarmlara izin verilmedi", "reliability_notifications_missing" to "Bildirimler kapalı", "reliability_fullscreen_missing" to "Tam ekran alarm izni kapalı", "reliability_next" to "Sonraki alarm", "reliability_last_schedule" to "Son yeniden planlama", "reliability_caveat" to "Cihaz veya üretici arka plan kısıtlamaları teslimatı yine etkileyebilir."
        )
        else -> mapOf(
            "legacy_history_note" to "سُجل كمكتمل في إصدار سابق قبل حفظ تفاصيل الأحداث؛ لا يتوفر وقت تأكيد دقيق.", "setup_assistant" to "مساعد إعداد التنبيهات", "setup_assistant_sub" to "مراجعة أذونات التنبيه وإعداداته", "schedule_name" to "اسم الجدول", "new_schedule" to "جدول جديد", "schedule_ended" to "انتهت مواعيد هذا الجدول", "schedule_ended_sub" to "اكتملت جميع المواعيد المحددة. عدّل الجدول لإضافة دورات، أو أنشئ جدولاً آخر.", "extend_schedule" to "تمديد الجدول", "not_yet" to "لم يحن الموعد بعد", "available_now" to "الموعد متاح الآن",
            "confirmed_at" to "وقت التأكيد", "confirmation_method" to "طريقة التأكيد", "note" to "ملاحظة", "note_optional" to "ملاحظة اختيارية", "add_note" to "إضافة ملاحظة", "edit_note" to "تعديل الملاحظة",
            "reliability_title" to "حالة موثوقية التنبيهات", "reliability_ready" to "الأذونات الأساسية للتنبيه متاحة", "reliability_no_schedule" to "لا يوجد جدول محفوظ", "reliability_exact_missing" to "المنبهات الدقيقة غير مسموحة", "reliability_notifications_missing" to "الإشعارات معطلة", "reliability_fullscreen_missing" to "إذن التنبيه بملء الشاشة معطل", "reliability_next" to "التنبيه التالي", "reliability_last_schedule" to "آخر إعادة جدولة", "reliability_caveat" to "قد تؤثر قيود الخلفية الخاصة بالشركة المصنّعة على وصول التنبيه."
        )
    }
    return approved[k] ?: added[k] ?: extra[k] ?: values[k] ?: k
}

@Composable internal fun currentLanguage(): String = AlarmPrefs.language(LocalContext.current)
