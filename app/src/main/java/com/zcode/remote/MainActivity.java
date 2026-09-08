package com.zcode.remote;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

import android.Manifest;
import android.content.pm.ApplicationInfo;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.webkit.WebStorage;
import android.webkit.WebViewDatabase;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;


public class MainActivity extends Activity {
    private static final String KEY_URL = "url";
    private static final String KEY_FAB_X = "fab_x";
    private static final String KEY_FAB_Y = "fab_y";
    private static final String KEY_SHOW_FAB = "show_fab";
    private static final String KEY_APP_LOCK = "app_lock";
    private static final String KEY_ZOOM = "zoom_scale";
    private static final String ACTION_CHANGE_URL = "com.zcode.remote.CHANGE_URL";
    private static final String ACTION_SCAN_BIND = "com.zcode.remote.SCAN_BIND";
    private static final String ACTION_OPEN_SESSION = "com.zcode.remote.OPEN_SESSION";
    private static final Pattern REMOTE_URL = Pattern.compile("https://zcode\\.z\\.ai/remote\\S*");
    // 版本自动更新:GitHub Releases 元数据,tag 命名 v1.3,asset 为任意 .apk(逻辑在 Updater)
    static final String KEY_KEEP_SCREEN_ON = "keep_screen_on";
    private static final String KEY_HISTORY = "history_urls";
    private static final int MAX_HISTORY = 8;
    static final int BG = 0xFF0F1014;
    static final int FG = 0xFFEDEEF0;
    static final int FG_DIM = 0xFF9AA0A6;
    private static final int RED = 0xFFFF6E6E;
    static final int ACCENT = 0xFF4C8DFF;
    private static final int BTN_PRIMARY = 0xFF1B5BD7;
    private static final int BTN_SECONDARY = 0xFF2A2D36;
    private static final int REQ_FILE = 1;
    private static final int REQ_SCAN = 2;
    private static final int REQ_NOTIF = 3;
    // 页面缩放:zoomFactor 是"相对页面自然缩放的倍数"(1.0=原始大小),范围与步进
    private static final float ZOOM_MIN = 0.5f;
    private static final float ZOOM_MAX = 3.0f;
    private static final float ZOOM_STEP = 1.25f;

