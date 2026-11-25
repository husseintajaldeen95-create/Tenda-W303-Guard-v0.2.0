package com.tenda.w303guard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.webkit.CookieManager;
import android.webkit.HttpAuthHandler;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebViewDatabase;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import org.json.JSONException;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Persistent local-router monitor. It deliberately watches Wi-Fi transport,
 * not NET_CAPABILITY_VALIDATED: the router remains reachable when WAN Internet
 * is down, and the service must wait through Wi-Fi/router outages without stopping.
 */
public class RouterBackgroundService extends Service {
    public static final String ACTION_START = "com.tenda.w303guard.action.MONITOR_START";
    public static final String ACTION_STOP = "com.tenda.w303guard.action.MONITOR_STOP";
    public static final String ACTION_UPDATE_INTERVAL = "com.tenda.w303guard.action.MONITOR_INTERVAL";
    public static final String ACTION_RETRY_AUTH = "com.tenda.w303guard.action.MONITOR_RETRY_AUTH";
    public static final String ACTION_STATUS = "com.tenda.w303guard.action.MONITOR_STATUS";
    public static final String ACTION_TRAFFIC = "com.tenda.w303guard.action.MONITOR_TRAFFIC";
    public static final String ACTION_MAC_START = "com.tenda.w303guard.action.MAC_AUTOMATION_START";
    public static final String ACTION_MAC_STOP = "com.tenda.w303guard.action.MAC_AUTOMATION_STOP_RESTORE";
    public static final String ACTION_MAC_STATUS = "com.tenda.w303guard.action.MAC_AUTOMATION_STATUS";

    public static final String EXTRA_ROUTER_HOST = "router_host";
    public static final String EXTRA_INTERVAL_MS = "interval_ms";
    public static final String EXTRA_RUNNING = "running";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_JSON = "traffic_json";
    public static final String EXTRA_SOURCE_PATH = "source_path";
    public static final String EXTRA_CAPTURED_AT = "captured_at";
    public static final String EXTRA_MAC_ACTIVE = "mac_active";
    public static final String EXTRA_MAC_STATUS = "mac_status";

    public static final String PREFS_NAME = "tenda_w303_guard";
    public static final String PREF_ENABLED = "live_monitor_enabled";
    public static final String PREF_INTERVAL_MS = "live_poll_interval_ms";
    public static final String PREF_LAST_TRAFFIC_JSON = "live_monitor_last_traffic_json";
    public static final String PREF_LAST_TRAFFIC_SOURCE = "live_monitor_last_traffic_source";
    public static final String PREF_LAST_TRAFFIC_AT = "live_monitor_last_traffic_at";
    public static final String PREF_MAC_AUTO_ENABLED = "mac_auto_enabled";
    public static final String PREF_MAC_AUTO_STATE = "mac_auto_state_v1";
    public static final String PREF_MAC_AUTO_STOPPING = "mac_auto_stopping";
    public static final String PREF_MAC_AUTO_RECAPTURE = "mac_auto_recapture";

    private static final String CHANNEL_ID = "tenda_router_monitor";
    private static final int NOTIFICATION_ID = 30320;
    private static final long RETRY_DELAY_MS = 15000L;
    private static final long ROUTER_PROBE_TIMEOUT_MS = 2500L;
    private static final long PAGE_TIMEOUT_MS = 15000L;
    private static final String TRAFFIC_PATH = "/sys_iptAccount.asp";
    private static final long MAC_AUTO_INTERVAL_MS = 15000L;
    private static final long MAC_OPERATION_TIMEOUT_MS = 90000L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback wifiCallback;
    private Network wifiNetwork;
    private WebView monitorWebView;
    private WebView macAutomationWebView;
    private PowerManager.WakeLock cpuWakeLock;
    private WifiManager.WifiLock wifiLock;

    private String routerHost = "192.168.0.1";
    private String currentStatus = "تهيئة خدمة المراقبة الخلفية...";
    private int pollIntervalMs = 3000;
    private int trafficMisses = 0;
    private int lastTrafficCount = 0;
    private long lastRouterProbeAt = 0L;
    private boolean monitoring = false;
    private boolean routerReachable = false;
    private boolean checkingRouter = false;
    private boolean pageLoading = false;
    private boolean authRequired = false;
    private boolean stopRequested = false;
    private boolean macAutomationBusy = false;
    private boolean macAutomationFramesReady = false;
    private boolean macAutomationAuthRequired = false;
    private boolean macAutomationStopping = false;
    private int macAutomationZeroedCount = 0;
    private int macAutomationRestoredCount = 0;
    private long macAutomationCycleStartedAt = 0L;
    private String macAutomationStatus = "أتمتة MAC غير مفعّلة.";
    private final Runnable macAutomationRunnable = this::runMacAutomationCycle;
    private final Runnable macAutomationWatchdog = () -> {
        if (!macAutomationBusy) return;
        macAutomationBusy = false;
        macAutomationFramesReady = false;
        updateMacStatus("انتهت مهلة عملية MAC دون تأكيد. احتفظت بحالة pending وأوقات السياسات الأصلية؛ سأعيد القراءة والتحقق قبل أي إعادة إرسال.");
        scheduleMacAutomation(RETRY_DELAY_MS);
    };

    private final Runnable pollRunnable = this::pollTrafficStatistics;
    private final Runnable pageTimeoutRunnable = () -> {
        if (!monitoring || !pageLoading) return;
        pageLoading = false;
        routerReachable = false;
        updateStatus("انتهت مهلة تحميل صفحة الإحصائيات. سأعيد فحص الراوتر والمحاولة تلقائياً.");
        if (wifiNetwork != null) probeRouter(wifiNetwork);
        schedulePoll(RETRY_DELAY_MS);
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("جارٍ تهيئة المراقبة المحلية..."));
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        registerWifiCallback();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        if (ACTION_STOP.equals(action)) {
            prefs.edit().putBoolean(PREF_ENABLED, false).apply();
            mainHandler.removeCallbacks(pollRunnable);
            updateStatus("تم إيقاف مراقبة حركة البيانات. أتمتة MAC تستمر إن كانت مفعّلة.");
            stopServiceIfIdle();
            return START_STICKY;
        }

        if (ACTION_MAC_STOP.equals(action)) {
            prefs.edit().putBoolean(PREF_MAC_AUTO_ENABLED, false)
                    .putBoolean(PREF_MAC_AUTO_STOPPING, true).apply();
            macAutomationStopping = true;
            startMonitoring();
            updateMacStatus("طلب إيقاف الأتمتة. سأستعيد كل سياسة غيّرت وقت بدايتها، ثم أتوقف عن التصفير.");
            if (routerReachable) scheduleMacAutomation(0L);
            else scheduleMacAutomation(RETRY_DELAY_MS);
            return START_STICKY;
        }

        if (ACTION_MAC_START.equals(action)) {
            String requestedHost = intent == null ? null : intent.getStringExtra(EXTRA_ROUTER_HOST);
            if (isPrivateIpv4(requestedHost)) routerHost = requestedHost;
            else {
                String savedHost = prefs.getString("router_ip", routerHost);
                if (isPrivateIpv4(savedHost)) routerHost = savedHost;
            }
            pollIntervalMs = normalizeInterval(intent == null
                    ? prefs.getInt(PREF_INTERVAL_MS, pollIntervalMs)
                    : intent.getIntExtra(EXTRA_INTERVAL_MS, prefs.getInt(PREF_INTERVAL_MS, pollIntervalMs)));
            // MAC automation and live subscriber monitoring share one foreground service.
            prefs.edit().putBoolean(PREF_ENABLED, true)
                    .putBoolean(PREF_MAC_AUTO_ENABLED, true)
                    .putBoolean(PREF_MAC_AUTO_STOPPING, false)
                    .putBoolean(PREF_MAC_AUTO_RECAPTURE, true)
                    .putString("router_ip", routerHost)
                    .putInt(PREF_INTERVAL_MS, pollIntervalMs).apply();
            macAutomationStopping = false;
            startMonitoring();
            updateMacStatus("تم تشغيل أتمتة MAC في الخلفية. أقرأ كل رقم سياسة بصورة مستقلة؛ تساوي الأوقات لا يدمج السياسات.");
            scheduleMacAutomation(0L);
            return START_STICKY;
        }

        if (ACTION_UPDATE_INTERVAL.equals(action)) {
            if (intent != null) {
                pollIntervalMs = normalizeInterval(intent.getIntExtra(EXTRA_INTERVAL_MS, pollIntervalMs));
                prefs.edit().putInt(PREF_INTERVAL_MS, pollIntervalMs).apply();
            }
            if (hasAnyEnabledTask()) {
                String savedHost = prefs.getString("router_ip", routerHost);
                if (isPrivateIpv4(savedHost)) routerHost = savedHost;
                startMonitoring();
                if (isTrafficEnabled()) schedulePoll(0L);
            }
            return START_STICKY;
        }

        if (ACTION_RETRY_AUTH.equals(action)) {
            if (!monitoring && hasAnyEnabledTask()) {
                String savedHost = prefs.getString("router_ip", routerHost);
                if (isPrivateIpv4(savedHost)) routerHost = savedHost;
                startMonitoring();
            }
            authRequired = false;
            macAutomationAuthRequired = false;
            if (monitoring && routerReachable) {
                if (isTrafficEnabled()) loadTrafficPage();
                if (isMacTaskActive()) {
                    macAutomationFramesReady = false;
                    loadMacAutomationShell();
                    scheduleMacAutomation(1000L);
                }
            }
            return START_STICKY;
        }

