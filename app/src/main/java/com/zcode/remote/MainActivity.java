package com.zcode.remote;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
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

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import android.Manifest;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.webkit.WebStorage;
import android.webkit.WebViewDatabase;


public class MainActivity extends Activity {
    private static final String KEY_URL = "url";
    private static final String KEY_FAB_X = "fab_x";
    private static final String KEY_FAB_Y = "fab_y";
    private static final String KEY_SHOW_FAB = "show_fab";
    private static final String KEY_AUTO_UPDATE = "auto_update";
    private static final String ACTION_CHANGE_URL = "com.zcode.remote.CHANGE_URL";
    private static final Pattern REMOTE_URL = Pattern.compile("https://zcode\\.z\\.ai/remote\\S*");
    // 版本自动更新:GitHub Releases 元数据,tag 命名 v1.3,asset 为任意 .apk
    static final String KEY_KEEP_SCREEN_ON = "keep_screen_on";
    private static final String KEY_HISTORY = "history_urls";
    private static final int MAX_HISTORY = 8;
    private static final String KEY_LAST_UPDATE_CHECK = "last_update_check";
    private static final long UPDATE_CHECK_INTERVAL_MS = 4L * 60L * 60L * 1000L; // 自动检查节流:4 小时
    private static final String RELEASE_API = "https://api.github.com/repos/LShang001/zcode-remote/releases/latest";
    private static final Pattern TAG_JSON = Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([0-9][0-9.]*)\"");
    private static final Pattern APK_URL_JSON = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.apk)\"");
    // 更新安装包下载源:国内 GitHub 加速前缀优先(把完整 github.com 下载 URL 拼在后面),
    // 失败或卡住自动切下一个,全部不可用再回退 GitHub 官方源。免费公共代理可用性会变,多放几个兜底。
    private static final String[] DL_MIRRORS = {
            // gh-proxy.com 用户真机实测可用(2026-09-04),排第一优先命中
            "https://gh-proxy.com/",
            "https://ghfast.top/",
            "https://gh.llkk.cc/",
            "https://ghproxy.net/"
    };
    private static final long DL_STALL_TIMEOUT_MS = 20_000L; // 下载卡住(无字节增长)判定阈值,超时切换下一源
    private static final int BG = 0xFF0F1014;
    private static final int FG = 0xFFEDEEF0;
    private static final int FG_DIM = 0xFF9AA0A6;
    private static final int RED = 0xFFFF6E6E;
    private static final int ACCENT = 0xFF4C8DFF;
    private static final int BTN_PRIMARY = 0xFF1B5BD7;
    private static final int BTN_SECONDARY = 0xFF2A2D36;
    private static final int REQ_FILE = 1;
    private static final int REQ_SCAN = 2;
    private static final int REQ_NOTIF = 3;

    private FrameLayout root;
    private WebView webView;
    private ProgressBar progress;
    private ValueCallback<Uri[]> fileCallback;
    private String allowedHost;
    private String loadedUrl;
    private String lastAdoptedClip;
    private long pendingDownloadId = -1L;
    private String pendingVersion;
    private String pendingUrl;
    private AlertDialog updateDialog;
    private ProgressBar updateBar;
    private TextView updateText;
    private Handler updateHandler;
    private TextView fab;
    private AlertDialog menuDialog;
    private long lastBackTime = 0L;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean onErrorPage = false;
    // 是否在前台:剪贴板监听只在前台响应;版本/签名缓存
    private boolean inForeground = false;
    private ClipboardManager.OnPrimaryClipChangedListener clipListener;
    // 上次已处理过的剪贴板时间戳:内容没变就不重复读取(Android 12+ 读取他应用剪贴板会弹系统提示)
    private long lastClipTimestamp = -1L;
    private String appVersionCache;
    private Signature[] ownSignatures;
    // 下载完成但缺"安装未知应用"权限时,暂存下载 id;授权回前台后续装(文件还在,不用重新下载)
    private long pendingInstallDownloadId = -1L;
    // 更新下载源切换状态:-1..N-2 为 DL_MIRRORS 下标,N-1 表示官方源;-1 表示尚未开始
    private int dlSourceIndex = -1;
    private int dlRound = 0;
    private long dlLastBytes = -1L;
    private long dlLastProgressAt = 0L;
    private final Runnable progressPoller = new Runnable() {
        @Override
        public void run() {
            if (updateDialog == null || pendingDownloadId < 0) {
                return;
            }
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            Cursor c = dm.query(new DownloadManager.Query().setFilterById(pendingDownloadId));
            if (c == null) {
                repost();
                return;
            }
            try {
                if (!c.moveToFirst()) {
                    failDownload();
                    return;
                }
                int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                long done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                long total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    long id = pendingDownloadId;
                    pendingDownloadId = -1L;
                    verifyAndInstall(id);
                    return;
                }
                if (status == DownloadManager.STATUS_FAILED) {
                    failDownload();
                    return;
                }
                if (updateText != null && updateBar != null) {
                    int pct = total > 0 ? (int) (done * 100 / total) : 0;
                    updateBar.setProgress(pct);
                    String extra = status == DownloadManager.STATUS_PAUSED ? " · 等待网络…" : "";
                    updateText.setText(pct + "% · " + formatSize(done) + " / "
                            + (total > 0 ? formatSize(total) : "?") + extra + " · " + dlSourceLabel());
                }
                // 卡住检测:仅 RUNNING 状态下字节长时间不增长才算(代理挂起/0 字节);
                // PAUSED(等待网络/Wi-Fi 切换恢复)是系统调度,不计超时,否则弱网会无谓轮换所有源
                long now = System.currentTimeMillis();
                if (done > dlLastBytes) {
                    dlLastBytes = done;
                    dlLastProgressAt = now;
                } else if (status == DownloadManager.STATUS_PAUSED) {
                    // 系统暂停(等网络/切 Wi-Fi)期间持续刷新计时,恢复 RUNNING 后不会立刻误判超时
                    dlLastProgressAt = now;
                } else if (status == DownloadManager.STATUS_RUNNING
                        && now - dlLastProgressAt > DL_STALL_TIMEOUT_MS) {
                    failDownload();
                    return;
                }
            } finally {
                c.close();
            }
            repost();
        }

        private void repost() {
            if (updateHandler != null) {
                updateHandler.postDelayed(this, 400);
            }
        }
    };

    private final BroadcastReceiver downloadDone = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
            // 与 progressPoller 同为主线程串行:谁先处理谁把 pendingDownloadId 置 -1,另一个自然跳过
            if (id == pendingDownloadId) {
                pendingDownloadId = -1L;
                verifyAndInstall(id);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyKeepScreenOn(getPreferences(Context.MODE_PRIVATE).getBoolean(KEY_KEEP_SCREEN_ON, true));
        // 只在 debug 包里开启 WebView 远程调试,release 关闭减少调试口暴露
        boolean debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        WebView.setWebContentsDebuggingEnabled(debuggable);
        // targetSdk 34 要求动态注册非豁免系统广播时显式声明导出标志;系统服务(DownloadManager)不受 NOT_EXPORTED 影响
        IntentFilter doneFilter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(downloadDone, doneFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(downloadDone, doneFilter);
        }
        registerNetworkCallback();
        registerClipboardListener();
        maybeRequestNotificationPermission();
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        setContentView(root);
        route(getIntent());
        SharedPreferences sp = getPreferences(Context.MODE_PRIVATE);
        // 自动检查节流:距上次检查不足 4 小时则跳过,避免每次冷启动都打 GitHub API
        if (sp.getBoolean(KEY_AUTO_UPDATE, true)
                && System.currentTimeMillis() - sp.getLong(KEY_LAST_UPDATE_CHECK, 0L) >= UPDATE_CHECK_INTERVAL_MS) {
            checkUpdate(false);
        }
    }

    /** 版本号单一来源:运行时读 PackageInfo,菜单/关于/更新比较都用它,避免与 build.gradle 双写漏改 */
    private String appVersion() {
        if (appVersionCache == null) {
            try {
                appVersionCache = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                appVersionCache = "0";
            }
        }
        return appVersionCache;
    }

    private long appVersionCode() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : (long) info.versionCode;
        } catch (Exception e) {
            return 0L;
        }
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
                                showWeb(url);
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
        maybeResumeInstallAfterPermission();
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

        if (intent != null && ACTION_CHANGE_URL.equals(intent.getAction())) {
            showSetup(prefs.getString(KEY_URL, null), null);
            return;
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
                return;
            }
        }

        if (switchToClipboardUrl()) {
            return;
        }

        String saved = prefs.getString(KEY_URL, null);
        if (saved != null) {
            if (!saved.equals(loadedUrl)) {
                showWeb(saved);
            }
        } else {
            showSetup(null, null);
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
    }

    private void clearHistory() {
        getPreferences(Context.MODE_PRIVATE).edit().remove(KEY_HISTORY).apply();
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
        destroyWeb();
        onErrorPage = false;
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

        root.removeAllViews();
        root.addView(container, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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
        box.addView(panelRow("⬇", "检查更新", "查看 GitHub 上是否有新版本", v -> checkUpdate(true)));

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
        box.addView(panelSwitch("启动时自动检查更新", "打开 App 时在后台静默检查", KEY_AUTO_UPDATE, (c) -> {
        }));

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
        if (menuDialog != null && menuDialog.isShowing()) {
            try {
                menuDialog.dismiss();
            } catch (Exception ignored) {
            }
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
        sw.setChecked(getPreferences(Context.MODE_PRIVATE).getBoolean(prefKey, true));
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
        loadedUrl = null;
        destroyWeb();
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
            if (url != null) {
                showWeb(url);
            }
        });

        Button change = new Button(this);
        change.setText("更换链接");
        styleSecondary(change);
        box.addView(change, buttonLp());
        change.setOnClickListener(v -> showSetup(url, null));

        root.removeAllViews();
        root.addView(scrollWrap(box), contentLp());
    }

    private void showSetup(String prefill, String error) {
        onErrorPage = false;
        loadedUrl = null;
        destroyWeb();
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
        scan.setOnClickListener(v -> {
            try {
                startActivityForResult(new Intent(this, ScanActivity.class), REQ_SCAN);
            } catch (Exception e) {
                Toast.makeText(this, "无法启动扫码:请确认已授予相机权限", Toast.LENGTH_LONG).show();
            }
        });

        Button hist = new Button(this);
        hist.setText("历史会话");
        styleSecondary(hist);
        box.addView(hist, buttonLp());
        hist.setOnClickListener(v -> showHistoryDialog());

        Button check = new Button(this);
        check.setText("检查更新");
        styleSecondary(check);
        box.addView(check, buttonLp());
        check.setOnClickListener(v -> checkUpdate(true));

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

        root.removeAllViews();
        root.addView(scrollWrap(box), contentLp());
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

    private void checkUpdate(final boolean manual) {
        if (manual) {
            toast("正在检查更新…");
        } else {
            getPreferences(Context.MODE_PRIVATE).edit()
                    .putLong(KEY_LAST_UPDATE_CHECK, System.currentTimeMillis()).apply();
        }
        new Thread(() -> {
            String version = null;
            String apkUrl = null;
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(RELEASE_API).openConnection();
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                if (conn.getResponseCode() == 200) {
                    String body = readAll(conn.getInputStream());
                    conn.disconnect();
                    Matcher tag = TAG_JSON.matcher(body);
                    Matcher apk = APK_URL_JSON.matcher(body);
                    if (tag.find() && apk.find()) {
                        version = tag.group(1);
                        apkUrl = apk.group(1);
                    }
                }
            } catch (Exception ignored) {
            }
            final String v = version;
            final String url = apkUrl;
            runOnUiThread(() -> {
                if (v != null && url != null && versionNewer(v, appVersion())) {
                    offerUpdate(v, url);
                } else if (manual) {
                    toast(v == null ? "检查更新失败,请稍后再试" : "已是最新版本 v" + appVersion());
                }
            });
        }).start();
    }

    private boolean versionNewer(String remote, String local) {
        try {
            String[] a = remote.split("\\.");
            String[] b = local.split("\\.");
            for (int i = 0; i < Math.max(a.length, b.length); i++) {
                int x = i < a.length ? Integer.parseInt(a[i]) : 0;
                int y = i < b.length ? Integer.parseInt(b[i]) : 0;
                if (x != y) {
                    return x > y;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private String readAll(java.io.InputStream in) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) {
            sb.append(line);
        }
        r.close();
        return sb.toString();
    }

    private void offerUpdate(final String version, final String url) {
        new AlertDialog.Builder(this)
                .setTitle("发现新版本 v" + version)
                .setMessage("建议更新以获得最新修复。\n\n国内网络将自动优先走加速源下载,失败会自动切换;下载完成后会自动弹出安装界面,首次安装需允许本应用\"安装未知应用\"。")
                .setPositiveButton("立即更新", (d, w) -> downloadUpdate(version, url))
                .setNegativeButton("暂不", null)
                .show();
    }

    private void downloadUpdate(String version, String url) {
        pendingVersion = version;
        pendingUrl = url;
        dlSourceIndex = -1;
        dlRound = 0;
        startDownloadFromNextSource();
    }

    /** 依次尝试:国内加速镜像 → GitHub 官方源;失败/卡住自动换下一个,两轮用尽才报最终失败 */
    private void startDownloadFromNextSource() {
        dlSourceIndex++;
        if (dlSourceIndex > DL_MIRRORS.length) {
            if (dlRound < 1) {
                // 一轮走完全部源都失败:再从头来一轮(代理可能刚恢复),第二轮结束仍失败则放弃
                dlRound++;
                dlSourceIndex = -1;
                toast("所有下载源均失败,重新尝试…");
                startDownloadFromNextSource();
                return;
            }
            dismissUpdateDialog();
            new AlertDialog.Builder(this)
                    .setTitle("下载失败")
                    .setMessage("已尝试全部国内加速镜像与 GitHub 官方源,均未成功。\n\n可能是当前网络无法访问这些站点,建议稍后重试,或在电脑端下载后传到手机安装。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        String target = (dlSourceIndex < DL_MIRRORS.length)
                ? DL_MIRRORS[dlSourceIndex] + pendingUrl
                : pendingUrl;
        dlLastBytes = -1L;
        dlLastProgressAt = System.currentTimeMillis();
        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(target));
            req.setMimeType("application/vnd.android.package-archive");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                    "ZCodeRemote-v" + pendingVersion + ".apk");
            pendingDownloadId = ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(req);
            if (updateDialog == null || !updateDialog.isShowing()) {
                showDownloadProgress(pendingVersion);
            } else {
                toast("正在切换下载源:" + dlSourceLabel());
                // 对话框已存在时不会重建,轮询也不会自动续上,这里显式重启
                if (updateHandler == null) {
                    updateHandler = new Handler(Looper.getMainLooper());
                }
                updateHandler.removeCallbacks(progressPoller);
                updateHandler.postDelayed(progressPoller, 400);
            }
        } catch (Exception e) {
            failDownload();
        }
    }

    private String dlSourceLabel() {
        if (dlSourceIndex < 0) {
            return "";
        }
        if (dlSourceIndex < DL_MIRRORS.length) {
            try {
                return "加速源 " + (dlSourceIndex + 1);
            } catch (Exception ignored) {
                return "加速源";
            }
        }
        return "GitHub 官方";
    }

    private void showDownloadProgress(String version) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(4), dp(24), 0);

        updateText = new TextView(this);
        updateText.setTextColor(FG_DIM);
        updateText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        updateText.setText("准备下载…");
        box.addView(updateText);

        updateBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        updateBar.setMax(100);
        updateBar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.topMargin = dp(12);
        box.addView(updateBar, barLp);

        updateDialog = new AlertDialog.Builder(this)
                .setTitle("正在下载 v" + version)
                .setView(box)
                .setCancelable(false)
                .setPositiveButton("取消下载", (d, w) -> cancelDownload())
                .show();
        if (updateHandler == null) {
            updateHandler = new Handler(Looper.getMainLooper());
        }
        updateHandler.removeCallbacks(progressPoller);
        updateHandler.postDelayed(progressPoller, 400);
    }

    private void cancelDownload() {
        if (pendingDownloadId >= 0) {
            try {
                ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).remove(pendingDownloadId);
            } catch (Exception ignored) {
            }
            pendingDownloadId = -1L;
        }
        dismissUpdateDialog();
        toast("已取消下载");
    }

    private void failDownload() {
        if (pendingDownloadId >= 0) {
            try {
                ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).remove(pendingDownloadId);
            } catch (Exception ignored) {
            }
            pendingDownloadId = -1L;
        }
        // 当前源失败:换下一个下载源(镜像 → 官方 → 再来一轮),轮次用尽由 startDownloadFromNextSource 报最终失败
        if (pendingUrl != null) {
            toast(dlSourceLabel() + " 不可用,切换下载源…");
            startDownloadFromNextSource();
        }
    }

    private void dismissUpdateDialog() {
        if (updateHandler != null) {
            updateHandler.removeCallbacks(progressPoller);
        }
        if (updateDialog != null && updateDialog.isShowing()) {
            try {
                updateDialog.dismiss();
            } catch (Exception ignored) {
            }
        }
        updateDialog = null;
    }

    private String formatSize(long b) {
        if (b >= 1024 * 1024) {
            return String.format("%.1f MB", b / 1048576.0);
        }
        if (b >= 1024) {
            return (b / 1024) + " KB";
        }
        return b + " B";
    }

    /**
     * 下载完成后的安装入口:先验签再安装。第三方加速镜像是不可信通道(HTTPS 只保证到代理的链路,
     * 代理返回什么内容完全由它决定),必须确认下载的 APK 包名一致、版本更高、签名与已装本应用完全相同,
     * 才拉起安装器;任一不符都删文件并报错,挡住镜像投毒/返回错误内容。
     */
    private void verifyAndInstall(long downloadId) {
        dismissUpdateDialog();
        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        String path = null;
        try {
            Cursor c = dm.query(new DownloadManager.Query().setFilterById(downloadId));
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        int idx = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_FILENAME);
                        if (idx >= 0) {
                            path = c.getString(idx);
                        }
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Exception ignored) {
        }
        if (path == null) {
            // 连文件都找不到,当本源失败处理,轮换下一个下载源
            failDownload();
            return;
        }
        String error = verifyApk(path);
        if (error == null) {
            proceedToInstall(downloadId);
            return;
        }
        try {
            new File(path).delete();
        } catch (Exception ignored) {
        }
        if (error.startsWith(NOT_VALID_APK)) {
            // 文件根本不是有效 APK(代理返回错误页/垃圾内容):本源不可用,failDownload 会换下一个源
            failDownload();
            return;
        }
        // 包名/版本/签名不符:所有镜像服务的是同一个 GitHub 文件,换源无意义,直接阻断
        new AlertDialog.Builder(this)
                .setTitle("更新包校验失败,已阻止安装")
                .setMessage("下载的安装包" + error + ",文件已删除。\n\n这可能是下载源内容被篡改,请不要安装;可稍后重试,或在电脑端下载后传到手机安装。")
                .setPositiveButton("知道了", null)
                .show();
    }

    private static final String NOT_VALID_APK = "NOT_VALID_APK";

    /** 校验下载的 APK:包名/版本/签名,全部通过返回 null;文件不是有效 APK 返回 NOT_VALID_APK 前缀(可换源);
     *  其余为内容不符(包名/版本/签名),返回中文原因(应阻断) */
    private String verifyApk(String path) {
        try {
            PackageManager pm = getPackageManager();
            int flags = Build.VERSION.SDK_INT >= 28
                    ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageInfo info = pm.getPackageArchiveInfo(path, flags);
            if (info == null) {
                return NOT_VALID_APK;
            }
            if (!getPackageName().equals(info.packageName)) {
                return "包名不一致(实际为 " + info.packageName + ")";
            }
            long remoteVc = Build.VERSION.SDK_INT >= 28
                    ? info.getLongVersionCode() : (long) info.versionCode;
            if (remoteVc <= appVersionCode()) {
                return "版本不高于当前已安装版本";
            }
            Signature[] got = Build.VERSION.SDK_INT >= 28 && info.signingInfo != null
                    ? info.signingInfo.getApkContentsSigners() : info.signatures;
            if (!sameSignatures(got, ownSignatures())) {
                return "签名与已安装版本不一致";
            }
            return null;
        } catch (Exception e) {
            // 解析过程出错(文件损坏等):按无效包处理,换源重试
            return NOT_VALID_APK;
        }
    }

    private Signature[] ownSignatures() {
        if (ownSignatures == null) {
            try {
                int flags = Build.VERSION.SDK_INT >= 28
                        ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
                PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), flags);
                ownSignatures = Build.VERSION.SDK_INT >= 28 && info.signingInfo != null
                        ? info.signingInfo.getApkContentsSigners() : info.signatures;
            } catch (Exception e) {
                ownSignatures = new Signature[0];
            }
        }
        return ownSignatures;
    }

    /** 签名集合比对(debug 包只有一个签名;用集合比较兼容多签名/v2 签名方案) */
    private boolean sameSignatures(Signature[] a, Signature[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return false;
        }
        Set<String> set = new HashSet<>();
        for (Signature s : a) {
            set.add(s.toCharsString());
        }
        for (Signature s : b) {
            if (!set.contains(s.toCharsString())) {
                return false;
            }
        }
        return true;
    }

    /** 验签通过:有安装权限直接装;没有就先引导授权,文件留着,授权回前台后续装(不再重新下载) */
    private void proceedToInstall(long downloadId) {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            pendingInstallDownloadId = downloadId;
            new AlertDialog.Builder(this)
                    .setTitle("需要安装权限")
                    .setMessage("安装更新前需允许本应用\"安装未知应用\"(只需授权一次)。\n\n授权后回到本 App 会自动继续安装,无需重新下载。")
                    .setPositiveButton("去授权", (d, w) -> {
                        try {
                            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (Exception e) {
                            pendingInstallDownloadId = -1L;
                            toast("无法打开授权设置");
                        }
                    })
                    .setNegativeButton("以后再说", (d, w) -> pendingInstallDownloadId = -1L)
                    .setOnCancelListener(d -> pendingInstallDownloadId = -1L)
                    .show();
            return;
        }
        launchInstaller(downloadId);
    }

    private void launchInstaller(long downloadId) {
        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        Uri uri = dm.getUriForDownloadedFile(downloadId);
        if (uri == null) {
            new AlertDialog.Builder(this)
                    .setTitle("安装失败")
                    .setMessage("安装包文件已不存在(可能被系统清理),请重新检查更新下载。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Exception e) {
            toast("无法启动安装界面");
        }
    }

    /** 从"安装未知应用"授权设置回到前台:权限已给且有待装包,直接续装 */
    private void maybeResumeInstallAfterPermission() {
        if (pendingInstallDownloadId < 0) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            return;
        }
        long id = pendingInstallDownloadId;
        pendingInstallDownloadId = -1L;
        toast("已获得安装权限,继续安装…");
        launchInstaller(id);
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
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackTime < 2000L) {
            super.onBackPressed();
        } else {
            lastBackTime = now;
            Toast.makeText(this, "再按一次退出 ZCode Remote", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        dismissUpdateDialog();
        dismissMenu();
        try {
            unregisterReceiver(downloadDone);
        } catch (Exception ignored) {
        }
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
                    break;
                case MotionEvent.ACTION_MOVE:
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
                    break;
            }
            return dragging;
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    if (downY >= 0) {
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
                    break;
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    downY = -1f;
                    if (progress != null) {
                        progress.setVisibility(View.GONE);
                    }
                    break;
            }
            return true;
        }
    }
}
