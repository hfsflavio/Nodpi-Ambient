# nodpi Ambient

شاشة توقف (Screensaver / Daydream) لأندرويد 4.4+ : صور متبدلة + ساعة + طقس عين البيضاء + أزرار تنقل.

## البناء عبر GitHub
1. أنشئ مستودعاً جديداً (New repository) واتركه فارغاً.
2. ارفع **كل محتويات هذا المجلد** (Add file > Upload files) بما فيها المجلد المخفي `.github`.
   إذا لم يظهر المجلد `.github` بعد الرفع: Add file > Create new file، واكتب في الاسم
   `.github/workflows/build.yml` وألصق محتوى الملف.
3. افتح تبويب **Actions**، انتظر الدائرة الخضراء (3 إلى 6 دقائق). إن لم يبدأ البناء: Build APK > Run workflow.
4. افتح آخر عملية بناء ونزّل **nodpi-ambient-apk** من أسفل الصفحة (Artifacts)، فك الضغط وستجد `nodpi-ambient.apk`.

## التثبيت على التلفاز
انقل الملف بفلاشة وثبّته (فعّل "مصادر غير معروفة" من الإعدادات)، أو عبر adb:

    adb connect <IP_التلفاز>:5555
    adb install -r nodpi-ambient.apk

## التفعيل كشاشة توقف
إن وجدت في الإعدادات: Display / Screensaver (أو Daydream) فاختر **nodpi Ambient**.
وإن لم توجد، فعّلها بأوامر adb:

    adb shell settings put secure screensaver_components com.nodpi.ambient/.AmbientDream
    adb shell settings put secure screensaver_enabled 1
    adb shell settings put secure screensaver_activate_on_sleep 1
    adb shell settings put system screen_off_timeout 60000

(الأخير: تبدأ الشاشة بعد 60 ثانية من الخمول.) لتجربتها فوراً:

    adb shell am start -n com.android.systemui/.Somnambulator

كما يمكنك فتح تطبيق **nodpi Ambient** من قائمة التطبيقات لمعاينتها بملء الشاشة (زر Back للخروج).

## التحكم
- سهم يمين / OK : الصورة التالية — سهم يسار : السابقة — Back : خروج.

## التعديل
- الصور: مجلد `app/src/main/assets/photos` (ملفات 1.jpg, 2.jpg ... بالترتيب).
- المدينة، مدة الصورة، سرعة الانتقال، الخط: الثوابت أعلى ملف `AmbientView.java`.
- حجم الساعة والمواضع: دوال `buildUi` في نفس الملف (الأرقام مكتوبة لشاشة 1920 بكسل).
