# اختبار رجوع التنقل — v0.2.17

## الهدف
التأكد من أن اختصارات التطبيق تستخدم آلية التنقل السابقة في v0.2.15، وأن كل اختصار يفتح المسار الصحيح داخل واجهة الراوتر.

## الاختصارات والمسارات المتوقعة

| الاختصار | المسار المتوقع |
|---|---|
| Advanced > DHCP Client List | `/lan_dhcp_clients.asp` |
| Wireless > Wireless Connection Status | `/wireless_state.asp` |
| Wireless > Access Control | `/wireless_filter.asp` |
| Security > URL Filter Settings | `/firewall_urlfilter.asp` |
| Security > MAC Address Filter Settings | `/firewall_mac.asp` |
| Tools > Time Settings | `/system_hostname.asp` |
| QoS > Bandwidth Control | `/net_tc.asp` |
| QoS > Traffic Statistics | `/sys_iptAccount.asp` |

## خطوات الاختبار
1. افتح اتصال الراوتر وسجّل الدخول.
2. اضغط اختصاراً واحداً فقط وانتظر انتهاء التنقل.
3. تحقق من عنوان الصفحة في تقرير التشخيص.
4. تحقق من أن عنصر القائمة الجانبية المطابق هو المحدد.
5. كرر الخطوات لكل اختصار في الجدول.
6. لا تضغط OK أو Clear أثناء الاختبار؛ جميع الاختبارات هنا للتنقل والقراءة فقط.

## معيار النجاح
يجب أن يطابق مسار الصفحة المسار المتوقع، وألا ينتقل الاختصار إلى صفحة أخرى. يجب أن تبقى آلية التنقل هي دوال v0.2.15 (`clickRouterPathLink` و`navigateRouterSectionPath`) دون دوال v0.2.16 (`clickRouterTopTab` و`clickRouterChildMenu`).
