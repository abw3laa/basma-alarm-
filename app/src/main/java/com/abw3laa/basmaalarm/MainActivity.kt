package com.abw3laa.basmaalarm
import android.app.*
import android.os.*
import android.content.*
import android.net.Uri
import android.webkit.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONObject
import java.net.URL
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

class MainActivity: ComponentActivity() {
 lateinit var web: WebView
 private val updatesRepo="https://raw.githubusercontent.com/abw3laa/basma-alarm-updates/main/update.json"
 override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState)
  window.statusBarColor=android.graphics.Color.rgb(15,37,55); window.navigationBarColor=android.graphics.Color.rgb(15,37,55)
  web=WebView(this); web.settings.javaScriptEnabled=true; web.settings.domStorageEnabled=true; web.settings.allowFileAccess=true; web.webViewClient=object:WebViewClient(){override fun shouldOverrideUrlLoading(v:WebView?,r:WebResourceRequest?):Boolean { val u=r?.url?.toString()?:return false; if(u.startsWith("https://basma.local/")){val p=u.substringAfterLast('/'); val theme=if(web.getTag(R_id_theme)=="dark")"dark" else "light"; load(p,theme); return true}; if(u.startsWith("https://www.facebook.com")||u.startsWith("https://t.me")||u.startsWith("https://wa.me")){startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(u)));return true};return false }}
  setContentView(web); load("welcome",if(resources.configuration.uiMode and 0x30==0x20)"dark" else "light"); checkUpdate()
 }
 private fun load(page:String,theme:String){ val safe=if(page in listOf("home","new","history","active","settings","welcome"))page else "home"; web.setTag(R_id_theme,theme); web.loadUrl("file:///android_asset/${safe}_${theme}.html") }
 override fun onBackPressed(){if(web.canGoBack())web.goBack() else super.onBackPressed()}
 private fun checkUpdate(){ thread { try { val json=JSONObject(URL(updatesRepo).readText()); val latest=json.optInt("versionCode",1); if(latest>packageManager.getPackageInfo(packageName,0).longVersionCode.toInt()) runOnUiThread { AlertDialog.Builder(this).setTitle("تحديث متوفر").setMessage("هل تريد تثبيت التحديث الآن؟").setNegativeButton("لاحقاً",null).setPositiveButton("تثبيت الآن"){_,_-> downloadAndInstall(json.optString("apkUrl")) }.show() } } catch(_:Exception){} } }
 private fun downloadAndInstall(url:String){ if(url.isBlank()||url=="https://www.mediafire.com/PUT_DOWNLOAD_LINK_HERE") {Toast.makeText(this,"رابط التحديث غير مضبوط بعد",Toast.LENGTH_LONG).show();return}; thread { try { val f=File(cacheDir,"basma-update.apk"); URL(url).openStream().use { input->FileOutputStream(f).use{input.copyTo(it)} }; runOnUiThread { if(android.os.Build.VERSION.SDK_INT>=26 && !packageManager.canRequestPackageInstalls()) startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:$packageName"))) else install(f) } } catch(e:Exception){runOnUiThread{Toast.makeText(this,"تعذر تنزيل التحديث",Toast.LENGTH_LONG).show()}} } }
 private fun install(f:File){val i=Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(f),"application/vnd.android.package-archive").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(i)}
 companion object { const val R_id_dummy=1 }
 private val R_id_theme=0x7f0a0001
}
