package com.tenda.w303guard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.method.DigitsKeyListener;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.HttpAuthHandler;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebViewDatabase;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Calendar;
import java.util.GregorianCalendar;

/**
 * Android companion for the legacy Tenda W303R V5.07.46_en web interface.
 * Router settings are handled through the local WebView. MAC automation writes
 * only the two start-time fields through SafeMacFilter and verifies each save.
 */
public class MainActivity extends Activity {
    private interface TrafficReadCallback {
        void onComplete(boolean found);
    }

    private static final String DEFAULT_ROUTER = "192.168.0.1";
    private static final int ORANGE = Color.rgb(232, 117, 0);
    private static final int DARK = Color.rgb(31, 41, 55);
    private static final int MUTED = Color.rgb(90, 99, 110);
    private static final int BORDER = Color.rgb(224, 228, 233);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ScrollView mainScrollView;
    private EditText routerIp;
    private WebView webView;
    private AlertDialog routerAuthDialog;
    private TextView statusView;
    private TextView pageView;
    private TextView devicesView;
    private TextView diagnosticView;
    private TextView scanMetaView;
    private EditText searchDevices;
    private Button scanAllButton;
    private LinearLayout deviceCards;
    // Static Assignment is tracked independently from active DHCP leases.
    // Keys include both values so duplicate/conflicting router rows remain visible.
    private final Map<String, JSONObject> staticAssignmentSnapshot = new LinkedHashMap<>();
    private boolean hasStaticAssignmentSnapshot = false;
    private boolean staticAssignmentHasInvalidRows = false;
    private long staticAssignmentSnapshotAt = 0L;
    private TextView staticReservationSummaryView;
    private LinearLayout staticReservationCards;
    private EditText reservationIpInput;
    private EditText reservationMacInput;
    private String filterMode = "all";
    private boolean scanInProgress = false;
    private Runnable pendingReadCompletion;
    private SharedPreferences preferences;
    private String activeHost = DEFAULT_ROUTER;
    private String lastDiagnostic = "";
    private String lastDevices = "";
    private final Map<String, JSONObject> dhcpSnapshot = new LinkedHashMap<>();
    // Recent DHCP associations are retained separately so a wireless-only scan
    // can still show the last known IP without presenting it as a live lease.
    private final Map<String, JSONObject> dhcpHistory = new LinkedHashMap<>();
    private static final long DHCP_HISTORY_MAX_AGE_MS = 24L * 60L * 60L * 1000L;
    private final Map<String, JSONObject> wirelessSnapshot = new LinkedHashMap<>();
    // Traffic Statistics is keyed by router-reported IP address; DHCP is used
    // only to enrich the row with the most recent known name and MAC address.
    private final Map<String, JSONObject> trafficSnapshot = new LinkedHashMap<>();
    private long trafficSnapshotAt = 0L;
    private String trafficSourcePath = "";
    private TextView trafficSummaryView;
    private LinearLayout trafficCards;
    private TextView liveStatusView;
    private Button liveStartButton;
    private Button liveStopButton;
    private Button liveIntervalButton;
    private boolean liveMonitorRunning = false;
    private boolean liveCycleBusy = false;
    private boolean liveLifecyclePaused = false;
    private int liveConsecutiveMisses = 0;
    private long liveCycleStartedAt = 0L;
    private int livePollIntervalMs = 3000;
    private final int[] livePollIntervalsMs = new int[]{3000, 5000, 10000};
    private int livePollIntervalIndex = 0;
    private final Runnable liveMonitorRunnable = this::runLiveMonitorCycle;
    private boolean monitorReceiverRegistered = false;
    private final BroadcastReceiver backgroundMonitorReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (RouterBackgroundService.ACTION_STATUS.equals(action)) {
                liveMonitorRunning = intent.getBooleanExtra(RouterBackgroundService.EXTRA_RUNNING, false);
                String message = intent.getStringExtra(RouterBackgroundService.EXTRA_STATUS);
                if (liveStatusView != null && message != null) liveStatusView.setText(message);
                updateLiveControls();
            } else if (RouterBackgroundService.ACTION_MAC_STATUS.equals(action)) {
                macAutomationRunning = intent.getBooleanExtra(RouterBackgroundService.EXTRA_MAC_ACTIVE, false);
                String message = intent.getStringExtra(RouterBackgroundService.EXTRA_MAC_STATUS);
                updateMacAutomationUi(message == null ? "حالة أتمتة MAC غير متاحة." : message);
            } else if (RouterBackgroundService.ACTION_TRAFFIC.equals(action)) {
                String json = intent.getStringExtra(RouterBackgroundService.EXTRA_JSON);
                String source = intent.getStringExtra(RouterBackgroundService.EXTRA_SOURCE_PATH);
                if (json != null) {
                    renderTrafficStatistics(json, source);
                    trafficSnapshotAt = intent.getLongExtra(RouterBackgroundService.EXTRA_CAPTURED_AT,
                            System.currentTimeMillis());
                    renderTrafficCards();
                }
                if (liveStatusView != null) {
                    liveStatusView.setText("مراقبة الخلفية تعمل • آخر تحديث: " + formatTime(trafficSnapshotAt)
                            + " • الفاصل: " + (livePollIntervalMs / 1000) + " ثوانٍ");
                }
            }
        }
    };
    private boolean hasDhcpSnapshot = false;
    private boolean hasWirelessSnapshot = false;
    private long dhcpSnapshotAt = 0L;
    private long wirelessSnapshotAt = 0L;

    // MAC schedule automation is isolated from the existing navigation and live-traffic code.
    // It uses hidden same-origin router frames so the visible page and left menu are not changed.
    private TextView macAutomationStatusView;
    private Button macAutomationStartButton;
    private Button macAutomationStopButton;
    private boolean macAutomationRunning = false;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("tenda_w303_guard", MODE_PRIVATE);
        activeHost = preferences.getString("router_ip", DEFAULT_ROUTER);
        macAutomationRunning = preferences.getBoolean(RouterBackgroundService.PREF_MAC_AUTO_ENABLED, false)
                || preferences.getBoolean(RouterBackgroundService.PREF_MAC_AUTO_STOPPING, false)
                || hasPendingMacRestoreInPreferences();
        liveMonitorRunning = preferences.getBoolean(RouterBackgroundService.PREF_ENABLED, false);
        livePollIntervalMs = normalizeLivePollInterval(preferences.getInt(
                RouterBackgroundService.PREF_INTERVAL_MS, 3000));
        for (int i = 0; i < livePollIntervalsMs.length; i++) {
            if (livePollIntervalsMs[i] == livePollIntervalMs) livePollIntervalIndex = i;
        }
        loadDhcpHistory();
        buildUi();
        if (liveIntervalButton != null) {
            liveIntervalButton.setText("فاصل التحديث: " + (livePollIntervalMs / 1000) + " ثوانٍ");
        }
        configureWebView();
        updateLiveControls();
        restoreLastTrafficSnapshot();
        updateMacAutomationUi(macAutomationRunning
                ? "أتمتة MAC محفوظة ومفعّلة في الخدمة الخلفية؛ ستستأنف عند اتصال الراوتر."
                : "التصفير التلقائي متوقف. اتصل بالراوتر ثم ابدأ التشغيل.");
    }

    private void buildUi() {
        ScrollView outer = new ScrollView(this);
        mainScrollView = outer;
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
        // The field accepts an IPv4 address only (digits and dots), not a full URL.
        // DigitsKeyListener also exposes a numeric keypad that includes the dot.
        routerIp.setKeyListener(DigitsKeyListener.getInstance("0123456789."));
        routerIp.setHint("مثال: 192.168.0.1");
        routerIp.setSelectAllOnFocus(true);
        routerIp.setPadding(dp(12), dp(8), dp(12), dp(8));
        connection.addView(routerIp, new LinearLayout.LayoutParams(-1, dp(48)));
        connection.addView(text("أدخل عنوان IP فقط، مثل 192.168.0.1، دون كتابة http:// أو اسم الصفحة.", 11, false, MUTED), matchWrap());
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
        addNavButton(nav, "السماح أو منع اتصال Wi-Fi (MAC)", () -> openRouterSection("Wireless", "Access Control"));
        addNavButton(nav, "تصفية المواقع وجدولة الوصول", () -> openRouterSection("Security", "URL Filtering"));
        addNavButton(nav, "جدولة الإنترنت حسب MAC", () -> openRouterSection("Security", "MAC Address Filter Settings"));
        addNavButton(nav, "ضبط وقت الراوتر", () -> openRouterSection("Tools", "Time Settings"));
        addNavButton(nav, "QoS وتحديد السرعة", () -> openRouterSection("QoS", "Bandwidth Control"));
        addNavButton(nav, "QoS - Traffic Statistics", () -> openRouterSection("QoS", "Traffic Statistics"));
        addNavButton(nav, "الرجوع في صفحات الراوتر", () -> {
            if (webView != null && webView.canGoBack()) webView.goBack();
        });

        gap(root, 10);
        LinearLayout macAutomationCard = card(root);
        macAutomationCard.addView(text("التصفير والاستعادة التلقائية لجدولة MAC", 17, true, DARK), matchWrap());
        macAutomationCard.addView(text("يحفظ وقت البداية الأصلي لكل رقم سياسة MAC على حدة. كل سياسة تُحتسب مستقلة حتى إذا تطابق وقتها مع سياسة أخرى. عند حلول وقت بدايتها تُصفّر إلى 00:00، ثم تُستعاد الساعة 23:55. تعمل الأتمتة مع مراقبة المشتركين في خدمة خلفية واحدة وتنتظر عودة الراوتر عند الانقطاع.", 12, false, MUTED), matchWrap());
        macAutomationStatusView = text("التصفير التلقائي متوقف. اتصل بالراوتر ثم ابدأ التشغيل لقراءة السياسات وحفظ أوقاتها الأصلية.", 12, true, MUTED);
        macAutomationStatusView.setPadding(0, dp(7), 0, dp(7));
        macAutomationCard.addView(macAutomationStatusView, matchWrap());
        LinearLayout macAutomationControls = new LinearLayout(this);
        macAutomationControls.setOrientation(LinearLayout.HORIZONTAL);
        macAutomationStartButton = button("بدء التصفير التلقائي", true);
        macAutomationStartButton.setOnClickListener(v -> startMacAutomation());
        macAutomationStopButton = button("إيقاف واستعادة", false);
        macAutomationStopButton.setOnClickListener(v -> stopMacAutomation());
        macAutomationStopButton.setEnabled(false);
        macAutomationControls.addView(macAutomationStartButton, new LinearLayout.LayoutParams(0, -2, 2));
        macAutomationControls.addView(macAutomationStopButton, new LinearLayout.LayoutParams(0, -2, 1));
        macAutomationCard.addView(macAutomationControls, matchWrap());
        macAutomationCard.addView(text("مهم: يعتمد التنفيذ على ساعة الراوتر من Time Settings وليس ساعة الهاتف. لا يلزم إبقاء واجهة التطبيق مفتوحة. إذا انقطع Wi-Fi أو أُعيد تشغيل الراوتر تنتظر الخدمة ثم تستأنف عند عودة الاتصال. لا تستخدم Force stop ولا تمسح بيانات التطبيق أثناء وجود سياسات معدّلة.", 11, false, MUTED), matchWrap());

        gap(root, 10);
        LinearLayout trafficCard = card(root);
        trafficCard.addView(text("المراقبة اللحظية للمشتركين - Traffic Statistics", 18, true, DARK), matchWrap());
        trafficCard.addView(text("الأولوية لسرعة الرفع والتنزيل الحالية لكل مشترك. تعمل المراقبة في خدمة Android مستقلة، وتنتظر عودة Wi-Fi أو الراوتر تلقائياً. لا تحتاج إلى إنترنت خارجي ما دام الهاتف متصلاً محلياً بالراوتر.", 12, false, MUTED), matchWrap());

        liveStatusView = text("المراقبة متوقفة. يمكنك تشغيلها الآن لتنتظر Wi-Fi والراوتر تلقائياً؛ يلزم تسجيل الدخول إلى الراوتر مرة واحدة إذا طُلبت المصادقة.", 12, true, MUTED);
        liveStatusView.setPadding(0, dp(7), 0, dp(7));
        trafficCard.addView(liveStatusView, matchWrap());

        Button openTrafficPage = button("فتح QoS ثم Traffic Statistics", false);
        openTrafficPage.setOnClickListener(v -> openRouterSection("QoS", "Traffic Statistics"));
        trafficCard.addView(openTrafficPage, matchWrap());

        LinearLayout liveControls = new LinearLayout(this);
        liveControls.setOrientation(LinearLayout.HORIZONTAL);
        liveStartButton = button("بدء المراقبة الحية في الخلفية", true);
        liveStartButton.setOnClickListener(v -> startLiveMonitoring());
        liveStopButton = button("إيقاف", false);
        liveStopButton.setEnabled(false);
        liveStopButton.setOnClickListener(v -> stopLiveMonitoring("أوقفت المراقبة الحية يدوياً."));
        liveControls.addView(liveStartButton, new LinearLayout.LayoutParams(0, -2, 2));
        liveControls.addView(liveStopButton, new LinearLayout.LayoutParams(0, -2, 1));
        trafficCard.addView(liveControls, matchWrap());

        liveIntervalButton = button("فاصل التحديث: 3 ثوانٍ", false);
        liveIntervalButton.setOnClickListener(v -> cycleLivePollInterval());
        trafficCard.addView(liveIntervalButton, matchWrap());

        Button refreshTrafficNow = button("تحديث السرعات الآن", false);
        refreshTrafficNow.setOnClickListener(v -> refreshTrafficFrameThenRead(true, null));
        trafficCard.addView(refreshTrafficNow, matchWrap());

        Button readTraffic = button("قراءة Traffic Statistics من الصفحة الحالية", false);
        readTraffic.setOnClickListener(v -> readTrafficStatisticsFromPage(true));
        trafficCard.addView(readTraffic, matchWrap());

        Button inspectTraffic = button("فحص الصفحة وقراءة الإحصائيات", false);
        inspectTraffic.setOnClickListener(v -> {
            inspectCurrentPage();
            readTrafficStatisticsFromPage(true);
        });
        trafficCard.addView(inspectTraffic, matchWrap());

        trafficSummaryView = text("لم تُقرأ إحصائيات حركة البيانات بعد.", 13, true, DARK);
        trafficSummaryView.setPadding(0, dp(8), 0, dp(6));
        trafficCard.addView(trafficSummaryView, matchWrap());
        trafficCards = new LinearLayout(this);
        trafficCards.setOrientation(LinearLayout.VERTICAL);
        trafficCard.addView(trafficCards, matchWrap());
        trafficCard.addView(text("ملاحظة: التحديث شبه لحظي ويعتمد على سرعة استجابة Firmware الراوتر. صفر KB/s يعني عدم رصد نقل بيانات في لحظة القراءة، ولا يعني أن الجهاز مفصول. إجماليات MB تراكمية وليست سرعة حالية.", 11, false, MUTED), matchWrap());

        gap(root, 10);
        LinearLayout schedule = card(root);
        schedule.addView(text("تصفية المواقع وجدولة الوصول", 15, true, DARK), matchWrap());
        schedule.addView(text(
                "تقرير Firmware الذي أرسلته يثبت أن Security يفتح صفحة URL Filtering، وفيها نطاق IP (Start IP / End IP)، ونص URL، ووقت البداية والنهاية، وأيام الأسبوع، وخيار Enable. هذه قاعدة لتصفية المواقع وفق IP والوقت، وليست تصفية MAC.",
                13, false, MUTED), matchWrap());
        schedule.addView(text(
                "مهم: Wireless > Access Control هو المكان المؤكد للتحكم بعناوين MAC المسموح لها أو الممنوعة من الانضمام إلى شبكة Wi-Fi. أما Security > URL Filtering فيتحكم في الوصول إلى مواقع محددة وفق الحقول التي يعرضها الراوتر. لا تخلط بين الوظيفتين.",
                13, true, DARK), matchWrap());
        schedule.addView(text(
                "مرحلة آمنة: يفتح التطبيق صفحة MAC Address Filter Settings من قائمة الراوتر ويقرأ نموذجها للتشخيص فقط. لن يغيّر أي وقت أو سياسة ولن يضغط OK قبل التحقق من أسماء الحقول وطريقة الحفظ الفعلية.",
                12, false, MUTED), matchWrap());

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
        Button routerHome = button("صفحة الدخول إلى الراوتر", false);
        routerHome.setOnClickListener(v -> {
            if (webView.getUrl() == null) connectToRouter();
            else openRouterLoginPage(true);
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

        gap(root, 10);
        LinearLayout reservationsPanel = card(root);
        reservationsPanel.addView(text("حجوزات IP الثابتة - Static Assignment", 17, true, DARK), matchWrap());
        reservationsPanel.addView(text("هذه القائمة مستقلة عن DHCP: الحجز يثبت IP لعنوان MAC، أما DHCP فيعرض الإيجار الحالي. اقرأ الجدولين قبل فحص أي حجز جديد.", 12, false, MUTED), matchWrap());
        Button readReservations = button("قراءة حجوزات IP من الراوتر", false);
        readReservations.setOnClickListener(v -> readStaticAssignments());
        reservationsPanel.addView(readReservations, matchWrap());
        staticReservationSummaryView = text("لم تتم قراءة جدول Static Assignment بعد.", 12, true, MUTED);
        reservationsPanel.addView(staticReservationSummaryView, matchWrap());
        staticReservationCards = new LinearLayout(this);
        staticReservationCards.setOrientation(LinearLayout.VERTICAL);
        reservationsPanel.addView(staticReservationCards, matchWrap());
        gap(reservationsPanel, 5);
        reservationsPanel.addView(text("فحص حجز جديد", 15, true, DARK), matchWrap());
        TextView ipLabel = text("IP Address (اكتب آخر رقم مثل 110 أو العنوان كاملاً)", 12, false, MUTED);
        ipLabel.setGravity(Gravity.LEFT);
        reservationsPanel.addView(ipLabel, matchWrap());
        reservationIpInput = new EditText(this);
        reservationIpInput.setSingleLine(true);
        reservationIpInput.setTextDirection(View.TEXT_DIRECTION_LTR);
        reservationIpInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        reservationIpInput.setKeyListener(DigitsKeyListener.getInstance("0123456789."));
        reservationIpInput.setHint("110 أو 192.168.0.110");
        reservationsPanel.addView(reservationIpInput, matchWrap());
        TextView macLabel = text("MAC Address", 12, false, MUTED);
        macLabel.setGravity(Gravity.LEFT);
        reservationsPanel.addView(macLabel, matchWrap());
        reservationMacInput = new EditText(this);
        reservationMacInput.setSingleLine(true);
        reservationMacInput.setTextDirection(View.TEXT_DIRECTION_LTR);
        reservationMacInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        reservationMacInput.setHint("AA:BB:CC:DD:EE:FF");
        reservationsPanel.addView(reservationMacInput, matchWrap());
        Button prepareReservation = button("فحص التعارض وتعبئة نموذج الراوتر", true);
        prepareReservation.setOnClickListener(v -> prepareStaticReservation());
        reservationsPanel.addView(prepareReservation, matchWrap());
        reservationsPanel.addView(text("لأمان الشبكة، التطبيق يفحص التعارضات ويملأ حقول الراوتر فقط. لا يضغط Add أو OK ولا يحفظ أي تغيير تلقائياً. راجع القيم ثم أكمل الحجز يدوياً داخل صفحة الراوتر. وللحذف استخدم Delete في جدول Static Assignment ثم OK.", 11, false, MUTED), matchWrap());

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

        TextView footer = text("الإصدار 0.2.20: مراقبة Traffic Statistics في الخلفية وانتظار عودة Wi-Fi والراوتر تلقائياً.", 12, true, MUTED);
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
            public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler authHandler, String host, String realm) {
                // W303R firmware protects the management interface with an HTTP
                // authentication challenge when the user opens the router IP.
                // WebView's default handler cancels this challenge, which can leave
                // the router frame blank. Ask the user for credentials explicitly.
                showRouterAuthenticationDialog(authHandler, host, realm);
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
                if (liveMonitorRunning || preferences.getBoolean(RouterBackgroundService.PREF_MAC_AUTO_ENABLED, false)
                        || preferences.getBoolean(RouterBackgroundService.PREF_MAC_AUTO_STOPPING, false)) {
                    Intent retryBackgroundAuth = new Intent(MainActivity.this, RouterBackgroundService.class)
                            .setAction(RouterBackgroundService.ACTION_RETRY_AUTH);
                    startService(retryBackgroundAuth);
                }
                // Inspect the loaded router page and refresh Traffic Statistics if
                // its table is present. Device lists are read explicitly by the page
                // shortcuts or by the sequential DHCP/Wi-Fi scan to avoid racing frames.
                handler.postDelayed(() -> {
                    if (webView != null && webView.getUrl() != null) {
                        inspectCurrentPage();
                        readTrafficStatisticsFromPage(false);
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
        if (!ip.equals(activeHost) && (preferences.getBoolean(RouterBackgroundService.PREF_MAC_AUTO_ENABLED, false)
                || preferences.getBoolean(RouterBackgroundService.PREF_MAC_AUTO_STOPPING, false))) {
            toast("أوقف أتمتة MAC واستعد السياسات أولاً قبل تغيير عنوان الراوتر.");
            return;
        }
        if (liveMonitorRunning && !ip.equals(activeHost)) {
            stopLiveMonitoring("أوقفت مراقبة حركة البيانات لأن عنوان الراوتر تغيّر.");
        }
        activeHost = ip;
        preferences.edit().putString("router_ip", ip).apply();
        dhcpHistory.clear();
        loadDhcpHistory();
        lastDiagnostic = "";
        lastDevices = "";
        dhcpSnapshot.clear();
        wirelessSnapshot.clear();
        staticAssignmentSnapshot.clear();
        hasStaticAssignmentSnapshot = false;
        staticAssignmentHasInvalidRows = false;
        staticAssignmentSnapshotAt = 0L;
        if (staticReservationCards != null) staticReservationCards.removeAllViews();
        if (staticReservationSummaryView != null) staticReservationSummaryView.setText("لم تتم قراءة جدول Static Assignment بعد.");
        trafficSnapshot.clear();
        trafficSnapshotAt = 0L;
        trafficSourcePath = "";
        if (trafficCards != null) trafficCards.removeAllViews();
        if (trafficSummaryView != null) trafficSummaryView.setText("لم تُقرأ إحصائيات حركة البيانات بعد.");
        hasDhcpSnapshot = false;
        hasWirelessSnapshot = false;
        dhcpSnapshotAt = 0L;
        wirelessSnapshotAt = 0L;
        if (deviceCards != null) deviceCards.removeAllViews();
        devicesView.setText("لم تتم قراءة الأجهزة بعد.");
        diagnosticView.setText("سيظهر تقرير الصفحة بعد تحميل واجهة الراوتر.");
        // Start at the router's normal entry point. On this W303R firmware the
        // router requests its administrator password through HTTP authentication
        // before serving the administration shell (usually /advance.asp).
        openRouterLoginPage(true);
    }

    private void openRouterLoginPage(boolean requestPasswordAgain) {
        if (webView == null || activeHost == null || activeHost.trim().isEmpty()) return;
        if (requestPasswordAgain) {
            try {
                // Clear only WebView's cached HTTP-auth credentials so the router
                // can ask for the password again. Do not clear router cookies.
                WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword();
            } catch (Exception ignored) { }
        }
        if (pageView != null) pageView.setText("صفحة الدخول: http://" + activeHost + "/");
        status("فتح صفحة الدخول الأصلية للراوتر. أدخل كلمة مرور الإدارة عند ظهور نافذة المصادقة.");
        webView.loadUrl("http://" + activeHost + "/");
    }

    private void showRouterAuthenticationDialog(HttpAuthHandler authHandler, String host, String realm) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) {
                authHandler.cancel();
                return;
            }
            if (routerAuthDialog != null && routerAuthDialog.isShowing()) {
                // Avoid stacking authentication prompts if the router challenges
                // multiple resources while a prompt is already visible.
                authHandler.cancel();
                return;
            }

            LinearLayout form = new LinearLayout(this);
            form.setOrientation(LinearLayout.VERTICAL);
            form.setPadding(dp(22), dp(8), dp(22), dp(4));

            TextView explanation = text("الراوتر يطلب بيانات الدخول إلى لوحة الإدارة. أدخل كلمة المرور التي تستخدمها في المتصفح العادي.", 13, false, MUTED);
            form.addView(explanation, matchWrap());

            EditText usernameInput = new EditText(this);
            usernameInput.setSingleLine(true);
            usernameInput.setText("admin");
            usernameInput.setHint("اسم المستخدم");
            usernameInput.setInputType(InputType.TYPE_CLASS_TEXT);
            form.addView(usernameInput, matchWrap());

            EditText passwordInput = new EditText(this);
            passwordInput.setSingleLine(true);
            passwordInput.setHint("كلمة مرور الراوتر");
            passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            form.addView(passwordInput, matchWrap());

            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle("تسجيل الدخول إلى الراوتر")
                    .setMessage("العنوان: " + host + (realm == null || realm.trim().isEmpty() ? "" : "\n" + realm))
                    .setView(form)
                    .setPositiveButton("دخول", null)
                    .setNegativeButton("إلغاء", (d, which) -> authHandler.cancel())
                    .create();
            routerAuthDialog = dialog;
            dialog.setOnCancelListener(d -> authHandler.cancel());
            dialog.setOnDismissListener(d -> {
                if (routerAuthDialog == dialog) routerAuthDialog = null;
            });
            dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String username = usernameInput.getText().toString().trim();
                String password = passwordInput.getText().toString();
                if (username.isEmpty()) username = "admin";
                if (password.isEmpty()) {
                    passwordInput.setError("أدخل كلمة المرور");
                    passwordInput.requestFocus();
                    return;
                }
                status("تم إرسال بيانات الدخول إلى الراوتر للتحقق منها...");
                try {
                    WebViewDatabase.getInstance(MainActivity.this)
                            .setHttpAuthUsernamePassword(host, realm, username, password);
                } catch (Exception ignored) { }
                authHandler.proceed(username, password);
                dialog.dismiss();
            }));
            dialog.show();
        });
    }

    private boolean isAllowedRouterUri(Uri uri) {
        if (uri == null || !"http".equalsIgnoreCase(uri.getScheme())) return false;
        return activeHost.equals(uri.getHost());
    }

    private boolean isPrivateIpv4(String ip) {
        if (ip == null || !ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return false;
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) return false;
        int[] octets = new int[4];
        try {
            for (int i = 0; i < 4; i++) {
                if (parts[i].isEmpty() || parts[i].length() > 3) return false;
                octets[i] = Integer.parseInt(parts[i]);
                if (octets[i] < 0 || octets[i] > 255) return false;
            }
        } catch (NumberFormatException e) {
            return false;
        }
        int first = octets[0], second = octets[1];
        return first == 10 || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168) || (first == 169 && second == 254);
    }

    private String routerPagePath(String parentLabel, String childLabel) {
        if ("Advanced".equalsIgnoreCase(parentLabel) && "DHCP Client List".equalsIgnoreCase(childLabel)) return "/lan_dhcp_clients.asp";
        if ("Wireless".equalsIgnoreCase(parentLabel) && "Wireless Connection Status".equalsIgnoreCase(childLabel)) return "/wireless_state.asp";
        if ("Wireless".equalsIgnoreCase(parentLabel) && "Access Control".equalsIgnoreCase(childLabel)) return "/wireless_filter.asp";
        if ("Security".equalsIgnoreCase(parentLabel) && ("URL Filtering".equalsIgnoreCase(childLabel)
                || "URL Filter".equalsIgnoreCase(childLabel))) return "/firewall_urlfilter.asp";
        if ("Security".equalsIgnoreCase(parentLabel) && "MAC Address Filter Settings".equalsIgnoreCase(childLabel)) return "/firewall_mac.asp";
        if ("Tools".equalsIgnoreCase(parentLabel) && "Time Settings".equalsIgnoreCase(childLabel)) return "/system_hostname.asp";
        if ("QoS".equalsIgnoreCase(parentLabel) && "Bandwidth Control".equalsIgnoreCase(childLabel)) return "/net_tc.asp";
        if ("QoS".equalsIgnoreCase(parentLabel) && "Traffic Statistics".equalsIgnoreCase(childLabel)) return "/sys_iptAccount.asp";
        return null;
    }

    private void clickRouterPathLink(String targetPath, BooleanResult callback) {
        String js = "(function(){" +
                "var target=" + JSONObject.quote(targetPath) + ",current=false,links=[];" +
                "var wins=[];" +
                "function path(w){try{return new URL(w.location.href).pathname;}catch(e){return '';}}" +
                "function walk(w,depth){try{wins.push({w:w,depth:depth,path:path(w)});for(var i=0;i<w.frames.length;i++){try{walk(w.frames[i],depth+1);}catch(e){}}}catch(e){}}" +
                "walk(window,0);" +
                "for(var i=0;i<wins.length;i++){" +
                "if(wins[i].path===target)current=true;" +
                "try{var as=wins[i].w.document.querySelectorAll('a[href]');" +
                "for(var j=0;j<as.length;j++){try{" +
                "var u=new URL(as[j].href,wins[i].w.location.href);" +
                "if(u.pathname===target&&u.host===location.host)links.push({a:as[j],depth:wins[i].depth});" +
                "}catch(e){}}}catch(e){}}" +
                "links.sort(function(a,b){return b.depth-a.depth;});" +
                "for(var k=0;k<links.length;k++){try{links[k].a.click();return true;}catch(e){}}" +
                "return current;})()";
        webView.evaluateJavascript(js, result -> callback.onResult("true".equals(result)));
    }

    private void clickRouterPathLinkWithRetry(String targetPath, int attempt, BooleanResult callback) {
        clickRouterPathLink(targetPath, found -> {
            if (found || attempt >= 3) {
                callback.onResult(found);
            } else {
                handler.postDelayed(() -> clickRouterPathLinkWithRetry(targetPath, attempt + 1, callback), 400);
            }
        });
    }

    private void navigateRouterSectionPath(String parentLabel, String childLabel,
                                           String targetPath, BooleanResult callback) {
        if (targetPath == null) {
            callback.onResult(false);
            return;
        }
        clickRouterLabel(parentLabel, parentFound -> {
            Runnable openChild = () -> handler.postDelayed(() ->
                    clickRouterPathLinkWithRetry(targetPath, 0, linked -> {
                        if (linked) {
                            handler.postDelayed(() -> verifyRouterContentPath(targetPath, 0, callback), 650);
                            return;
                        }
                        navigateRouterContentPath(targetPath, navigated -> {
                            if (!navigated) {
                                clickRouterLabelAny(childLabelAliases(childLabel), 0, childFound -> {
                                    if (!childFound) {
                                        callback.onResult(false);
                                        return;
                                    }
                                    handler.postDelayed(() -> verifyRouterContentPath(targetPath, 0, callback), 650);
                                });
                                return;
                            }
                            handler.postDelayed(() -> verifyRouterContentPath(targetPath, 0, callback), 650);
                        });
                    }), 700);
            if (parentFound) {
                openChild.run();
            } else {
                navigateRouterContentPath(targetPath, navigated -> {
                    if (!navigated) {
                        callback.onResult(false);
                        return;
                    }
                    handler.postDelayed(() -> verifyRouterContentPath(targetPath, 0, callback), 650);
                });
            }
        });
    }

    private void openRouterSection(String parentLabel, String childLabel) {
        if (webView == null || webView.getUrl() == null) {
            toast("اتصل بالراوتر وسجّل الدخول أولاً.");
            return;
        }
        final String targetPath = routerPagePath(parentLabel, childLabel);
        if (targetPath == null) {
            openRouterSectionByMenu(parentLabel, childLabel);
            return;
        }
        status("أفتح " + parentLabel + " > " + childLabel + " مع مزامنة قائمة الراوتر...");
        navigateRouterSectionPath(parentLabel, childLabel, targetPath, verified -> {
            if (verified) {
                finishRouterSectionOpen(childLabel);
            } else {
                status("تعذر تأكيد فتح " + childLabel + ". لم أغيّر أي إعداد في الراوتر. أرسل تقرير الصفحة الحالية.");
                inspectCurrentPage();
            }
        });
    }

    private void finishRouterSectionOpen(String childLabel) {
        status("تم فتح " + childLabel + ". جارٍ فحص محتوى الصفحة...");
        inspectCurrentPage();
        if ("DHCP Client List".equalsIgnoreCase(childLabel)
                || "Wireless Connection Status".equalsIgnoreCase(childLabel)) {
            readDevicesFromPage();
        } else if ("Traffic Statistics".equalsIgnoreCase(childLabel)) {
            readTrafficStatisticsFromPage(true);
        }
    }

    private void openRouterSectionByMenu(String parentLabel, String childLabel) {
        String expectedPath = routerPagePath(parentLabel, childLabel);
        if (expectedPath != null) {
            navigateRouterSectionPath(parentLabel, childLabel, expectedPath, verified -> {
                if (verified) {
                    finishRouterSectionOpen(childLabel);
                } else {
                    status("تعذر فتح " + parentLabel + " > " + childLabel + ". افتحها يدوياً ثم أرسل تقرير الصفحة.");
                    inspectCurrentPage();
                }
            });
            return;
        }
        clickRouterLabel(parentLabel, parentFound -> {
            if (!parentFound) {
                status("لم أجد تبويب " + parentLabel + ". تأكد من تسجيل الدخول ثم أعد المحاولة.");
                return;
            }
            handler.postDelayed(() -> clickRouterChildFromMenu(parentLabel, childLabel, null), 850);
        });
    }

    private void clickRouterChildFromMenu(String parentLabel, String childLabel, String expectedPath) {
        clickRouterLabelAny(childLabelAliases(childLabel), 0, childFound -> {
            if (!childFound) {
                status("فُتح " + parentLabel + " لكن لم أجد الصفحة الفرعية " + childLabel + ". افتحها يدوياً ثم أعد المحاولة.");
                return;
            }
            handler.postDelayed(() -> {
                if (expectedPath == null) {
                    finishRouterSectionOpen(childLabel);
                } else {
                    verifyRouterContentPath(expectedPath, 0, verified -> {
                        if (verified) finishRouterSectionOpen(childLabel);
                        else {
                            status("تم اختيار " + childLabel + " لكن لم أستطع تأكيد عنوان الصفحة. افحص الصفحة قبل المتابعة.");
                            inspectCurrentPage();
                        }
                    });
                }
            }, 900);
        });
    }

    private String[] childLabelAliases(String childLabel) {
        if ("URL Filtering".equalsIgnoreCase(childLabel) || "URL Filter".equalsIgnoreCase(childLabel)) {
            return new String[]{"URL Filtering", "URL Filter", "URL Filter Settings", "Website Filter"};
        }
        if ("Time Settings".equalsIgnoreCase(childLabel)) return new String[]{"Time Settings", "Time"};
        if ("Access Control".equalsIgnoreCase(childLabel)) return new String[]{"Access Control"};
        if ("MAC Address Filter Settings".equalsIgnoreCase(childLabel)) {
            return new String[]{"MAC Address Filter Settings", "MAC Filter Settings", "MAC Address Filter"};
        }
        return new String[]{childLabel};
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

    private void clickRouterLabelAny(String[] labels, int index, BooleanResult callback) {
        if (labels == null || index >= labels.length) {
            callback.onResult(false);
            return;
        }
        clickRouterLabel(labels[index], found -> {
            if (found) callback.onResult(true);
            else clickRouterLabelAny(labels, index + 1, callback);
        });
    }

    private void verifyRouterContentPath(String targetPath, int attempt, BooleanResult callback) {
        String js = "(function(){" +
                "var target=" + JSONObject.quote(targetPath) + ";var found=false;" +
                "function walk(w){try{var p=new URL(w.location.href).pathname;if(p===target)found=true;for(var i=0;i<w.frames.length&&!found;i++)walk(w.frames[i]);}catch(e){}}" +
                "walk(window);return found;})()";
        webView.evaluateJavascript(js, result -> {
            if ("true".equals(result)) callback.onResult(true);
            else if (attempt < 3) handler.postDelayed(() -> verifyRouterContentPath(targetPath, attempt + 1, callback), 550);
            else callback.onResult(false);
        });
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
        if (scanMetaView != null) scanMetaView.setText("المرحلة 1 من 2: فتح Advanced > DHCP Client List...");
        status("بدأ تحديث القائمتين. لن يتم تغيير أي إعداد في الراوتر.");
        navigateRouterSectionPath("Advanced", "DHCP Client List", "/lan_dhcp_clients.asp", verified -> {
            if (!verified) {
                finishFullScan(false, "تعذر فتح DHCP Client List والتحقق من مساره. افتحها يدوياً ثم أعد الفحص.");
                return;
            }
            handler.postDelayed(() -> readDevicesFromPage(() -> {
                if (scanMetaView != null) scanMetaView.setText("اكتملت قراءة DHCP. المرحلة 2 من 2: فتح قائمة Wi-Fi...");
                handler.postDelayed(this::scanWirelessStage, 450);
            }), 650);
        });
    }


    private void scanWirelessStage() {
        if (scanMetaView != null) {
            scanMetaView.setText("اكتملت قراءة DHCP. جارٍ فتح صفحة Wireless Connection Status...");
        }
        navigateRouterSectionPath("Wireless", "Wireless Connection Status", "/wireless_state.asp", verified -> {
            if (verified) {
                status("تم فتح Wireless Connection Status. جارٍ قراءة الأجهزة...");
                handler.postDelayed(this::readWirelessAndFinishScan, 650);
            } else {
                finishFullScan(false, "تعذر فتح Wireless Connection Status والتحقق من مساره. افتحها يدوياً ثم أعد الفحص.");
            }
        });
    }


        private void openWirelessStatusByMenu() {
        navigateRouterSectionPath("Wireless", "Wireless Connection Status", "/wireless_state.asp", verified -> {
            if (verified) handler.postDelayed(this::readWirelessAndFinishScan, 650);
            else finishFullScan(false, "تعذر فتح Wireless Connection Status. افتحها يدوياً ثم أعد الفحص.");
        });
    }


    private void readWirelessAndFinishScan() {
        readDevicesFromPage(() -> {
            boolean complete = hasDhcpSnapshot && hasWirelessSnapshot;
            finishFullScan(complete, complete
                    ? "اكتملت قراءة DHCP وWi-Fi ودمج الأجهزة بنجاح."
                    : "لم تكتمل قراءة إحدى القائمتين. راجع آخر قراءة DHCP وWi-Fi ثم أعد الفحص.");
        });
    }

    /**
     * Navigate the router's existing same-origin content frame to a verified
     * local page path. This is more reliable than matching translated/menu text.
     * Returns false when no suitable frame or exact href is present.
     */
    private void navigateRouterContentPath(String targetPath, BooleanResult callback) {
        String js = "(function(){" +
                "var target=" + JSONObject.quote(targetPath) + ";" +
                "function path(w){try{return new URL(w.location.href).pathname;}catch(e){return '';}}" +
                "function full(){try{return new URL(target,window.location.href).href;}catch(e){return location.protocol+'//'+location.host+target;}}" +
                "var wins=[];" +
                "function walk(w,depth){try{wins.push({w:w,depth:depth,path:path(w)});for(var i=0;i<w.frames.length;i++){try{walk(w.frames[i],depth+1);}catch(e){}}}catch(e){}}" +
                "walk(window,0);" +
                "var current=false,links=[];" +
                "for(var i=0;i<wins.length;i++){" +
                "if(wins[i].path===target)current=true;" +
                "try{var as=wins[i].w.document.querySelectorAll('a[href]');" +
                "for(var j=0;j<as.length;j++){try{" +
                "var u=new URL(as[j].href,wins[i].w.location.href);" +
                "if(u.pathname===target&&u.host===location.host)links.push({a:as[j],depth:wins[i].depth});" +
                "}catch(e){}}}catch(e){}}" +
                "links.sort(function(a,b){return b.depth-a.depth;});" +
                "for(var k=0;k<links.length;k++){try{links[k].a.click();return true;}catch(e){}}" +
                "if(current)return true;" +
                "var candidates=wins.filter(function(x){return x.depth>0&&x.path!=='/advance.asp'&&x.path!=='/index.asp'&&x.path!=='/';});" +
                "candidates.sort(function(a,b){return b.depth-a.depth;});" +
                "if(candidates.length){try{candidates[0].w.location.href=full();return true;}catch(e){}}" +
                "var nested=wins.filter(function(x){return x.depth>0;});" +
                "if(nested.length){try{nested[nested.length-1].w.location.href=full();return true;}catch(e){}}" +
                "try{window.location.href=full();return true;}catch(e){}" +
                "return false;})()";
        webView.evaluateJavascript(js, result -> callback.onResult("true".equals(result)));
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
                "var docs=collectDocs(),dhcp=[],wifi=[],staticReservations=[],generic=[],seenD={},seenW={},seenS={},seenG={},foundD=false,foundW=false,foundS=false,framePaths=[];" +
                "for(var x=0;x<docs.length;x++){var d=docs[x];framePaths.push(pathOf(d));var tables=d.querySelectorAll('table');" +
                "for(var t=0;t<tables.length;t++){var table=tables[t],rows=table.querySelectorAll('tr'),headerAt=-1,headers=[],kind='';" +
                "for(var r=0;r<rows.length;r++){var hc=Array.prototype.slice.call(rows[r].querySelectorAll('th,td')).map(cellText);var hn=hc.map(norm);" +
                "var hasMac=hn.some(function(v){return v==='mac address'||v.indexOf('mac address')>=0;}),hasIp=hn.some(function(v){return v==='ip address'||v.indexOf('ip address')>=0;}),hasHost=hn.some(function(v){return v==='host name'||v.indexOf('host name')>=0;}),hasLease=hn.some(function(v){return v==='lease time'||v.indexOf('lease time')>=0;}),hasBw=hn.some(function(v){return v==='bandwidth'||v.indexOf('bandwidth')>=0;}),hasDelete=hn.some(function(v){return v==='delete'||v.indexOf('delete')>=0;});" +
                "if(hasMac&&hasIp&&hasHost&&hasLease){kind='dhcp';headerAt=r;headers=hc;break;}if(hasMac&&hasBw){kind='wifi';headerAt=r;headers=hc;break;}if(hasMac&&hasIp&&hasDelete){kind='static';headerAt=r;headers=hc;break;}}" +
                "if(!kind)continue;if(kind==='dhcp')foundD=true;else if(kind==='wifi')foundW=true;else foundS=true;" +
                "var macI=idx(headers,'MAC Address'),ipI=idx(headers,'IP Address'),nameI=idx(headers,'Host Name'),leaseI=idx(headers,'Lease Time'),bwI=idx(headers,'Bandwidth');" +
                "for(var rr=headerAt+1;rr<rows.length;rr++){var row=rows[rr];if(row.querySelector('th'))continue;var cells=Array.prototype.slice.call(row.querySelectorAll('td')).map(cellText);if(!cells.length)continue;var raw=cells.join(' | '),mac=macValue(macI>=0?cells[macI]:raw);if(!mac){if(kind==='static'&&raw.replace(/\\s/g,'').length>0)staticReservations.push({ip:'',mac:'',sourcePath:pathOf(d),raw:raw});continue;}" +
                "if(kind==='dhcp'){if(seenD[mac])continue;seenD[mac]=1;var ip=ipI>=0?(cells[ipI]||''):'';if(!/^\\d{1,3}(?:\\.\\d{1,3}){3}$/.test(ip)){var im=raw.match(/(?:\\d{1,3}\\.){3}\\d{1,3}/);ip=im?im[0]:'';}var nm=nameI>=0?(cells[nameI]||''):'';var lease=leaseI>=0?(cells[leaseI]||''):'';dhcp.push({mac:mac,ip:ip,name:nm,lease:lease,sourcePath:pathOf(d),raw:raw});}" +
                "else if(kind==='wifi'){if(seenW[mac])continue;seenW[mac]=1;var bw=bwI>=0?(cells[bwI]||''):'';if(!bw){for(var z=0;z<cells.length;z++){if(/^\\d{1,3}\\s*M$/i.test(cells[z])){bw=cells[z].toUpperCase();break;}}}wifi.push({mac:mac,bandwidth:bw,sourcePath:pathOf(d),raw:raw});}" +
                "else{var sip=ipI>=0?(cells[ipI]||''):'';if(!/^\\d{1,3}(?:\\.\\d{1,3}){3}$/.test(sip)){var sim=raw.match(/(?:\\d{1,3}\\.){3}\\d{1,3}/);sip=sim?sim[0]:'';}var sk=sip+'|'+mac;if(seenS[sk])continue;seenS[sk]=1;staticReservations.push({ip:sip,mac:mac,sourcePath:pathOf(d),raw:raw});}}}}" +
                "var onDhcpClientPage=docs.some(function(d){return pathOf(d).indexOf('/lan_dhcp_clients.asp')>=0;}),onDeviceListPage=docs.some(function(d){var p=pathOf(d);return p.indexOf('/lan_dhcp_clients.asp')>=0||p.indexOf('/wireless_state.asp')>=0;});" +
                "if(!foundD&&!foundW&&!foundS&&onDeviceListPage){for(var x=0;x<docs.length;x++){var ts=docs[x].querySelectorAll('table');for(var t=0;t<ts.length;t++){var rs=ts[t].querySelectorAll('tr');for(var r=0;r<rs.length;r++){var cells=Array.prototype.slice.call(rs[r].querySelectorAll('th,td')).map(cellText),raw=cells.join(' | '),mac=macValue(raw);if(!mac||seenG[mac])continue;seenG[mac]=1;var ipm=raw.match(/(?:\\d{1,3}\\.){3}\\d{1,3}/);generic.push({mac:mac,ip:ipm?ipm[0]:'',name:cells.length?cells[0]:'',raw:raw,sourcePath:pathOf(docs[x])});}}}}" +
                "var count=dhcp.length+wifi.length+staticReservations.length+generic.length;" +
                "if(foundD&&(dhcp.length>0||attempt>=4))TendaBridge.clients(JSON.stringify(dhcp));" +
                "if(foundW&&(wifi.length>0||attempt>=4))TendaBridge.wirelessDevices(JSON.stringify(wifi));" +
                "if(foundS)TendaBridge.staticAssignments(JSON.stringify(staticReservations));" +
                "if(!foundD&&!foundW&&!foundS&&onDeviceListPage&&(generic.length>0||attempt>=4))TendaBridge.genericDevices(JSON.stringify(generic));" +
                "if((count>0&&(dhcp.length>0||!foundD)&&(!onDhcpClientPage||foundS))||attempt>=4)TendaBridge.readResult(dhcp.length,wifi.length,generic.length,foundD,foundW);" +
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

    /**
     * Read the QoS > Traffic Statistics table from the current page or any
     * same-origin frame. This parser is separate from DHCP/Wi-Fi parsing
     * because the traffic table is keyed by IP and has no MAC column.
     */
    private void readTrafficStatisticsFromPage(boolean announceIfMissing) {
        readTrafficStatisticsFromPage(announceIfMissing, 0, null);
    }

    /**
     * Read the Traffic Statistics table from the top document or any same-origin
     * router frame. A short retry is important because the Tenda firmware fills
     * the statistics frame asynchronously after navigation/reload.
     */
    private void readTrafficStatisticsFromPage(boolean announceIfMissing, int attempt,
                                               TrafficReadCallback callback) {
        if (webView == null || webView.getUrl() == null) {
            if (announceIfMissing) toast("اتصل بالراوتر وسجّل الدخول أولاً.");
            if (callback != null) callback.onComplete(false);
            return;
        }
        String js = "(function(){" +
                "function txt(e){return ((e&&(e.innerText||e.textContent))||'').replace(/\\u00a0/g,' ').replace(/\\s+/g,' ').trim();}" +
                "function norm(s){return (s||'').toLowerCase().replace(/[^a-z0-9]/g,'');}" +
                "function pathOf(d){try{return new URL(d.location.href).pathname;}catch(e){return '';}}" +
                "function docs(){var a=[document],seen=[document];for(var i=0;i<a.length;i++){try{var fs=a[i].querySelectorAll('frame,iframe');for(var j=0;j<fs.length;j++){try{var d=fs[j].contentDocument;if(d&&d.documentElement&&seen.indexOf(d)<0){seen.push(d);a.push(d);}}catch(e){}}}catch(e){}}return a;}" +
                "function find(h,part){for(var i=0;i<h.length;i++){if(norm(h[i]).indexOf(part)>=0)return i;}return -1;}" +
                "function num(v){var m=String(v||'').replace(/,/g,'.').match(/-?\\d+(?:\\.\\d+)?/);return m?parseFloat(m[0]):0;}" +
                "var ds=docs(),records=[],seen={},source='';" +
                "for(var x=0;x<ds.length;x++){var d=ds[x],tables=d.querySelectorAll('table');" +
                "for(var t=0;t<tables.length;t++){var rows=tables[t].querySelectorAll('tr'),headerAt=-1,headers=[];" +
                "for(var r=0;r<rows.length;r++){var hs=Array.prototype.slice.call(rows[r].querySelectorAll('th,td')).map(txt),hn=hs.map(norm);" +
                "var ip=hn.some(function(v){return v.indexOf('ipaddress')>=0;}),up=hn.some(function(v){return v.indexOf('uplinkrate')>=0;}),down=hn.some(function(v){return v.indexOf('downlinkrate')>=0;}),sent=hn.some(function(v){return v.indexOf('sentbytes')>=0;}),received=hn.some(function(v){return v.indexOf('receivedbytes')>=0;});" +
                "if(ip&&up&&down&&sent&&received){headerAt=r;headers=hs;break;}}" +
                "if(headerAt<0)continue;" +
                "var ipI=find(headers,'ipaddress'),upI=find(headers,'uplinkrate'),downI=find(headers,'downlinkrate'),smI=find(headers,'sentmessage'),sbI=find(headers,'sentbytes'),rmI=find(headers,'receivedmessage'),rbI=find(headers,'receivedbytes');" +
                "source=pathOf(d);" +
                "for(var rr=headerAt+1;rr<rows.length;rr++){var cells=Array.prototype.slice.call(rows[rr].querySelectorAll('td')).map(txt);if(!cells.length)continue;" +
                "var ipText=ipI>=0?(cells[ipI]||''):cells.join(' '),im=ipText.match(/(?:\\d{1,3}\\.){3}\\d{1,3}/);if(!im)continue;var addr=im[0];if(seen[addr])continue;seen[addr]=1;" +
                "records.push({ip:addr,uplinkKBps:num(upI>=0?cells[upI]:''),downlinkKBps:num(downI>=0?cells[downI]:''),sentMessages:num(smI>=0?cells[smI]:''),sentMB:num(sbI>=0?cells[sbI]:''),receivedMessages:num(rmI>=0?cells[rmI]:''),receivedMB:num(rbI>=0?cells[rbI]:''),raw:cells.join(' | '),sourcePath:source});}}}" +
                "if(records.length){TendaBridge.trafficStatistics(JSON.stringify(records),source);return records.length;}return 0;})()";
        final int currentAttempt = attempt;
        webView.evaluateJavascript(js, result -> {
            int count = 0;
            try {
                String clean = result == null ? "" : result.replace("\"", "").trim();
                count = Integer.parseInt(clean);
            } catch (Exception ignored) { }
            boolean found = count > 0;
            if (!found && currentAttempt < 2) {
                handler.postDelayed(() ->
                        readTrafficStatisticsFromPage(announceIfMissing, currentAttempt + 1, callback), 650);
                return;
            }
            if (!found && announceIfMissing) {
                status("لم أتعرف على جدول Traffic Statistics. افتح QoS ثم Traffic Statistics داخل نافذة الراوتر، وانتظر تحميل الجدول، ثم أعد القراءة.");
                toast("لم أجد جدول Traffic Statistics في الصفحة الحالية. افتح صفحة الإحصائيات ثم أعد القراءة.");
            }
            if (callback != null) {
                boolean finalFound = found;
                handler.postDelayed(() -> callback.onComplete(finalFound), 180);
            }
        });
    }

    /**
     * Refresh only the router's Traffic Statistics content frame, not the whole
     * advance.asp shell. The W303R firmware exposes this page as
     * sys_iptAccount.asp inside the same-origin content frame.
     */
    private void refreshTrafficFrameThenRead(boolean announceIfMissing, TrafficReadCallback callback) {
        if (webView == null || webView.getUrl() == null) {
            if (announceIfMissing) toast("اتصل بالراوتر وافتح صفحة Traffic Statistics أولاً.");
            if (callback != null) callback.onComplete(false);
            return;
        }
        String js = "(function(){" +
                "function norm(s){return (s||'').toLowerCase().replace(/[^a-z0-9]/g,'');}" +
                "function hasTrafficTable(d){try{if(!d)return false;var ts=d.querySelectorAll('table');for(var i=0;i<ts.length;i++){var rs=ts[i].querySelectorAll('tr');for(var r=0;r<rs.length;r++){var h=Array.prototype.slice.call(rs[r].querySelectorAll('th,td')).map(function(e){return norm(e.innerText||e.textContent||'');});" +
                "var ip=h.some(function(v){return v.indexOf('ipaddress')>=0;}),up=h.some(function(v){return v.indexOf('uplinkrate')>=0;}),down=h.some(function(v){return v.indexOf('downlinkrate')>=0;}),sent=h.some(function(v){return v.indexOf('sentbytes')>=0;}),recv=h.some(function(v){return v.indexOf('receivedbytes')>=0;});if(ip&&up&&down&&sent&&recv)return true;}}}catch(e){}return false;}" +
                "function walk(w,depth){if(depth>5)return false;try{for(var i=0;i<w.frames.length;i++){var f=w.frames[i],p='',d=null;try{p=new URL(f.location.href).pathname.toLowerCase();}catch(e){}try{d=f.document;}catch(e){}" +
                "if(p.indexOf('iptaccount.asp')>=0||hasTrafficTable(d)){try{f.location.reload();return true;}catch(e){}}" +
                "if(walk(f,depth+1))return true;}}catch(e){}return false;}" +
                "var p='';try{p=location.pathname.toLowerCase();}catch(e){}" +
                "if(p.indexOf('iptaccount.asp')>=0||hasTrafficTable(document)){location.reload();return true;}" +
                "return walk(window,0);})()";
        webView.evaluateJavascript(js, result -> {
            boolean reloaded = "true".equals(result);
            long waitMs = reloaded ? 1300L : 250L;
            handler.postDelayed(() ->
                    readTrafficStatisticsFromPage(announceIfMissing, 0, callback), waitMs);
        });
    }

    private void startLiveMonitoring() {
        if (liveMonitorRunning) {
            toast("المراقبة الخلفية تعمل بالفعل.");
            return;
        }
        String requestedHost = routerIp == null ? activeHost : routerIp.getText().toString().trim();
        if (!isPrivateIpv4(requestedHost)) {
            toast("أدخل عنوان الراوتر المحلي الصحيح أولاً، مثل 192.168.0.1");
            return;
        }
        activeHost = requestedHost;
        preferences.edit()
                .putBoolean(RouterBackgroundService.PREF_ENABLED, true)
                .putString("router_ip", activeHost)
                .putInt(RouterBackgroundService.PREF_INTERVAL_MS, livePollIntervalMs)
                .apply();

        Intent service = new Intent(this, RouterBackgroundService.class)
                .setAction(RouterBackgroundService.ACTION_START)
                .putExtra(RouterBackgroundService.EXTRA_ROUTER_HOST, activeHost)
                .putExtra(RouterBackgroundService.EXTRA_INTERVAL_MS, livePollIntervalMs);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
            else startService(service);
            liveMonitorRunning = true;
            liveCycleBusy = false;
            liveConsecutiveMisses = 0;
            updateLiveControls();
            if (liveStatusView != null) {
                liveStatusView.setText("خدمة المراقبة الخلفية بدأت. أنتظر الراوتر المحلي وأعيد المحاولة تلقائياً عند الانقطاع.");
            }
            status("بدأت مراقبة Traffic Statistics في الخلفية.");
        } catch (Exception e) {
            liveMonitorRunning = false;
            preferences.edit().putBoolean(RouterBackgroundService.PREF_ENABLED, false).apply();
            updateLiveControls();
            toast("تعذر تشغيل خدمة الخلفية: " + (e.getMessage() == null ? "خطأ غير معروف" : e.getMessage()));
        }
    }

    private void stopLiveMonitoring(String reason) {
        liveMonitorRunning = false;
        liveCycleBusy = false;
        preferences.edit().putBoolean(RouterBackgroundService.PREF_ENABLED, false).apply();
        handler.removeCallbacks(liveMonitorRunnable);
        Intent stopTraffic = new Intent(this, RouterBackgroundService.class)
                .setAction(RouterBackgroundService.ACTION_STOP);
        try { startService(stopTraffic); } catch (Exception ignored) { }
        updateLiveControls();
        if (liveStatusView != null) liveStatusView.setText(reason + " آخر قراءة ناجحة: " +
                (trafficSnapshotAt > 0 ? formatTime(trafficSnapshotAt) : "غير متاحة"));
        status(reason);
    }

    private void cycleLivePollInterval() {
        livePollIntervalIndex = (livePollIntervalIndex + 1) % livePollIntervalsMs.length;
        livePollIntervalMs = livePollIntervalsMs[livePollIntervalIndex];
        preferences.edit().putInt(RouterBackgroundService.PREF_INTERVAL_MS, livePollIntervalMs).apply();
        if (liveIntervalButton != null) {
            liveIntervalButton.setText("فاصل التحديث: " + (livePollIntervalMs / 1000) + " ثوانٍ");
        }
        if (liveMonitorRunning) {
            Intent update = new Intent(this, RouterBackgroundService.class)
                    .setAction(RouterBackgroundService.ACTION_UPDATE_INTERVAL)
                    .putExtra(RouterBackgroundService.EXTRA_INTERVAL_MS, livePollIntervalMs);
            startService(update);
        }
    }

    private int normalizeLivePollInterval(int value) {
        return value == 5000 || value == 10000 ? value : 3000;
    }

    private void restoreLastTrafficSnapshot() {
        if (preferences == null || trafficCards == null) return;
        String json = preferences.getString(RouterBackgroundService.PREF_LAST_TRAFFIC_JSON, "");
        if (json == null || json.trim().isEmpty()) return;
        String source = preferences.getString(RouterBackgroundService.PREF_LAST_TRAFFIC_SOURCE, "");
        renderTrafficStatistics(json, source);
        long savedAt = preferences.getLong(RouterBackgroundService.PREF_LAST_TRAFFIC_AT, 0L);
        if (savedAt > 0L) {
            trafficSnapshotAt = savedAt;
            renderTrafficCards();
            if (liveStatusView != null) {
                liveStatusView.setText((liveMonitorRunning ? "المراقبة الخلفية مفعّلة" : "آخر قراءة محفوظة")
                        + " • وقت القراءة: " + formatTime(savedAt));
            }
        }
    }

    private boolean hasPendingMacRestoreInPreferences() {
        if (preferences == null) return false;
        String raw = preferences.getString(RouterBackgroundService.PREF_MAC_AUTO_STATE, "");
        if (raw == null || raw.trim().isEmpty()) return false;
        try {
            JSONObject state = new JSONObject(raw);
            JSONArray policies = state.optJSONArray("policies");
            if (policies == null) return false;
            for (int i = 0; i < policies.length(); i++) {
                JSONObject policy = policies.optJSONObject(i);
                if (policy != null && !policy.optBoolean("conflict", false)
                        && (policy.optBoolean("modified", false) || policy.optBoolean("pending", false))) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private void ensureBackgroundMonitorStarted() {
        if (preferences == null) return;
        boolean trafficEnabled = preferences.getBoolean(RouterBackgroundService.PREF_ENABLED, false);
        boolean macAutoEnabled = preferences.getBoolean(RouterBackgroundService.PREF_MAC_AUTO_ENABLED, false);
        boolean pendingRestore = hasPendingMacRestoreInPreferences();
        if (pendingRestore && !macAutoEnabled) {
            preferences.edit().putBoolean(RouterBackgroundService.PREF_MAC_AUTO_STOPPING, true).apply();
        }
        boolean macEnabled = macAutoEnabled
                || preferences.getBoolean(RouterBackgroundService.PREF_MAC_AUTO_STOPPING, false)
                || pendingRestore;
        if (!trafficEnabled && !macEnabled) return;
        liveMonitorRunning = trafficEnabled;
        macAutomationRunning = macEnabled;
        updateLiveControls();
        updateMacAutomationUi(macEnabled
                ? "أتمتة MAC محفوظة؛ يجري استئنافها من خدمة الخلفية."
                : "التصفير التلقائي متوقف.");
        Intent service = new Intent(this, RouterBackgroundService.class)
                .setAction(RouterBackgroundService.ACTION_START)
                .putExtra(RouterBackgroundService.EXTRA_ROUTER_HOST, activeHost)
                .putExtra(RouterBackgroundService.EXTRA_INTERVAL_MS, livePollIntervalMs);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
            else startService(service);
        } catch (Exception e) {
            if (liveStatusView != null) liveStatusView.setText("المهام محفوظة، لكن تعذر إعادة تشغيل خدمة الخلفية. افتح التطبيق مجدداً.");
        }
    }

    private void updateLiveControls() {
        if (liveStartButton != null) liveStartButton.setEnabled(!liveMonitorRunning);
        if (liveStopButton != null) liveStopButton.setEnabled(liveMonitorRunning);
        if (liveIntervalButton != null) liveIntervalButton.setEnabled(true);
    }

    private void runLiveMonitorCycle() {
        if (!liveMonitorRunning || liveLifecyclePaused) return;
        if (webView == null || webView.getUrl() == null) {
            stopLiveMonitoring("توقفت المراقبة لعدم وجود اتصال بصفحة الراوتر.");
            return;
        }
        if (liveCycleBusy) {
            handler.postDelayed(liveMonitorRunnable, 500L);
            return;
        }
        liveCycleBusy = true;
        liveCycleStartedAt = System.currentTimeMillis();
        refreshTrafficFrameThenRead(false, found -> {
            liveCycleBusy = false;
            if (!liveMonitorRunning || liveLifecyclePaused) return;
            if (found) {
                liveConsecutiveMisses = 0;
                if (liveStatusView != null) {
                    liveStatusView.setText("المراقبة الحية تعمل • آخر تحديث: " + formatTime(trafficSnapshotAt) +
                            " • الفاصل: " + (livePollIntervalMs / 1000) + " ثوانٍ");
                }
            } else {
                liveConsecutiveMisses++;
                if (liveConsecutiveMisses >= 3) {
                    stopLiveMonitoring("توقفت المراقبة: لم أجد جدول Traffic Statistics بعد 3 محاولات. افتح QoS > Traffic Statistics ثم ابدأ مجدداً.");
                    return;
                }
                if (liveStatusView != null) {
                    liveStatusView.setText("أحاول الوصول إلى جدول Traffic Statistics... المحاولة " +
                            liveConsecutiveMisses + " من 3");
                }
            }
            if (liveMonitorRunning) {
                long elapsed = System.currentTimeMillis() - liveCycleStartedAt;
                long nextDelay = Math.max(250L, livePollIntervalMs - elapsed);
                handler.postDelayed(liveMonitorRunnable, nextDelay);
            }
        });
    }

    private void renderTrafficStatistics(String json, String sourcePath) {
        try {
            JSONArray arr = new JSONArray(json);
            trafficSnapshot.clear();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;
                String ip = item.optString("ip", "").trim();
                if (ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) trafficSnapshot.put(ip, item);
            }
            trafficSnapshotAt = System.currentTimeMillis();
            trafficSourcePath = sourcePath == null ? "" : sourcePath;
            renderTrafficCards();
            if (liveStatusView != null && !liveMonitorRunning) {
                liveStatusView.setText("آخر قراءة ناجحة: " + formatTime(trafficSnapshotAt) +
                        " • المراقبة الحية متوقفة.");
            }
            status("تمت قراءة Traffic Statistics: " + trafficSnapshot.size() + " عنوان IP.");
        } catch (JSONException e) {
            if (trafficSummaryView != null) trafficSummaryView.setText("تعذر تحليل جدول Traffic Statistics. انسخ تقرير التشخيص وأرسله.");
        }
    }

    private void renderTrafficCards() {
        if (trafficSummaryView == null || trafficCards == null) return;
        final int oldScrollY = mainScrollView == null ? 0 : mainScrollView.getScrollY();
        trafficCards.removeAllViews();
        if (trafficSnapshot.isEmpty()) {
            trafficSummaryView.setText("لم يتم العثور على سجلات Traffic Statistics.");
            return;
        }
        double totalUp = 0, totalDown = 0, totalSent = 0, totalReceived = 0;
        for (JSONObject item : trafficSnapshot.values()) {
            totalUp += item.optDouble("uplinkKBps", 0);
            totalDown += item.optDouble("downlinkKBps", 0);
            totalSent += item.optDouble("sentMB", 0);
            totalReceived += item.optDouble("receivedMB", 0);
        }
        trafficSummaryView.setText("المشتركون المقروءون: " + trafficSnapshot.size() +
                "\nإجمالي سرعة الرفع الآن: " + String.format(Locale.US, "%.2f", totalUp) + " KB/s" +
                "\nإجمالي سرعة التنزيل الآن: " + String.format(Locale.US, "%.2f", totalDown) + " KB/s" +
                "\nإجمالي البيانات المرسلة: " + String.format(Locale.US, "%.2f", totalSent) + " MB" +
                "\nإجمالي البيانات المستلمة: " + String.format(Locale.US, "%.2f", totalReceived) + " MB" +
                "\nآخر تحديث: " + formatTime(trafficSnapshotAt) +
                (liveMonitorRunning ? " | تحديث آلي كل " + (livePollIntervalMs / 1000) + " ثوانٍ" : ""));

        for (JSONObject item : trafficSnapshot.values()) {
            String ip = item.optString("ip", "");
            JSONObject dhcp = knownDhcpForIp(ip);
            boolean historicalDhcp = dhcp != null && !dhcpSnapshot.containsKey(dhcp.optString("mac", ""));
            String name = dhcp == null ? "جهاز غير مربوط بسجل DHCP" :
                    displayName(dhcp.optString("mac", ""), dhcp);
            String mac = dhcp == null ? "MAC غير متاح في آخر قراءة DHCP" :
                    dhcp.optString("mac", "غير متاح");
            double up = item.optDouble("uplinkKBps", 0);
            double down = item.optDouble("downlinkKBps", 0);
            boolean active = (up + down) > 0.0001;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(11), dp(9), dp(11), dp(9));
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(Color.WHITE);
            bg.setCornerRadius(dp(10));
            bg.setStroke(dp(1), BORDER);
            row.setBackground(bg);

            row.addView(text(name + "  |  " + ip, 15, true, DARK), matchWrap());
            row.addView(text("MAC: " + mac + (historicalDhcp ? " (آخر سجل DHCP معروف)" : ""), 11, false, MUTED), matchWrap());
            row.addView(text(active ? "حركة بيانات مرصودة الآن" : "لا يوجد نقل بيانات في لحظة القراءة",
                    11, true, active ? Color.rgb(22, 128, 70) : MUTED), matchWrap());

            LinearLayout rates = new LinearLayout(this);
            rates.setOrientation(LinearLayout.HORIZONTAL);
            TextView downloadRate = text("↓ Download\n" +
                    String.format(Locale.US, "%.2f", down) + " KB/s", 13, true,
                    Color.rgb(22, 128, 70));
            TextView uploadRate = text("↑ Upload\n" +
                    String.format(Locale.US, "%.2f", up) + " KB/s", 13, true,
                    Color.rgb(37, 99, 235));
            downloadRate.setGravity(Gravity.CENTER);
            uploadRate.setGravity(Gravity.CENTER);
            rates.addView(downloadRate, new LinearLayout.LayoutParams(0, -2, 1));
            rates.addView(uploadRate, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(rates, matchWrap());

            row.addView(text("Sent: " + String.format(Locale.US, "%.2f", item.optDouble("sentMB", 0)) + " MB" +
                    "     Received: " + String.format(Locale.US, "%.2f", item.optDouble("receivedMB", 0)) + " MB",
                    12, false, MUTED), matchWrap());
            row.addView(text("Sent packets: " + String.format(Locale.US, "%.0f", item.optDouble("sentMessages", 0)) +
                    "     Received packets: " + String.format(Locale.US, "%.0f", item.optDouble("receivedMessages", 0)),
                    11, false, MUTED), matchWrap());
            trafficCards.addView(row, matchWrap());
            gap(trafficCards, 6);
        }
        if (mainScrollView != null) {
            mainScrollView.post(() -> mainScrollView.scrollTo(0, oldScrollY));
        }
    }

    // ---------------- MAC schedule automation ----------------

    private void startMacAutomation() {
        if (webView == null || webView.getUrl() == null || !isAllowedRouterUri(Uri.parse(webView.getUrl()))) {
            toast("اتصل بالراوتر وسجّل الدخول أولاً.");
            return;
        }
        if (macAutomationRunning || preferences.getBoolean(RouterBackgroundService.PREF_MAC_AUTO_ENABLED, false)) {
            toast("أتمتة MAC تعمل بالفعل في الخلفية.");
            return;
        }
        String requestedHost = routerIp == null ? activeHost : routerIp.getText().toString().trim();
        if (!isPrivateIpv4(requestedHost)) {
            toast("عنوان الراوتر المحلي غير صالح.");
            return;
        }
        activeHost = requestedHost;
        preferences.edit()
                .putString("router_ip", activeHost)
                .putBoolean(RouterBackgroundService.PREF_ENABLED, true)
                .putBoolean(RouterBackgroundService.PREF_MAC_AUTO_ENABLED, true)
                .putBoolean(RouterBackgroundService.PREF_MAC_AUTO_STOPPING, false)
                .putInt(RouterBackgroundService.PREF_INTERVAL_MS, livePollIntervalMs)
                .apply();
        Intent service = new Intent(this, RouterBackgroundService.class)
                .setAction(RouterBackgroundService.ACTION_MAC_START)
                .putExtra(RouterBackgroundService.EXTRA_ROUTER_HOST, activeHost)
                .putExtra(RouterBackgroundService.EXTRA_INTERVAL_MS, livePollIntervalMs);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
            else startService(service);
            macAutomationRunning = true;
            liveMonitorRunning = true;
            updateMacAutomationUi("تم طلب تشغيل أتمتة MAC ومراقبة المشتركين في خدمة خلفية واحدة. جارٍ قراءة السياسات.");
            updateLiveControls();
            toast("بدأت خدمة التصفير والاستعادة ومراقبة المشتركين.");
        } catch (Exception e) {
            macAutomationRunning = false;
            preferences.edit().putBoolean(RouterBackgroundService.PREF_MAC_AUTO_ENABLED, false)
                    .putBoolean(RouterBackgroundService.PREF_ENABLED, false).apply();
            updateMacAutomationUi("تعذر تشغيل الخدمة الخلفية: " + (e.getMessage() == null ? "خطأ غير معروف" : e.getMessage()));
        }
    }

    private void stopMacAutomation() {
        if (!preferences.getBoolean(RouterBackgroundService.PREF_MAC_AUTO_ENABLED, false)
                && !preferences.getBoolean(RouterBackgroundService.PREF_MAC_AUTO_STOPPING, false)) {
            toast("أتمتة MAC متوقفة بالفعل.");
            return;
        }
        preferences.edit().putBoolean(RouterBackgroundService.PREF_MAC_AUTO_ENABLED, false)
                .putBoolean(RouterBackgroundService.PREF_MAC_AUTO_STOPPING, true).apply();
        Intent service = new Intent(this, RouterBackgroundService.class)
                .setAction(RouterBackgroundService.ACTION_MAC_STOP)
                .putExtra(RouterBackgroundService.EXTRA_ROUTER_HOST, activeHost);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
            else startService(service);
            updateMacAutomationUi("طلبت إيقاف التصفير. ستبقى الخدمة تعمل حتى تستعيد كل سياسة تم تعديلها.");
            toast("سيتم إيقاف الأتمتة بعد استعادة أوقات السياسات.");
        } catch (Exception e) {
            updateMacAutomationUi("تعذر إرسال طلب الاستعادة: " + (e.getMessage() == null ? "خطأ غير معروف" : e.getMessage()));
        }
    }

    private void updateMacAutomationUi(String message) {
        if (macAutomationStatusView != null) macAutomationStatusView.setText(message);
        if (macAutomationStartButton != null) macAutomationStartButton.setEnabled(!macAutomationRunning);
        if (macAutomationStopButton != null) macAutomationStopButton.setEnabled(macAutomationRunning
                || preferences.getBoolean(RouterBackgroundService.PREF_MAC_AUTO_STOPPING, false));
    }

    private void inspectCurrentPage() {
        if (webView == null || webView.getUrl() == null) return;
        // Read-only inspection: this code records form metadata and handler source;
        // it never invokes router submit/delete functions or changes field values.
        String js = "(function(){" +
                "function pathOnly(u){try{return new URL(u,location.href).pathname;}catch(e){return '';}}" +
                "function txt(e){return ((e&&(e.innerText||e.textContent))||'').replace(/\\u00a0/g,' ').replace(/\\s+/g,' ').trim();}" +
                "function docs(){var a=[document],seen=[document];for(var i=0;i<a.length;i++){try{var fs=a[i].querySelectorAll('frame,iframe');for(var j=0;j<fs.length;j++){try{var d=fs[j].contentDocument;if(d&&d.documentElement&&seen.indexOf(d)<0){seen.push(d);a.push(d);}}catch(e){}}}catch(e){}}return a;}" +
                "var ds=docs(),o={url:pathOnly(location.href),title:document.title,frames:[],links:[],forms:[],tables:[],macSchedule:[]};" +
                "for(var x=0;x<ds.length;x++){var d=ds[x],framePath='';try{framePath=pathOnly(d.location.href);}catch(e){framePath='frame';}o.frames.push(framePath);" +
                "var as=d.querySelectorAll('a');for(var i=0;i<as.length&&o.links.length<120;i++){var a=as[i],tx=txt(a);if(tx)o.links.push({text:tx,href:pathOnly(a.href||'')});}" +
                "var fs=d.querySelectorAll('form');for(var f=0;f<fs.length&&o.forms.length<30;f++){var fm=fs[f],ob={frame:framePath,name:fm.name||'',id:fm.id||'',action:pathOnly(fm.action||''),method:fm.method||'',target:fm.target||'',enctype:fm.enctype||'',onSubmit:(fm.getAttribute('onsubmit')||'').slice(0,500),fields:[]},cs=fm.querySelectorAll('input,select,textarea,button');" +
                "for(var k=0;k<cs.length&&ob.fields.length<120;k++){var c=cs[k],typ=(c.type||'').toLowerCase(),nm=(c.name||'')+' '+(c.id||''),secret=typ==='password'||typ==='hidden'||/(pass|token|cookie|auth|session|key|csrf|stok)/i.test(nm),clickAttr=(c.getAttribute('onclick')||'').slice(0,500),changeAttr=(c.getAttribute('onchange')||'').slice(0,500),clickProp='',changeProp='';" +
                "try{if(typeof c.onclick==='function')clickProp=c.onclick.toString().slice(0,1200);}catch(e){}try{if(typeof c.onchange==='function')changeProp=c.onchange.toString().slice(0,1200);}catch(e){}" +
                "var z={tag:c.tagName.toLowerCase(),type:typ,name:c.name||'',id:c.id||'',value:secret?'[محجوب]':(c.value||''),text:txt(c),onClick:clickAttr,onChange:changeAttr,onClickHandler:clickProp,onChangeHandler:changeProp};" +
                "if(c.tagName==='SELECT'){z.options=[];for(var n=0;n<c.options.length&&n<50;n++){z.options.push({text:c.options[n].text,value:c.options[n].value});}}ob.fields.push(z);}o.forms.push(ob);}" +
                "if(framePath==='/firewall_mac.asp'){var ms={frame:framePath,functions:{},policyOptions:[],formName:'',formId:'',formAction:'',formMethod:'',controls:[]};" +
                "var fnNames=['preSubmit','onSelected','onDel','init'];for(var q=0;q<fnNames.length;q++){var fnName=fnNames[q];try{var fn=d.defaultView[fnName];ms.functions[fnName]=typeof fn==='function'?fn.toString().slice(0,6000):'غير موجود';}catch(e){ms.functions[fnName]='تعذر القراءة';}}" +
                "var policy=d.getElementById('curNum');if(policy){for(var po=0;po<policy.options.length&&po<20;po++){ms.policyOptions.push({text:policy.options[po].text,value:policy.options[po].value});}}" +
                "var mf=d.forms['frmSetup']||d.querySelector('form');if(mf){ms.formName=mf.name||'';ms.formId=mf.id||'';ms.formAction=pathOnly(mf.action||'');ms.formMethod=mf.method||'';var mc=mf.querySelectorAll('input,select,textarea,button');for(var mi=0;mi<mc.length&&mi<100;mi++){var el=mc[mi],et=(el.type||'').toLowerCase(),en=(el.name||'')+' '+(el.id||''),hide=et==='password'||et==='hidden'||/(pass|token|cookie|auth|session|key|csrf|stok)/i.test(en);ms.controls.push({tag:el.tagName.toLowerCase(),type:et,name:el.name||'',id:el.id||'',value:hide?'[محجوب]':(el.value||''),checked:el.type==='checkbox'?!!el.checked:undefined,onChange:(el.getAttribute('onchange')||'').slice(0,500),onClick:(el.getAttribute('onclick')||'').slice(0,500)});}}" +
                "o.macSchedule.push(ms);}" +
                "var ts=d.querySelectorAll('table');for(var t=0;t<ts.length&&o.tables.length<40;t++){var tab=ts[t],rs=tab.querySelectorAll('tr'),headers=[],headerAt=-1,preview=[];for(var r=0;r<rs.length;r++){var cc=Array.prototype.slice.call(rs[r].querySelectorAll('th,td')).map(txt);var joined=cc.join(' ').toLowerCase();if((joined.indexOf('host name')>=0&&joined.indexOf('lease time')>=0)||(joined.indexOf('mac address')>=0&&joined.indexOf('bandwidth')>=0)||(joined.indexOf('ip address')>=0&&joined.indexOf('uplink')>=0&&joined.indexOf('downlink')>=0)){headers=cc;headerAt=r;break;}}if(headerAt<0&&rs.length>0){headers=Array.prototype.slice.call(rs[0].querySelectorAll('th,td')).map(txt);headerAt=0;}var maxRows=framePath==='/firewall_mac.asp'?20:5;for(var pr=headerAt+1;pr<rs.length&&preview.length<maxRows;pr++){var vals=Array.prototype.slice.call(rs[pr].querySelectorAll('th,td')).map(txt);if(vals.length)preview.push(vals);}o.tables.push({frame:framePath,headers:headers,rowCount:rs.length,previewRows:preview});}}" +
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
            if (arr.length() == 0) {
                // The JavaScript parser calls this only after it has recognized the DHCP table.
                // Therefore an empty array is a verified empty lease list, not an early DOM read.
                dhcpSnapshot.clear();
                hasDhcpSnapshot = true;
                dhcpSnapshotAt = System.currentTimeMillis();
                renderCombinedDevices();
                renderReservationCards();
                status("تمت قراءة جدول DHCP، ولا توجد إيجارات حالية ظاهرة فيه.");
                return;
            }
            long now = System.currentTimeMillis();
            Map<String, JSONObject> fresh = new LinkedHashMap<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;
                String mac = MacAddress.normalize(item.optString("mac", ""));
                if (mac.isEmpty()) continue;
                JSONObject record = new JSONObject(item.toString());
                record.put("mac", mac);
                record.put("seenAt", now);
                fresh.put(mac, record);
                dhcpHistory.put(mac, record);
            }
            if (fresh.isEmpty()) {
                status("لم أجد سجلات DHCP صالحة؛ احتفظت بآخر البيانات الصحيحة.");
                return;
            }
            dhcpSnapshot.clear();
            dhcpSnapshot.putAll(fresh);
            pruneDhcpHistory(now);
            saveDhcpHistory();
            hasDhcpSnapshot = true;
            dhcpSnapshotAt = now;
            renderCombinedDevices();
            renderReservationCards();
            if (!trafficSnapshot.isEmpty()) renderTrafficCards();
            status("تم تحديث قائمة DHCP: " + dhcpSnapshot.size() + " سجلاً، وحُفظت آخر عناوين IP المعروفة.");
        } catch (JSONException e) {
            devicesView.setText("تعذر تحليل قائمة DHCP. أرسل تقرير التشخيص.");
        }
    }

    private void readStaticAssignments() {
        openDhcpPageAndRead(() -> {
            if (!hasStaticAssignmentSnapshot) {
                status("لم أتعرف على جدول Static Assignment. افتح Advanced > DHCP Client List يدوياً ثم أعد القراءة.");
                return;
            }
            status("تمت قراءة حجوزات IP الثابتة: " + staticAssignmentSnapshot.size() + " حجزاً.");
            renderReservationCards();
        });
    }

    private void openDhcpPageAndRead(Runnable completion) {
        if (webView == null || webView.getUrl() == null) {
            toast("اتصل بالراوتر وسجّل الدخول أولاً.");
            return;
        }
        hasStaticAssignmentSnapshot = false;
        staticAssignmentHasInvalidRows = false;
        staticAssignmentSnapshot.clear();
        hasDhcpSnapshot = false;
        renderReservationCards();
        navigateRouterSectionPath("Advanced", "DHCP Client List", "/lan_dhcp_clients.asp", verified -> {
            if (!verified) {
                status("لم أتحقق من فتح DHCP Client List؛ لم أنفذ أي تغيير.");
                return;
            }
            handler.postDelayed(() -> readDevicesFromPage(() -> {
                if (completion != null) completion.run();
            }), 650);
        });
    }


    private void prepareStaticReservation() {
        String rawIp = reservationIpInput == null ? "" : reservationIpInput.getText().toString().trim();
        String rawMac = reservationMacInput == null ? "" : reservationMacInput.getText().toString().trim();
        if (rawIp.isEmpty() || rawMac.isEmpty()) {
            toast("أدخل IP وMAC أولاً.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("فحص حجز IP جديد")
                .setMessage("سيقرأ التطبيق DHCP وStatic Assignment من الراوتر، ثم يفحص التعارض ويملأ حقول Static Assignment فقط. لن يضغط Add أو OK ولن يحفظ أي تغيير تلقائياً.")
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("فحص ومتابعة", (dialog, which) -> openDhcpPageAndRead(() -> validateAndFillReservation(rawIp, rawMac)))
                .show();
    }

    private void validateAndFillReservation(String rawIp, String rawMac) {
        if (!hasDhcpSnapshot || !hasStaticAssignmentSnapshot || dhcpSnapshotAt <= 0L || staticAssignmentSnapshotAt <= 0L) {
            showReservationMessage("تعذر التحقق", "لم تكتمل قراءة DHCP وStatic Assignment معاً. لم يتم تعبئة النموذج. أعد قراءة صفحة DHCP Client List.");
            return;
        }
        if (staticAssignmentHasInvalidRows) {
            showReservationMessage("جدول الحجوزات غير مكتمل", "وجد التطبيق صفاً أو أكثر في Static Assignment بلا IP صالح. لا يمكن ضمان عدم التعارض؛ راجع الجدول في الراوتر وصحح الصفوف قبل الحجز.");
            return;
        }
        java.util.List<IpReservationValidator.Entry> leases = new java.util.ArrayList<>();
        for (JSONObject item : dhcpSnapshot.values()) {
            if (IpReservationValidator.normalizeIpv4(item.optString("ip", "")).isEmpty()
                    || MacAddress.normalize(item.optString("mac", "")).isEmpty()) {
                showReservationMessage("سجل DHCP غير مكتمل", "يوجد سجل DHCP لا يحتوي على IP وMAC صالحين. أعد قراءة الصفحة وتأكد من اكتمال جدول DHCP قبل الحجز.");
                return;
            }
            leases.add(new IpReservationValidator.Entry(item.optString("ip", ""), item.optString("mac", "")));
        }
        java.util.List<IpReservationValidator.Entry> reservations = new java.util.ArrayList<>();
        for (JSONObject item : staticAssignmentSnapshot.values()) {
            reservations.add(new IpReservationValidator.Entry(item.optString("ip", ""), item.optString("mac", "")));
        }
        IpReservationValidator.Result result = IpReservationValidator.validate(rawIp, rawMac, activeHost, leases, reservations);
        if (!result.allowed) {
            showReservationMessage(result.alreadyReserved ? "الحجز موجود بالفعل" : "تم منع الحجز بسبب تعارض", result.message);
            return;
        }
        String[] octets = result.ip.split("\\.");
        String lastOctet = octets[3];
        String script = "(function(){" +
                "function docs(){var a=[document];for(var i=0;i<a.length;i++){try{var fs=a[i].querySelectorAll('frame,iframe');for(var j=0;j<fs.length;j++){try{var d=fs[j].contentDocument;if(d&&a.indexOf(d)<0)a.push(d);}catch(e){}}}catch(e){}}return a;}" +
                "var ds=docs();for(var i=0;i<ds.length;i++){var d=ds[i],ip=d.getElementById('IP'),macs=d.querySelectorAll('input[id=\"MAC\"]');if(!ip||macs.length<6)continue;" +
                "ip.value=" + JSONObject.quote(lastOctet) + ";var parts=" + JSONObject.quote(result.mac) + ".split(':');for(var j=0;j<6;j++){macs[j].value=parts[j];}try{ip.scrollIntoView({block:'center'});}catch(e){}return 'filled';}return 'fields-not-found';})()";
        webView.evaluateJavascript(script, value -> {
            if ("\"filled\"".equals(value)) {
                String message = "اجتاز الحجز فحص التعارض. تم ملء الحقول فقط:\nIP: " + result.ip + "\nMAC: " + result.mac +
                        "\n\nلم يضغط التطبيق Add أو OK. راجع الحقول في صفحة الراوتر، ثم اضغط Add وبعدها OK بنفسك إذا كانت البيانات صحيحة.";
                showReservationMessage("النموذج جاهز للمراجعة", message);
                status("تم فحص الحجز وتعبئة نموذج الراوتر دون حفظ أي تغيير.");
            } else {
                showReservationMessage("لم أجد حقول Static Assignment", "وصل التطبيق إلى صفحة DHCP، لكنه لم يتعرف على حقول IP وMAC الستة. لم يتم تعديل أي حقل. افتح الصفحة يدوياً وأرسل تقرير التشخيص.");
            }
        });
    }

    private void showReservationMessage(String title, String message) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("حسناً", null).show();
    }

    private void renderStaticAssignments(String json) {
        try {
            JSONArray arr = new JSONArray(json);
            Map<String, JSONObject> fresh = new LinkedHashMap<>();
            staticAssignmentHasInvalidRows = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;
                String ip = IpReservationValidator.normalizeIpInput(item.optString("ip", ""), activeHost);
                String mac = MacAddress.normalize(item.optString("mac", ""));
                if (ip.isEmpty() || mac.isEmpty()) {
                    staticAssignmentHasInvalidRows = true;
                    continue;
                }
                JSONObject record = new JSONObject(item.toString());
                record.put("ip", ip);
                record.put("mac", mac);
                fresh.put(ip + "|" + mac, record);
            }
            staticAssignmentSnapshot.clear();
            staticAssignmentSnapshot.putAll(fresh);
            hasStaticAssignmentSnapshot = true;
            staticAssignmentSnapshotAt = System.currentTimeMillis();
            renderReservationCards();
            renderCombinedDevices();
        } catch (JSONException e) {
            hasStaticAssignmentSnapshot = false;
            status("تعذر تحليل جدول Static Assignment. لم يتم تغيير بيانات DHCP.");
        }
    }

    private JSONObject reservationForMac(String mac) {
        JSONObject found = null;
        for (JSONObject item : staticAssignmentSnapshot.values()) {
            if (mac.equals(item.optString("mac", ""))) {
                if (found != null) return found; // Duplicate reservations remain visible in the reservation list.
                found = item;
            }
        }
        return found;
    }

    private int reservationCountForMac(String mac) {
        int count = 0;
        for (JSONObject item : staticAssignmentSnapshot.values()) {
            if (mac.equals(item.optString("mac", ""))) count++;
        }
        return count;
    }

    private JSONObject otherReservationForMac(String mac, String currentIp) {
        for (JSONObject item : staticAssignmentSnapshot.values()) {
            if (mac.equals(item.optString("mac", "")) && !currentIp.equals(item.optString("ip", ""))) return item;
        }
        return null;
    }

    private JSONObject reservationForIpByOtherMac(String ip, String mac) {
        for (JSONObject item : staticAssignmentSnapshot.values()) {
            if (ip.equals(item.optString("ip", "")) && !mac.equals(item.optString("mac", ""))) return item;
        }
        return null;
    }

    private JSONObject dhcpForIp(String ip) {
        if (ip == null || ip.isEmpty()) return null;
        for (JSONObject item : dhcpSnapshot.values()) {
            if (ip.equals(item.optString("ip", ""))) return item;
        }
        return null;
    }

    private void renderReservationCards() {
        if (staticReservationCards == null || staticReservationSummaryView == null) return;
        staticReservationCards.removeAllViews();
        if (!hasStaticAssignmentSnapshot) {
            staticReservationSummaryView.setText("لم تتم قراءة جدول Static Assignment بعد.");
            return;
        }
        int conflicts = 0, active = 0, idle = 0, mismatched = 0, duplicateMacs = 0;
        for (JSONObject reservation : staticAssignmentSnapshot.values()) {
            String ip = reservation.optString("ip", "");
            String mac = reservation.optString("mac", "");
            JSONObject leaseAtIp = dhcpForIp(ip);
            JSONObject leaseForMac = dhcpSnapshot.get(mac);
            if (reservationForIpByOtherMac(ip, mac) != null || (leaseAtIp != null && !mac.equals(leaseAtIp.optString("mac", "")))) conflicts++;
            else if (otherReservationForMac(mac, ip) != null) duplicateMacs++;
            else if (leaseAtIp != null) active++;
            else if (leaseForMac != null && !ip.equals(leaseForMac.optString("ip", ""))) mismatched++;
            else idle++;
        }
        staticReservationSummaryView.setText("عدد الحجوزات الثابتة: " + staticAssignmentSnapshot.size() +
                " | مستخدمة من الجهاز نفسه: " + active + " | غير ظاهرة في DHCP: " + idle + " | IP غير مطابق: " + mismatched + " | تعارض IP: " + conflicts + " | MAC له أكثر من حجز: " + duplicateMacs +
                (staticAssignmentHasInvalidRows ? "\nتنبيه: توجد صفوف غير مكتملة؛ فحص الحجز معطّل." : "") +
                "\nآخر قراءة: " + formatTime(staticAssignmentSnapshotAt));
        if (staticAssignmentSnapshot.isEmpty()) {
            TextView empty = text("جدول Static Assignment فارغ. لا توجد حجوزات IP ثابتة حالياً.", 12, false, MUTED);
            staticReservationCards.addView(empty, matchWrap());
            return;
        }
        for (JSONObject reservation : staticAssignmentSnapshot.values()) {
            String ip = reservation.optString("ip", "");
            String mac = reservation.optString("mac", "");
            JSONObject leaseAtIp = dhcpForIp(ip);
            JSONObject leaseForMac = dhcpSnapshot.get(mac);
            String state;
            int stateColor;
            JSONObject staticIpConflict = reservationForIpByOtherMac(ip, mac);
            JSONObject duplicateMacReservation = otherReservationForMac(mac, ip);
            if (staticIpConflict != null) {
                state = "تعارض: هذا IP محجوز أيضاً لـ MAC آخر " + staticIpConflict.optString("mac", "");
                stateColor = Color.rgb(180, 45, 45);
            } else if (duplicateMacReservation != null) {
                state = "تنبيه: هذا MAC لديه حجز ثابت آخر " + duplicateMacReservation.optString("ip", "");
                stateColor = Color.rgb(180, 105, 0);
            } else if (leaseAtIp != null && !mac.equals(leaseAtIp.optString("mac", ""))) {
                state = "تعارض محتمل: هذا IP مستخدم بواسطة MAC آخر " + leaseAtIp.optString("mac", "");
                stateColor = Color.rgb(180, 45, 45);
            } else if (leaseAtIp != null) {
                state = "محجوز ومستخدم حالياً من الجهاز نفسه عبر DHCP";
                stateColor = Color.rgb(22, 128, 70);
            } else if (leaseForMac != null && !ip.equals(leaseForMac.optString("ip", ""))) {
                state = "الحجز لا يطابق IP الحالي للجهاز: " + leaseForMac.optString("ip", "");
                stateColor = Color.rgb(180, 105, 0);
            } else {
                state = "محجوز، لكنه غير ظاهر حالياً في DHCP";
                stateColor = MUTED;
            }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(10), dp(9), dp(10), dp(9));
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(Color.rgb(250, 250, 251));
            bg.setCornerRadius(dp(9));
            bg.setStroke(dp(1), BORDER);
            row.setBackground(bg);
            row.addView(text("IP المحجوز: " + ip, 14, true, DARK), matchWrap());
            row.addView(text("MAC: " + mac, 12, false, MUTED), matchWrap());
            row.addView(text("الحالة: " + state, 12, true, stateColor), matchWrap());
            Button open = button("فتح صفحة الحجوزات لإدارة هذا العنوان", false);
            open.setOnClickListener(v -> openRouterSection("Advanced", "DHCP Client List"));
            row.addView(open, matchWrap());
            staticReservationCards.addView(row, matchWrap());
            gap(staticReservationCards, 6);
        }
    }

    private void loadDhcpHistory() {
        try {
            JSONArray saved = new JSONArray(preferences.getString("dhcp_history_v1_" + activeHost, "[]"));
            long now = System.currentTimeMillis();
            for (int i = 0; i < saved.length(); i++) {
                JSONObject item = saved.optJSONObject(i);
                if (item == null) continue;
                String mac = MacAddress.normalize(item.optString("mac", ""));
                String ip = item.optString("ip", "");
                long seenAt = item.optLong("seenAt", 0L);
                if (!mac.isEmpty() && isValidRouterIp(ip) && seenAt > 0 && now - seenAt <= DHCP_HISTORY_MAX_AGE_MS) {
                    item.put("mac", mac);
                    dhcpHistory.put(mac, item);
                }
            }
        } catch (Exception ignored) { }
    }

    private boolean isValidRouterIp(String ip) {
        return ip != null && ip.matches("\\d{1,3}(\\.\\d{1,3}){3}") && !"0.0.0.0".equals(ip);
    }

    private void pruneDhcpHistory(long now) {
        java.util.Iterator<Map.Entry<String, JSONObject>> it = dhcpHistory.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, JSONObject> entry = it.next();
            if (now - entry.getValue().optLong("seenAt", 0L) > DHCP_HISTORY_MAX_AGE_MS) it.remove();
        }
    }

    private void saveDhcpHistory() {
        JSONArray saved = new JSONArray();
        for (JSONObject item : dhcpHistory.values()) saved.put(item);
        preferences.edit().putString("dhcp_history_v1_" + activeHost, saved.toString()).apply();
    }

    private JSONObject knownDhcpForMac(String mac) {
        JSONObject current = dhcpSnapshot.get(mac);
        if (current != null) return current;
        JSONObject known = dhcpHistory.get(mac);
        if (known == null) return null;
        long seenAt = known.optLong("seenAt", 0L);
        if (seenAt <= 0 || System.currentTimeMillis() - seenAt > DHCP_HISTORY_MAX_AGE_MS) return null;
        return known;
    }

    private JSONObject knownDhcpForIp(String ip) {
        for (JSONObject item : dhcpSnapshot.values()) {
            if (ip.equals(item.optString("ip", ""))) return item;
        }
        JSONObject newest = null;
        long newestSeenAt = 0L;
        long now = System.currentTimeMillis();
        for (JSONObject item : dhcpHistory.values()) {
            if (!ip.equals(item.optString("ip", ""))) continue;
            long seenAt = item.optLong("seenAt", 0L);
            if (seenAt > 0 && now - seenAt <= DHCP_HISTORY_MAX_AGE_MS && seenAt > newestSeenAt) {
                newest = item;
                newestSeenAt = seenAt;
            }
        }
        return newest;
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
        summary.append("Static Assignment: ").append(hasStaticAssignmentSnapshot ? staticAssignmentSnapshot.size() : "لم تُقرأ").append("\n");
        if (hasDhcpSnapshot && hasWirelessSnapshot) {
            summary.append("في القائمتين: ").append(both)
                    .append("  |  Wi-Fi فقط: ").append(wifiOnly)
                    .append("  |  DHCP فقط: ").append(dhcpOnly);
        }
        if (devicesView != null) devicesView.setText(summary.toString());

        StringBuilder report = new StringBuilder(summary).append("\n");
        for (String mac : macs) {
            JSONObject current = dhcpSnapshot.get(mac);
            JSONObject d = current != null ? current : knownDhcpForMac(mac);
            JSONObject w = wirelessSnapshot.get(mac);
            String ipLabel = current != null ? "IP" : (d != null ? "آخر IP معروف" : "IP");
            report.append(displayName(mac, d)).append(" | ").append(mac)
                    .append(" | ").append(ipLabel).append(": ").append(d == null ? "غير متاح" : d.optString("ip", ""))
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
        JSONObject record = dhcp != null ? dhcp : knownDhcpForMac(mac);
        String routerName = record == null ? "" : record.optString("name", "").trim();
        return routerName.isEmpty() ? "جهاز غير مسمى" : routerName;
    }

    private void renderDeviceCards(Set<String> macs) {
        if (deviceCards == null) return;
        deviceCards.removeAllViews();
        String query = searchDevices == null ? "" : searchDevices.getText().toString().trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        for (String mac : macs) {
            JSONObject currentDhcp = dhcpSnapshot.get(mac);
            JSONObject d = currentDhcp != null ? currentDhcp : knownDhcpForMac(mac);
            JSONObject w = wirelessSnapshot.get(mac);
            String name = displayName(mac, d);
            String ip = d == null ? "غير متاح" : d.optString("ip", "غير متاح");
            boolean historicalIp = currentDhcp == null && d != null && isValidRouterIp(ip);
            String lease = currentDhcp == null || d == null ? "" : d.optString("lease", "");
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
            detail.append(historicalIp ? "آخر IP معروف من DHCP: " : (currentDhcp != null ? "IP الحالي من DHCP: " : "IP: ")).append(ip).append("\nMAC: ").append(mac);
            if (historicalIp) detail.append("\nملاحظة: هذا آخر عنوان DHCP معروف، وليس تأكيداً بأنه العنوان الحالي.");
            if (!bandwidth.isEmpty()) detail.append("\nعرض قناة Wi-Fi: ").append(bandwidth);
            if (!lease.isEmpty()) detail.append("\nLease Time: ").append(lease);
            if (hasStaticAssignmentSnapshot) {
                JSONObject reservation = reservationForMac(mac);
                if (reservation != null) {
                    String reservedIp = reservation.optString("ip", "");
                    detail.append("\nIP محجوز ثابت: ").append(reservedIp);
                    if (reservationCountForMac(mac) > 1) detail.append("\nتحذير: لهذا MAC أكثر من حجز ثابت؛ راجع جدول Static Assignment.");
                    if (currentDhcp != null && !reservedIp.equals(ip)) detail.append("\nتنبيه: IP الحالي من DHCP لا يطابق الحجز الثابت.");
                    else if (currentDhcp != null) detail.append("\nDHCP الحالي يطابق الحجز الثابت.");
                }
            }
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
        JSONObject current = dhcpSnapshot.get(mac);
        JSONObject d = current != null ? current : knownDhcpForMac(mac);
        JSONObject w = wirelessSnapshot.get(mac);
        String ip = d == null ? "غير متاح" : d.optString("ip", "غير متاح");
        StringBuilder details = new StringBuilder();
        details.append("الاسم: ").append(displayName(mac, d)).append("\n");
        details.append("MAC: ").append(mac).append("\n");
        details.append(current == null ? "آخر IP معروف: " : "IP الحالي من DHCP: ").append(ip).append("\n");
        details.append("DHCP: ").append(current != null ? "موجود في آخر قراءة" : (d != null ? "لا يوجد في آخر قراءة؛ العنوان المعروض تاريخي" : "غير موجود في آخر قراءة")).append("\n");
        details.append("Wi-Fi: ").append(w == null ? "غير ظاهر في آخر لقطة" : "ظاهر في آخر لقطة").append("\n");
        if (current != null) details.append("Lease Time: ").append(current.optString("lease", "غير متاح")).append("\n");
        if (hasStaticAssignmentSnapshot) {
            JSONObject reservation = reservationForMac(mac);
            details.append("Static Assignment: ").append(reservation == null ? "لا يوجد حجز ثابت في آخر قراءة" : reservation.optString("ip", "")).append("\n");
            if (reservationCountForMac(mac) > 1) details.append("تحذير: يوجد أكثر من حجز ثابت لعنوان MAC نفسه.\n");
        } else {
            details.append("Static Assignment: لم تتم قراءته بعد\n");
        }
        if (d != null) details.append("تاريخ آخر ربط DHCP: ").append(formatTime(d.optLong("seenAt", 0L))).append("\n");
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
        public void staticAssignments(String json) {
            runOnUiThread(() -> renderStaticAssignments(json));
        }

        @JavascriptInterface
        public void trafficStatistics(String json, String sourcePath) {
            runOnUiThread(() -> renderTrafficStatistics(json, sourcePath));
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
                    status("وجدت سجلات غير مصنفة في الصفحة الحالية. لم أغيّر سجلات DHCP أو Wi-Fi المحفوظة.");
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
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(RouterBackgroundService.ACTION_STATUS);
        filter.addAction(RouterBackgroundService.ACTION_TRAFFIC);
        filter.addAction(RouterBackgroundService.ACTION_MAC_STATUS);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(backgroundMonitorReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(backgroundMonitorReceiver, filter);
            }
            monitorReceiverRegistered = true;
        } catch (Exception ignored) { }
        ensureBackgroundMonitorStarted();
        restoreLastTrafficSnapshot();
    }

    @Override
    protected void onResume() {
        super.onResume();
        liveMonitorRunning = preferences.getBoolean(RouterBackgroundService.PREF_ENABLED, false);
        macAutomationRunning = preferences.getBoolean(RouterBackgroundService.PREF_MAC_AUTO_ENABLED, false)
                || preferences.getBoolean(RouterBackgroundService.PREF_MAC_AUTO_STOPPING, false)
                || hasPendingMacRestoreInPreferences();
        updateLiveControls();
        updateMacAutomationUi(macAutomationRunning
                ? "أتمتة MAC محفوظة في الخدمة الخلفية؛ تستمر حتى عند إغلاق واجهة التطبيق."
                : "التصفير التلقائي متوقف.");
        restoreLastTrafficSnapshot();
    }

    @Override
    protected void onStop() {
        if (monitorReceiverRegistered) {
            try { unregisterReceiver(backgroundMonitorReceiver); }
            catch (Exception ignored) { }
            monitorReceiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(liveMonitorRunnable);
        if (webView != null) {
            webView.removeJavascriptInterface("TendaBridge");
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
        // Keep the local router session cookies available to RouterBackgroundService.
        CookieManager.getInstance().flush();
        super.onDestroy();
    }
}
