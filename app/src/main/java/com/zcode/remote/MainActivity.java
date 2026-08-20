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
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
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
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final String KEY_URL = "url";
    private static final String ACTION_CHANGE_URL = "com.zcode.remote.CHANGE_URL";
    private static final Pattern REMOTE_URL = Pattern.compile("https://zcode\\.z\\.ai/remote\\S*");
    // 版本自动更新:GitHub Releases 元数据,tag 命名 v1.3,asset 为任意 .apk
    private static final String APP_VERSION = "1.4";
    private static final String RELEASE_API = "https://api.github.com/repos/LShang001/zcode-remote/releases/latest";
    private static final Pattern TAG_JSON = Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([0-9][0-9.]*)\"");
    private static final Pattern APK_URL_JSON = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.apk)\"");
    private static final int BG = 0xFF0F1014;
    private static final int FG = 0xFFEDEEF0;
    private static final int FG_DIM = 0xFF9AA0A6;
    private static final int RED = 0xFFFF6E6E;
    private static final int ACCENT = 0xFF4C8DFF;
    private static final int BTN_PRIMARY = 0xFF1B5BD7;
    private static final int BTN_SECONDARY = 0xFF2A2D36;
    private static final int REQ_FILE = 1;

    private FrameLayout root;
    private WebView webView;
    private ProgressBar progress;
    private ValueCallback<Uri[]> fileCallback;
    private String allowedHost;
    private String loadedUrl;
    private String lastAdoptedClip;
    private long pendingDownloadId = -1L;

    private final BroadcastReceiver downloadDone = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
            if (id == pendingDownloadId) {
                pendingDownloadId = -1L;
                installApk(id);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WebView.setWebContentsDebuggingEnabled(true);
        // targetSdk 34 要求动态注册非豁免系统广播时显式声明导出标志;系统服务(DownloadManager)不受 NOT_EXPORTED 影响
        IntentFilter doneFilter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(downloadDone, doneFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(downloadDone, doneFilter);
        }
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        setContentView(root);
        route(getIntent());
        checkUpdate(false);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        route(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        switchToClipboardUrl();
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

    private boolean switchToClipboardUrl() {
        SharedPreferences prefs = getPreferences(Context.MODE_PRIVATE);
        String saved = prefs.getString(KEY_URL, null);
        String clip = remoteUrlFromClipboard();
        // 同一段剪贴板内容只采纳一次,避免切走再回来时把用户手动换的链接又覆盖回去
        if (clip != null && !clip.equals(saved) && !clip.equals(lastAdoptedClip)) {
            lastAdoptedClip = clip;
            prefs.edit().putString(KEY_URL, clip).apply();
            Toast.makeText(this, "已识别剪贴板中的新会话链接", Toast.LENGTH_SHORT).show();
            showWeb(clip);
            return true;
        }
        return false;
    }

    private void destroyWeb() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
    }

    private void showWeb(String url) {
        destroyWeb();
        loadedUrl = url;
        allowedHost = Uri.parse(url).getHost();
        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);
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
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showError("页面加载失败(错误码 " + error.getErrorCode() + ")");
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                if (request.isForMainFrame()) {
                    showError("服务器返回错误(HTTP " + errorResponse.getStatusCode() + ")");
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
                dp(44), dp(44), Gravity.BOTTOM | Gravity.END);
        fabLp.rightMargin = dp(16);
        fabLp.bottomMargin = dp(28);
        container.addView(menuFab(), fabLp);

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
        fab.setOnClickListener(v -> showMenu());
        return fab;
    }

    private void showMenu() {
        final String url = getPreferences(Context.MODE_PRIVATE).getString(KEY_URL, "");
        String shown = url.length() > 46 ? url.substring(0, 43) + "…" : url;
        new AlertDialog.Builder(this)
                .setTitle("ZCode Remote v" + APP_VERSION)
                .setMessage("当前会话:\n" + shown
                        + "\n\n链接失效了?在电脑上复制新链接,回到本 App 会自动切换。")
                .setItems(new String[]{"刷新会话", "更换链接", "复制当前链接", "检查更新"}, (d, which) -> {
                    switch (which) {
                        case 0:
                            if (webView != null) {
                                webView.reload();
                            }
                            break;
                        case 1:
                            showSetup(url, null);
                            break;
                        case 2:
                            try {
                                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                                cm.setPrimaryClip(ClipData.newPlainText("url", url));
                                Toast.makeText(this, "已复制当前链接", Toast.LENGTH_SHORT).show();
                            } catch (Exception ignored) {
                            }
                            break;
                        case 3:
                            checkUpdate(true);
                            break;
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showError(String message) {
        final String url = getPreferences(Context.MODE_PRIVATE).getString(KEY_URL, null);
        loadedUrl = null;
        destroyWeb();
        LinearLayout box = darkBox();

        TextView icon = new TextView(this);
        icon.setText("⚠️");
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        icon.setPadding(0, dp(48), 0, dp(16));
        box.addView(icon);

        TextView err = new TextView(this);
        err.setText(message + "\n可能是网络波动或会话链接已失效。");
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
        root.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void showSetup(String prefill, String error) {
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
            getPreferences(Context.MODE_PRIVATE).edit().putString(KEY_URL, url).apply();
            showWeb(url);
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

        Button check = new Button(this);
        check.setText("检查更新");
        styleSecondary(check);
        box.addView(check, buttonLp());
        check.setOnClickListener(v -> checkUpdate(true));

        TextView footer = new TextView(this);
        footer.setText("v" + APP_VERSION + " · ZCode 网页的独立窗口封装");
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        footer.setTextColor(FG_DIM);
        footer.setPadding(0, dp(28), 0, 0);
        box.addView(footer);

        root.removeAllViews();
        root.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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
                if (v != null && url != null && versionNewer(v, APP_VERSION)) {
                    offerUpdate(v, url);
                } else if (manual) {
                    toast(v == null ? "检查更新失败,请稍后再试" : "已是最新版本 v" + APP_VERSION);
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
                .setMessage("建议更新以获得最新修复。\n\n下载完成后会自动弹出安装界面,首次安装需允许本应用\"安装未知应用\"。")
                .setPositiveButton("立即更新", (d, w) -> downloadUpdate(version, url))
                .setNegativeButton("暂不", null)
                .show();
    }

    private void downloadUpdate(String version, String url) {
        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setMimeType("application/vnd.android.package-archive");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                    "ZCodeRemote-v" + version + ".apk");
            pendingDownloadId = ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(req);
            toast("正在下载 v" + version + "…");
        } catch (Exception e) {
            toast("下载失败,请稍后再试");
        }
    }

    private void installApk(long downloadId) {
        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        Uri uri = dm.getUriForDownloadedFile(downloadId);
        if (uri == null) {
            toast("安装包下载失败");
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

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
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
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(downloadDone);
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
