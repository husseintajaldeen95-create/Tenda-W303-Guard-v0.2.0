## 0.2.26 — مطابقة حالة التصفير مع القراءة الحية ومنع النجاح الوهمي

- إصلاح حالة يمكن فيها أن تعرض البيانات المحفوظة `modified=true` على أنها «تم تصفيرها» بينما يعرض الراوتر وقت البداية الأصلي فعلياً.
- تمرير لقطة السياسات الحية المقروءة من الراوتر إلى محرك الجدولة، ومقارنة كل خانة برقمها مع حالتها المحفوظة قبل اتخاذ قرار التصفير أو الاستعادة.
- إذا كانت السياسة مسجلة كمصفّرة لكن الراوتر يعرض وقت البداية الأصلي في تاريخ العملية نفسه وقبل 23:55، تُسجل محاولة تصفير معلّقة وتُعاد المحاولة في الدورة الحالية.
- إذا كان الراوتر يعرض 00:00، لا تُقبل حالة التصفير إلا بعد مطابقة رقم الخانة وMAC والاسم ووقت النهاية والأيام وحالة Enable ووضع التصفية.
- إذا كان وقت البداية قيمة أخرى غير الوقت الأصلي أو 00:00، أو تغيرت هوية/حقول السياسة، تُسجل حالة تعارض ولا يكتب التطبيق فوق التعديل اليدوي.
- إذا تعذرت قراءة الخانة، لا يعرض التقرير نجاحاً مبنياً على حالة قديمة؛ بل يوضح أن الحالة الفعلية غير مؤكدة.
- حفظ تاريخ العملية الأصلي عند تأكيد تصفير معلّق متأخر، حتى لا تضيع استعادة متأخرة عند 23:55 أو بعد تغير التاريخ.
- تحديث اختيار رقم السياسة بإعادة استدعاء معالج الاختيار في كل قراءة، واشتراط ثلاث قراءات متتالية متطابقة قبل اعتماد بيانات الخانة.
- إبقاء مراقبة المشتركين، وتحديث قوائم DHCP/Wireless، والفحص الدوري المستهدف كل 15 ثانية، ومواعيد التصفير والاستعادة 23:55 دون تغيير في وظائفها.
- لم يتغير `.github/workflows/android.yml`.
- أضيفت 13 حالة اختبار لمنطق مطابقة الحالة الحية وإعادة المحاولة والتعارض؛ نجحت 61 اختبارات MAC و6 اختبارات IP (67 اختباراً إجمالاً). هذه اختبارات منطقية وليست اختباراً على راوتر حقيقي.

## 0.2.25 — تحديث الأجهزة المتصلة وفحص السياسات في الخلفية

- إضافة قارئ خلفي مستقل لقائمتي `DHCP Client List` و`Wireless Connection Status`.
- تحديث قائمتي DHCP وWi-Fi كل 15 ثانية عند توفر اتصال Wi-Fi المحلي والراوتر، حتى مع إغلاق واجهة التطبيق.
- حفظ آخر لقطة صحيحة من القائمتين محلياً وإرسالها إلى الواجهة عند فتح التطبيق أو أثناء ظهوره.
- إذا تعذّر التعرف على أحد الجدولين، يحتفظ التطبيق بآخر لقطة صحيحة له؛ وإذا تم التعرف على جدول فارغ فعلاً، يعرضه فارغاً.
- تبقى مراقبة `Traffic Statistics` على الفاصل الذي اختاره المستخدم دون تغيير.
- يبقى فحص سياسات MAC والتصفير والاستعادة على دورة 15 ثانية المستقلة، مع إعادة محاولة عمليات التصفير المعلّقة.
- تحسين قراءة كل خانة MAC: يعتمد التطبيق القراءة بعد ثبات قراءتين متتاليتين بعد اختيار رقم السياسة، لتقليل احتمال استخدام بيانات الخانة السابقة.
- إضافة زر «فحص تفاصيل سياسات MAC العشر» لعرض سبب تأهل كل خانة أو استبعادها وحالة التصفير والاستعادة والتعارض.
- لا تُدمج السياسات المتطابقة في الوقت؛ لكل خانة رقم مستقل.
- الاستعادة اليومية عند 23:55 كما هي.
- لم يتغير `.github/workflows/android.yml`.

## 0.2.24 — Catch-up للتصفير الفائت وإعادة محاولة العمليات المعلّقة