        if (intent != null && ACTION_START.equals(action)) {
            String requestedHost = intent.getStringExtra(EXTRA_ROUTER_HOST);
            if (isPrivateIpv4(requestedHost)) routerHost = requestedHost;
            else {
                String savedHost = prefs.getString("router_ip", routerHost);
                if (isPrivateIpv4(savedHost)) routerHost = savedHost;
            }
            int requestedInterval = intent.hasExtra(EXTRA_INTERVAL_MS)
                    ? intent.getIntExtra(EXTRA_INTERVAL_MS, pollIntervalMs)
                    : prefs.getInt(PREF_INTERVAL_MS, pollIntervalMs);
            pollIntervalMs = normalizeInterval(requestedInterval);
            prefs.edit().putBoolean(PREF_ENABLED, true)
                    .putString("router_ip", routerHost)
                    .putInt(PREF_INTERVAL_MS, pollIntervalMs).apply();
        } else {
            if (!hasAnyEnabledTask()) {
                stopRequested = true;
                stopForeground(true);
                stopSelf();
                return START_NOT_STICKY;
            }
            String savedHost = prefs.getString("router_ip", routerHost);
            if (isPrivateIpv4(savedHost)) routerHost = savedHost;
            pollIntervalMs = normalizeInterval(prefs.getInt(PREF_INTERVAL_MS, pollIntervalMs));
        }

