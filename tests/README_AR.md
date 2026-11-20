# اختبار محلل Traffic Statistics

الملف `traffic_statistics_sample.html` يحتوي بنية جدول مشابهة للجدول الذي أرسله المستخدم من صفحة Tenda Traffic Statistics بتاريخ 20-11-2026.

تم اختبار منطق JavaScript المستخرج من `MainActivity.java` باستخدام Node.js على هذا المثال، وكانت النتيجة:

- التعرف على أربعة صفوف IP.
- قراءة Uplink وDownlink بوحدة KB/s.
- قراءة Sent/Received Message.
- قراءة Sent/Received Bytes بوحدة MB.
- قراءة صف `192.168.0.105` بقيم Uplink=23.70 وDownlink=116.20 وSent=77.20 MB وReceived=867.40 MB.

هذا اختبار لوظيفة تحليل الجدول على عينة، وليس اختباراً لبناء APK أو اتصالاً حقيقياً بالراوتر. يجب إكمال اختبار GitHub Actions ثم اختبار التطبيق على الهاتف.
