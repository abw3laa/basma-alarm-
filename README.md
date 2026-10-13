# منبه بصمة — Android/Kotlin

مشروع Android أولي مبني على ملفات التصميم المرفقة، مع موارد كل شاشة فاتحة/داكنة وأيقونة التطبيق. الحزمة: `com.abw3laa.basmaalarm`، الحد الأدنى Android 6.0.

## GitHub Actions
Workflow `.github/workflows/android.yml` runs on each push to `main` and can also be run manually from Actions. It always builds a **Debug CI APK** for build verification; that APK is not a production release. A stable, signed Release APK is produced only after these repository Actions secrets are configured:

- `ANDROID_KEYSTORE_BASE64` — base64-encoded JKS/PKCS12 signing keystore
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The signed release artifact is named `basma-release-signed`. Back up the keystore and passwords securely. Never commit them or regenerate the signing key: every future update must use the same key and application ID. Actions builds the artifact; this workflow does not publish a public GitHub Release.

## التحديثات (مستودع basma-alarm-updates)
أضف `update.json` بالصيغة التالية، وضع رابط تنزيل APK حقيقي في `apkUrl`:
```json
{"versionCode":2,"versionName":"1.0.1","apkUrl":"https://www.mediafire.com/file/.../basma.apk"}
```
يراجع التطبيق manifest عند بدء التشغيل ويعرض موافقة التثبيت من المصدر عند ضغط «تثبيت الآن». Android يتطلب موافقة المستخدم لكل تثبيت؛ التنزيل لا يعني تثبيتاً صامتاً. يجب توقيع جميع الإصدارات بنفس المفتاح وبـ`applicationId` و`versionCode` متوافقين. لا ترفع مفتاح التوقيع إلى Git.

## نطاق التنفيذ والاختبار
ملفات الشاشات وأيقونة التطبيق الأصلية مضمنة. هذا هيكل Android أولي، وليس تطبيق منبه إنتاجياً مُتحققاً منه: جدولة المنبهات الأصلية، حفظ التذكيرات، اكتمال ترجمة التركية والإنجليزية، الأذونات وروابط المطور ما زالت تحتاج التنفيذ والاختبار على جهاز. بعض ملفات HTML تعتمد على Tailwind والخطوط الخارجية، لذلك قد يحتاج التنسيق إلى الإنترنت. لا يوجد Android SDK في بيئة إعداد المشروع الحالية؛ سيتم التحقق من البناء عبر Actions.
