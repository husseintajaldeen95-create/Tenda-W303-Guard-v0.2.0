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
import android.webkit.CookieManager;
import android.webkit.HttpAuthHandler;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import java.io.ByteArrayInputStream;
import org.json.JSONException;

import java.net.InetSocketAddress;
import java.net.Socket;
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

    public static final String EXTRA_ROUTER_HOST = "router_host";
    public static final String EXTRA_INTERVAL_MS = "interval_ms";
    public static final String EXTRA_RUNNING = "running";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_JSON = "traffic_json";
    public static final String EXTRA_SOURCE_PATH = "source_path";
    public static final String EXTRA_CAPTURED_AT = "captured_at";

    public static final String PREFS_NAME = "tenda_w303_guard";
    public static final String PREF_ENABLED = "live_monitor_enabled";
    public static final String PREF_INTERVAL_MS = "live_poll_interval_ms";
    public static final String PREF_LAST_TRAFFIC_JSON = "live_monitor_last_traffic_json";
    public static final String PREF_LAST_TRAFFIC_SOURCE = "live_monitor_last_traffic_source";
    public static final String PREF_LAST_TRAFFIC_AT = "live_monitor_last_traffic_at";

    private static final String CHANNEL_ID = "tenda_router_monitor";
    private static final int NOTIFICATION_ID = 30320;
    private static final long RETRY_DELAY_MS = 15000L;
    private static final long ROUTER_PROBE_TIMEOUT_MS = 2500L;
    private static final long PAGE_TIMEOUT_MS = 15000L;
    private static final String TRAFFIC_PATH = "/sys_iptAccount.asp";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback wifiCallback;
    private Network wifiNetwork;
    private WebView monitorWebView;
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
        if (ACTION_STOP.equals(action)) {
            stopRequested = true;
            stopMonitoringByUser();
            return START_NOT_STICKY;
        }

        if (ACTION_UPDATE_INTERVAL.equals(action)) {
            android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            if (intent != null) {
                pollIntervalMs = normalizeInterval(intent.getIntExtra(EXTRA_INTERVAL_MS, pollIntervalMs));
                prefs.edit().putInt(PREF_INTERVAL_MS, pollIntervalMs).apply();
            }
            if (!monitoring && prefs.getBoolean(PREF_ENABLED, false)) {
                String savedHost = prefs.getString("router_ip", routerHost);
                if (isPrivateIpv4(savedHost)) routerHost = savedHost;
                pollIntervalMs = normalizeInterval(prefs.getInt(PREF_INTERVAL_MS, pollIntervalMs));
                startMonitoring();
            } else if (monitoring) {
                schedulePoll(0L);
            }
            return START_STICKY;
        }

        if (ACTION_RETRY_AUTH.equals(action)) {
            android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            if (!monitoring && prefs.getBoolean(PREF_ENABLED, false)) {
                String savedHost = prefs.getString("router_ip", routerHost);
                if (isPrivateIpv4(savedHost)) routerHost = savedHost;
                pollIntervalMs = normalizeInterval(prefs.getInt(PREF_INTERVAL_MS, pollIntervalMs));
                startMonitoring();
            }
            authRequired = false;
            if (monitoring && routerReachable) loadTrafficPage();
            return START_STICKY;
        }

        if (intent != null && ACTION_START.equals(action)) {
            android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
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
            prefs.edit()
                    .putBoolean(PREF_ENABLED, true)
                    .putString("router_ip", routerHost)
                    .putInt(PREF_INTERVAL_MS, pollIntervalMs)
                    .apply();
        } else {
            android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            if (!prefs.getBoolean(PREF_ENABLED, false)) {
                stopRequested = true;
                stopForeground(true);
                stopSelf();
                return START_NOT_STICKY;
            }
            String savedHost = prefs.getString("router_ip", routerHost);
            if (isPrivateIpv4(savedHost)) routerHost = savedHost;
            pollIntervalMs = normalizeInterval(prefs.getInt(PREF_INTERVAL_MS, pollIntervalMs));
        }

        startMonitoring();
        return START_STICKY;
    }

    private void startMonitoring() {
        if (monitoring) {
            acquireLocks();
            discoverCurrentWifiNetwork();
            return;
        }
        monitoring = true;
        stopRequested = false;
        acquireLocks();
        updateStatus("المراقبة الخلفية مفعّلة. أنتظر اتصال Wi-Fi بالراوتر؛ لا يلزم وجود إنترنت خارجي.");
        discoverCurrentWifiNetwork();
        schedulePoll(RETRY_DELAY_MS);
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
                            mainHandler.removeCallbacks(pageTimeoutRunnable);
                            releaseLocks();
                            try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    connectivityManager.bindProcessToNetwork(null);
                                }
                            } catch (Exception ignored) { }
                            updateStatus("انقطع Wi-Fi عن الهاتف. المراقبة لم تتوقف؛ أنتظر عودة الاتصال بالراوتر تلقائياً.");
                            schedulePoll(RETRY_DELAY_MS);
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
        updateStatus("بانتظار اتصال Wi-Fi. سيبدأ الفحص تلقائياً عند الاتصال بالشبكة المحلية.");
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
                    ensureMonitorWebView();
                    if (!authRequired && !pageLoading) {
                        if (monitorWebView == null || monitorWebView.getUrl() == null) loadTrafficPage();
                        else if (!isTrafficPage(monitorWebView.getUrl())) loadTrafficPage();
                        else schedulePoll(0L);
                    }
                    if (!authRequired) updateStatus("الراوتر متاح عبر Wi-Fi المحلي. تستمر القراءة حتى لو انقطع الإنترنت الخارجي.");
                } else {
                    routerReachable = false;
                    updateStatus("Wi-Fi متصل، لكن " + routerHost + " لا يستجيب الآن. سأعيد الفحص تلقائياً دون إيقاف الخدمة.");
                    schedulePoll(RETRY_DELAY_MS);
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
        if (!monitoring) return;
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
        if (monitoring) mainHandler.postDelayed(pollRunnable, Math.max(500L, delayMs));
    }

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
        status.putExtra(EXTRA_RUNNING, monitoring);
        status.putExtra(EXTRA_STATUS, value);
        sendBroadcast(status);
    }

    private void stopMonitoringByUser() {
        monitoring = false;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_ENABLED, false).apply();
        mainHandler.removeCallbacks(pollRunnable);
        releaseLocks();
        updateStatus("المراقبة الخلفية متوقفة.");
        stopForeground(true);
        stopSelf();
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
                .addAction(android.R.drawable.ic_media_pause, "إيقاف المراقبة", stopPending);
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
