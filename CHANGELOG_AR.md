## 0.2.17 — استعادة آلية التنقل السابقة مع إبقاء تدقيق MAC للقراءة فقط
- أُعيدت آلية التنقل من v0.2.15 كما هي: اختيار التبويب من خلال روابط الراوتر الموجودة، ثم اختيار رابط الصفحة الفرعية المطابق للمسار، مع التحقق من المسار.
- أُلغي منطق v0.2.16 الجديد الذي كان يبحث عن التبويب العلوي في المستند الرئيسي ثم يختار رابطاً من الإطارات الفرعية؛ لأنه تسبب في توجيه القوائم إلى صفحات غير صحيحة.
- أُضيف مسار Security > MAC Address Filter Settings إلى `/firewall_mac.asp`.
- أُبقي تقرير جدولة MAC الموسع للقراءة فقط. لا توجد أوامر حفظ أو تعديل أو POST تلقائي.
- لم تُغيّر واجهة التطبيق أو المراقبة اللحظية أو منطق DHCP والحجوزات الثابتة.

## 0.2.15 — MAC Schedule Form Audit (Read Only)
- Expanded the current-page diagnostic to report form name/id/target/enctype and input event handlers.
- Added read-only capture of the MAC schedule page's `preSubmit`, `onSelected`, `onDel`, and `init` function source.
- Captures the ten policy selector options and checkbox checked state without invoking router functions.
- Continues to redact hidden/password/auth-related field values.
- Does not submit, delete, switch policy selections, or modify router settings.
- `.github/workflows/android.yaml` is unchanged.

## 0.2.14 — MAC Schedule Page Discovery
- Added a separate shortcut for `Security > MAC Address Filter Settings` using the router's visible menu labels because its exact page path has not yet been verified.
- Added label aliases for firmware menu variants.
- Added an explicit read-only notice: no MAC policy, time, or router setting is changed in this stage.
- Existing live upload/download monitoring, DHCP/Wireless parsing, and router navigation recovery are preserved.
- `.github/workflows/android.yaml` is unchanged.

## 0.2.13 — Navigation Recovery
- Restored the nested-frame fallback for router page navigation.
- Open the Tenda top-level section before navigating to its child page, keeping the left navigation aligned with the content frame.
- Retry the exact child-page link while the router menu loads; use the previous content-frame fallback if the link is not exposed.
- Apply the same parent-first navigation to DHCP scans, Wireless scans, and Static Assignment DHCP reads.
- No changes to subscriber live traffic parsing, dashboard UI, or router configuration writes.
- `.github/workflows/android.yaml` preserved unchanged.

