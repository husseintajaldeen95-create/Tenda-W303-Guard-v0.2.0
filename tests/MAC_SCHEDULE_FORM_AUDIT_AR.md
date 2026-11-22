# تدقيق نموذج جدولة MAC — v0.2.16 (قراءة فقط)

## الغرض
استخراج تفاصيل النموذج الحقيقي ودوال JavaScript المسؤولة عن تغيير السياسة وحفظها، قبل كتابة أي أتمتة.

## ما يقرأه التشخيص
- مسار `/firewall_mac.asp` وخصائص النموذج: الاسم والمعرّف وaction وmethod وtarget وenctype.
- حقول النموذج ومعرّفاتها وقيمها غير الحساسة وحالة checkbox.
- أحداث `onclick` و`onchange` للحقول، ومصدر معالجاتها إن وجد.
- مصدر الدوال `preSubmit` و`onSelected` و`onDel` و`init` دون تشغيلها.
- خيارات `curNum` للسياسات من 1 إلى 10.
- أسماء الحقول المخفية فقط؛ قيم hidden/password/auth/token تبقى محجوبة.

## ما لا يفعله
- لا يغيّر `curNum` ولا يبدّل السياسة المختارة.
- لا يشغّل `preSubmit` أو `onDel` أو `init` أو `onSelected`.
- لا يضغط OK أو Clear أو Cancel.
- لا يرسل POST أو أي طلب تعديل إلى الراوتر.

## طريقة الاختبار
1. افتح صفحة MAC Address Filter Settings.
2. اضغط «فحص حقول الصفحة الحالية».
3. اضغط «نسخ تقرير التشخيص» وأرسل التقرير كاملاً.


## إضافات v0.2.16
- `macSchedule.currentPolicy`: السياسة المحددة حالياً مع Filter Mode وPolicy Name وMAC segments ووقت البداية والنهاية والأيام وحالة Enable.
- `macSchedule.policyChangeAttribute` و`macSchedule.policyChangeHandler`: معالج تغيير رقم السياسة `curNum` إن كان موجوداً.
- `macSchedule.controls[].onChangeHandler`: مصدر معالج التغيير لكل عنصر، دون تشغيله.

ما زال الفحص للقراءة فقط؛ لا تُشغّل دوال الراوتر ولا تضغط OK أو Clear ولا تُرسل POST.