- إذا فات وقت بداية سياسة بسبب إغلاق التطبيق أو انقطاع الخدمة، يعالج التطبيق الموعد الفائت عند عودة الخدمة؛ ويشمل ذلك فتح التطبيق في اليوم التالي قبل وقت البداية المعتاد، بشرط أن يكون يوم السياسة الحالي ضمن نطاق أيامها وأن يكون يوم الموعد الفائت ضمن النطاق أيضاً.
- يحتفظ التطبيق بتاريخ آخر تصفير ناجح لكل رقم سياسة حتى لا يعيد تصفير سياسة سبق تنفيذها واستعادتها بعد منتصف الليل.
- يحفظ تاريخ بدء تتبع كل سياسة؛ لذلك لا يطبق سياسة أُضيفت حديثاً بأثر رجعي على يوم سابق لإضافتها.
- يعيد إدراج عملية التصفير المعلّقة في قائمة المحاولة بدلاً من تجاهلها إلى اليوم التالي.
- يفصل قرارات الاستعادة والتصفير لكل سياسة؛ وجود استعادة متأخرة أو معلّقة في خانة لا يمنع تصفير السياسات المستحقة في الخانات الأخرى.
- تبقى الاستعادة اليومية عند 23:55، ولا يبدأ تصفير جديد بعد هذا الوقت؛ إذا فات الموعد قرب نهاية اليوم، يعالج عند أول دورة بعد منتصف الليل إذا ظل نطاق الأيام مؤهلاً.
- أضيفت اختبارات لحالات التأخر إلى اليوم التالي، وعدم تكرار التصفير المنفذ، واحترام تاريخ بدء تتبع السياسة، واستقلال عمليات الاستعادة والتصفير؛ نجح 48 اختباراً لمنطق MAC و6 اختبارات IP (54 اختباراً).
- لم يتغير `.github/workflows/android.yml`؛ لا يلزم استبداله.
- يتطلب بناء APK واختبار عمليات الكتابة على راوتر Tenda فعلياً قبل اعتماد النسخة.

## 0.2.23 — إعادة فحص جميع سياسات MAC كل 15 ثانية دون إيقاف تلقائي

- إعادة قراءة خانات سياسات MAC من الراوتر في كل دورة أتمتة، بفاصل مستهدف 15 ثانية بين بدايات الدورات؛ لا يكتفي التطبيق بلقطة السياسات الأولى.
- إذا لم توجد سياسة مؤهلة، تبقى أتمتة MAC مفعّلة وتستمر الخدمة في الفحص الدوري؛ لا تُطفأ تلقائياً بسبب أن العدد المؤهل صفر.
- تُكتشف السياسة الجديدة أو التي أصبحت مؤهلة لاحقاً في الفحص التالي، دون الحاجة إلى إيقاف الأتمتة وإعادة تشغيلها.
- دمج القراءة الجديدة حسب رقم الخانة 1–10، مع الحفاظ على وقت البداية الأصلي وحالة `modified/pending/conflict` لكل سياسة سبق أن عالجها التطبيق.
- لا تُستبدل بيانات الاستعادة المحفوظة بوقت `00:00` المقروء بعد التصفير؛ تبقى كل سياسة ذات رقم مختلف مستقلة حتى لو تطابق وقتها.
- إبقاء مراقبة المشتركين، وإعادة الاتصال، والتصفير عند وقت البداية، والاستعادة اليومية 23:55 دون تغيير في منطقها.
- ملف `.github/workflows/android.yml` لم يتغير؛ لا يلزم استبداله إذا كان المستودع يستخدم ملف 0.2.22 الحالي.
- أضيفت اختبارات لبقاء المهمة فعالة عند صفر سياسات مؤهلة، ولعدّ الخانات العشر المستقلة.
- لم يتوفر Android SDK/Gradle في بيئة إعداد هذا المصدر؛ يلزم بناء APK عبر GitHub Actions واختباره على راوتر W303R فعلياً.

## 0.2.22 — Restore Original MAC Start Times at 23:55

- Changed the daily restoration threshold from 23:00 to 23:55 using the router's hour and minute.
- Allowed policies with start times from 23:00 through 23:54 to be zeroed at their own start time; no new zeroing starts at or after 23:55.
- Kept per-policy zeroing times, policy-number identity, same-time policy counting, background monitoring, reconnection, and pending-operation recovery unchanged.
- Preserved the existing `.github/workflows/android.yml` workflow file without modifying its contents.
- Added regression checks for 23:54 (not yet restore), 23:55 (restore), and times after 23:55.

## 0.2.21 — Background MAC Automation + Subscriber Monitoring

