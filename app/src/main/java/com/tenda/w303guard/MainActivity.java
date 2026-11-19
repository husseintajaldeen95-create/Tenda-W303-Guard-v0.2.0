package com.tenda.w303guard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.InetAddress;
import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashSet;

/**
 * First-stage Android companion for the legacy Tenda W303R web interface.
 * It deliberately uses the router's own UI in a local-only WebView until the
 * exact V5.07.46_en form fields and write semantics have been verified.
 */
public class MainActivity extends Activity {
    private static final String DEFAULT_ROUTER = "192.168.0.1";
    private static final int ORANGE = Color.rgb(232, 117, 0);
    private static final int DARK = Color.rgb(31, 41, 55);
    private static final int MUTED = Color.rgb(90, 99, 110);
    private static final int BORDER = Color.rgb(224, 228, 233);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private EditText routerIp;
    private WebView webView;
    private TextView statusView;
    private TextView pageView;
    private TextView devicesView;
    private TextView diagnosticView;
    private TextView scanMetaView;
    private EditText searchDevices;
    private Button scanAllButton;
    private LinearLayout deviceCards;
    private String filterMode = "all";
    private boolean scanInProgress = false;
    private Runnable pendingReadCompletion;
    private SharedPreferences preferences;
    private String activeHost = DEFAULT_ROUTER;
    private String lastDiagnostic = "";
    private String lastDevices = "";
    private final Map<String, JSONObject> dhcpSnapshot = new LinkedHashMap<>();
    private final Map<String, JSONObject> wirelessSnapshot = new LinkedHashMap<>();
    private boolean hasDhcpSnapshot = false;
    private boolean hasWirelessSnapshot = false;
    private long dhcpSnapshotAt = 0L;
    private long wirelessSnapshotAt = 0L;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("tenda_w303_guard", MODE_PRIVATE);
        buildUi();
        configureWebView();
    }

    private void buildUi() {
        ScrollView outer = new ScrollView(this);
        outer.setFillViewport(true);
        outer.setBackgroundColor(Color.rgb(247, 248, 250));
        outer.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(20));
        outer.addView(root);

        TextView title = text("Tenda W303 Guard", 23, true, DARK);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());
        TextView subtitle = text("إدارة محلية لراوتر Tenda W303R", 14, false, MUTED);
        subtitle.setGravity(Gravity.CENTER);
        root.addView(subtitle, matchWrap());
        gap(root, 12);

        LinearLayout connection = card(root);
        connection.addView(text("عنوان الراوتر", 14, true, DARK), matchWrap());
        routerIp = new EditText(this);
        routerIp.setSingleLine(true);
        routerIp.setText(preferences.getString("router_ip", DEFAULT_ROUTER));
        routerIp.setTextDirection(View.TEXT_DIRECTION_LTR);
        routerIp.setInputType(InputType.TYPE_CLASS_PHONE);
        routerIp.setPadding(dp(12), dp(8), dp(12), dp(8));
        connection.addView(routerIp, new LinearLayout.LayoutParams(-1, dp(48)));
        Button connect = button("اتصال وفتح صفحة الراوتر", true);
        connect.setOnClickListener(v -> connectToRouter());
        connection.addView(connect, matchWrap());
        statusView = text("الحالة: لم يتم الاتصال بعد", 13, false, MUTED);
        connection.addView(statusView, matchWrap());

        gap(root, 10);
        LinearLayout nav = card(root);
        nav.addView(text("اختصارات صفحات الراوتر", 15, true, DARK), matchWrap());
        addNavButton(nav, "قائمة أجهزة DHCP", () -> openRouterSection("Advanced", "DHCP Client List"));
        addNavButton(nav, "الأجهزة اللاسلكية المتصلة", () -> openRouterSection("Wireless", "Wireless Connection Status"));
        addNavButton(nav, "تصفية MAC وجدولة الإنترنت", () -> openRouterSection("Security", "MAC Address Filter Settings"));
        addNavButton(nav, "ضبط وقت الراوتر", () -> openRouterSection("Tools", "Time Settings"));
        addNavButton(nav, "QoS وتحديد السرعة", () -> openRouterSection("QoS", "Bandwidth Control"));
        addNavButton(nav, "الرجوع في صفحات الراوتر", () -> {
            if (webView != null && webView.canGoBack()) webView.goBack();
        });

        gap(root, 10);
        LinearLayout schedule = card(root);
        schedule.addView(text("مساعد الجدولة: 4:00 مساءً إلى 12:00 منتصف الليل", 15, true, DARK), matchWrap());
        schedule.addView(text(
                "دليل W303R يوضح أن MAC Address Filter يدعم وضع Forbid only أو Permit only، ورقم سياسة، وعنوان MAC، ووقت البداية والنهاية، وأيام الأسبوع، وخيار Enable. " +
                "إذا كان المطلوب منع الجهاز خارج فترة 16:00–24:00، فالخطة المرشحة هي Forbid only بوقت 00:00–16:00 لكل جهاز، مع اختيار جميع الأيام. " +
                "هذا شرح للقاعدة وليس تطبيقاً تلقائياً لها؛ اختبر جهازاً واحداً أولاً، ولا تستخدم 00:00–00:00 لأنها موثقة كقاعدة تعمل طوال 24 ساعة.",
                13, false, MUTED), matchWrap());
        schedule.addView(text(
                "مهم: لا تخلط بين Wireless Access Control (منع الاتصال بالواي فاي) وبين MAC Address Filter ضمن Security settings (التحكم بوصول الجهاز إلى الإنترنت).",
                13, true, DARK), matchWrap());

        gap(root, 10);
        LinearLayout browserCard = card(root);
        browserCard.addView(text("واجهة الراوتر الأصلية", 15, true, DARK), matchWrap());
        pageView = text("لم تُفتح صفحة الراوتر بعد.", 12, false, MUTED);
        browserCard.addView(pageView, matchWrap());
        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);
        browserCard.addView(webView, new LinearLayout.LayoutParams(-1, dp(480)));
        LinearLayout browserActions = new LinearLayout(this);
        browserActions.setOrientation(LinearLayout.HORIZONTAL);
        Button reloadPage = button("تحديث الصفحة", false);
        reloadPage.setOnClickListener(v -> {
            if (webView.getUrl() == null) toast("اتصل بالراوتر أولاً.");
            else { status("أعيد تحميل صفحة الراوتر..."); webView.reload(); }
        });
        Button routerHome = button("الصفحة الرئيسية", false);
        routerHome.setOnClickListener(v -> {
            if (webView.getUrl() == null) connectToRouter();
            else webView.loadUrl("http://" + activeHost + "/");
        });
        browserActions.addView(reloadPage, new LinearLayout.LayoutParams(0, -2, 1));
        browserActions.addView(routerHome, new LinearLayout.LayoutParams(0, -2, 1));
        browserCard.addView(browserActions, matchWrap());
        TextView webHint = text("سجّل الدخول داخل صفحة الراوتر نفسها. بعد فتح صفحة DHCP أو Wireless سيحاول التطبيق قراءة الجدول تلقائياً. لا تكتب كلمة المرور خارج صفحة الإدارة المحلية.", 12, false, MUTED);
        browserCard.addView(webHint, matchWrap());

        gap(root, 10);
        LinearLayout tools = card(root);
        tools.addView(text("إدارة المشتركين", 17, true, DARK), matchWrap());
        scanAllButton = button("تحديث القائمتين تلقائياً", true);
        scanAllButton.setOnClickListener(v -> refreshBothLists());
        tools.addView(scanAllButton, matchWrap());
        scanMetaView = text("لم يبدأ الفحص بعد. اتصل بالراوتر ثم حدّث القائمتين.", 12, false, MUTED);
        tools.addView(scanMetaView, matchWrap());
        devicesView = text("لا توجد بيانات بعد. اضغط تحديث القائمتين.", 13, true, DARK);
        devicesView.setPadding(0, dp(8), 0, dp(8));
        tools.addView(devicesView, matchWrap());

        searchDevices = new EditText(this);
        searchDevices.setSingleLine(true);
        searchDevices.setTextSize(14);
        searchDevices.setHint("ابحث بالاسم أو IP أو MAC");
        searchDevices.setInputType(InputType.TYPE_CLASS_TEXT);
        searchDevices.setPadding(dp(10), dp(7), dp(10), dp(7));
        tools.addView(searchDevices, new LinearLayout.LayoutParams(-1, dp(46)));
        searchDevices.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { renderDeviceCards(currentMacs()); }
            @Override public void afterTextChanged(Editable e) { }
        });

        LinearLayout filters = new LinearLayout(this);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        Button allFilter = button("الكل", false);
        Button wifiFilter = button("ظاهر في Wi-Fi", false);
        Button dhcpFilter = button("DHCP فقط", false);
        allFilter.setOnClickListener(v -> { filterMode = "all"; renderDeviceCards(currentMacs()); });
        wifiFilter.setOnClickListener(v -> { filterMode = "wifi"; renderDeviceCards(currentMacs()); });
        dhcpFilter.setOnClickListener(v -> { filterMode = "dhcp"; renderDeviceCards(currentMacs()); });
        filters.addView(allFilter, new LinearLayout.LayoutParams(0, -2, 1));
        filters.addView(wifiFilter, new LinearLayout.LayoutParams(0, -2, 1));
        filters.addView(dhcpFilter, new LinearLayout.LayoutParams(0, -2, 1));
        tools.addView(filters, matchWrap());

        deviceCards = new LinearLayout(this);
        deviceCards.setOrientation(LinearLayout.VERTICAL);
        tools.addView(deviceCards, matchWrap());

        LinearLayout diagnostics = card(root);
        diagnostics.addView(text("أدوات التشخيص", 15, true, DARK), matchWrap());
        Button read = button("قراءة الأجهزة من الصفحة الحالية", false);
        read.setOnClickListener(v -> readDevicesFromPage());
        diagnostics.addView(read, matchWrap());
        Button inspect = button("فحص حقول الصفحة الحالية", false);
        inspect.setOnClickListener(v -> inspectCurrentPage());
        diagnostics.addView(inspect, matchWrap());
        Button copy = button("نسخ تقرير التشخيص", false);
        copy.setOnClickListener(v -> copyDiagnostic());
        diagnostics.addView(copy, matchWrap());
        diagnosticView = text("تقرير الصفحة: لم يتم الفحص بعد.", 11, false, MUTED);
        diagnosticView.setTextIsSelectable(true);
        diagnostics.addView(diagnosticView, matchWrap());

        TextView footer = text("الإصدار 0.2.3: لوحة مشتركين، بحث وتصفية، وتحديث متتابع لقائمتي DHCP وWi-Fi. الحظر وتحديد السرعة غير مفعّلين بعد حتى نتحقق من نماذج الكتابة في الراوتر.", 12, true, MUTED);
        footer.setPadding(dp(4), dp(12), dp(4), dp(4));
        root.addView(footer, matchWrap());

        setContentView(outer);
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSavePassword(false);
        CookieManager.getInstance().setAcceptCookie(true);
        webView.addJavascriptInterface(new RouterBridge(), "TendaBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (isAllowedRouterUri(uri)) return false;
                status("تم منع فتح عنوان خارج شبكة الراوتر المحلية.");
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Uri uri = Uri.parse(url);
                if (isAllowedRouterUri(uri)) return false;
                status("تم منع فتح عنوان خارج شبكة الراوتر المحلية.");
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (isAllowedRouterUri(request.getUrl())) return null;
                return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                if (pageView != null) pageView.setText("جارٍ تحميل: " + url);
                status("جارٍ الاتصال بواجهة الراوتر...");
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                pageView.setText("الصفحة الحالية: " + url);
                if (!isAllowedRouterUri(Uri.parse(url))) return;
                status("تم تحميل صفحة الراوتر. إذا ظهرت شاشة الدخول، سجّل الدخول من داخلها.");
                // Read supported tables automatically after the router page settles.
                handler.postDelayed(() -> {
                    if (webView != null && webView.getUrl() != null) {
                        inspectCurrentPage();
                        readDevicesFromPage();
                    }
                }, 850);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) {
                    status("تعذر تحميل صفحة الراوتر: " + error.getDescription() + ". تأكد من اتصال الهاتف بشبكة الراوتر وعنوانه.");
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                super.onReceivedHttpError(view, request, response);
                if (request.isForMainFrame()) status("أعاد الراوتر خطأ HTTP: " + response.getStatusCode());
            }
        });
    }

    private void connectToRouter() {
        String ip = routerIp.getText().toString().trim();
        if (!isPrivateIpv4(ip)) {
            toast("أدخل عنوان IPv4 محلياً صحيحاً، مثل 192.168.0.1");
            return;
        }
        activeHost = ip;
        preferences.edit().putString("router_ip", ip).apply();
        lastDiagnostic = "";
        lastDevices = "";
        dhcpSnapshot.clear();
        wirelessSnapshot.clear();
        hasDhcpSnapshot = false;
        hasWirelessSnapshot = false;
        dhcpSnapshotAt = 0L;
        wirelessSnapshotAt = 0L;
        if (deviceCards != null) deviceCards.removeAllViews();
        devicesView.setText("لم تتم قراءة الأجهزة بعد.");
        diagnosticView.setText("سيظهر تقرير الصفحة بعد تحميل واجهة الراوتر.");
        webView.loadUrl("http://" + activeHost + "/");
    }

    private boolean isAllowedRouterUri(Uri uri) {
        if (uri == null || !"http".equalsIgnoreCase(uri.getScheme())) return false;
        return activeHost.equals(uri.getHost());
    }

    private boolean isPrivateIpv4(String ip) {
        if (ip == null || !ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return false;
        try {
            byte[] b = InetAddress.getByName(ip).getAddress();
            if (b.length != 4) return false;
            int a = b[0] & 255, second = b[1] & 255;
            return a == 10 || (a == 172 && second >= 16 && second <= 31)
                    || (a == 192 && second == 168) || (a == 169 && second == 254);
        } catch (Exception e) {
            return false;
        }
    }

    private void openRouterSection(String parentLabel, String childLabel) {
        if (webView.getUrl() == null) {
            toast("اتصل بالراوتر وسجّل الدخول أولاً.");
            return;
        }
        clickRouterLabel(parentLabel, parentFound -> handler.postDelayed(() ->
                clickRouterLabel(childLabel, childFound -> {
                    if (parentFound && childFound) status("تم فتح " + parentLabel + " ثم " + childLabel + ".");
                    else if (parentFound) status("فُتح القسم الرئيسي، لكن لم أجد " + childLabel + ". افتحه يدوياً ثم أعد التشخيص.");
                    else status("لم أجد رابط " + parentLabel + ". تأكد من تسجيل الدخول أو افتح القسم يدوياً.");
                }), 1200));
        status("أحاول فتح: " + parentLabel + " ثم " + childLabel + " — تأكد من تسجيل الدخول.");
    }

    private interface BooleanResult { void onResult(boolean value); }

    private void clickRouterLabel(String label, BooleanResult callback) {
        String script = "(function(){" +
                "function docs(){var a=[document];for(var i=0;i<a.length;i++){try{var fs=a[i].querySelectorAll('frame,iframe');for(var j=0;j<fs.length;j++){try{var d=fs[j].contentDocument;if(d&&a.indexOf(d)<0)a.push(d);}catch(e){}}}catch(e){}}return a;}" +
                "function norm(s){return (s||'').replace(/\\s+/g,' ').trim().toLowerCase();}" +
                "var ds=docs(),q=" + JSONObject.quote(label.toLowerCase(Locale.ROOT)) + ";" +
                "for(var i=0;i<ds.length;i++){var es=ds[i].querySelectorAll('a,button,input[type=button],input[type=submit],area,[onclick],[role=button],img[alt],[title]');" +
                "for(var j=0;j<es.length;j++){var e=es[j],t=norm((e.innerText||e.textContent||e.value||'')+' '+(e.title||'')+' '+(e.alt||''));" +
                "if(t===q||t.indexOf(q)>=0){var target=e.closest?e.closest('a,button,[onclick],[role=button]'):null;if(!target)target=e;try{target.scrollIntoView({block:'center'});}catch(x){};target.click();return true;}}}return false;})()";
        webView.evaluateJavascript(script, result -> callback.onResult("true".equals(result)));
    }

    /**
     * Read both router tables in sequence using the router's own navigation.
     * The operation is read-only: it only opens the two list pages and parses them.
     */
    private void refreshBothLists() {
        if (webView == null || webView.getUrl() == null) {
            toast("اتصل بالراوتر وسجّل الدخول أولاً.");
            return;
        }
        if (scanInProgress) {
            toast("الفحص جارٍ بالفعل، انتظر اكتماله.");
            return;
        }
        scanInProgress = true;
        if (scanAllButton != null) scanAllButton.setEnabled(false);
        if (scanMetaView != null) scanMetaView.setText("المرحلة 1 من 2: فتح قائمة DHCP...");
        status("بدأ تحديث القائمتين. لن يتم تغيير أي إعداد في الراوتر.");
        clickRouterLabel("Advanced", advancedFound -> {
            if (!advancedFound) {
                finishFullScan(false, "لم أجد تبويب Advanced. افتحه يدوياً ثم أعد الفحص.");
                return;
            }
            handler.postDelayed(() -> clickRouterLabel("DHCP Client List", found -> {
                if (!found) {
                    finishFullScan(false, "فُتح Advanced، لكن لم أجد رابط DHCP Client List.");
                    return;
                }
                handler.postDelayed(() -> readDevicesFromPage(() -> {
                    if (scanMetaView != null) scanMetaView.setText("اكتملت قراءة DHCP. المرحلة 2 من 2: فتح قائمة Wi-Fi...");
                    handler.postDelayed(this::scanWirelessStage, 650);
                }), 1100);
            }), 1200);
        });
    }

    private void scanWirelessStage() {
        clickRouterLabel("Wireless", found -> {
            if (!found) {
                finishFullScan(false, "قُرئت DHCP، لكن لم أجد تبويب Wireless. افتح Wireless Connection Status يدوياً.");
                return;
            }
            handler.postDelayed(() -> clickRouterLabel("Wireless Connection Status", childFound -> {
                if (!childFound) {
                    finishFullScan(false, "قُرئت DHCP، لكن لم أجد Wireless Connection Status.");
                    return;
                }
                handler.postDelayed(() -> readDevicesFromPage(() -> {
                    boolean complete = hasDhcpSnapshot && hasWirelessSnapshot;
                    finishFullScan(complete, complete
                            ? "اكتملت قراءة DHCP وWi-Fi ودمج الأجهزة بنجاح."
                            : "انتهت المحاولة، لكن إحدى القائمتين لم تُقرأ. راجع آخر النتائج أو افتح الصفحة يدوياً.");
                }), 1100);
            }), 1200);
        });
    }

    private void finishFullScan(boolean success, String message) {
        scanInProgress = false;
        if (scanAllButton != null) scanAllButton.setEnabled(true);
        if (scanMetaView != null) scanMetaView.setText(message + "\nآخر قراءة DHCP: " +
                (hasDhcpSnapshot ? formatTime(dhcpSnapshotAt) : "غير متاحة") +
                " | آخر قراءة Wi-Fi: " + (hasWirelessSnapshot ? formatTime(wirelessSnapshotAt) : "غير متاحة"));
        status(message);
        if (success) toast("تم تحديث قائمتي المشتركين بنجاح.");
    }

    private void finishPendingRead() {
        Runnable callback = pendingReadCompletion;
        pendingReadCompletion = null;
        if (callback != null) callback.run();
    }

    private void readDevicesFromPage() {
        readDevicesFromPage(null);
    }

    private void readDevicesFromPage(Runnable completion) {
        if (completion != null) pendingReadCompletion = completion;
        readDevicesFromPageAttempt(0);
    }

    /**
     * The W303R V5.07.46 DHCP and wireless pages are rendered in same-origin
     * frames. Retry briefly because Android WebView may finish the parent page
     * before the child frame has populated its table.
     */
    private void readDevicesFromPageAttempt(int attempt) {
        if (webView == null || webView.getUrl() == null) {
            toast("اتصل بالراوتر وسجّل الدخول أولاً.");
            finishPendingRead();
            return;
        }
        final int currentAttempt = attempt;
        String js = "(function(){" +
                "var attempt=" + currentAttempt + ";" +
                "function pathOf(d){try{return new URL(d.location.href).pathname;}catch(e){return '';}}" +
                "function collectDocs(){var out=[document],seen=[document];for(var i=0;i<out.length;i++){var d=out[i];try{var fs=d.querySelectorAll('frame,iframe');for(var j=0;j<fs.length;j++){try{var child=fs[j].contentDocument;if(child&&child.documentElement&&seen.indexOf(child)<0){seen.push(child);out.push(child);}}catch(e){}}}catch(e){}}return out;}" +
                "function cellText(e){return ((e&&(e.innerText||e.textContent))||'').replace(/\\u00a0/g,' ').replace(/\\s+/g,' ').trim();}" +
                "function norm(s){return (s||'').toLowerCase().replace(/\\s+/g,' ').replace(/[.:]/g,' ').trim();}" +
                "function macValue(s){var m=(s||'').match(/[0-9a-f]{2}(?:[:-][0-9a-f]{2}){5}/i);return m?m[0].replace(/-/g,':').toUpperCase():'';}" +
                "function idx(headers,needle){for(var i=0;i<headers.length;i++){if(norm(headers[i])===norm(needle)||norm(headers[i]).indexOf(norm(needle))>=0)return i;}return -1;}" +
                "var docs=collectDocs(),dhcp=[],wifi=[],generic=[],seenD={},seenW={},seenG={},foundD=false,foundW=false,framePaths=[];" +
                "for(var x=0;x<docs.length;x++){var d=docs[x];framePaths.push(pathOf(d));var tables=d.querySelectorAll('table');" +
                "for(var t=0;t<tables.length;t++){var table=tables[t],rows=table.querySelectorAll('tr'),headerAt=-1,headers=[],kind='';" +
                "for(var r=0;r<rows.length;r++){var hc=Array.prototype.slice.call(rows[r].querySelectorAll('th,td')).map(cellText);var hn=hc.map(norm);" +
                "var hasMac=hn.some(function(v){return v==='mac address'||v.indexOf('mac address')>=0;}),hasIp=hn.some(function(v){return v==='ip address'||v.indexOf('ip address')>=0;}),hasHost=hn.some(function(v){return v==='host name'||v.indexOf('host name')>=0;}),hasLease=hn.some(function(v){return v==='lease time'||v.indexOf('lease time')>=0;}),hasBw=hn.some(function(v){return v==='bandwidth'||v.indexOf('bandwidth')>=0;});" +
                "if(hasMac&&hasIp&&hasHost&&hasLease){kind='dhcp';headerAt=r;headers=hc;break;}if(hasMac&&hasBw){kind='wifi';headerAt=r;headers=hc;break;}}" +
                "if(!kind)continue;if(kind==='dhcp')foundD=true;else foundW=true;" +
                "var macI=idx(headers,'MAC Address'),ipI=idx(headers,'IP Address'),nameI=idx(headers,'Host Name'),leaseI=idx(headers,'Lease Time'),bwI=idx(headers,'Bandwidth');" +
                "for(var rr=headerAt+1;rr<rows.length;rr++){var row=rows[rr];if(row.querySelector('th'))continue;var cells=Array.prototype.slice.call(row.querySelectorAll('td')).map(cellText);if(!cells.length)continue;var raw=cells.join(' | '),mac=macValue(macI>=0?cells[macI]:raw);if(!mac)continue;" +
                "if(kind==='dhcp'){if(seenD[mac])continue;seenD[mac]=1;var ip=ipI>=0?(cells[ipI]||''):'';if(!/^\\d{1,3}(?:\\.\\d{1,3}){3}$/.test(ip)){var im=raw.match(/(?:\\d{1,3}\\.){3}\\d{1,3}/);ip=im?im[0]:'';}var nm=nameI>=0?(cells[nameI]||''):'';var lease=leaseI>=0?(cells[leaseI]||''):'';dhcp.push({mac:mac,ip:ip,name:nm,lease:lease,sourcePath:pathOf(d),raw:raw});}" +
                "else{if(seenW[mac])continue;seenW[mac]=1;var bw=bwI>=0?(cells[bwI]||''):'';if(!bw){for(var z=0;z<cells.length;z++){if(/^\\d{1,3}\\s*M$/i.test(cells[z])){bw=cells[z].toUpperCase();break;}}}wifi.push({mac:mac,bandwidth:bw,sourcePath:pathOf(d),raw:raw});}}}}" +
                "if(!foundD&&!foundW){for(var x=0;x<docs.length;x++){var ts=docs[x].querySelectorAll('table');for(var t=0;t<ts.length;t++){var rs=ts[t].querySelectorAll('tr');for(var r=0;r<rs.length;r++){var cells=Array.prototype.slice.call(rs[r].querySelectorAll('th,td')).map(cellText),raw=cells.join(' | '),mac=macValue(raw);if(!mac||seenG[mac])continue;seenG[mac]=1;var ipm=raw.match(/(?:\\d{1,3}\\.){3}\\d{1,3}/);generic.push({mac:mac,ip:ipm?ipm[0]:'',name:cells.length?cells[0]:'',raw:raw,sourcePath:pathOf(docs[x])});}}}}" +
                "var count=dhcp.length+wifi.length+generic.length;" +
                "if(foundD&&(dhcp.length>0||attempt>=4))TendaBridge.clients(JSON.stringify(dhcp));" +
                "if(foundW&&(wifi.length>0||attempt>=4))TendaBridge.wirelessDevices(JSON.stringify(wifi));" +
                "if(!foundD&&!foundW&&(generic.length>0||attempt>=4))TendaBridge.genericDevices(JSON.stringify(generic));" +
                "if(count>0||attempt>=4)TendaBridge.readResult(dhcp.length,wifi.length,generic.length,foundD,foundW);" +
                "return count;})()";
        if (attempt == 0) status("أبحث عن جداول الأجهزة في الصفحة والإطارات الداخلية...");
        webView.evaluateJavascript(js, result -> {
            if (result == null || "null".equals(result)) {
                status("لم تصل نتيجة القراءة من WebView. أعد فتح صفحة DHCP ثم حاول مرة أخرى.");
                finishPendingRead();
                return;
            }
            if ("0".equals(result) && currentAttempt < 4) {
                handler.postDelayed(() -> readDevicesFromPageAttempt(currentAttempt + 1), 650);
            }
        });
    }

    private void inspectCurrentPage() {
        if (webView.getUrl() == null) return;
        String js = "(function(){" +
                "function pathOnly(u){try{return new URL(u,location.href).pathname;}catch(e){return '';}}" +
                "function txt(e){return ((e&&(e.innerText||e.textContent))||'').replace(/\\u00a0/g,' ').replace(/\\s+/g,' ').trim();}" +
                "function docs(){var a=[document],seen=[document];for(var i=0;i<a.length;i++){try{var fs=a[i].querySelectorAll('frame,iframe');for(var j=0;j<fs.length;j++){try{var d=fs[j].contentDocument;if(d&&d.documentElement&&seen.indexOf(d)<0){seen.push(d);a.push(d);}}catch(e){}}}catch(e){}}return a;}" +
                "var ds=docs(),o={url:pathOnly(location.href),title:document.title,frames:[],links:[],forms:[],tables:[]};" +
                "for(var x=0;x<ds.length;x++){var d=ds[x],framePath='';try{framePath=pathOnly(d.location.href);}catch(e){framePath='frame';}o.frames.push(framePath);" +
                "var as=d.querySelectorAll('a');for(var i=0;i<as.length&&o.links.length<120;i++){var a=as[i],tx=txt(a);if(tx)o.links.push({text:tx,href:pathOnly(a.href||'')});}" +
                "var fs=d.querySelectorAll('form');for(var f=0;f<fs.length&&o.forms.length<30;f++){var fm=fs[f],ob={frame:framePath,action:pathOnly(fm.action||''),method:fm.method||'',onSubmit:(fm.getAttribute('onsubmit')||'').slice(0,300),fields:[]},cs=fm.querySelectorAll('input,select,textarea,button');for(var k=0;k<cs.length&&ob.fields.length<100;k++){var c=cs[k],typ=(c.type||'').toLowerCase(),nm=(c.name||'')+' '+(c.id||''),secret=typ==='password'||typ==='hidden'||/(pass|token|cookie|auth|session|key|csrf|stok)/i.test(nm),z={tag:c.tagName.toLowerCase(),type:typ,name:c.name||'',id:c.id||'',value:secret?'[محجوب]':(c.value||''),text:txt(c),onClick:(c.getAttribute('onclick')||'').slice(0,300)};if(c.tagName==='SELECT'){z.options=[];for(var n=0;n<c.options.length&&n<40;n++)z.options.push({text:c.options[n].text,value:c.options[n].value});}ob.fields.push(z);}o.forms.push(ob);}" +
                "var ts=d.querySelectorAll('table');for(var t=0;t<ts.length&&o.tables.length<40;t++){var tab=ts[t],rs=tab.querySelectorAll('tr'),headers=[],headerAt=-1,preview=[];for(var r=0;r<rs.length;r++){var cc=Array.prototype.slice.call(rs[r].querySelectorAll('th,td')).map(txt);var joined=cc.join(' ').toLowerCase();if((joined.indexOf('host name')>=0&&joined.indexOf('lease time')>=0)||(joined.indexOf('mac address')>=0&&joined.indexOf('bandwidth')>=0)){headers=cc;headerAt=r;break;}}if(headerAt<0){var hs=tab.querySelectorAll('th');for(var h=0;h<hs.length&&h<20;h++)headers.push(txt(hs[h]));}if(headerAt>=0){for(var pr=headerAt+1;pr<rs.length&&preview.length<5;pr++){var vals=Array.prototype.slice.call(rs[pr].querySelectorAll('td')).map(txt);if(vals.length)preview.push(vals);}}o.tables.push({frame:framePath,headers:headers,rowCount:rs.length,previewRows:preview});}}" +
                "TendaBridge.diagnostic(JSON.stringify(o));return 'ok';})()";
        webView.evaluateJavascript(js, null);
    }

    private void copyDiagnostic() {
        String value = lastDiagnostic;
        if (value == null || value.trim().isEmpty()) {
            toast("لا يوجد تقرير بعد. اضغط فحص حقول الصفحة الحالية أولاً.");
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Tenda W303 diagnostic", value));
        toast("تم نسخ تقرير التشخيص. يمكنك لصقه هنا في المحادثة.");
    }

    private void renderClients(String json) {
        updateDhcpSnapshot(json);
    }

    private void renderWirelessClients(String json) {
        try {
            JSONArray arr = new JSONArray(json);
            wirelessSnapshot.clear();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;
                String mac = MacAddress.normalize(item.optString("mac", ""));
                if (mac.isEmpty()) continue;
                wirelessSnapshot.put(mac, item);
            }
            hasWirelessSnapshot = true;
            wirelessSnapshotAt = System.currentTimeMillis();
            renderCombinedDevices();
            status("تم تحديث قائمة Wi-Fi: " + wirelessSnapshot.size() + " جهازاً ظاهراً في صفحة Wireless Connection Status.");
        } catch (JSONException e) {
            devicesView.setText("تعذر تحليل قائمة Wi-Fi. أرسل تقرير التشخيص.");
        }
    }

    private void updateDhcpSnapshot(String json) {
        try {
            JSONArray arr = new JSONArray(json);
            dhcpSnapshot.clear();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;
                String mac = MacAddress.normalize(item.optString("mac", ""));
                if (mac.isEmpty()) continue;
                dhcpSnapshot.put(mac, item);
            }
            hasDhcpSnapshot = true;
            dhcpSnapshotAt = System.currentTimeMillis();
            renderCombinedDevices();
            status("تم تحديث قائمة DHCP: " + dhcpSnapshot.size() + " سجلاً.");
        } catch (JSONException e) {
            devicesView.setText("تعذر تحليل قائمة DHCP. أرسل تقرير التشخيص.");
        }
    }

    private void renderGenericDevices(String json) {
        // Unknown tables are displayed only as unclassified records. They must
        // never overwrite a verified DHCP or wireless snapshot.
        try {
            JSONArray arr = new JSONArray(json);
            StringBuilder b = new StringBuilder();
            if (arr.length() == 0) {
                b.append("لم أتعرف على جدول أجهزة في الصفحة الحالية.\n");
                b.append("افتح DHCP Client List أو Wireless Connection Status من قائمة الراوتر ثم أعد القراءة.\n\n");
            } else {
                b.append("عُثر على ").append(arr.length()).append(" سجلاً يحتوي على MAC، لكن نوع الجدول غير معروف.\n");
                b.append("لم نصنّف هذه السجلات كأجهزة DHCP أو كأجهزة متصلة. افتح إحدى الصفحتين المدعومتين ثم أعد القراءة.\n\n");
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;
                b.append(i + 1).append(". ").append(item.optString("mac", "")).append("\n");
                b.append("   ").append(item.optString("raw", "")).append("\n");
            }
            devicesView.setText(b.toString());
        } catch (JSONException e) {
            devicesView.setText("تعذر تحليل الجدول الحالي.");
        }
    }

    private Set<String> currentMacs() {
        Set<String> macs = new LinkedHashSet<>();
        macs.addAll(dhcpSnapshot.keySet());
        macs.addAll(wirelessSnapshot.keySet());
        return macs;
    }

    private void renderCombinedDevices() {
        Set<String> macs = currentMacs();
        if (macs.isEmpty()) {
            lastDevices = "لم يتم العثور على أجهزة. افتح DHCP Client List أو Wireless Connection Status ثم اضغط قراءة الأجهزة.";
            if (devicesView != null) devicesView.setText(lastDevices);
            if (deviceCards != null) deviceCards.removeAllViews();
            return;
        }

        int both = 0, wifiOnly = 0, dhcpOnly = 0;
        for (String mac : macs) {
            boolean inD = dhcpSnapshot.containsKey(mac), inW = wirelessSnapshot.containsKey(mac);
            if (inD && inW) both++; else if (inW) wifiOnly++; else dhcpOnly++;
        }

        StringBuilder summary = new StringBuilder();
        summary.append("إجمالي الأجهزة الفريدة: ").append(macs.size()).append("\n");
        summary.append("DHCP: ").append(hasDhcpSnapshot ? dhcpSnapshot.size() : "لم تُقرأ");
        summary.append("  |  Wi-Fi: ").append(hasWirelessSnapshot ? wirelessSnapshot.size() : "لم تُقرأ").append("\n");
        if (hasDhcpSnapshot && hasWirelessSnapshot) {
            summary.append("في القائمتين: ").append(both)
                    .append("  |  Wi-Fi فقط: ").append(wifiOnly)
                    .append("  |  DHCP فقط: ").append(dhcpOnly);
        }
        if (devicesView != null) devicesView.setText(summary.toString());

        StringBuilder report = new StringBuilder(summary).append("\n");
        for (String mac : macs) {
            JSONObject d = dhcpSnapshot.get(mac);
            JSONObject w = wirelessSnapshot.get(mac);
            report.append(displayName(mac, d)).append(" | ").append(mac)
                    .append(" | IP: ").append(d == null ? "غير متاح" : d.optString("ip", ""))
                    .append(" | Wi-Fi: ").append(w == null ? "غير ظاهر في آخر لقطة" : "ظاهر")
                    .append("\n");
        }
        lastDevices = report.toString();
        renderDeviceCards(macs);
    }

    private String formatTime(long time) {
        if (time <= 0L) return "غير متاح";
        return new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(time));
    }

    private String displayName(String mac, JSONObject dhcp) {
        String custom = preferences.getString("device_name_" + mac, "").trim();
        if (!custom.isEmpty()) return custom;
        String routerName = dhcp == null ? "" : dhcp.optString("name", "").trim();
        return routerName.isEmpty() ? "جهاز غير مسمى" : routerName;
    }

    private void renderDeviceCards(Set<String> macs) {
        if (deviceCards == null) return;
        deviceCards.removeAllViews();
        String query = searchDevices == null ? "" : searchDevices.getText().toString().trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        for (String mac : macs) {
            JSONObject d = dhcpSnapshot.get(mac);
            JSONObject w = wirelessSnapshot.get(mac);
            String name = displayName(mac, d);
            String ip = d == null ? "غير متاح" : d.optString("ip", "غير متاح");
            String lease = d == null ? "" : d.optString("lease", "");
            String bandwidth = w == null ? "" : w.optString("bandwidth", "");
            boolean wifiSeen = w != null;
            if ("wifi".equals(filterMode) && !wifiSeen) continue;
            if ("dhcp".equals(filterMode) && (d == null || wifiSeen)) continue;
            String searchable = (name + " " + mac + " " + ip).toLowerCase(Locale.ROOT);
            if (!query.isEmpty() && !searchable.contains(query)) continue;
            shown++;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(12), dp(11), dp(12), dp(11));
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(Color.WHITE);
            bg.setCornerRadius(dp(11));
            bg.setStroke(dp(1), BORDER);
            row.setBackground(bg);

            LinearLayout header = new LinearLayout(this);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            TextView heading = text(name, 16, true, DARK);
            heading.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
            TextView state = text(wifiSeen ? "متصل عبر Wi-Fi" : (d != null ? "DHCP فقط" : "Wi-Fi فقط"), 11, true,
                    wifiSeen ? Color.rgb(22, 128, 70) : Color.rgb(160, 105, 0));
            state.setGravity(Gravity.CENTER);
            header.addView(state, new LinearLayout.LayoutParams(-2, -2));
            row.addView(header, matchWrap());

            StringBuilder detail = new StringBuilder();
            detail.append("IP: ").append(ip).append("\nMAC: ").append(mac);
            if (!bandwidth.isEmpty()) detail.append("\nعرض قناة Wi-Fi: ").append(bandwidth);
            if (!lease.isEmpty()) detail.append("\nLease Time: ").append(lease);
            if (!wifiSeen && d != null && hasWirelessSnapshot) detail.append("\nملاحظة: لم يظهر في آخر لقطة Wi-Fi؛ لا يعني ذلك بالضرورة أنه غير متصل بالشبكة عبر LAN.");
            TextView details = text(detail.toString(), 12, false, MUTED);
            details.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            row.addView(details, matchWrap());

            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            Button rename = button("تغيير الاسم", false);
            rename.setOnClickListener(v -> promptRename(mac, displayName(mac, dhcpSnapshot.get(mac))));
            Button copyMac = button("نسخ MAC", false);
            copyMac.setOnClickListener(v -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("MAC", mac));
                toast("تم نسخ عنوان MAC.");
            });
            Button more = button("تفاصيل", false);
            more.setOnClickListener(v -> showDeviceDetails(mac));
            actions.addView(rename, new LinearLayout.LayoutParams(0, -2, 1));
            actions.addView(copyMac, new LinearLayout.LayoutParams(0, -2, 1));
            actions.addView(more, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(actions, matchWrap());
            deviceCards.addView(row, matchWrap());
            gap(deviceCards, 7);
        }
        if (shown == 0) {
            TextView empty = text("لا توجد أجهزة مطابقة للبحث أو عامل التصفية.", 13, false, MUTED);
            empty.setGravity(Gravity.CENTER);
            deviceCards.addView(empty, matchWrap());
        }
    }

    private void showDeviceDetails(String mac) {
        JSONObject d = dhcpSnapshot.get(mac);
        JSONObject w = wirelessSnapshot.get(mac);
        StringBuilder details = new StringBuilder();
        details.append("الاسم: ").append(displayName(mac, d)).append("\n");
        details.append("MAC: ").append(mac).append("\n");
        details.append("IP: ").append(d == null ? "غير متاح" : d.optString("ip", "غير متاح")).append("\n");
        details.append("DHCP: ").append(d == null ? "غير موجود في آخر لقطة" : "موجود في آخر لقطة").append("\n");
        details.append("Wi-Fi: ").append(w == null ? "غير ظاهر في آخر لقطة" : "ظاهر في آخر لقطة").append("\n");
        if (d != null) details.append("Lease Time: ").append(d.optString("lease", "غير متاح")).append("\n");
        if (w != null) details.append("عرض قناة Wi-Fi: ").append(w.optString("bandwidth", "غير متاح")).append("\n");
        details.append("آخر تحديث DHCP: ").append(hasDhcpSnapshot ? formatTime(dhcpSnapshotAt) : "غير متاح").append("\n");
        details.append("آخر تحديث Wi-Fi: ").append(hasWirelessSnapshot ? formatTime(wirelessSnapshotAt) : "غير متاح");
        new AlertDialog.Builder(this).setTitle("تفاصيل المشترك").setMessage(details.toString())
                .setPositiveButton("إغلاق", null).show();
    }

    private void promptRename(String mac, String currentName) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(currentName.equals("جهاز غير مسمى") ? "" : currentName);
        input.setHint("مثال: هاتف أحمد");
        new AlertDialog.Builder(this)
                .setTitle("تسمية الجهاز")
                .setMessage(mac)
                .setView(input)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("حفظ", (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    if (value.isEmpty()) preferences.edit().remove("device_name_" + mac).apply();
                    else preferences.edit().putString("device_name_" + mac, value).apply();
                    renderCombinedDevices();
                    toast("تم حفظ اسم الجهاز على الهاتف.");
                }).show();
    }

    private void renderDiagnostic(String json) {
        lastDiagnostic = json;
        String preview = json;
        if (preview.length() > 7000) preview = preview.substring(0, 7000) + "\n... تم اختصار العرض؛ التقرير الكامل جاهز للنسخ.";
        diagnosticView.setText(preview);
        status("تم فحص الصفحة الحالية. انسخ التقرير وأرسله لتحديد حقول Firmware بدقة.");
    }

    private void status(String value) {
        if (statusView != null) statusView.setText("الحالة: " + value);
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_LONG).show();
    }

    private class RouterBridge {
        @JavascriptInterface
        public void clients(String json) {
            runOnUiThread(() -> renderClients(json));
        }

        @JavascriptInterface
        public void wirelessDevices(String json) {
            runOnUiThread(() -> renderWirelessClients(json));
        }

        @JavascriptInterface
        public void genericDevices(String json) {
            runOnUiThread(() -> renderGenericDevices(json));
        }

        @JavascriptInterface
        public void diagnostic(String json) {
            runOnUiThread(() -> renderDiagnostic(json));
        }

        @JavascriptInterface
        public void readResult(int dhcpCount, int wifiCount, int genericCount, boolean foundDhcp, boolean foundWifi) {
            runOnUiThread(() -> {
                if (foundDhcp || foundWifi) {
                    status("اكتملت القراءة: DHCP=" + dhcpCount + "، Wi-Fi=" + wifiCount + ".");
                } else if (genericCount > 0) {
                    status("وجدت سجلات غير مصنفة. أرسل تقرير التشخيص.");
                } else {
                    status("لم أجد جدول أجهزة معروفاً. افتح DHCP Client List أو Wireless Connection Status.");
                }
                finishPendingRead();
            });
        }

        @JavascriptInterface
        public void navResult(boolean parentFound, boolean childFound, String labels) {
            runOnUiThread(() -> {
                if (parentFound && childFound) status("تم الضغط على " + labels + ".");
                else if (parentFound) status("تم فتح القسم الرئيسي، لكن لم أجد الرابط الفرعي " + labels + ". افتحه يدوياً ثم أعد التشخيص.");
                else status("لم أجد رابط " + labels + ". تأكد من تسجيل الدخول أو افتح القسم يدوياً.");
            });
        }
    }

    private void addNavButton(LinearLayout parent, String label, Runnable action) {
        Button b = button(label, false);
        b.setOnClickListener(v -> action.run());
        parent.addView(b, matchWrap());
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(12), dp(12), dp(12));
        box.setBackgroundColor(Color.WHITE);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), BORDER);
        box.setBackground(bg);
        parent.addView(box, matchWrap());
        return box;
    }

    private TextView text(String value, int size, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setGravity(Gravity.RIGHT);
        t.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        t.setPadding(0, dp(3), 0, dp(5));
        return t;
    }

    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(primary ? Color.WHITE : DARK);
        b.setPadding(dp(8), dp(4), dp(8), dp(4));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(primary ? ORANGE : Color.rgb(245, 246, 248));
        bg.setCornerRadius(dp(9));
        if (!primary) bg.setStroke(dp(1), BORDER);
        b.setBackground(bg);
        return b;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private void gap(LinearLayout parent, int h) {
        View v = new View(this);
        parent.addView(v, new LinearLayout.LayoutParams(1, dp(h)));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("TendaBridge");
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();
        super.onDestroy();
    }
}