        macAutomationStopping = prefs.getBoolean(PREF_MAC_AUTO_STOPPING, false);
        startMonitoring();
        return START_STICKY;
    }

    private void startMonitoring() {
        if (monitoring) {
            acquireLocks();
            discoverCurrentWifiNetwork();
            if (isTrafficEnabled()) schedulePoll(0L);
            if (isMacTaskActive()) scheduleMacAutomation(0L);
            return;
        }
        monitoring = true;
        stopRequested = false;
        acquireLocks();
        macAutomationStopping = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(PREF_MAC_AUTO_STOPPING, false);
        updateStatus("خدمة الخلفية تعمل: مراقبة المشتركين وأتمتة MAC حسب الخيارات المفعّلة. أنتظر Wi-Fi المحلي؛ لا يلزم إنترنت خارجي.");
        discoverCurrentWifiNetwork();
        if (isTrafficEnabled()) schedulePoll(RETRY_DELAY_MS);
        if (isMacTaskActive()) scheduleMacAutomation(RETRY_DELAY_MS);
    }

    private void registerWifiCallback() {
        if (connectivityManager == null || wifiCallback != null) return;
        try {
            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build();
            wifiCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    mainHandler.post(() -> {
                        if (!monitoring) return;
                        wifiNetwork = network;
                        routerReachable = false;
                        authRequired = false;
                        acquireLocks();
                        updateStatus("تم العثور على اتصال Wi-Fi. أفحص الوصول المحلي إلى الراوتر...");
                        macAutomationFramesReady = false;
                        probeRouter(network);
                    });
                }

                @Override
                public void onLost(Network network) {
                    mainHandler.post(() -> {
                        if (wifiNetwork != null && wifiNetwork.equals(network)) {
                            wifiNetwork = null;
                            routerReachable = false;
                            pageLoading = false;
                            macAutomationFramesReady = false;
                            macAutomationBusy = false;
                            mainHandler.removeCallbacks(macAutomationWatchdog);
                            mainHandler.removeCallbacks(pageTimeoutRunnable);
                            releaseLocks();
                            try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    connectivityManager.bindProcessToNetwork(null);
                                }
                            } catch (Exception ignored) { }
                            updateStatus("انقطع Wi-Fi عن الهاتف. مهام الخلفية لم تتوقف؛ أنتظر عودة الاتصال بالراوتر تلقائياً.");
                            if (isTrafficEnabled()) schedulePoll(RETRY_DELAY_MS);
                            if (isMacTaskActive()) scheduleMacAutomation(RETRY_DELAY_MS);
                        }
                    });
                }
            };
            connectivityManager.registerNetworkCallback(request, wifiCallback);
        } catch (Exception e) {
            updateStatus("تعذر تسجيل مراقبة Wi-Fi: " + safeMessage(e));
        }
    }

    private void discoverCurrentWifiNetwork() {
        if (!monitoring || connectivityManager == null) return;
        try {
            Network[] networks = connectivityManager.getAllNetworks();
            for (Network network : networks) {
                NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(network);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    wifiNetwork = network;
                    acquireLocks();
                    probeRouter(network);
                    return;
                }
            }
        } catch (Exception ignored) { }
        wifiNetwork = null;
        routerReachable = false;
        releaseLocks();
        updateStatus("بانتظار اتصال Wi-Fi. ستستأنف مراقبة المشتركين وأتمتة MAC تلقائياً عند الاتصال بالشبكة المحلية.");
        if (isMacTaskActive()) scheduleMacAutomation(RETRY_DELAY_MS);
    }

    private void probeRouter(Network network) {
        if (!monitoring || network == null || checkingRouter) return;
        checkingRouter = true;
        networkExecutor.execute(() -> {
            boolean reachable = false;
            Socket socket = null;
            try {
                socket = network.getSocketFactory().createSocket();
                socket.connect(new InetSocketAddress(routerHost, 80), (int) ROUTER_PROBE_TIMEOUT_MS);
                reachable = true;
            } catch (Exception ignored) {
                reachable = false;
            } finally {
                if (socket != null) {
                    try { socket.close(); } catch (Exception ignored) { }
                }
            }
            final boolean result = reachable;
            mainHandler.post(() -> {
                checkingRouter = false;
                if (!monitoring || wifiNetwork == null || !wifiNetwork.equals(network)) return;
                lastRouterProbeAt = System.currentTimeMillis();
                if (result) {
                    routerReachable = true;
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            connectivityManager.bindProcessToNetwork(network);
                        }
                    } catch (Exception ignored) { }
                    if (isTrafficEnabled()) {
                        ensureMonitorWebView();
                        if (!authRequired && !pageLoading) {
                            if (monitorWebView == null || monitorWebView.getUrl() == null) loadTrafficPage();
                            else if (!isTrafficPage(monitorWebView.getUrl())) loadTrafficPage();
                            else schedulePoll(0L);
                        }
                    }
                    if (isMacTaskActive()) {
                        ensureMacAutomationWebView();
                        if (!macAutomationAuthRequired) {
                            if (macAutomationWebView == null || macAutomationWebView.getUrl() == null
                                    || !isAllowedRouterUri(Uri.parse(macAutomationWebView.getUrl()))) {
                                loadMacAutomationShell();
                            }
                            scheduleMacAutomation(500L);
                        }
                    }
                    if (!authRequired) updateStatus("الراوتر متاح عبر Wi-Fi المحلي. تستمر المراقبة والأتمتة حتى لو انقطع الإنترنت الخارجي.");
                } else {
                    routerReachable = false;
                    macAutomationBusy = false;
                    macAutomationFramesReady = false;
                    mainHandler.removeCallbacks(macAutomationWatchdog);
                    updateStatus("Wi-Fi متصل، لكن " + routerHost + " لا يستجيب الآن. سأعيد الفحص تلقائياً دون إيقاف الخدمة.");
                    if (isTrafficEnabled()) schedulePoll(RETRY_DELAY_MS);
                    if (isMacTaskActive()) scheduleMacAutomation(RETRY_DELAY_MS);
                }
            });
        });
    }

    private void ensureMonitorWebView() {
        if (monitorWebView != null) return;
        try {
            monitorWebView = new WebView(this);
            WebSettings settings = monitorWebView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setDatabaseEnabled(false);
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
            settings.setSupportMultipleWindows(false);
            settings.setJavaScriptCanOpenWindowsAutomatically(false);
            settings.setSavePassword(false);
            settings.setLoadsImagesAutomatically(false);
            settings.setBlockNetworkImage(true);
            CookieManager.getInstance().setAcceptCookie(true);
            CookieManager.getInstance().flush();
            monitorWebView.addJavascriptInterface(new MonitorBridge(), "TendaBridge");
            monitorWebView.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    return !isAllowedRouterUri(request.getUrl());
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    return !isAllowedRouterUri(Uri.parse(url));
                }

                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    if (isAllowedRouterUri(request.getUrl())) return null;
                    return new WebResourceResponse("text/plain", "UTF-8",
                            new ByteArrayInputStream(new byte[0]));
                }

                @Override
                public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                    super.onPageStarted(view, url, favicon);
                    pageLoading = true;
                    schedulePageTimeout();
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    if (!isAllowedRouterUri(Uri.parse(url))) {
                        pageLoading = false;
                        mainHandler.removeCallbacks(pageTimeoutRunnable);
                        updateStatus("وصلت صفحة غير متوقعة من الراوتر. سأحاول إعادة فتح Traffic Statistics.");
                        schedulePoll(RETRY_DELAY_MS);
                        return;
                    }
                    mainHandler.postDelayed(() -> readTrafficStatistics(0), 450L);
                }

                @Override
                public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler,
                                                      String host, String realm) {
                    authRequired = true;
                    pageLoading = false;
                    mainHandler.removeCallbacks(pageTimeoutRunnable);
                    handler.cancel();
                    updateStatus("تسجيل الدخول إلى الراوتر مطلوب. افتح التطبيق وسجّل الدخول من صفحة الراوتر، ثم أعد المحاولة.");
                    schedulePoll(RETRY_DELAY_MS);
                }

                @Override
                public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    super.onReceivedError(view, request, error);
                    if (request.isForMainFrame()) {
                        pageLoading = false;
                        mainHandler.removeCallbacks(pageTimeoutRunnable);
                        routerReachable = false;
                        updateStatus("تعذر تحميل إحصائيات الراوتر مؤقتاً. سأنتظر الشبكة وأعيد الاتصال تلقائياً.");
                        schedulePoll(RETRY_DELAY_MS);
                    }
                }

                @Override
                public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                                WebResourceResponse response) {
                    super.onReceivedHttpError(view, request, response);
                    if (request.isForMainFrame()) {
                        pageLoading = false;
                        mainHandler.removeCallbacks(pageTimeoutRunnable);
                        updateStatus("أعاد الراوتر HTTP " + response.getStatusCode() + ". سأعيد المحاولة تلقائياً.");
                        schedulePoll(RETRY_DELAY_MS);
                    }
                }
            });
        } catch (Exception e) {
            monitorWebView = null;
            updateStatus("تعذر تهيئة قارئ Traffic Statistics: " + safeMessage(e));
        }
    }

    private void loadTrafficPage() {
        if (!monitoring || !routerReachable || monitorWebView == null || pageLoading || authRequired) return;
        pageLoading = true;
        schedulePageTimeout();
        String url = "http://" + routerHost + TRAFFIC_PATH;
        updateStatus("متصل بالراوتر. جارٍ قراءة سرعات Download وUpload للمشتركين...");
        try {
            monitorWebView.loadUrl(url);
        } catch (Exception e) {
            pageLoading = false;
            mainHandler.removeCallbacks(pageTimeoutRunnable);
            updateStatus("تعذر فتح Traffic Statistics؛ سأعيد المحاولة تلقائياً.");
            schedulePoll(RETRY_DELAY_MS);
        }
    }

    private void pollTrafficStatistics() {
        if (!monitoring || !isTrafficEnabled()) return;
        if (wifiNetwork == null) {
            discoverCurrentWifiNetwork();
            if (wifiNetwork == null) {
                updateStatus("بانتظار Wi-Fi. الخدمة تعمل ولم تتوقف.");
                schedulePoll(RETRY_DELAY_MS);
                return;
            }
        }
        if (!routerReachable || System.currentTimeMillis() - lastRouterProbeAt > RETRY_DELAY_MS) {
            probeRouter(wifiNetwork);
            schedulePoll(RETRY_DELAY_MS);
            return;
        }
        if (authRequired) {
            updateStatus("بانتظار تسجيل الدخول إلى الراوتر من التطبيق؛ سأعيد المحاولة بعده.");
            schedulePoll(RETRY_DELAY_MS);
            return;
        }
        if (pageLoading) {
            schedulePoll(2500L);
            return;
        }
        ensureMonitorWebView();
        if (monitorWebView == null) {
            schedulePoll(RETRY_DELAY_MS);
            return;
        }
        if (!isTrafficPage(monitorWebView.getUrl())) loadTrafficPage();
        else {
            pageLoading = true;
            schedulePageTimeout();
            try {
                monitorWebView.reload();
            } catch (Exception e) {
                pageLoading = false;
                mainHandler.removeCallbacks(pageTimeoutRunnable);
                updateStatus("تعذر تحديث الصفحة؛ سأعيد المحاولة تلقائياً.");
                schedulePoll(RETRY_DELAY_MS);
            }
        }
    }

    private void readTrafficStatistics(int attempt) {
        if (!monitoring || monitorWebView == null) {
            pageLoading = false;
            mainHandler.removeCallbacks(pageTimeoutRunnable);
            schedulePoll(RETRY_DELAY_MS);
            return;
        }
        final int currentAttempt = attempt;
        schedulePageTimeout();
        try {
            monitorWebView.evaluateJavascript(TRAFFIC_READ_JS, result -> {
                int count = 0;
                try {
                    String clean = result == null ? "" : result.replace("\"", "").trim();
                    count = Integer.parseInt(clean);
                } catch (Exception ignored) { }

                if (count <= 0 && currentAttempt < 2) {
                    mainHandler.postDelayed(() -> readTrafficStatistics(currentAttempt + 1), 650L);
                    return;
                }
                pageLoading = false;
                mainHandler.removeCallbacks(pageTimeoutRunnable);
                if (count > 0) {
                    trafficMisses = 0;
                    lastTrafficCount = count;
                    updateStatus("مراقبة الخلفية تعمل • " + count + " مشتركين • تحديث كل "
                            + (pollIntervalMs / 1000) + " ثوانٍ.");
                    schedulePoll(pollIntervalMs);
                } else {
                    trafficMisses++;
                    updateStatus("جدول Traffic Statistics غير متاح مؤقتاً (محاولات متتالية: "
                            + trafficMisses + "). لم أوقف المراقبة؛ سأعيد المحاولة.");
                    schedulePoll(RETRY_DELAY_MS);
                }
            });
        } catch (Exception e) {
            pageLoading = false;
            mainHandler.removeCallbacks(pageTimeoutRunnable);
            trafficMisses++;
            updateStatus("تعذر قراءة جدول السرعات مؤقتاً. سأعيد المحاولة دون إيقاف الخدمة.");
            schedulePoll(RETRY_DELAY_MS);
        }
    }

    private static final String TRAFFIC_READ_JS = "(function(){" +
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

    private class MonitorBridge {
        @JavascriptInterface
        public void trafficStatistics(String json, String sourcePath) {
            mainHandler.post(() -> persistTrafficSnapshot(json, sourcePath));
        }
    }

    private void persistTrafficSnapshot(String json, String sourcePath) {
        if (!monitoring || json == null) return;
        try {
            JSONArray rows = new JSONArray(json);
            long capturedAt = System.currentTimeMillis();
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(PREF_LAST_TRAFFIC_JSON, json)
                    .putString(PREF_LAST_TRAFFIC_SOURCE, sourcePath == null ? "" : sourcePath)
                    .putLong(PREF_LAST_TRAFFIC_AT, capturedAt)
                    .apply();
            Intent update = new Intent(ACTION_TRAFFIC);
            update.setPackage(getPackageName());
            update.putExtra(EXTRA_JSON, json);
            update.putExtra(EXTRA_SOURCE_PATH, sourcePath == null ? "" : sourcePath);
            update.putExtra(EXTRA_CAPTURED_AT, capturedAt);
            sendBroadcast(update);
            lastTrafficCount = rows.length();
        } catch (JSONException ignored) { }
    }

    private void schedulePageTimeout() {
        mainHandler.removeCallbacks(pageTimeoutRunnable);
        if (monitoring) mainHandler.postDelayed(pageTimeoutRunnable, PAGE_TIMEOUT_MS);
    }

    private void schedulePoll(long delayMs) {
        mainHandler.removeCallbacks(pollRunnable);
        if (monitoring && isTrafficEnabled()) mainHandler.postDelayed(pollRunnable, Math.max(500L, delayMs));
    }

    // ---------------- Background MAC reset/restore engine ----------------

    private interface JsonResultCallback { void onResult(JSONObject value, String error); }
    private interface PolicyListCallback { void onResult(List<JSONObject> policies, String error); }
    private interface PolicyOperationCallback { void onResult(boolean success, String message); }
    private interface ClockCallback { void onResult(JSONObject clock, String error); }
    private interface BooleanResult { void onResult(boolean success); }

    private boolean isTrafficEnabled() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_ENABLED, false);
    }

    private boolean isMacEnabled() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_MAC_AUTO_ENABLED, false);
    }

    private boolean isMacStopping() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_MAC_AUTO_STOPPING, false);
    }

    private boolean isMacTaskActive() {
        return MacScheduleLogic.shouldKeepAutomationTaskActive(
                isMacEnabled(), isMacStopping(), hasModifiedMacPolicies(loadMacAutomationState()));
    }

    private boolean hasAnyEnabledTask() {
        return isTrafficEnabled() || isMacTaskActive();
    }

    private void stopServiceIfIdle() {
        if (isTrafficEnabled() || isMacEnabled()) return;
        if (hasModifiedMacPolicies(loadMacAutomationState())) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_MAC_AUTO_STOPPING, true).apply();
            macAutomationStopping = true;
            if (routerReachable) scheduleMacAutomation(0L);
            else scheduleMacAutomation(RETRY_DELAY_MS);
            return;
        }
        if (isMacStopping()) return;
        monitoring = false;
        mainHandler.removeCallbacks(pollRunnable);
        mainHandler.removeCallbacks(macAutomationRunnable);
        releaseLocks();
        updateStatus("كل مهام الخلفية متوقفة.");
        stopForeground(true);
        stopSelf();
    }

    private void updateMacStatus(String message) {
        if (message == null || message.trim().isEmpty()) return;
        macAutomationStatus = message;
        Intent status = new Intent(ACTION_MAC_STATUS);
        status.setPackage(getPackageName());
        status.putExtra(EXTRA_MAC_ACTIVE, isMacTaskActive());
        status.putExtra(EXTRA_MAC_STATUS, message);
        sendBroadcast(status);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && monitoring) nm.notify(NOTIFICATION_ID, buildNotification(message));
    }

    private void scheduleMacAutomation(long delayMs) {
        mainHandler.removeCallbacks(macAutomationRunnable);
        if (monitoring && isMacTaskActive()) {
            mainHandler.postDelayed(macAutomationRunnable, Math.max(500L, delayMs));
        }
    }

    private void finishMacAutomationStop() {
        macAutomationBusy = false;
        macAutomationStopping = false;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(PREF_MAC_AUTO_ENABLED, false)
                .putBoolean(PREF_MAC_AUTO_STOPPING, false).apply();
        int conflicts = countMacConflicts(loadMacAutomationState());
        updateMacStatus(conflicts == 0
                ? "اكتملت الاستعادة. أتمتة MAC متوقفة؛ لم تبقَ سياسة معدّلة معلّقة."
                : "اكتملت الاستعادة الآلية الممكنة. توجد " + conflicts + " خانة تغيّرت يدوياً أو تغيّر MAC الخاص بها؛ تُركت دون كتابة آمنة وتحتاج مراجعة يدوية.");
        stopServiceIfIdle();
    }

    private void ensureMacAutomationWebView() {
        if (macAutomationWebView != null) return;
        try {
            macAutomationWebView = new WebView(this);
            WebSettings settings = macAutomationWebView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setDatabaseEnabled(false);
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
            settings.setSupportMultipleWindows(false);
            settings.setJavaScriptCanOpenWindowsAutomatically(false);
            settings.setSavePassword(false);
            settings.setLoadsImagesAutomatically(false);
            settings.setBlockNetworkImage(true);
            CookieManager.getInstance().setAcceptCookie(true);
            CookieManager.getInstance().flush();
            macAutomationWebView.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    return !isAllowedRouterUri(request.getUrl());
                }
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    return !isAllowedRouterUri(Uri.parse(url));
                }
                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    if (isAllowedRouterUri(request.getUrl())) return null;
                    return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
                }
                @Override
                public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler, String host, String realm) {
                    String[] credentials = null;
                    try { credentials = WebViewDatabase.getInstance(RouterBackgroundService.this)
                            .getHttpAuthUsernamePassword(host, realm); } catch (Exception ignored) { }
                    if (credentials != null && credentials.length >= 2 && credentials[0] != null && credentials[1] != null) {
                        handler.proceed(credentials[0], credentials[1]);
                    } else {
                        macAutomationAuthRequired = true;
                        handler.cancel();
                        updateMacStatus("تسجيل الدخول إلى الراوتر مطلوب. افتح التطبيق وسجّل الدخول أولاً؛ ستبقى السياسات محفوظة بانتظار الاتصال.");
                        scheduleMacAutomation(RETRY_DELAY_MS);
                    }
                }
                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    Uri finishedUri = Uri.parse(url);
                    if (!isAllowedRouterUri(finishedUri)) return;
                    String finishedPath = finishedUri.getPath() == null ? "/" : finishedUri.getPath().toLowerCase(Locale.ROOT);
                    if (!(finishedPath.endsWith("advance.asp") || finishedPath.endsWith("index.asp") || "/".equals(finishedPath))) return;
                    macAutomationAuthRequired = false;
                    macAutomationFramesReady = false;
                    ensureMacAutomationFrames(0, () -> {
                        macAutomationFramesReady = true;
                        updateMacStatus("صفحات MAC ووقت الراوتر جاهزة. جارٍ تنفيذ دورة الأتمتة الخلفية.");
                        scheduleMacAutomation(500L);
                    });
                }
                @Override
                public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    super.onReceivedError(view, request, error);
                    if (request.isForMainFrame()) {
                        macAutomationFramesReady = false;
                        macAutomationBusy = false;
                        mainHandler.removeCallbacks(macAutomationWatchdog);
                        updateMacStatus("تعذر تحميل صفحة أتمتة MAC مؤقتاً. سأنتظر عودة الراوتر وأعيد المحاولة.");
                        scheduleMacAutomation(RETRY_DELAY_MS);
                    }
                }
            });
        } catch (Exception e) {
            macAutomationWebView = null;
            updateMacStatus("تعذر إنشاء قارئ سياسات MAC في الخلفية: " + safeMessage(e));
        }
    }

    private void loadMacAutomationShell() {
        if (!monitoring || !routerReachable || !isMacTaskActive()) return;
        ensureMacAutomationWebView();
        if (macAutomationWebView == null) return;
        macAutomationFramesReady = false;
        try {
            macAutomationWebView.loadUrl("http://" + routerHost + "/advance.asp?__tenda_mac_bg=" + System.currentTimeMillis());
        } catch (Exception e) {
            updateMacStatus("تعذر فتح واجهة MAC الخلفية؛ سأعيد المحاولة.");
            scheduleMacAutomation(RETRY_DELAY_MS);
        }
    }

    private void ensureMacAutomationFrames(int attempt, Runnable callback) {
        if (macAutomationWebView == null || !routerReachable) return;
        String js = "(function(){try{var base=location.protocol+'//'+location.host;function add(id,path){var f=document.getElementById(id);if(!f){f=document.createElement('iframe');f.id=id;f.name=id;f.setAttribute('aria-hidden','true');f.style.cssText='position:absolute;left:-10000px;top:-10000px;width:1px;height:1px;border:0;';f.src=base+path;(document.body||document.documentElement).appendChild(f);}return f;}" +
                "var m=add('tendaW303MacAutomationFrame','/firewall_mac.asp');var t=add('tendaW303ClockFrame','/system_hostname.asp');" +
                "var md=m.contentDocument,td=t.contentDocument;try{var mp=md&&md.location?new URL(md.location.href).pathname:'';if(md&&md.readyState==='complete'&&(mp!='/firewall_mac.asp'||!md.getElementById('curNum')))m.src=base+'/firewall_mac.asp?__tenda_auto='+Date.now();}catch(e){m.src=base+'/firewall_mac.asp?__tenda_auto='+Date.now();}" +
                "var ready=!!(md&&md.readyState==='complete'&&md.getElementById('curNum')&&(md.forms['frmSetup']||md.querySelector('form')));return JSON.stringify({mac:ready,clock:!!(td&&td.readyState==='complete')});" +
                "}catch(e){return JSON.stringify({error:String(e)});}})()";
        macAutomationWebView.evaluateJavascript(js, raw -> {
            JSONObject result = parseJsJson(raw);
            if (result != null && result.optBoolean("mac", false) && result.optBoolean("clock", false)) {
                macAutomationFramesReady = true;
                callback.run();
            } else if (attempt < 18) {
                mainHandler.postDelayed(() -> ensureMacAutomationFrames(attempt + 1, callback), 650L);
            } else {
                macAutomationFramesReady = false;
                macAutomationBusy = false;
                mainHandler.removeCallbacks(macAutomationWatchdog);
                updateMacStatus("لم تكتمل صفحة MAC أو Time Settings. لم أغيّر أي سياسة؛ سأعيد المحاولة لاحقاً.");
                scheduleMacAutomation(RETRY_DELAY_MS);
            }
        });
    }

    private void runMacAutomationCycle() {
        if (!monitoring || !isMacTaskActive() || macAutomationBusy) return;
        macAutomationCycleStartedAt = SystemClock.elapsedRealtime();
        macAutomationStopping = isMacStopping();
        if (macAutomationStopping && !hasModifiedMacPolicies(loadMacAutomationState())) {
            finishMacAutomationStop();
            return;
        }
        if (!routerReachable || wifiNetwork == null) {
            updateMacStatus("أتمتة MAC مفعّلة ومحفوظة؛ أنتظر اتصال Wi-Fi والراوتر. سيستمر فحص السياسات عند عودة الاتصال.");
            scheduleMacAutomation(RETRY_DELAY_MS);
            return;
        }
        if (macAutomationAuthRequired) {
            updateMacStatus("أتمتة MAC مفعّلة؛ بانتظار تسجيل الدخول إلى الراوتر. سأستأنف فحص السياسات بعد المصادقة.");
            scheduleMacAutomation(RETRY_DELAY_MS);
            return;
        }
        ensureMacAutomationWebView();
        if (macAutomationWebView == null) {
            scheduleMacAutomation(RETRY_DELAY_MS);
            return;
        }
        if (macAutomationWebView.getUrl() == null || !isAllowedRouterUri(Uri.parse(macAutomationWebView.getUrl()))) {
            loadMacAutomationShell();
            scheduleMacAutomation(RETRY_DELAY_MS);
            return;
        }
        macAutomationBusy = true;
        mainHandler.removeCallbacks(macAutomationWatchdog);
        mainHandler.postDelayed(macAutomationWatchdog, MAC_OPERATION_TIMEOUT_MS);
        ensureMacAutomationFrames(0, () -> {
            if (macAutomationStopping || isMacStopping()) {
                restorePendingMacChangesNow();
                return;
            }
            // Refresh every router policy slot on every automation cycle. The saved
            // original values of policies already changed by this app are merged
            // back in, so a fresh router read can never replace their restore data.
            refreshMacPoliciesAndProcess();
        });
    }

    private void scheduleNextMacAutomationCycle() {
        long elapsed = SystemClock.elapsedRealtime() - macAutomationCycleStartedAt;
        long delay = Math.max(500L, MAC_AUTO_INTERVAL_MS - Math.max(0L, elapsed));
        scheduleMacAutomation(delay);
    }

    private void refreshMacPoliciesAndProcess() {
        readAllMacPolicies((currentPolicies, error) -> {
            if (error != null || currentPolicies == null) {
                macAutomationBusy = false;
                mainHandler.removeCallbacks(macAutomationWatchdog);
                updateMacStatus("تعذرت إعادة قراءة خانات سياسات MAC؛ لم أغيّر أي إعداد، وستبقى الأتمتة مفعّلة وتعيد المحاولة. "
                        + (error == null ? "" : error));
                scheduleMacAutomation(RETRY_DELAY_MS);
                return;
            }

            JSONObject previousState = loadMacAutomationState();
            boolean previousHasPendingChanges = hasModifiedMacPolicies(previousState);
            String previousHost = previousState == null ? "" : previousState.optString("routerHost", "");
            if (previousState != null && !routerHost.equals(previousHost) && previousHasPendingChanges) {
                macAutomationBusy = false;
                mainHandler.removeCallbacks(macAutomationWatchdog);
                updateMacStatus("توجد سياسات معدّلة محفوظة للراوتر "
                        + (previousHost.isEmpty() ? "سابق غير محدد" : previousHost)
                        + ". لن أكتب على راوتر مختلف؛ أعد الاتصال بالراوتر الأصلي لاستكمال الاستعادة بأمان.");
                scheduleMacAutomation(RETRY_DELAY_MS);
                return;
            }

            boolean forceRecapture = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getBoolean(PREF_MAC_AUTO_RECAPTURE, false) && !previousHasPendingChanges;
            if (previousState == null || !routerHost.equals(previousHost)) {
                previousState = null;
                forceRecapture = true;
            }

            JSONObject refreshedState = mergeCurrentMacPolicies(currentPolicies, previousState, forceRecapture);
            if (refreshedState == null) {
                macAutomationBusy = false;
                mainHandler.removeCallbacks(macAutomationWatchdog);
                updateMacStatus("تعذر تجهيز حالة السياسات المقروءة. لم أنفذ أي تعديل، وستبقى الأتمتة مفعّلة لإعادة المحاولة.");
                scheduleMacAutomation(RETRY_DELAY_MS);
                return;
            }
            saveMacAutomationState(refreshedState);
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putBoolean(PREF_MAC_AUTO_RECAPTURE, false).apply();

            int totalSlots = refreshedState.optInt("totalPolicySlots", currentPolicies.size());
            int eligibleCount = refreshedState.optInt("eligiblePolicyCount", 0);
            int pendingRestoreCount = modifiedPolicies(refreshedState).size();
            int conflicts = countMacConflicts(refreshedState);
            if (eligibleCount == 0) {
                updateMacStatus("التصفير التلقائي مفعّل ويعمل في الخلفية | تم فحص " + totalSlots
                        + " خانات سياسة | لا توجد سياسة جديدة مؤهلة حالياً | سياسات بانتظار الاستعادة "
                        + pendingRestoreCount + " | تعارضات تحتاج مراجعة " + conflicts
                        + " | سأعيد فحص الخانات تلقائياً كل 15 ثانية دون إيقاف الأتمتة.");
            } else {
                updateMacStatus("التصفير التلقائي مفعّل | تم فحص " + totalSlots + " خانات سياسة | السياسات المؤهلة حالياً "
                        + eligibleCount + " | سياسات بانتظار الاستعادة " + pendingRestoreCount
                        + " | تعارضات " + conflicts + " | إعادة الفحص كل 15 ثانية.");
            }

            readRouterClock((clock, clockError) -> {
                if (clockError != null || clock == null) {
                    macAutomationBusy = false;
                    mainHandler.removeCallbacks(macAutomationWatchdog);
                    updateMacStatus("تم فحص " + totalSlots + " خانات سياسة، لكن تعذرت قراءة ساعة الراوتر؛ لم أغيّر أي سياسة. "
                            + (clockError == null ? "" : clockError));
                    scheduleMacAutomation(RETRY_DELAY_MS);
                } else {
                    processMacAutomationClock(clock);
                }
            });
        });
    }

    /**
     * Builds the latest router snapshot while preserving the original start time
     * and operation flags for every policy that this app has already modified
     * or is still verifying. Policy identity is always the numbered router slot.
     */
    private JSONObject mergeCurrentMacPolicies(List<JSONObject> currentPolicies,
                                               JSONObject previousState,
                                               boolean forceRecapture) {
        try {
            JSONArray previousPolicies = previousState == null ? null : previousState.optJSONArray("policies");
            JSONArray merged = new JSONArray();
            boolean[] included = new boolean[11];
            int currentEligibleCount = 0;

            for (JSONObject current : currentPolicies) {
                if (current == null) continue;
                int number = current.optInt("number", -1);
                if (number < 1 || number > 10 || included[number]) continue;

                JSONObject previous = findMacPolicyByNumber(previousPolicies, number);
                boolean currentEligible = isEligibleMacPolicy(current);
                if (currentEligible && (forceRecapture || previous == null || !previous.optBoolean("conflict", false))) {
                    currentEligibleCount++;
                }
                if (!forceRecapture && previous != null && isMacPolicyStateProtected(previous)) {
                    // Preserve the exact original snapshot while a reset/restore is pending,
                    // completed, or marked as a manual-change conflict.
                    merged.put(new JSONObject(previous.toString()));
                    included[number] = true;
                    continue;
                }

                if (currentEligible) {
                    JSONObject fresh = new JSONObject(current.toString());
                    fresh.put("slotKey", routerHost + "#" + number);
                    fresh.put("modified", false);
                    fresh.put("modifiedDate", "");
                    fresh.put("pending", false);
                    fresh.put("conflict", false);
                    fresh.remove("conflictReason");
                    merged.put(fresh);
                    included[number] = true;
                }
            }

            // Keep modified/pending/conflicted records even if the router's selector
            // temporarily omits their slot; losing them would lose restoration data.
            if (!forceRecapture && previousPolicies != null) {
                for (int i = 0; i < previousPolicies.length(); i++) {
                    JSONObject previous = previousPolicies.optJSONObject(i);
                    if (previous == null) continue;
                    int number = previous.optInt("number", -1);
                    if (number < 1 || number > 10 || included[number]) continue;
                    if (isMacPolicyStateProtected(previous)) {
                        merged.put(new JSONObject(previous.toString()));
                        included[number] = true;
                    }
                }
            }

            JSONObject state = new JSONObject();
            state.put("schema", 3);
            state.put("routerHost", routerHost);
            state.put("capturedAt", previousState == null || forceRecapture
                    ? System.currentTimeMillis() : previousState.optLong("capturedAt", System.currentTimeMillis()));
            state.put("lastScanAt", System.currentTimeMillis());
            state.put("totalPolicySlots", currentPolicies.size());
            state.put("eligiblePolicyCount", currentEligibleCount);
            state.put("trackedPolicyCount", merged.length());
            state.put("policies", merged);
            return state;
        } catch (Exception e) {
            return null;
        }
    }

    private JSONObject findMacPolicyByNumber(JSONArray policies, int number) {
        if (policies == null) return null;
        for (int i = 0; i < policies.length(); i++) {
            JSONObject policy = policies.optJSONObject(i);
            if (policy != null && policy.optInt("number", -1) == number) return policy;
        }
        return null;
    }

    private boolean isMacPolicyStateProtected(JSONObject policy) {
        return policy.optBoolean("modified", false)
                || policy.optBoolean("pending", false)
                || policy.optBoolean("conflict", false);
    }

    private boolean isEligibleMacPolicy(JSONObject policy) {
        if (policy == null || policy.optInt("number", -1) < 1 || policy.optInt("number", -1) > 10) return false;
        if (!"pass".equalsIgnoreCase(policy.optString("filterMode", ""))
                || !policy.optBoolean("enabled", false)
                || !isValidMacValue(policy.optString("mac", ""))) return false;
        int sh = policy.optInt("startHour", -1);
        int sm = policy.optInt("startMinute", -1);
        int eh = policy.optInt("endHour", -1);
        int em = policy.optInt("endMinute", -1);
        int sd = policy.optInt("startDay", -1);
        int ed = policy.optInt("endDay", -1);
        return sh >= 0 && sh <= 23 && sm >= 0 && sm <= 59 && !(sh == 0 && sm == 0)
                && eh >= 0 && eh <= 23 && em >= 0 && em <= 59
                && sd >= 0 && sd <= 6 && ed >= 0 && ed <= 6;
    }

    private void processMacAutomationClock(JSONObject clock) {
        if (macAutomationStopping || isMacStopping()) {
            restorePendingMacChangesNow();
            return;
        }
        JSONObject state = loadMacAutomationState();
        if (state == null || !routerHost.equals(state.optString("routerHost", ""))) {
            macAutomationBusy = false;
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_MAC_AUTO_ENABLED, false).apply();
            updateMacStatus("عنوان الراوتر لا يطابق السياسات المحفوظة؛ أوقفت الأتمتة حمايةً من تعديل راوتر آخر.");
            stopServiceIfIdle();
            return;
        }
        int hour = clock.optInt("hour", -1);
        int minute = clock.optInt("minute", -1);
        int day = clock.optInt("weekday", -1);
        String date = clock.optString("date", "");
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59 || day < 0 || day > 6 || date.isEmpty()) {
            macAutomationBusy = false;
            mainHandler.removeCallbacks(macAutomationWatchdog);
            updateMacStatus("ساعة الراوتر غير مكتملة أو غير صالحة؛ لم أغيّر أي سياسة، وستبقى الأتمتة مفعّلة.");
            scheduleNextMacAutomationCycle();
            return;
        }
        JSONArray policies = state.optJSONArray("policies");
        boolean staleModification = false;
        if (policies != null) {
            for (int i = 0; i < policies.length(); i++) {
                JSONObject policy = policies.optJSONObject(i);
                if (policy == null) continue;
                String modifiedDate = policy.optBoolean("pending", false)
                        ? policy.optString("pendingDate", policy.optString("modifiedDate", ""))
                        : policy.optString("modifiedDate", "");
                if ((policy.optBoolean("modified", false) || policy.optBoolean("pending", false))
                        && !modifiedDate.isEmpty() && !modifiedDate.equals(date)) {
                    staleModification = true;
                    break;
                }
            }
        }
        boolean restoreMode = MacScheduleLogic.isRestoreTime(hour, minute) || staleModification;
        List<JSONObject> operations = new ArrayList<>();
        if (policies != null) {
            for (int i = 0; i < policies.length(); i++) {
                JSONObject policy = policies.optJSONObject(i);
                if (policy == null || policy.optBoolean("conflict", false)) continue;
                if (restoreMode) {
                    if (policy.optBoolean("modified", false) || policy.optBoolean("pending", false)) operations.add(policy);
                } else if (!policy.optBoolean("modified", false) && !policy.optBoolean("pending", false)) {
                    if (MacScheduleLogic.shouldZeroStart(hour, minute,
                            policy.optInt("startHour", -1), policy.optInt("startMinute", -1), day,
                            policy.optInt("startDay", -1), policy.optInt("endDay", -1))) operations.add(policy);
                }
            }
        }
        if (operations.isEmpty()) {
            macAutomationBusy = false;
            mainHandler.removeCallbacks(macAutomationWatchdog);
            int totalSlots = state.optInt("totalPolicySlots", policies == null ? 0 : policies.length());
            int eligibleCount = state.optInt("eligiblePolicyCount", 0);
            int pendingRestoreCount = modifiedPolicies(state).size();
            int conflicts = countMacConflicts(state);
            updateMacStatus("التصفير التلقائي مفعّل في الخلفية | ساعة الراوتر " + two(hour) + ":" + two(minute)
                    + " | خانات تم فحصها " + totalSlots + " | سياسات مؤهلة حالياً " + eligibleCount
                    + " | بانتظار الاستعادة " + pendingRestoreCount + " | تعارضات " + conflicts
                    + " | تصفير ناجح " + macAutomationZeroedCount
                    + " | استعادة ناجحة " + macAutomationRestoredCount
                    + " | الاستعادة اليومية 23:55 | الفحص الدوري كل 15 ثانية.");
            scheduleNextMacAutomationCycle();
            return;
        }
        processMacPolicyOperations(operations, 0, restoreMode, date, () -> {
            macAutomationBusy = false;
            mainHandler.removeCallbacks(macAutomationWatchdog);
            if (isMacStopping()) {
                macAutomationStopping = true;
                scheduleMacAutomation(500L);
                return;
            }
            JSONObject latest = loadMacAutomationState();
            int totalSlots = latest == null ? 0 : latest.optInt("totalPolicySlots", 0);
            int eligibleCount = latest == null ? 0 : latest.optInt("eligiblePolicyCount", 0);
            int pendingRestoreCount = latest == null ? 0 : modifiedPolicies(latest).size();
            int conflicts = countMacConflicts(latest);
            updateMacStatus((restoreMode ? "مرحلة الاستعادة" : "مرحلة التصفير")
                    + " | التصفير التلقائي مفعّل | ساعة الراوتر " + two(hour) + ":" + two(minute)
                    + " | خانات مفحوصة " + totalSlots + " | سياسات مؤهلة حالياً " + eligibleCount
                    + " | بانتظار الاستعادة " + pendingRestoreCount + " | تعارضات " + conflicts
                    + " | تصفير " + macAutomationZeroedCount + " | استعادة " + macAutomationRestoredCount
                    + " | الاستعادة اليومية 23:55.");
            scheduleNextMacAutomationCycle();
        });
    }

    private void restorePendingMacChangesNow() {
        JSONObject state = loadMacAutomationState();
        List<JSONObject> restore = modifiedPolicies(state);
        if (restore.isEmpty()) {
            mainHandler.removeCallbacks(macAutomationWatchdog);
            finishMacAutomationStop();
            return;
        }
        if (!routerReachable || !macAutomationFramesReady) {
            macAutomationBusy = false;
            mainHandler.removeCallbacks(macAutomationWatchdog);
            updateMacStatus("توجد " + restore.size() + " سياسة تحتاج استعادة. أنتظر عودة الراوتر؛ لن أحذف القيم الأصلية المحفوظة.");
            scheduleMacAutomation(RETRY_DELAY_MS);
            return;
        }
        processMacPolicyOperations(restore, 0, true, routerDateFromStateOrToday(), () -> {
            macAutomationBusy = false;
            mainHandler.removeCallbacks(macAutomationWatchdog);
            if (hasModifiedMacPolicies(loadMacAutomationState())) {
                updateMacStatus("لم تكتمل استعادة جميع السياسات. سأبقي الأوقات الأصلية محفوظة وأعيد المحاولة.");
                scheduleMacAutomation(RETRY_DELAY_MS);
            } else {
                finishMacAutomationStop();
            }
        });
    }

    private void processMacPolicyOperations(List<JSONObject> operations, int index, boolean restore,
                                            String date, Runnable onComplete) {
        if (!restore && (macAutomationStopping || isMacStopping())) { onComplete.run(); return; }
        if (index >= operations.size()) { onComplete.run(); return; }
        JSONObject policy = operations.get(index);
        executeMacPolicyOperation(policy, restore, date, (success, message) -> {
            if (!success && message != null && !message.isEmpty()) {
                updateMacStatus((restore ? "تعذر استعادة السياسة " : "تعذر تصفير السياسة ")
                        + policy.optString("number", "?") + ": " + message);
            }
            mainHandler.postDelayed(() -> processMacPolicyOperations(operations, index + 1, restore, date, onComplete), 450L);
        });
    }

    private void executeMacPolicyOperation(JSONObject original, boolean restore, String date,
                                           PolicyOperationCallback callback) {
        int number = original.optInt("number", -1);
        if (number < 1 || number > 10) { callback.onResult(false, "رقم السياسة غير صالح."); return; }
        readMacPolicyAt(number, 0, (current, error) -> {
            if (error != null || current == null) { callback.onResult(false, error == null ? "تعذرت قراءة السياسة." : error); return; }
            String expectedMac = normalizeMac(original.optString("mac", ""));
            String currentMac = normalizeMac(current.optString("mac", ""));
            if (!expectedMac.equals(currentMac)) {
                markMacConflict(original, "تغيّر MAC في خانة السياسة " + number + ".");
                callback.onResult(false, "تغيّر MAC؛ تم تجاوز الخانة دون الكتابة على جهاز آخر.");
                return;
            }
            int originalHour = original.optInt("startHour", -1);
            int originalMinute = original.optInt("startMinute", -1);
            int currentHour = current.optInt("startHour", -1);
            int currentMinute = current.optInt("startMinute", -1);
            int targetHour = restore ? originalHour : 0;
            int targetMinute = restore ? originalMinute : 0;
            if (targetHour < 0 || targetHour > 23 || targetMinute < 0 || targetMinute > 59) {
                callback.onResult(false, "وقت البداية الأصلي غير صالح."); return;
            }
            boolean pending = original.optBoolean("pending", false);
            int pendingHour = original.optInt("pendingTargetHour", -1);
            int pendingMinute = original.optInt("pendingTargetMinute", -1);
            if (pending && currentHour == pendingHour && currentMinute == pendingMinute) {
                boolean wasZero = "zero".equals(original.optString("pendingOperation", ""));
                markMacOperationComplete(original, wasZero, date);
                if (wasZero) macAutomationZeroedCount++; else macAutomationRestoredCount++;
                callback.onResult(true, "تم التحقق من عملية محفوظة سابقاً."); return;
            }
            if (currentHour == targetHour && currentMinute == targetMinute) {
                if (restore) {
                    markMacOperationComplete(original, false, "");
                    macAutomationRestoredCount++;
                } else if (pending) {
                    markMacOperationComplete(original, true, date);
                    macAutomationZeroedCount++;
                }
                callback.onResult(true, "القيمة المطلوبة موجودة بالفعل."); return;
            }
            if (restore) {
                if (currentHour != 0 || currentMinute != 0) {
                    markMacConflict(original, "وقت البداية تغيّر يدوياً قبل الاستعادة.");
                    callback.onResult(false, "وقت البداية لم يعد صفراً؛ لم أستبدل التعديل اليدوي."); return;
                }
            } else {
                if (!"pass".equalsIgnoreCase(current.optString("filterMode", ""))
                        || !current.optBoolean("enabled", false)) {
                    markMacConflict(original, "تغيّر وضع التصفية أو حالة Enable بعد حفظ السياسة.");
                    callback.onResult(false, "السياسة لم تعد Permit Only ومفعّلة؛ تم تجاوزها بأمان."); return;
                }
                if (currentHour != originalHour || currentMinute != originalMinute
                        || current.optInt("startDay", -1) != original.optInt("startDay", -2)
                        || current.optInt("endDay", -1) != original.optInt("endDay", -2)
                        || current.optInt("endHour", -1) != original.optInt("endHour", -2)
                        || current.optInt("endMinute", -1) != original.optInt("endMinute", -2)
                        || !current.optString("name", "").equals(original.optString("name", ""))) {
                    markMacConflict(original, "تغيّرت بيانات السياسة يدوياً قبل التصفير.");
                    callback.onResult(false, "تغيّرت بيانات السياسة يدوياً؛ لم أستبدل التغيير."); return;
                }
            }
            setMacPending(original, restore ? "restore" : "zero", targetHour, targetMinute, date);
            saveMacPolicyState(original);
            submitMacPolicyStart(number, expectedMac, targetHour, targetMinute, submitted -> {
                if (!submitted) {
                    // Keep pending state: the router may have accepted the request even if the response was lost.
                    callback.onResult(false, "لم يصل تأكيد الحفظ؛ أبقيت العملية معلّقة لإعادة التحقق عند عودة الاتصال."); return;
                }
                mainHandler.postDelayed(() -> reloadMacAutomationFrame(() -> readMacPolicyAt(number, 0, (verified, verifyError) -> {
                    if (verifyError != null || verified == null
                            || !expectedMac.equals(normalizeMac(verified.optString("mac", "")))
                            || verified.optInt("startHour", -1) != targetHour
                            || verified.optInt("startMinute", -1) != targetMinute
                            || !samePolicyFieldsExceptStart(current, verified)) {
                        callback.onResult(false, "أُرسل الحفظ لكن التحقق الكامل لم ينجح؛ أبقيت العملية معلّقة ولم أعتبرها ناجحة."); return;
                    }
                    markMacOperationComplete(original, !restore, date);
                    if (restore) macAutomationRestoredCount++; else macAutomationZeroedCount++;
                    callback.onResult(true, "تم الحفظ والتحقق من رقم السياسة وحقولها.");
                })), 1700L);
            });
        });
    }

    private boolean samePolicyFieldsExceptStart(JSONObject before, JSONObject after) {
        return normalizeMac(before.optString("mac", "")).equals(normalizeMac(after.optString("mac", "")))
                && before.optString("name", "").equals(after.optString("name", ""))
                && before.optInt("endHour", -1) == after.optInt("endHour", -2)
                && before.optInt("endMinute", -1) == after.optInt("endMinute", -2)
                && before.optInt("startDay", -1) == after.optInt("startDay", -2)
                && before.optInt("endDay", -1) == after.optInt("endDay", -2)
                && before.optBoolean("enabled", false) == after.optBoolean("enabled", false)
                && before.optString("filterMode", "").equalsIgnoreCase(after.optString("filterMode", ""));
    }

    private void markMacConflict(JSONObject policy, String reason) {
        try {
            policy.put("conflict", true);
            policy.put("conflictReason", reason);
            policy.put("modified", false);
            policy.put("modifiedDate", "");
            clearMacPending(policy);
            saveMacPolicyState(policy);
        } catch (Exception ignored) { }
    }

    private void submitMacPolicyStart(int number, String expectedMac, int hour, int minute,
                                      BooleanResult callback) {
        String js = "(function(){try{" +
                "var f=document.getElementById('tendaW303MacAutomationFrame');if(!f||!f.contentDocument)return JSON.stringify({ok:false,error:'إطار MAC غير جاهز'});" +
                "var d=f.contentDocument,form=d.forms['frmSetup']||d.querySelector('form'),sel=d.getElementById('curNum');" +
                "if(!form||!sel||String(sel.value)!==" + JSONObject.quote(String.valueOf(number)) + ")return JSON.stringify({ok:false,error:'رقم السياسة المختار غير مطابق'});" +
                "var action='';try{action=new URL(form.action,d.location.href).pathname;}catch(e){};" +
                "if(action!='/goform/SafeMacFilter'||String(form.method||'').toLowerCase()!=='post')return JSON.stringify({ok:false,error:'مسار أو طريقة الحفظ غير مطابقة'});" +
                "var parts=form.querySelectorAll('input[id=\\\"mac\\\"]'),mac=[];for(var i=0;i<parts.length;i++)mac.push((parts[i].value||'').trim().toUpperCase());" +
                "if(mac.join(':').replace(/[^0-9A-F]/g,'')!==" + JSONObject.quote(expectedMac) + ")return JSON.stringify({ok:false,error:'MAC لا يطابق السياسة المحفوظة'});" +
                "var sh=form.querySelector('#sh'),sm=form.querySelector('#sm');if(!sh||!sm||typeof d.defaultView.preSubmit!=='function')return JSON.stringify({ok:false,error:'حقول البداية أو دالة preSubmit غير موجودة'});" +
                "sh.value=" + JSONObject.quote(String.valueOf(hour)) + ";sm.value=" + JSONObject.quote(String.valueOf(minute)) + ";" +
                "form.target=f.name||'tendaW303MacAutomationFrame';var buttons=form.querySelectorAll('input[type=button],button'),ok=null;" +
                "for(var b=0;b<buttons.length;b++){var v=(buttons[b].value||buttons[b].textContent||'').trim().toLowerCase();if(v==='ok'){ok=buttons[b];break;}}" +
                "if(ok)ok.click();else d.defaultView.preSubmit(form);return JSON.stringify({ok:true,submitted:true});" +
                "}catch(e){return JSON.stringify({ok:false,error:String(e)});}})()";
        if (macAutomationWebView == null) { callback.onResult(false); return; }
        macAutomationWebView.evaluateJavascript(js, raw -> {
            JSONObject result = parseJsJson(raw);
            callback.onResult(result != null && result.optBoolean("ok", false));
        });
    }

    private void reloadMacAutomationFrame(Runnable callback) {
        if (macAutomationWebView == null) { callback.run(); return; }
        String js = "(function(){var f=document.getElementById('tendaW303MacAutomationFrame');if(!f)return false;f.src=location.protocol+'//'+location.host+'/firewall_mac.asp?__tenda_auto='+Date.now();return true;})()";
        macAutomationWebView.evaluateJavascript(js, result -> mainHandler.postDelayed(callback, 1000L));
    }

    private void readAllMacPolicies(PolicyListCallback callback) {
        String js = "(function(){var f=document.getElementById('tendaW303MacAutomationFrame'),d=f&&f.contentDocument,s=d&&d.getElementById('curNum');if(!s)return JSON.stringify({ok:false,error:'لم أجد curNum في صفحة MAC'});var a=[];for(var i=0;i<s.options.length;i++)a.push(String(s.options[i].value));return JSON.stringify({ok:true,selected:String(s.value),options:a});})()";
        macAutomationWebView.evaluateJavascript(js, raw -> {
            JSONObject options = parseJsJson(raw);
            if (options == null || !options.optBoolean("ok", false)) {
                callback.onResult(null, options == null ? "تعذر قراءة قائمة السياسات." : options.optString("error")); return;
            }
            JSONArray arr = options.optJSONArray("options");
            if (arr == null || arr.length() == 0 || arr.length() > 10) {
                callback.onResult(null, "عدد خانات سياسات MAC خارج النطاق المتوقع (1–10). لم يتم تعديل الراوتر."); return;
            }
            List<Integer> parsed = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                try { parsed.add(Integer.parseInt(arr.optString(i))); }
                catch (Exception e) { callback.onResult(null, "رقم سياسة غير صالح في قائمة الراوتر."); return; }
            }
            // Identity is the router policy number, never its time fields. Same-time slots stay separate.
            List<Integer> numbers = MacScheduleLogic.uniquePolicyNumbers(parsed);
            if (numbers.isEmpty()) { callback.onResult(null, "لم أجد أرقام سياسات صالحة."); return; }
            readMacPolicyListAt(numbers, 0, new ArrayList<>(), options.optString("selected", "1"), callback);
        });
    }

    private void readMacPolicyListAt(List<Integer> numbers, int index, List<JSONObject> policies,
                                     String previousSelection, PolicyListCallback callback) {
        if (index >= numbers.size()) {
            int previous;
            try { previous = Integer.parseInt(previousSelection); } catch (Exception e) { previous = 1; }
            readMacPolicyAt(previous, 0, (ignored, error) -> callback.onResult(policies, null));
            return;
        }
        int number = numbers.get(index);
        readMacPolicyAt(number, 0, (policy, error) -> {
            if (error != null || policy == null) {
                callback.onResult(null, "تعذرت قراءة السياسة " + number + ": " + (error == null ? "بيانات ناقصة" : error)); return;
            }
            policies.add(policy);
            readMacPolicyListAt(numbers, index + 1, policies, previousSelection, callback);
        });
    }

    private void readMacPolicyAt(int number, int attempt, JsonResultCallback callback) {
        String selectJs = "(function(){try{var f=document.getElementById('tendaW303MacAutomationFrame'),d=f&&f.contentDocument,s=d&&d.getElementById('curNum'),form=d&&(d.forms['frmSetup']||d.querySelector('form'));if(!s||!form)return JSON.stringify({ok:false,error:'صفحة MAC غير جاهزة'});var n=" + JSONObject.quote(String.valueOf(number)) + ";var found=false;for(var i=0;i<s.options.length;i++)if(String(s.options[i].value)===n)found=true;if(!found)return JSON.stringify({ok:false,error:'رقم السياسة غير موجود'});if(String(s.value)!==n){s.value=n;if(typeof s.onchange==='function')s.onchange.call(s);else if(typeof d.defaultView.onChangeNum==='function')d.defaultView.onChangeNum(form);else s.dispatchEvent(new d.defaultView.Event('change',{bubbles:true}));}return JSON.stringify({ok:true,selected:String(s.value)});}catch(e){return JSON.stringify({ok:false,error:String(e)});}})()";
        macAutomationWebView.evaluateJavascript(selectJs, raw -> {
            JSONObject selected = parseJsJson(raw);
            if (selected == null || !selected.optBoolean("ok", false)) {
                if (attempt < 3) mainHandler.postDelayed(() -> readMacPolicyAt(number, attempt + 1, callback), 500L);
                else callback.onResult(null, selected == null ? "تعذر اختيار السياسة." : selected.optString("error"));
                return;
            }
            mainHandler.postDelayed(() -> {
                String readJs = "(function(){try{var f=document.getElementById('tendaW303MacAutomationFrame'),d=f&&f.contentDocument,s=d&&d.getElementById('curNum'),form=d&&(d.forms['frmSetup']||d.querySelector('form'));if(!s||!form||String(s.value)!==" + JSONObject.quote(String.valueOf(number)) + ")return JSON.stringify({ok:false,error:'لم تكتمل قراءة السياسة المختارة'});" +
                        "function v(id){var e=form.querySelector('#'+id);return e?String(e.value||''):'';}var parts=form.querySelectorAll('input[id=\\\"mac\\\"]'),m=[];for(var i=0;i<parts.length;i++)m.push((parts[i].value||'').trim().toUpperCase());" +
                        "var en=form.querySelector('#en'),fm=form.querySelector('#filter_mode');var a={ok:true,number:parseInt(s.value,10),filterMode:fm?String(fm.value):'',name:v('remark'),mac:m.join(':'),startHour:parseInt(v('sh'),10),startMinute:parseInt(v('sm'),10),endHour:parseInt(v('eh'),10),endMinute:parseInt(v('em'),10),startDay:parseInt(v('startw'),10),endDay:parseInt(v('endw'),10),enabled:!!(en&&en.checked)};return JSON.stringify(a);}catch(e){return JSON.stringify({ok:false,error:String(e)});}})()";
                macAutomationWebView.evaluateJavascript(readJs, readRaw -> {
                    JSONObject policy = parseJsJson(readRaw);
                    if (policy == null || !policy.optBoolean("ok", false)) {
                        if (attempt < 3) mainHandler.postDelayed(() -> readMacPolicyAt(number, attempt + 1, callback), 500L);
                        else callback.onResult(null, policy == null ? "تعذر قراءة حقول السياسة." : policy.optString("error"));
                        return;
                    }
                    try { policy.put("mac", normalizeMac(policy.optString("mac", ""))); }
                    catch (JSONException ignored) { }
                    callback.onResult(policy, null);
                });
            }, 550L);
        });
    }

    private void readRouterClock(ClockCallback callback) {
        String refresh = "(function(){var f=document.getElementById('tendaW303ClockFrame');if(!f)return JSON.stringify({ok:false,error:'إطار ساعة الراوتر غير موجود'});f.src=location.protocol+'//'+location.host+'/system_hostname.asp?__tenda_clock='+Date.now();return JSON.stringify({ok:true});})()";
        macAutomationWebView.evaluateJavascript(refresh, raw -> mainHandler.postDelayed(() -> {
            String read = "(function(){try{var f=document.getElementById('tendaW303ClockFrame'),d=f&&f.contentDocument;if(!d||d.readyState!=='complete')return JSON.stringify({ok:false,error:'صفحة Time Settings لم تكتمل'});" +
                    "var names=['year','month','day','hour','minute','second'],o={};function numeric(e){return e&&/^\\d+$/.test(String(e.value||'').trim())?parseInt(e.value,10):null;}" +
                    "for(var n=0;n<names.length;n++){var key=names[n],els=d.querySelectorAll('input');for(var i=0;i<els.length;i++){var id=((els[i].id||'')+' '+(els[i].name||'')).toLowerCase();if(id===key||id.indexOf(key)>=0){o[key]=numeric(els[i]);break;}}}" +
                    "if(names.some(function(n){return typeof o[n]!=='number';})){var ins=d.querySelectorAll('input[type=text],input:not([type])'),vals=[];for(var j=0;j<ins.length;j++){var v=String(ins[j].value||'').trim();if(/^\\d+$/.test(v))vals.push(parseInt(v,10));}var y=-1;for(var k=0;k<vals.length;k++)if(vals[k]>=2000&&vals[k]<=2099){y=k;break;}if(y>=0&&vals.length>=y+6){o={year:vals[y],month:vals[y+1],day:vals[y+2],hour:vals[y+3],minute:vals[y+4],second:vals[y+5]};}}" +
                    "if(!(o.year>=2000&&o.year<=2099&&o.month>=1&&o.month<=12&&o.day>=1&&o.day<=31&&o.hour>=0&&o.hour<=23&&o.minute>=0&&o.minute<=59&&o.second>=0&&o.second<=59))return JSON.stringify({ok:false,error:'لم أتعرف على حقول التاريخ والوقت في صفحة Time Settings'});" +
                    "var dt=new Date(o.year,o.month-1,o.day,12,0,0),wd=dt.getDay();o.weekday=wd;o.date=String(o.year)+'-'+('0'+o.month).slice(-2)+'-'+('0'+o.day).slice(-2);o.ok=true;return JSON.stringify(o);}catch(e){return JSON.stringify({ok:false,error:String(e)});}})()";
            macAutomationWebView.evaluateJavascript(read, readRaw -> {
                JSONObject clock = parseJsJson(readRaw);
                if (clock == null || !clock.optBoolean("ok", false)) callback.onResult(null, clock == null ? "تعذر قراءة ساعة الراوتر." : clock.optString("error"));
                else callback.onResult(clock, null);
            });
        }, 950L));
    }

    private JSONObject parseJsJson(String raw) {
        if (raw == null || "null".equals(raw)) return null;
        try {
            String value = raw;
            if (raw.startsWith("\"")) value = new JSONArray("[" + raw + "]").optString(0, "");
            return new JSONObject(value);
        } catch (Exception ignored) { return null; }
    }

    private JSONObject loadMacAutomationState() {
        String raw = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_MAC_AUTO_STATE, "");
        if (raw == null || raw.trim().isEmpty()) return null;
        try { return new JSONObject(raw); } catch (Exception ignored) { return null; }
    }

    private void saveMacAutomationState(JSONObject state) {
        if (state != null) getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(PREF_MAC_AUTO_STATE, state.toString()).apply();
    }

    private void saveMacPolicyState(JSONObject changedPolicy) {
        if (changedPolicy == null) return;
        JSONObject state = loadMacAutomationState();
        JSONArray policies = state == null ? null : state.optJSONArray("policies");
        if (policies == null) return;
        int number = changedPolicy.optInt("number", -1);
        for (int i = 0; i < policies.length(); i++) {
            JSONObject current = policies.optJSONObject(i);
            if (current != null && current.optInt("number", -2) == number) {
                try { policies.put(i, new JSONObject(changedPolicy.toString())); state.put("policies", policies); saveMacAutomationState(state); }
                catch (Exception ignored) { }
                return;
            }
        }
    }

    private boolean hasModifiedMacPolicies(JSONObject state) {
        JSONArray policies = state == null ? null : state.optJSONArray("policies");
        if (policies == null) return false;
        for (int i = 0; i < policies.length(); i++) {
            JSONObject policy = policies.optJSONObject(i);
            if (policy != null && !policy.optBoolean("conflict", false)
                    && (policy.optBoolean("modified", false) || policy.optBoolean("pending", false))) return true;
        }
        return false;
    }

    private int countMacConflicts(JSONObject state) {
        JSONArray policies = state == null ? null : state.optJSONArray("policies");
        int count = 0;
        if (policies != null) for (int i = 0; i < policies.length(); i++) {
            JSONObject policy = policies.optJSONObject(i);
            if (policy != null && policy.optBoolean("conflict", false)) count++;
        }
        return count;
    }

    private List<JSONObject> modifiedPolicies(JSONObject state) {
        List<JSONObject> out = new ArrayList<>();
        JSONArray policies = state == null ? null : state.optJSONArray("policies");
        if (policies != null) for (int i = 0; i < policies.length(); i++) {
            JSONObject policy = policies.optJSONObject(i);
            if (policy != null && !policy.optBoolean("conflict", false)
                    && (policy.optBoolean("modified", false) || policy.optBoolean("pending", false))) out.add(policy);
        }
        return out;
    }

    private void setMacPending(JSONObject policy, String operation, int hour, int minute, String date) {
        try {
            policy.put("pending", true); policy.put("pendingOperation", operation);
            policy.put("pendingTargetHour", hour); policy.put("pendingTargetMinute", minute);
            policy.put("pendingDate", date == null ? "" : date); saveMacPolicyState(policy);
        } catch (Exception ignored) { }
    }

    private void clearMacPending(JSONObject policy) {
        try {
            policy.put("pending", false); policy.remove("pendingOperation");
            policy.remove("pendingTargetHour"); policy.remove("pendingTargetMinute"); policy.remove("pendingDate");
        } catch (Exception ignored) { }
    }

    private void markMacOperationComplete(JSONObject policy, boolean modified, String date) {
        try {
            policy.put("modified", modified);
            policy.put("modifiedDate", modified ? (date == null ? policy.optString("pendingDate", "") : date) : "");
            clearMacPending(policy); saveMacPolicyState(policy);
        } catch (Exception ignored) { }
    }

    private String routerDateFromStateOrToday() {
        JSONObject state = loadMacAutomationState();
        JSONArray policies = state == null ? null : state.optJSONArray("policies");
        if (policies != null) for (int i = 0; i < policies.length(); i++) {
            JSONObject policy = policies.optJSONObject(i);
            if (policy != null && !policy.optString("modifiedDate", "").isEmpty()) return policy.optString("modifiedDate");
        }
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private boolean isValidMacValue(String mac) { return normalizeMac(mac).matches("[0-9A-F]{12}"); }
    private String normalizeMac(String mac) { return mac == null ? "" : mac.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.ROOT); }
    private String two(int value) { return value < 10 ? "0" + value : String.valueOf(value); }

    private boolean isTrafficPage(String url) {
        if (url == null) return false;
        try {
            Uri uri = Uri.parse(url);
            return routerHost.equals(uri.getHost()) && uri.getPath() != null
                    && uri.getPath().toLowerCase().endsWith("iptaccount.asp");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isAllowedRouterUri(Uri uri) {
        return uri != null && "http".equalsIgnoreCase(uri.getScheme())
                && routerHost.equals(uri.getHost());
    }

    private boolean isPrivateIpv4(String ip) {
        if (ip == null || !ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return false;
        String[] parts = ip.split("\\.");
        try {
            int[] o = new int[4];
            for (int i = 0; i < 4; i++) {
                o[i] = Integer.parseInt(parts[i]);
                if (o[i] < 0 || o[i] > 255) return false;
            }
            return o[0] == 10 || (o[0] == 172 && o[1] >= 16 && o[1] <= 31)
                    || (o[0] == 192 && o[1] == 168) || (o[0] == 169 && o[1] == 254);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private int normalizeInterval(int value) {
        if (value == 5000 || value == 10000) return value;
        return 3000;
    }

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                if (cpuWakeLock == null) {
                    cpuWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                            "TendaW303Guard:TrafficMonitor");
                    cpuWakeLock.setReferenceCounted(false);
                }
                if (!cpuWakeLock.isHeld()) cpuWakeLock.acquire();
            }
        } catch (Exception ignored) { }
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null) {
                if (wifiLock == null) {
                    wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL,
                            "TendaW303Guard:RouterWifi");
                    wifiLock.setReferenceCounted(false);
                }
                if (!wifiLock.isHeld()) wifiLock.acquire();
            }
        } catch (Exception ignored) { }
    }

    private void releaseLocks() {
        try { if (cpuWakeLock != null && cpuWakeLock.isHeld()) cpuWakeLock.release(); }
        catch (Exception ignored) { }
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); }
        catch (Exception ignored) { }
    }

    private void updateStatus(String value) {
        if (value == null || value.trim().isEmpty()) return;
        currentStatus = value;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && monitoring) nm.notify(NOTIFICATION_ID, buildNotification(value));
        Intent status = new Intent(ACTION_STATUS);
        status.setPackage(getPackageName());
        status.putExtra(EXTRA_RUNNING, isTrafficEnabled());
        status.putExtra(EXTRA_STATUS, value);
        sendBroadcast(status);
    }

    private void stopMonitoringByUser() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_ENABLED, false).apply();
        mainHandler.removeCallbacks(pollRunnable);
        updateStatus("مراقبة حركة البيانات متوقفة. أتمتة MAC تستمر إذا كانت مفعّلة.");
        stopServiceIfIdle();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "Tenda W303 Guard background monitor", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("إشعار مستمر لمراقبة الراوتر المحلي وانتظار عودة Wi-Fi");
            channel.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String message) {
        Intent openApp = new Intent(this, MainActivity.class);
        openApp.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 30321, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, RouterBackgroundService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 30322, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopMac = new Intent(this, RouterBackgroundService.class).setAction(ACTION_MAC_STOP);
        PendingIntent stopMacPending = PendingIntent.getService(this, 30323, stopMac,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Tenda W303 Guard")
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(android.R.drawable.ic_media_pause, "إيقاف مراقبة البيانات", stopPending)
                .addAction(android.R.drawable.ic_menu_revert, "إيقاف MAC واستعادة", stopMacPending);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) builder.setPriority(Notification.PRIORITY_LOW);
        return builder.build();
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName() : message;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Keep the foreground monitor alive when the user removes the Activity task.
        // Android's explicit Force stop still terminates all app components.
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        monitoring = false;
        mainHandler.removeCallbacksAndMessages(null);
        if (connectivityManager != null && wifiCallback != null) {
            try { connectivityManager.unregisterNetworkCallback(wifiCallback); }
            catch (Exception ignored) { }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                connectivityManager.bindProcessToNetwork(null);
            }
        } catch (Exception ignored) { }
        if (monitorWebView != null) {
            try {
                monitorWebView.removeJavascriptInterface("TendaBridge");
                monitorWebView.stopLoading();
                monitorWebView.loadUrl("about:blank");
                monitorWebView.destroy();
            } catch (Exception ignored) { }
            monitorWebView = null;
        }
        if (macAutomationWebView != null) {
            try {
                macAutomationWebView.stopLoading();
                macAutomationWebView.loadUrl("about:blank");
                macAutomationWebView.destroy();
            } catch (Exception ignored) { }
            macAutomationWebView = null;
        }
        releaseLocks();
        networkExecutor.shutdownNow();
        if (stopRequested) {
            Intent status = new Intent(ACTION_STATUS);
            status.setPackage(getPackageName());
            status.putExtra(EXTRA_RUNNING, false);
            status.putExtra(EXTRA_STATUS, "المراقبة الخلفية متوقفة.");
            sendBroadcast(status);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