    private FrameLayout root;
    private WebView webView;
    private ProgressBar progress;
    // 会话层(WebView+进度条+悬浮钮的常驻容器):设置/错误页以覆盖层叠在其上,
    // 返回会话只移除覆盖层,网页不重载、滚动位置与页面状态全保留("会话秒回")
    private RefreshLayout sessionView;
    private View overlayView;
    // WebView 内容是否处于加载失败态:失败后"返回会话"要 reload 而不是只掀掉覆盖层(否则露出内核错误白页)
    private boolean webLoadFailed = false;
    private ValueCallback<Uri[]> fileCallback;
    private String allowedHost;
    private String loadedUrl;
    private String lastAdoptedClip;
    private TextView fab;
    private AlertDialog menuDialog;
    // 页面缩放:zoomFactor 是用户选定的倍数(相对页面自然缩放,1.0=原始大小),持久化;
    // naturalScale 是本页未缩放时的基准缩放,只在 WebView 全新时测一次(重载后 getScale
    // 已是缩放值,重测会叠乘);restoringZoom 期间忽略 WebView 自身回传的 onScaleChanged,
    // 否则程序化 zoomBy 的回调会覆盖用户选择
    private float zoomFactor = 1f;
    private float naturalScale = 0f;
    private boolean restoringZoom = false;
    private TextView zoomLabel;
    private long lastBackTime = 0L;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean onErrorPage = false;
    // 是否在前台:剪贴板监听只在前台响应
    private boolean inForeground = false;
    private ClipboardManager.OnPrimaryClipChangedListener clipListener;
    // 上次已处理过的剪贴板时间戳:内容没变就不重复读取(Android 12+ 读取他应用剪贴板会弹系统提示)
    private long lastClipTimestamp = -1L;
    // 更新链路(v2.6 拆分为独立类):检查/下载/验签/安装
    private final Updater updater = new Updater(this);
    // 应用锁:进程存活期间验过一次即可(unlockedThisSession),lockArmed 防 onResume 重入重复弹验证
    private boolean unlockedThisSession = false;
    private boolean lockArmed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyKeepScreenOn(getPreferences(Context.MODE_PRIVATE).getBoolean(KEY_KEEP_SCREEN_ON, true));
        // 只在 debug 包里开启 WebView 远程调试,release 关闭减少调试口暴露
        boolean debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        WebView.setWebContentsDebuggingEnabled(debuggable);
        // targetSdk 34 要求动态注册非豁免系统广播时显式声明导出标志;系统服务(DownloadManager)不受 NOT_EXPORTED 影响
        updater.registerReceiver();
        registerBackCallback();
        registerNetworkCallback();
        registerClipboardListener();
        maybeRequestNotificationPermission();
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        setContentView(root);
        // 历史可能来自上次会话的持久化(本次启动不一定会走 recordHistory),启动即同步一次快捷方式
        updateDynamicShortcuts();
        route(getIntent());
        SharedPreferences sp = getPreferences(Context.MODE_PRIVATE);
        // 自动检查节流:距上次检查不足 4 小时则跳过,避免每次冷启动都打 GitHub API
        if (sp.getBoolean(Updater.KEY_AUTO_UPDATE, true)
                && System.currentTimeMillis() - sp.getLong(Updater.KEY_LAST_UPDATE_CHECK, 0L)
                >= Updater.UPDATE_CHECK_INTERVAL_MS) {
            updater.checkUpdate(false);
        }
    }

    /**
     * 预测性返回(Android 13+):Manifest 声明 enableOnBackInvokedCallback 后系统不再回调
     * onBackPressed,必须显式注册 OnBackInvokedCallback 接管,否则返回手势直接退出 App、
     * 覆盖层"返回=回会话"的语义失效。低版本仍走 onBackPressed,两条路共用 handleBack。
     */
    private void registerBackCallback() {
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    () -> {
                        if (!handleBack()) {
                            finish();
                        }
                    });
        }
    }

    /** 版本号单一来源在 Updater(运行时读 PackageInfo),避免与 build.gradle 双写漏改 */
    private String appVersion() {
        return updater.appVersion();
    }

    /**
     * 剪贴板监听:App 在前台时剪贴板一变化就尝试采纳远程链接。
     * 相比每次 onResume 轮询,只在内容真正变化时读一次,减少 Android 12+ 的"已粘贴自…"系统提示频次。
     */
    private void registerClipboardListener() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            return;
        }
        clipListener = new ClipboardManager.OnPrimaryClipChangedListener() {
            @Override
            public void onPrimaryClipChanged() {
                // 前台时剪贴板刚被写入:同步时间戳(只读描述不触发系统提示),内容与已保存不同才采纳
                if (inForeground) {
                    lastClipTimestamp = clipboardTimestamp();
                    switchToClipboardUrl();
                }
            }
        };
        cm.addPrimaryClipChangedListener(clipListener);
    }

    /** Android 13+ 下载/更新通知需要运行时申请 POST_NOTIFICATIONS,否则通知静默不显示 */
    private void maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            try {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            } catch (Exception ignored) {
            }
        }
    }

    /** 监听网络:从断网恢复时,若正停在错误页则自动重连(弱网/切 Wi-Fi 场景免手动重试) */
    private void registerNetworkCallback() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return;
            }
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    runOnUiThread(() -> {
                        if (onErrorPage) {
                            String url = getPreferences(Context.MODE_PRIVATE).getString(KEY_URL, null);
                            if (url != null) {
                                Toast.makeText(MainActivity.this, "网络已恢复,正在重连…", Toast.LENGTH_SHORT).show();
                                onErrorPage = false;
                                webLoadFailed = false;
                                removeOverlay();
                                if (webView != null) {
                                    // 会话层还在:原样 reload(页面处于加载失败态,必须重载)
                                    webView.reload();
                                } else {
                                    showWeb(url);
                                }
                            }
                        }
                    });
                }
            };
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), networkCallback);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        route(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        inForeground = true;
        // 回前台时若剪贴板时间戳变了才读一次(复制发生在其他 App 时 listener 收不到变化,
        // 必须回前台补检;时间戳没变说明同一份内容已处理过,跳过以避免反复触发系统"已粘贴自"提示)
        long ts = clipboardTimestamp();
        if (ts != lastClipTimestamp) {
            lastClipTimestamp = ts;
            switchToClipboardUrl();
        }
        // 从"安装未知应用"授权设置回来:已有验签通过的包就直接续装
        updater.maybeResumeInstallAfterPermission();
        maybeArmAppLock();
    }

    @Override
    protected void onPause() {
        super.onPause();
        inForeground = false;
    }

    /** 读取剪贴板时间戳(不读内容,不触发 Android 12+ 系统提示);拿不到时返回 -1 */
    private long clipboardTimestamp() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) {
                return -1L;
            }
            return cm.getPrimaryClipDescription() != null
                    ? cm.getPrimaryClipDescription().getTimestamp() : -1L;
        } catch (Exception e) {
            return -1L;
        }
    }

    private void route(Intent intent) {
        SharedPreferences prefs = getPreferences(Context.MODE_PRIVATE);
        // 长按图标「扫码绑定」:先把主界面加载好再拉扫码页,取消扫码回来不留白屏
        boolean launchScan = intent != null && ACTION_SCAN_BIND.equals(intent.getAction());

        if (intent != null && ACTION_CHANGE_URL.equals(intent.getAction())) {
            showSetup(prefs.getString(KEY_URL, null), null);
            return;
        }

        // 长按图标的动态快捷方式:直达指定历史会话
        if (intent != null && ACTION_OPEN_SESSION.equals(intent.getAction())) {
            String target = intent.getStringExtra(KEY_URL);
            if (target != null && REMOTE_URL.matcher(target).find()) {
                prefs.edit().putString(KEY_URL, target).apply();
                recordHistory(target);
                showWeb(target);
                return;
            }
        }

        // 在其他 App(微信/QQ/短信)里点击或分享远程链接,直接用本 App 打开
        if (intent != null) {
            String shared = null;
            if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
                shared = intent.getData().toString();
            } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
                String text = intent.getStringExtra(Intent.EXTRA_TEXT);
                if (text != null) {
                    Matcher m = REMOTE_URL.matcher(text);
                    if (m.find()) {
                        shared = m.group();
                    }
                }
            }
            if (shared != null && REMOTE_URL.matcher(shared).matches()) {
                prefs.edit().putString(KEY_URL, shared).apply();
                recordHistory(shared);
                showWeb(shared);
                if (launchScan) {
                    startScan();
                }
                return;
            }
        }

        if (switchToClipboardUrl()) {
            if (launchScan) {
                startScan();
            }
            return;
        }

        String saved = prefs.getString(KEY_URL, null);
        if (saved != null) {
            if (!saved.equals(loadedUrl)) {
                showWeb(saved);
            } else if (overlayView != null) {
                // 同一链接的 onNewIntent:回到会话层即可,不重载
                removeOverlay();
                onErrorPage = false;
            }
        } else {
            showSetup(null, null);
        }
        if (launchScan) {
            startScan();
        }
    }


    private void applyKeepScreenOn(boolean keepOn) {
        if (keepOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private static class HistoryItem {
        final String url;
        final long time;
        HistoryItem(String url, long time) {
            this.url = url;
            this.time = time;
        }
    }

    private List<HistoryItem> getHistoryList() {
        List<HistoryItem> list = new ArrayList<>();
        String jsonStr = getPreferences(Context.MODE_PRIVATE).getString(KEY_HISTORY, "[]");
        try {
            JSONArray arr = new JSONArray(jsonStr);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                list.add(new HistoryItem(obj.optString("url"), obj.optLong("time")));
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    private void recordHistory(String url) {
        if (url == null || url.trim().isEmpty()) {
            return;
        }
        List<HistoryItem> list = getHistoryList();
        List<HistoryItem> updated = new ArrayList<>();
        updated.add(new HistoryItem(url, System.currentTimeMillis()));
        for (HistoryItem item : list) {
            if (!item.url.equals(url)) {
                updated.add(item);
            }
            if (updated.size() >= MAX_HISTORY) {
                break;
            }
        }
        JSONArray arr = new JSONArray();
        for (HistoryItem item : updated) {
            try {
                JSONObject obj = new JSONObject();
                obj.put("url", item.url);
                obj.put("time", item.time);
                arr.put(obj);
            } catch (Exception ignored) {
            }
        }
        getPreferences(Context.MODE_PRIVATE).edit().putString(KEY_HISTORY, arr.toString()).apply();
        updateDynamicShortcuts();
    }

    private void clearHistory() {
        getPreferences(Context.MODE_PRIVATE).edit().remove(KEY_HISTORY).apply();
        updateDynamicShortcuts();
    }

    /** 删除单条历史;若删的是当前会话,不改变当前加载,仅从列表移除 */
    private void removeHistoryItem(String url) {
        List<HistoryItem> list = getHistoryList();
        JSONArray arr = new JSONArray();
        for (HistoryItem item : list) {
            if (item.url.equals(url)) {
                continue;
            }
            try {
                JSONObject obj = new JSONObject();
                obj.put("url", item.url);
                obj.put("time", item.time);
                arr.put(obj);
            } catch (Exception ignored) {
            }
        }
        getPreferences(Context.MODE_PRIVATE).edit().putString(KEY_HISTORY, arr.toString()).apply();
        updateDynamicShortcuts();
    }

    private String formatRelativeTime(long time) {
        long diff = System.currentTimeMillis() - time;
        if (diff < 60_000L) {
            return "刚刚";
        } else if (diff < 3600_000L) {
            return (diff / 60_000L) + " 分钟前";
        } else if (diff < 86400_000L) {
            return (diff / 3600_000L) + " 小时前";
        } else {
            return (diff / 86400_000L) + " 天前";
        }
    }

    private String summarizeUrl(String url) {
        try {
            Uri uri = Uri.parse(url);
            String sid = uri.getQueryParameter("sid");
            if (sid != null && !sid.isEmpty()) {
                String sub = sid.length() > 8 ? sid.substring(0, 8) + "…" : sid;
                return "会话 sid: " + sub;
            }
            String path = uri.getPath();
            if (path != null && !path.isEmpty()) {
                return path;
            }
        } catch (Exception ignored) {
        }
        return "远程会话";
    }

    private void showHistoryDialog() {
        final List<HistoryItem> list = getHistoryList();
        if (list.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("历史会话")
                    .setMessage("暂无历史会话记录。\n\n当你使用新链接连接后，会自动记录在此处，方便日后切换。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, pad, pad, pad);

        TextView head = new TextView(this);
        head.setText("最近使用的会话（最多保存 " + MAX_HISTORY + " 条）");
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        head.setTextColor(FG_DIM);
        head.setPadding(0, 0, 0, dp(12));
        box.addView(head);

        final AlertDialog[] dialogHolder = new AlertDialog[1];

        for (final HistoryItem item : list) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setClickable(true);
            row.setFocusable(true);

            GradientDrawable rowBg = new GradientDrawable();
            rowBg.setColor(0xFF16181F);
            rowBg.setCornerRadius(dp(6));
            row.setBackground(rowBg);

            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = dp(8);

            TextView titleView = new TextView(this);
            titleView.setText(summarizeUrl(item.url) + "  ·  " + formatRelativeTime(item.time));
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            titleView.setTextColor(ACCENT);
            row.addView(titleView);

            TextView urlView = new TextView(this);
            urlView.setText(item.url);
            urlView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            urlView.setTextColor(FG_DIM);
            urlView.setSingleLine(true);
            urlView.setPadding(0, dp(4), 0, 0);
            row.addView(urlView);

            row.setOnClickListener(v -> {
                if (dialogHolder[0] != null) {
                    dialogHolder[0].dismiss();
                }
                dismissMenu();
                getPreferences(Context.MODE_PRIVATE).edit().putString(KEY_URL, item.url).apply();
                recordHistory(item.url);
                showWeb(item.url);
                toast("已切换到选中的历史会话");
            });

            // 长按单条:仅删除这条历史(不影响当前会话),避免只能一键清空
            row.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setTitle("删除这条历史会话")
                        .setMessage(summarizeUrl(item.url) + "\n\n仅从历史列表移除,不会影响当前打开的会话。")
                        .setPositiveButton("删除", (d, w) -> {
                            removeHistoryItem(item.url);
                            if (dialogHolder[0] != null) {
                                dialogHolder[0].dismiss();
                            }
                            toast("已删除该条历史");
                            showHistoryDialog();
                        })
                        .setNegativeButton("取消", null)
                        .show();
                return true;
            });

            box.addView(row, rowLp);
        }

        ScrollView sc = new ScrollView(this);
        sc.addView(box);

        dialogHolder[0] = new AlertDialog.Builder(this)
                .setTitle("历史会话")
                .setView(sc)
                .setPositiveButton("清空历史", (d, w) -> {
                    clearHistory();
                    toast("历史记录已清空");
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private boolean switchToClipboardUrl() {
        SharedPreferences prefs = getPreferences(Context.MODE_PRIVATE);
        String saved = prefs.getString(KEY_URL, null);
        String clip = remoteUrlFromClipboard();
        // 同一段剪贴板内容只采纳一次,避免切走再回来时把用户手动换的链接又覆盖回去
        if (clip != null && !clip.equals(saved) && !clip.equals(lastAdoptedClip)) {
            lastAdoptedClip = clip;
            prefs.edit().putString(KEY_URL, clip).apply();
            recordHistory(clip);
            Toast.makeText(this, "已识别剪贴板中的新会话链接", Toast.LENGTH_SHORT).show();
            showWeb(clip);
            return true;
        }
        return false;
    }

    private void destroyWeb() {
        if (webView != null) {
            webView.stopLoading();
            // 先从父容器摘除再 destroy:destroy() 只做原生资源清理,仍挂在视图树上的 WebView
            // 之后收到布局/绘制事件会打到已销毁的内核,偶发崩溃
            if (webView.getParent() instanceof ViewGroup) {
                ((ViewGroup) webView.getParent()).removeView(webView);
            }
            webView.destroy();
            webView = null;
        }
        progress = null;
        fab = null;
        sessionView = null;
        zoomLabel = null;
        naturalScale = 0f;
    }

    /** 把设置/错误页作为覆盖层叠在会话层之上;会话层(WebView)原样保留,秒回靠它 */
    private void showOverlay(View v) {
        removeOverlay();
        overlayView = v;
        root.addView(v, contentLp());
    }

    private void removeOverlay() {
        if (overlayView != null) {
            root.removeView(overlayView);
            overlayView = null;
        }
    }

    /** 拉起扫码页(长按图标快捷方式与设置页按钮共用) */
    private void startScan() {
        try {
            startActivityForResult(new Intent(this, ScanActivity.class), REQ_SCAN);
        } catch (Exception e) {
            Toast.makeText(this, "无法启动扫码:请确认已授予相机权限", Toast.LENGTH_LONG).show();
        }
    }

    /** 打开站外链接:http(s)/mailto/tel 直接交给系统;intent:// 用 parseUri 解析(网页跳 App 的标准写法),
     *  目标 App 不存在时按网页给的 browser_fallback_url 兜底,避免链接静默失效 */
    private void openExternal(Uri uri) {
        try {
            if ("intent".equals(uri.getScheme())) {
                Intent intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
                try {
                    startActivity(intent);
                    return;
                } catch (Exception e) {
                    String fallback = intent.getStringExtra("browser_fallback_url");
                    if (fallback != null) {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(fallback)));
                        return;
                    }
                    if (intent.getPackage() != null) {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW,
                                    Uri.parse("market://details?id=" + intent.getPackage())));
                            return;
                        } catch (Exception ignored) {
                        }
                    }
                }
            } else {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            }
        } catch (Exception ignored) {
        }
    }

    private void showWeb(String url) {
        onErrorPage = false;
        if (sessionView == null) {
            // 首次:构建并挂到 root(buildSessionView 内部完成 addView)
            buildSessionView(url);
        } else if (!url.equals(loadedUrl)) {
            // 同一 WebView 实例直接换链接:不重建,历史/扫码/剪贴板切会话都是秒切
            loadedUrl = url;
            allowedHost = Uri.parse(url).getHost();
            webLoadFailed = false;
            if (webView != null) {
                webView.loadUrl(url);
            }
        } else if (webLoadFailed && webView != null) {
            // 同一链接但上次加载失败(错误页→设置页→返回会话):必须 reload,只掀覆盖层会露出内核错误白页
            webLoadFailed = false;
            webView.reload();
        }
        // url 相同且未失败:只是回到会话层,网页不重载、滚动位置原样(会话秒回的核心)
        removeOverlay();
    }

    /** 构建常驻会话层(WebView+进度条+悬浮钮):整个 App 生命周期只建一次 */
    private void buildSessionView(String url) {
        loadedUrl = url;
        allowedHost = Uri.parse(url).getHost();
        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        // 页面缩放:setBuiltInZoomControls 默认 false,不开双指捏合完全无效;
        // 同时关掉已废弃的屏幕 +/− 浮层按钮,交互只用捏合 + 菜单里的缩放行
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                String host = uri.getHost();
                if (("http".equals(scheme) || "https".equals(scheme)) && host != null
                        && (host.equals(allowedHost) || host.endsWith("." + allowedHost))) {
                    return false;
                }
                openExternal(uri);
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // 重载/换链后 WebView 会保留上一次的缩放,getScale() 拿到的是"已缩放"的值,
                // 所以 naturalScale 只在 WebView 全新时测一次(buildSessionView 置 0),
                // 这里绝不重置——否则每次刷新都把基准当成已缩放值,倍数被反复叠乘。
                applyZoomNow();
            }

            /**
             * 用户捏合改变了缩放:折算成倍数存下来,换页/重载后仍生效。
             * restoringZoom 期间是程序化 zoomBy 的回调,不能当作捏合处理。
             */
            @Override
            public void onScaleChanged(WebView view, float oldScale, float newScale) {
                if (restoringZoom || naturalScale <= 0f || newScale <= 0f) {
                    return;
                }
                float f = clampZoom(newScale / naturalScale);
                if (Math.abs(f - zoomFactor) > 0.02f) {
                    zoomFactor = f;
                    saveZoom();
                    updateZoomLabel();
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    int code = error.getErrorCode();
                    String msg;
                    if (code == WebViewClient.ERROR_HOST_LOOKUP || code == WebViewClient.ERROR_CONNECT
                            || code == WebViewClient.ERROR_TIMEOUT) {
                        msg = "网络连接失败,请检查手机网络后重试。";
                    } else if (code == WebViewClient.ERROR_FILE_NOT_FOUND || code == WebViewClient.ERROR_BAD_URL) {
                        msg = "会话链接可能已失效或地址有误。";
                    } else {
                        msg = "页面加载失败(错误码 " + code + ")。";
                    }
                    showError(msg);
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                if (request.isForMainFrame()) {
                    int sc = errorResponse.getStatusCode();
                    String msg = (sc == 404 || sc == 410)
                            ? "会话链接已失效(HTTP " + sc + "),请在电脑端重新生成远程链接。"
                            : "服务器返回错误(HTTP " + sc + "),请稍后重试。";
                    showError(msg);
                }
            }

            /**
             * 渲染进程被系统杀掉(内存紧张常见):不处理会白屏卡死甚至连带杀 App。
             * 自愈流程:摘除并销毁死掉的 WebView,按当前链接重建会话层。返回 true 表示已接管。
             */
            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                String url = loadedUrl;
                destroyWeb();
                webLoadFailed = false;
                onErrorPage = false;
                removeOverlay();
                toast("页面进程意外终止,正在自动恢复…");
                if (url != null) {
                    showWeb(url);
                } else {
                    showSetup(null, null);
                }
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (progress == null) {
                    return;
                }
                if (newProgress >= 100) {
                    progress.setVisibility(View.GONE);
                } else {
                    progress.setVisibility(View.VISIBLE);
                    progress.setProgress(newProgress);
                }
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                try {
                    startActivityForResult(intent, REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                // 远程网页可能请求麦克风/摄像头(语音/视频类功能):有对应系统权限就授予,没有则拒绝。
                // 不主动申请权限——保持壳的本分,需要的用户在系统设置里给过即生效
                runOnUiThread(() -> {
                    String[] wanted = request.getResources();
                    List<String> grant = new ArrayList<>();
                    for (String res : wanted) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)
                                && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                                == PackageManager.PERMISSION_GRANTED) {
                            grant.add(res);
                        } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)
                                && checkSelfPermission(Manifest.permission.CAMERA)
                                == PackageManager.PERMISSION_GRANTED) {
                            grant.add(res);
                        }
                    }
                    if (grant.isEmpty()) {
                        request.deny();
                    } else {
                        request.grant(grant.toArray(new String[0]));
                    }
                });
            }
        });

        webView.setDownloadListener((dlUrl, userAgent, contentDisposition, mimetype, contentLength) -> {
            try {
                String name = URLUtil.guessFileName(dlUrl, contentDisposition, mimetype);
                DownloadManager.Request req = new DownloadManager.Request(Uri.parse(dlUrl));
                req.setMimeType(mimetype);
                req.addRequestHeader("User-Agent", userAgent);
                String cookie = CookieManager.getInstance().getCookie(dlUrl);
                if (cookie != null) {
                    req.addRequestHeader("Cookie", cookie);
                }
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(req);
                Toast.makeText(this, "开始下载:" + name, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(dlUrl)));
                } catch (Exception ignored) {
                }
            }
        });

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        progress.setProgressTintList(ColorStateList.valueOf(ACCENT));

        RefreshLayout container = new RefreshLayout(this);
        container.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        container.addView(progress, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3), Gravity.TOP));
        FrameLayout.LayoutParams fabLp = new FrameLayout.LayoutParams(
                dp(44), dp(44), Gravity.TOP | Gravity.START);
        SharedPreferences prefs = getPreferences(Context.MODE_PRIVATE);
        fabLp.leftMargin = prefs.getInt(KEY_FAB_X, dp(16));
        fabLp.topMargin = prefs.getInt(KEY_FAB_Y, dp(40));
        fab = menuFab();
        fab.setVisibility(prefs.getBoolean(KEY_SHOW_FAB, true) ? View.VISIBLE : View.GONE);
        container.addView(fab, fabLp);
        container.post(() -> {
            // 换设备/旋转后旧位置可能越界,布局完成时兜底拉回内容区安全边距内(避开状态栏/手势条方向)
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) fab.getLayoutParams();
            int padH = dp(12);
            int padTop = dp(8);
            int padBottom = dp(24);
            lp.leftMargin = clamp(lp.leftMargin, padH,
                    Math.max(padH, container.getWidth() - fab.getWidth() - padH));
            lp.topMargin = clamp(lp.topMargin, padTop,
                    Math.max(padTop, container.getHeight() - fab.getHeight() - padBottom));
            fab.setLayoutParams(lp);
        });

        sessionView = container;
        // 固定在 root 最底层(index 0):设置/错误覆盖层无论何时添加都在其上
        root.addView(sessionView, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        zoomFactor = clampZoom(getPreferences(Context.MODE_PRIVATE).getFloat(KEY_ZOOM, 1f));
        naturalScale = 0f;
        webView.loadUrl(url);
    }

    private TextView menuFab() {
        TextView fab = new TextView(this);
        fab.setText("⋮");
        fab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        fab.setTextColor(FG);
        fab.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xCC1B1F27);
        fab.setBackground(bg);
        fab.setElevation(dp(6));
        // 点击=开菜单;按住拖动超过阈值=移动按钮并记住位置,避免挡住网页自己的按钮
        fab.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private int startL, startT;
            private boolean moved = false;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startL = lp.leftMargin;
                        startT = lp.topMargin;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX;
                        float dy = e.getRawY() - downY;
                        if (!moved && dx * dx + dy * dy > dp(10) * dp(10)) {
                            moved = true;
                        }
                        if (moved) {
                            View parent = (View) v.getParent();
                            int padH = dp(12);
                            lp.leftMargin = clamp((int) (startL + dx), padH,
                                    Math.max(padH, parent.getWidth() - v.getWidth() - padH));
                            lp.topMargin = clamp((int) (startT + dy), dp(8),
                                    Math.max(dp(8), parent.getHeight() - v.getHeight() - dp(24)));
                            v.setLayoutParams(lp);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (moved) {
                            // 松手即停在拖放位置(可悬停在任意位置),只记录坐标,不做边缘吸附
                            getPreferences(Context.MODE_PRIVATE).edit()
                                    .putInt(KEY_FAB_X, lp.leftMargin)
                                    .putInt(KEY_FAB_Y, lp.topMargin).apply();
                        } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                            showMenu();
                        }
                        return true;
                }
                return false;
            }
        });
        return fab;
    }

    private int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(Math.max(hi, lo), v));
    }

    private void showMenu() {
        final String url = getPreferences(Context.MODE_PRIVATE).getString(KEY_URL, "");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("ZCode Remote · v" + appVersion());
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTextColor(FG);
        title.setPadding(0, 0, 0, dp(4));
        box.addView(title);

        TextView session = new TextView(this);
        session.setText("当前会话:" + (url.isEmpty() ? "(未设置)" : url));
        session.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        session.setTextColor(FG_DIM);
        session.setPadding(0, 0, 0, dp(8));
        box.addView(session);

        box.addView(panelRow("⟳", "刷新会话", "重新加载当前会话页面", v -> {
            dismissMenu();
            if (webView != null) {
                webView.reload();
            }
        }));
        box.addView(panelRow("✎", "更换链接", "回到设置页,粘贴新的远程链接", v -> {
            dismissMenu();
            showSetup(url, null);
        }));
        box.addView(panelRow("⧉", "复制当前链接", "把会话链接复制到剪贴板", v -> {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("url", url));
                Toast.makeText(this, "已复制当前链接", Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {
            }
        }));
        box.addView(panelRow("↗", "分享会话", "把会话链接发送到微信/QQ 等", v -> {
            if (url == null || url.isEmpty()) {
                toast("当前无有效会话链接");
                return;
            }
            try {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TEXT, url);
                startActivity(Intent.createChooser(send, "分享 ZCode 远程会话"));
            } catch (Exception e) {
                toast("无法拉起系统分享");
            }
        }));
        box.addView(panelRow("🕒", "历史会话", "查看或切换最近使用过的会话", v -> showHistoryDialog()));
        box.addView(panelRow("▣", "出示会话码", "把当前链接生成二维码,供其他设备扫码接管", v -> showSessionQr(url)));
        box.addView(panelRow("⬇", "检查更新", "查看 GitHub 上是否有新版本", v -> updater.checkUpdate(true)));

        box.addView(panelHeader("设置"));
        box.addView(panelSwitch("保持屏幕常亮", "监视任务时防止手机自动休眠", KEY_KEEP_SCREEN_ON, (c) -> {
            applyKeepScreenOn(c);
            Toast.makeText(this, c ? "已开启屏幕常亮" : "已恢复系统休眠", Toast.LENGTH_SHORT).show();
        }));
        box.addView(panelSwitch("悬浮按钮", "显示会话页的 ⋮ 菜单按钮", KEY_SHOW_FAB, (c) -> {
            if (fab != null) {
                fab.setVisibility(c ? View.VISIBLE : View.GONE);
            }
            Toast.makeText(this, c ? "悬浮按钮已开启"
                    : "已隐藏,从设置页可再开启", Toast.LENGTH_SHORT).show();
        }));
        box.addView(panelSwitch("启动时自动检查更新", "打开 App 时在后台静默检查", Updater.KEY_AUTO_UPDATE, (c) -> {
        }));
        // 应用锁默认关;开启即视为本次进程已验证,不立刻弹验证框
        box.addView(panelSwitch("应用锁(指纹/人脸)", "打开 App 时需生物识别验证,防止他人借用会话", KEY_APP_LOCK, false, (c) -> {
            if (c) {
                unlockedThisSession = true;
                Toast.makeText(this, "已开启:下次冷启动 App 时需要验证", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "已关闭应用锁", Toast.LENGTH_SHORT).show();
            }
        }));

        box.addView(panelHeader("页面缩放"));
        LinearLayout zoomHead = new LinearLayout(this);
        zoomHead.setOrientation(LinearLayout.HORIZONTAL);
        zoomHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView zoomHint = new TextView(this);
        zoomHint.setText("当前");
        zoomHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        zoomHint.setTextColor(FG_DIM);
        zoomHead.addView(zoomHint, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        zoomLabel = new TextView(this);
        zoomLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        zoomLabel.setTextColor(ACCENT);
        zoomHead.addView(zoomLabel);
        zoomHead.setPadding(0, dp(2), 0, dp(2));
        box.addView(zoomHead);
        updateZoomLabel();

        box.addView(panelRow("＋", "放大", "把网页内容放大一档(也可双指捏合)", v -> stepZoom(ZOOM_STEP)));
        box.addView(panelRow("－", "缩小", "把网页内容缩小一档(也可双指捏合)", v -> stepZoom(1f / ZOOM_STEP)));
        box.addView(panelRow("⟲", "重置缩放", "恢复网页原始大小(100%)", v -> resetZoom()));

        box.addView(panelHeader("工具"));
        box.addView(panelRow("✕", "清除网页数据", "解决网页卡死或状态错乱(清 Cookie/缓存)", v -> confirmClearData()));
        box.addView(panelRow("ⓘ", "关于本应用", "版本信息与使用说明", v -> showAbout(url)));

        TextView footer = new TextView(this);
        footer.setText("ZCode Remote v" + appVersion() + " · 网页的独立窗口封装");
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        footer.setTextColor(FG_DIM);
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        footer.setPadding(0, dp(14), 0, 0);
        box.addView(footer);

        ScrollView sc = new ScrollView(this);
        sc.addView(box);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(24), dp(20), dp(24), dp(16));
        wrap.addView(sc);

        menuDialog = new AlertDialog.Builder(this)
                .setView(wrap)
                .setNegativeButton("关闭", null)
                .show();
    }

    private void dismissMenu() {
        zoomLabel = null;
        if (menuDialog != null && menuDialog.isShowing()) {
            try {
                menuDialog.dismiss();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 页面缩放。远程网页自带 `width=device-width, initial-scale=1` 的 viewport meta,
     * 而 setInitialScale 官方只对"没有 viewport meta 的页面"生效,所以不用它:
     * 页面加载完成后按倍数调 zoomBy,倍数相对本页未缩放时的基准缩放 naturalScale。
     *
     * 注意 getScale() 返回的是"含设备像素密度"的绝对值(模拟器上 2.625),不是 1.0,
     * 所以必须用 naturalScale 做基准、只按倍数换算,不能直接拿百分比当 scale 用。
     * zoomBy 在 onPageFinished 刚回调时经常静默无效(内核布局未稳定),故发完延迟校验,
     * 没到位就重试,最多 6 次。
     */
    private void applyZoomNow() {
        applyZoomNow(0);
    }

    private void applyZoomNow(int attempt) {
        if (webView == null) {
            return;
        }
        float cur = webView.getScale();
        if (cur <= 0f) {
            if (attempt < 10) {
                webView.postDelayed(() -> applyZoomNow(attempt + 1), 120);
            }
            return;
        }
        if (naturalScale <= 0f) {
            naturalScale = cur;
        }
        float target = naturalScale * zoomFactor;
        if (Math.abs(target - cur) > 0.01f) {
            restoringZoom = true;
            webView.zoomBy(target / cur);
            webView.postDelayed(() -> restoringZoom = false, 300);
            // 校验是否真的生效:onPageFinished 刚回调时内核布局未稳定,zoomBy 常被静默吞掉,
            // 没到位就重试(上限 6 次)。TAG=ZCodeZoom 只在这条异常路径打点,便于日后排查
            if (attempt < 6) {
                webView.postDelayed(() -> {
                    if (webView != null && Math.abs(webView.getScale() - target) > 0.05f) {
                        Log.i("ZCodeZoom", "zoomBy 未生效,重试 attempt=" + (attempt + 1)
                                + " cur=" + webView.getScale() + " target=" + target);
                        applyZoomNow(attempt + 1);
                    }
                }, 220);
            }
        }
        updateZoomLabel();
    }

    /** 相对当前倍数增减一档并立即生效 */
    private void stepZoom(float factor) {
        if (webView == null) {
            return;
        }
        zoomFactor = clampZoom(zoomFactor * factor);
        saveZoom();
        applyZoomNow();
        updateZoomLabel();
    }

    private void resetZoom() {
        if (webView == null) {
            return;
        }
        zoomFactor = 1f;
        saveZoom();
        applyZoomNow();
        updateZoomLabel();
        Toast.makeText(this, "已恢复 100%", Toast.LENGTH_SHORT).show();
    }

    private void saveZoom() {
        getPreferences(Context.MODE_PRIVATE).edit().putFloat(KEY_ZOOM, zoomFactor).apply();
    }

    private float clampZoom(float v) {
        return Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, v));
    }

    /** 菜单里的当前缩放百分比;菜单未展示时静默跳过 */
    private void updateZoomLabel() {
        if (zoomLabel != null) {
            zoomLabel.setText(Math.round(zoomFactor * 100) + "%");
        }
    }

    private View panelRow(String icon, String title, String sub, View.OnClickListener action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        row.setOnClickListener(action);

        TextView ic = new TextView(this);
        ic.setText(icon);
        ic.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        ic.setTextColor(ACCENT);
        ic.setWidth(dp(34));
        ic.setGravity(Gravity.CENTER);
        row.addView(ic);

        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTextColor(FG);
        txt.addView(t);
        if (sub != null) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            s.setTextColor(FG_DIM);
            txt.addView(s);
        }
        row.addView(txt, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    private TextView panelHeader(String text) {
        TextView h = new TextView(this);
        h.setText(text);
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        h.setTextColor(ACCENT);
        h.setPadding(0, dp(14), 0, dp(2));
        return h;
    }

    private interface SwitchChanged {
        void onChanged(boolean checked);
    }

    private View panelSwitch(String title, String sub, String prefKey, SwitchChanged onChanged) {
        return panelSwitch(title, sub, prefKey, true, onChanged);
    }

    private View panelSwitch(String title, String sub, String prefKey, boolean def, SwitchChanged onChanged) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTextColor(FG);
        txt.addView(t);
        if (sub != null) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            s.setTextColor(FG_DIM);
            txt.addView(s);
        }
        row.addView(txt, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Switch sw = new Switch(this);
        sw.setChecked(getPreferences(Context.MODE_PRIVATE).getBoolean(prefKey, def));
        sw.setOnCheckedChangeListener((v, c) -> {
            getPreferences(Context.MODE_PRIVATE).edit().putBoolean(prefKey, c).apply();
            onChanged.onChanged(c);
        });
        row.addView(sw);
        return row;
    }

    private void confirmClearData() {
        new AlertDialog.Builder(this)
                .setTitle("清除网页数据")
                .setMessage("会删除网页的 Cookie、缓存、本地存储(localStorage/IndexedDB)和表单记录。\n\n用于网页卡死、状态错乱时强制重置;不影响已保存的远程链接。确定继续吗?")
                .setPositiveButton("清除并刷新", (d, w) -> {
                    dismissMenu();
                    CookieManager.getInstance().removeAllCookies(null);
                    CookieManager.getInstance().flush();
                    // localStorage / IndexedDB / WebSQL / ServiceWorker 等网页存储,
                    // 远程会话的 relay 登录态大概率在这里,不清等于没重置
                    try {
                        WebStorage.getInstance().deleteAllData();
                    } catch (Exception ignored) {
                    }
                    if (webView != null) {
                        webView.clearCache(true);
                        webView.clearFormData();
                        try {
                            WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword();
                        } catch (Exception ignored) {
                        }
                        webView.reload();
                    }
                    Toast.makeText(this, "网页数据已清除,正在刷新", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showAbout(String url) {
        new AlertDialog.Builder(this)
                .setTitle("关于 ZCode Remote")
                .setMessage("版本:v" + appVersion()
                        + "\n\n当前会话:\n" + (url.isEmpty() ? "(未设置)" : url)
                        + "\n\n说明:\n把 ZCode 桌面端的远程控制网页封装成独立 App,只做装载与移动体验增强,网页功能归 ZCode 官方。")
                .setPositiveButton("关闭", null)
                .show();
    }

    private void showError(String message) {
        final String url = getPreferences(Context.MODE_PRIVATE).getString(KEY_URL, null);
        onErrorPage = true;
        webLoadFailed = true;
        // 不销毁 WebView:错误页以覆盖层盖在会话层上,重试/网络恢复后 reload 即可
        LinearLayout box = darkBox();

        TextView icon = new TextView(this);
        icon.setText("⚠️");
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        icon.setPadding(0, dp(48), 0, dp(16));
        box.addView(icon);

        TextView err = new TextView(this);
        err.setText(message);
        err.setTextColor(RED);
        err.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        err.setPadding(0, 0, 0, dp(20));
        box.addView(err);

        Button retry = new Button(this);
        retry.setText("重试");
        stylePrimary(retry);
        box.addView(retry, buttonLp());
        retry.setOnClickListener(v -> {
            if (webView != null) {
                // 会话层还在:移除错误覆盖层后原样 reload,不重建
                onErrorPage = false;
                webLoadFailed = false;
                removeOverlay();
                webView.reload();
            } else if (url != null) {
                showWeb(url);
            }
        });

        Button change = new Button(this);
        change.setText("更换链接");
        styleSecondary(change);
        box.addView(change, buttonLp());
        change.setOnClickListener(v -> showSetup(url, null));

        showOverlay(scrollWrap(box));
    }

    private void showSetup(String prefill, String error) {
        onErrorPage = false;
        // 不销毁 WebView:设置页以覆盖层叠在会话层上,「返回会话」秒回、网页状态保留
        LinearLayout box = darkBox();

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher_foreground);
        logo.setBackgroundResource(R.drawable.logo_bg);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(72), dp(72));
        logoLp.topMargin = dp(24);
        box.addView(logo, logoLp);

        TextView title = new TextView(this);
        title.setText("ZCode Remote");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTextColor(FG);
        title.setPadding(0, dp(16), 0, 0);
        box.addView(title);

        TextView hint = new TextView(this);
        hint.setText("把电脑上 ZCode 生成的远程链接(以 https://zcode.z.ai/remote 开头)粘贴到下面保存。\n\n之后开启新会话有三种方式进入:\n· 在手机上复制新链接,打开本 App 自动识别\n· 在微信/QQ 里直接点开链接,选择用本 App 打开\n· 长按桌面图标 →「更换链接」回到本页");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hint.setTextColor(FG_DIM);
        hint.setPadding(0, dp(12), 0, dp(8));
        box.addView(hint);

        if (error != null) {
            TextView err = new TextView(this);
            err.setText(error);
            err.setTextColor(RED);
            err.setPadding(0, dp(8), 0, dp(4));
            box.addView(err);
        }

        final EditText input = new EditText(this);
        input.setHint("https://zcode.z.ai/remote/v4?sid=...");
        input.setTextColor(FG);
        input.setHintTextColor(FG_DIM);
        input.setBackgroundTintList(ColorStateList.valueOf(ACCENT));
        if (prefill != null) {
            input.setText(prefill);
        }
        input.setSingleLine(true);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputLp.topMargin = dp(8);
        box.addView(input, inputLp);

        Button save = new Button(this);
        save.setText("保存并打开");
        stylePrimary(save);
        box.addView(save, buttonLp());
        save.setOnClickListener(v -> {
            String url = input.getText().toString().trim();
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                Toast.makeText(this, "链接格式不对,应以 https:// 开头", Toast.LENGTH_LONG).show();
                return;
            }
            if (!REMOTE_URL.matcher(url).find()) {
                // 不阻拦任何链接(保持壳的通用性),但非 ZCode 远程地址时二次确认,防手滑粘错
                new AlertDialog.Builder(this)
                        .setTitle("这似乎不是 ZCode 远程链接")
                        .setMessage("粘贴的内容不是以 https://zcode.z.ai/remote 开头的地址,确定要保存并打开它吗?")
                        .setPositiveButton("仍然打开", (d, w) -> saveAndOpen(url))
                        .setNegativeButton("再看看", null)
                        .show();
                return;
            }
            saveAndOpen(url);
        });

        Button paste = new Button(this);
        paste.setText("从剪贴板粘贴");
        styleSecondary(paste);
        box.addView(paste, buttonLp());
        paste.setOnClickListener(v -> {
            String clip = clipboardText();
            if (clip == null || clip.trim().isEmpty()) {
                Toast.makeText(this, "剪贴板是空的", Toast.LENGTH_SHORT).show();
                return;
            }
            Matcher m = REMOTE_URL.matcher(clip);
            input.setText(m.find() ? m.group() : clip.trim());
            Toast.makeText(this, "已粘贴,确认后点「保存并打开」", Toast.LENGTH_SHORT).show();
        });

        Button scan = new Button(this);
        scan.setText("扫码绑定");
        styleSecondary(scan);
        box.addView(scan, buttonLp());
        scan.setOnClickListener(v -> startScan());

        Button hist = new Button(this);
        hist.setText("历史会话");
        styleSecondary(hist);
        box.addView(hist, buttonLp());
        hist.setOnClickListener(v -> showHistoryDialog());

        Button check = new Button(this);
        check.setText("检查更新");
        styleSecondary(check);
        box.addView(check, buttonLp());
        check.setOnClickListener(v -> updater.checkUpdate(true));

        final String savedUrl = getPreferences(Context.MODE_PRIVATE).getString(KEY_URL, null);
        if (savedUrl != null) {
            Button back = new Button(this);
            back.setText("返回会话");
            styleSecondary(back);
            box.addView(back, buttonLp());
            back.setOnClickListener(v -> showWeb(savedUrl));
        }

        TextView footer = new TextView(this);
        footer.setText("v" + appVersion() + " · ZCode 网页的独立窗口封装");
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        footer.setTextColor(FG_DIM);
        footer.setPadding(0, dp(28), 0, 0);
        box.addView(footer);

        showOverlay(scrollWrap(box));
    }

    /** 设置页确认后的统一保存入口:存偏好、记历史、打开 */
    private void saveAndOpen(String url) {
        getPreferences(Context.MODE_PRIVATE).edit().putString(KEY_URL, url).apply();
        recordHistory(url);
        showWeb(url);
    }

    /** 设置/错误页容器:竖屏占满,横屏与平板限宽居中,内容超高可滚动 */
    private View scrollWrap(LinearLayout box) {
        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        sc.addView(box, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sc;
    }

    private FrameLayout.LayoutParams contentLp() {
        int maxW = dp(600);
        if (getResources().getDisplayMetrics().widthPixels > maxW) {
            return new FrameLayout.LayoutParams(maxW, ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER_HORIZONTAL);
        }
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private void stylePrimary(Button b) {
        b.setBackgroundTintList(ColorStateList.valueOf(BTN_PRIMARY));
        b.setTextColor(0xFFFFFFFF);
    }

    private void styleSecondary(Button b) {
        b.setBackgroundTintList(ColorStateList.valueOf(BTN_SECONDARY));
        b.setTextColor(FG);
    }

    private LinearLayout darkBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        box.setPadding(pad, pad, pad, pad);
        box.setBackgroundColor(BG);
        return box;
    }

    private LinearLayout.LayoutParams buttonLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        return lp;
    }

    private String clipboardText() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) {
                return null;
            }
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                return null;
            }
            CharSequence text = clip.getItemAt(0).coerceToText(this);
            return text == null ? null : text.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String remoteUrlFromClipboard() {
        String text = clipboardText();
        if (text == null) {
            return null;
        }
        Matcher m = REMOTE_URL.matcher(text);
        return m.find() ? m.group() : null;
    }

    /** 应用锁:开启后每次冷启动(进程新建)首次回前台要求生物识别验证,进程存活期间不重复验 */
    private void maybeArmAppLock() {
        if (unlockedThisSession || lockArmed
                || !getPreferences(Context.MODE_PRIVATE).getBoolean(KEY_APP_LOCK, false)) {
            return;
        }
        lockArmed = true;
        // 稍作延迟等窗口 focus 就绪,BiometricPrompt 在 resume 瞬间弹出更稳
        root.postDelayed(this::showLock, 300);
    }

    private void showLock() {
        if (Build.VERSION.SDK_INT < 28) {
            // 框架版 BiometricPrompt API 28+;minSdk 26 的两档老系统直接放行(功能降级,不锁死用户)
            lockArmed = false;
            return;
        }
        BiometricManager bm = getSystemService(BiometricManager.class);
        int can = bm == null ? BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED : bm.canAuthenticate();
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            // 没录指纹/人脸或硬件不可用:弹窗说明并给"跳过",避免把自己锁在门外
            lockArmed = false;
            new AlertDialog.Builder(this)
                    .setTitle("应用锁无法验证")
                    .setMessage("设备未录入可用的指纹/人脸,本次跳过验证。\n\n请在系统设置中录入生物识别,或在菜单里关闭应用锁。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        BiometricPrompt prompt = new BiometricPrompt.Builder(this)
                .setTitle("验证以打开 ZCode Remote")
                .setSubtitle("会话链接可控制你的电脑,请本人验证")
                .setNegativeButton("跳过", getMainExecutor(), (d, w) -> {
                    lockArmed = false;
                    toast("已跳过本次验证");
                })
                .build();
        prompt.authenticate(new CancellationSignal(), getMainExecutor(),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        unlockedThisSession = true;
                        lockArmed = false;
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, CharSequence errString) {
                        // 用户取消/锁屏等:保持 armed,下次回前台再验
                        lockArmed = false;
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        // 单次比对失败,系统弹窗还在,允许继续尝试
                    }
                });
    }

    /** 出示会话码:当前链接生成 QR,供其他设备扫码接管;sid 是凭证,60 秒后自动遮蔽 */
    private void showSessionQr(String url) {
        if (url == null || url.isEmpty()) {
            toast("当前无有效会话链接");
            return;
        }
        Bitmap qr;
        try {
            // 高纠错等级:容忍扫码端反光/折叠;520 上限覆盖 200+ 字符链接的模块密度
            BitMatrix matrix = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 520, 520);
            int w = matrix.getWidth();
            int h = matrix.getHeight();
            int[] pixels = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    pixels[y * w + x] = matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
                }
            }
            qr = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            toast("二维码生成失败");
            return;
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);

        // 白底衬垫:深色主题下 QR 直接黑底白块对比度反了,扫码端二值化易失败
        FrameLayout pad = new FrameLayout(this);
        pad.setBackgroundColor(0xFFFFFFFF);
        pad.setPadding(dp(12), dp(12), dp(12), dp(12));
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(qr);
        pad.addView(iv, new FrameLayout.LayoutParams(dp(240), dp(240)));
        box.addView(pad);

        TextView hint = new TextView(this);
        hint.setTextColor(FG_DIM);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        hint.setPadding(0, dp(12), 0, 0);
        box.addView(hint);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("会话二维码")
                .setView(box)
                .setPositiveButton("关闭", null)
                .show();

        // 60 秒倒计时后遮蔽(sid=控制电脑的凭证,防止亮屏久置被旁人扫走)
        Handler countdown = new Handler(Looper.getMainLooper());
        final int[] left = {60};
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (!dialog.isShowing()) {
                    return;
                }
                left[0]--;
                if (left[0] > 0) {
                    hint.setText("链接含会话凭证,请勿外传 · " + left[0] + " 秒后自动遮蔽");
                    countdown.postDelayed(this, 1000);
                } else {
                    // 连白底衬垫一起隐藏:只藏 ImageView 会留下一块空白白条
                    pad.setVisibility(View.GONE);
                    hint.setText("已遮蔽:链接含会话凭证,久置可能被旁人扫走。\n如需继续出示,请重新打开本页。");
                }
            }
        };
        hint.setText("链接含会话凭证,请勿外传 · 60 秒后自动遮蔽");
        countdown.postDelayed(tick, 1000);
        dialog.setOnDismissListener(d -> countdown.removeCallbacks(tick));
    }

    /**
     * 动态快捷方式:长按图标直达最近两个会话(排在静态"更换链接/扫码绑定"之前)。
     * sid 会随会话轮换,快捷方式跟历史列表同步刷新;API 26+ 才支持 pinned/dynamic shortcuts。
     */
    private void updateDynamicShortcuts() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        try {
            ShortcutManager sm = getSystemService(ShortcutManager.class);
            if (sm == null) {
                return;
            }
            List<ShortcutInfo> list = new ArrayList<>();
            List<HistoryItem> history = getHistoryList();
            for (int i = 0; i < history.size() && i < 2; i++) {
                HistoryItem item = history.get(i);
                list.add(new ShortcutInfo.Builder(this, "session_" + i)
                        .setShortLabel(summarizeUrl(item.url))
                        .setLongLabel("打开会话 " + summarizeUrl(item.url))
                        .setIcon(Icon.createWithResource(this, R.drawable.ic_launcher_foreground))
                        .setIntent(new Intent(this, MainActivity.class)
                                .setAction(ACTION_OPEN_SESSION)
                                .putExtra(KEY_URL, item.url)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                        .build());
            }
            sm.setDynamicShortcuts(list);
        } catch (Exception ignored) {
            // 桌面启动器限流等场景会抛异常,快捷方式是锦上添花,失败不影响主流程
        }
    }

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SCAN) {
            if (resultCode == RESULT_OK && data != null) {
                String scanned = data.getStringExtra(ScanActivity.EXTRA_URL);
                if (scanned != null) {
                    Matcher m = REMOTE_URL.matcher(scanned);
                    String url = m.find() ? m.group() : scanned.trim();
                    getPreferences(Context.MODE_PRIVATE).edit().putString(KEY_URL, url).apply();
                    recordHistory(url);
                    Toast.makeText(this, "扫码成功,正在打开会话", Toast.LENGTH_SHORT).show();
                    showWeb(url);
                }
            }
            return;
        }
        if (requestCode == REQ_FILE && fileCallback != null) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    ClipData cd = data.getClipData();
                    results = new Uri[cd.getItemCount()];
                    for (int i = 0; i < cd.getItemCount(); i++) {
                        results[i] = cd.getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            fileCallback.onReceiveValue(results);
            fileCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        // Android 12 及以下的返回路径;13+ 由 registerBackCallback 注册的 OnBackInvokedCallback 接管
        if (!handleBack()) {
            super.onBackPressed();
        }
    }

    /**
     * 统一的返回语义:设置页等覆盖层优先(返回=回会话秒回,不退出);错误页覆盖层走退出确认。
     * 返回 true 表示已消费;false 表示应退出 Activity。
     */
    private boolean handleBack() {
        if (overlayView != null && !onErrorPage) {
            removeOverlay();
            return true;
        }
        if (!onErrorPage && webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackTime < 2000L) {
            return false;
        }
        lastBackTime = now;
        Toast.makeText(this, "再按一次退出 ZCode Remote", Toast.LENGTH_SHORT).show();
        return true;
    }

    @Override
    protected void onDestroy() {
        updater.onDestroy();
        dismissMenu();
        if (clipListener != null) {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.removePrimaryClipChangedListener(clipListener);
                }
            } catch (Exception ignored) {
            }
            clipListener = null;
        }
        if (networkCallback != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    cm.unregisterNetworkCallback(networkCallback);
                }
            } catch (Exception ignored) {
            }
            networkCallback = null;
        }
        destroyWeb();
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private class RefreshLayout extends FrameLayout {
        private float downY = -1f;
        private float downX = 0f;
        private float lastDy = 0f;
        private boolean dragging = false;
        // 双指捏合缩放时手指也会向下移动,必须排除多指手势,否则缩放会误触下拉刷新
        private boolean multiTouch = false;

        RefreshLayout(Context context) {
            super(context);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY = ev.getY();
                    downX = ev.getX();
                    dragging = false;
                    multiTouch = false;
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    // 第二根手指落下:本手势判定为缩放,不再参与下拉刷新
                    multiTouch = true;
                    dragging = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (multiTouch || ev.getPointerCount() > 1) {
                        break;
                    }
                    float dy = ev.getY() - downY;
                    float dx = Math.abs(ev.getX() - downX);
                    // 远程页面内部自滚动,WebView.getScrollY() 恒为 0,不能作为"已在顶部"的判据;
                    // 只认从屏幕顶部窄条开始、近乎垂直、拉得足够深的手势,避免页内操作误触刷新重连
                    if (!dragging && downY >= 0 && dy > dp(112) && dy > dx * 3
                            && downY < getHeight() / 6 && webView != null) {
                        dragging = true;
                        lastDy = dy;
                        if (progress != null) {
                            progress.setProgress(20);
                            progress.setVisibility(View.VISIBLE);
                        }
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    downY = -1f;
                    multiTouch = false;
                    break;
            }
            return dragging;
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_POINTER_DOWN:
                    multiTouch = true;
                    dragging = false;
                    if (progress != null) {
                        progress.setVisibility(View.GONE);
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (downY >= 0 && !multiTouch) {
                        lastDy = ev.getY() - downY;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    if (dragging && lastDy > dp(96) && webView != null) {
                        webView.reload();
                    } else if (progress != null) {
                        progress.setVisibility(View.GONE);
                    }
                    dragging = false;
                    downY = -1f;
                    multiTouch = false;
                    break;
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    downY = -1f;
                    multiTouch = false;
                    if (progress != null) {
                        progress.setVisibility(View.GONE);
                    }
                    break;
            }
            return true;
        }
    }
}