- نقل محرك تصفير واستعادة سياسات MAC من `MainActivity` إلى `RouterBackgroundService` حتى يستمر بعد إغلاق واجهة التطبيق.
- جعل خدمة الخلفية الواحدة تنفذ مراقبة Traffic Statistics وأتمتة MAC معاً، وتبقى منتظرة عند انقطاع Wi-Fi أو عدم استجابة الراوتر.
- الحفاظ على وقت البداية الأصلي لكل رقم سياسة؛ هوية السياسة هي رقم الخانة، وليس وقت البداية. السياسات المختلفة ذات الوقت المتطابق تُقرأ وتُحتسب وتُصفّر وتُستعاد كل واحدة على حدة.
- إضافة ملخص يوضح عدد خانات الراوتر، وعدد السياسات المؤهلة، وأرقامها وأوقات بدايتها.
- تحسين التعامل مع الحفظ غير المؤكد: تبقى العملية `pending` حتى يعاد التحقق منها، ولا تُمسح الحالة لمجرد انقطاع الرد.
- التحقق بعد الحفظ من MAC ورقم السياسة ووقت البداية والاسم ووقت النهاية والأيام وحالة Enable ووضع Filter Mode.
- عند طلب إيقاف أتمتة MAC، تبقى الخدمة عاملة حتى تستعيد السياسات التي سبق أن عدّلتها؛ ولا تتوقف مراقبة Traffic Statistics إلا بطلب مستقل.
- تخزين بيانات HTTP Authentication في WebViewDatabase حتى تتمكن WebView الخلفية من إعادة استخدام جلسة الدخول التي أنشأها المستخدم داخل التطبيق.
- توسيع إعادة التشغيل بعد إقلاع الهاتف لتشمل أتمتة MAC وحالات الاستعادة المعلّقة.
- إضافة اختبار يثبت أن ست سياسات بأرقام مختلفة تُحتسب ستاً حتى لو تشابهت أوقات بدايتها؛ تكرار رقم الخانة وحده هو الذي يُزال.
- لم يُبنَ APK في بيئة إعداد هذا المصدر ولم يُختبر إرسال POST على الراوتر الحقيقي بعد؛ يلزم GitHub Actions واختبار ميداني قبل اعتماد التنفيذ النهائي.

## 0.2.20 — Background Traffic Monitor and Network Recovery

- Added an Android foreground service with an independent WebView for `QoS > Traffic Statistics`.
- Watches Wi-Fi transport and the local router endpoint; it does not require validated WAN Internet.
- Keeps the service alive while Wi-Fi is disconnected and retries router reachability after Wi-Fi/router recovery.
- Retries missing Traffic Statistics tables instead of stopping after three failed reads.
- Persists the latest traffic snapshot and its capture time for display when the Activity returns.
- Adds an ongoing notification with a Stop action, CPU/Wi-Fi locks while connected to the router, releases locks while waiting for Wi-Fi, and boot-time restart when the user had enabled monitoring.
- Preserves the existing Activity traffic parser, DHCP/Wireless readers, static IP reservation logic, MAC schedule engine, navigation mappings, and `.github/workflows/android.yaml`.
- MAC schedule automation remains Activity/WebView-bound; this release does not claim it survives Force stop or Activity task removal.
- APK build and real-router background behavior still require GitHub Actions and on-device verification.

## 0.2.19 — Restore v0.2.17 router navigation mapping

- Restored the exact `Security > MAC Address Filter Settings` route mapping from v0.2.17: `/firewall_mac.asp`.
- Kept the v0.2.18 MAC schedule auto-zero/restore implementation unchanged.
- No other router-page route, menu label, traffic-monitoring function, DHCP/Wireless reader, or automation behavior was edited in this patch.
- Source correction only; APK build and real-router navigation still require device verification.

## 0.2.18 — MAC Schedule Auto Zero and Restore

- Kept the v0.2.15 navigation implementation unchanged.
- Added a separate MAC schedule automation panel; it does not replace or redesign existing router menus.
- Reads the ten MAC policy slots through the router's existing `curNum` selector and `onChangeNum` handler.
- Captures the original start time, MAC address, policy number, enabled state, weekday range, end time, and filter mode before any write.
- Supports only the router's `Permit Only` mode and enabled policies with a valid MAC and a non-zero start time.
- At each policy's own start time, changes only `sh` and `sm` to `00:00`; at 23:00 it restores that policy's original `sh` and `sm`.
- Keeps end time, MAC address, policy name, days, Enable checkbox, and global Filter Mode unchanged.
- Uses the router's Time Settings page as the clock source; if the clock cannot be parsed, no policy is changed.
- Uses the firmware's existing `/goform/SafeMacFilter` POST and `preSubmit`/OK handler, then reloads and verifies each changed policy.
- Persists original values and pending operations so an interrupted write can be recovered when the app reconnects.
- Adds regression tests for per-policy start times, weekday ranges, and 23:00 restoration.
- Automation requires the app to remain running and connected to the router; Android force-stop or process termination can prevent a scheduled action.

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

