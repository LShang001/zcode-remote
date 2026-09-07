package com.zcode.remote;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 版本自动更新链路:GitHub Releases 检查 → 镜像轮换下载 → 验签 → 安装引导。
 * 从 MainActivity 拆出(v2.6),逻辑与状态机不变;所有方法均假定在主线程调用,
 * 网络检查内部自起线程并 runOnUiThread 回主线程。
 */
class Updater {
    private static final String TAG = "ZCodeUpdater";
    static final String KEY_AUTO_UPDATE = "auto_update";
    static final String KEY_LAST_UPDATE_CHECK = "last_update_check";
    static final long UPDATE_CHECK_INTERVAL_MS = 4L * 60L * 60L * 1000L; // 自动检查节流:4 小时
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
    // 下载卡住(无字节增长)判定阈值:代理回源慢/弱网抖动 20s+ 很常见,误杀会整轮换源重下,代价远大于多等
    private static final long DL_STALL_TIMEOUT_MS = 60_000L;
    private static final String NOT_VALID_APK = "NOT_VALID_APK";

    private final Activity activity;
    private String appVersionCache;
    private Signature[] ownSignatures;

    private long pendingDownloadId = -1L;
    private String pendingVersion;
    private String pendingUrl;
    private AlertDialog updateDialog;
    private ProgressBar updateBar;
    private TextView updateText;
    private Handler updateHandler;
    // 下载完成但缺"安装未知应用"权限时,暂存下载 id;授权回前台后续装(文件还在,不用重新下载)
    private long pendingInstallDownloadId = -1L;
    // 更新下载源切换状态:-1..N-2 为 DL_MIRRORS 下标,N-1 表示官方源;-1 表示尚未开始
    private int dlSourceIndex = -1;
    private int dlRound = 0;
    private long dlLastBytes = -1L;
    private long dlLastProgressAt = 0L;
    // 已登记待验签的下载 id:-1 表示无;保证同一次下载完成只进一遍验签
    private long verifyPendingId = -1L;

    private final Runnable progressPoller = new Runnable() {
        @Override
        public void run() {
            if (updateDialog == null || pendingDownloadId < 0) {
                return;
            }
            DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
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
                    // 完成态统一交给 scheduleVerify 延迟验签:这里立即清零会让轮询停摆,
                    // 而验签一旦走"换源"分支,新下载的进度框将永远停在"准备下载…"
                    scheduleVerify(pendingDownloadId);
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
                // 卡住检测:仅 RUNNING 状态下字节长时间不增长才算(代理挂起/0 字节)。
                // PENDING(排队)/PAUSED(等网络/切 Wi-Fi/代理解析)是系统调度,字节必然不动,
                // 必须持续刷新计时戳:否则"等待时长"会累进 RUNNING 的卡住判定,刚恢复就被误杀
                long now = System.currentTimeMillis();
                if (done > dlLastBytes) {
                    dlLastBytes = done;
                    dlLastProgressAt = now;
                } else if (status == DownloadManager.STATUS_PENDING
                        || status == DownloadManager.STATUS_PAUSED) {
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
            // 与 progressPoller 同为主线程串行:谁先处理谁登记待验签,另一个自然跳过
            if (id == pendingDownloadId) {
                scheduleVerify(id);
            }
        }
    };

    Updater(Activity activity) {
        this.activity = activity;
    }

    /** MainActivity.onCreate 时注册下载完成广播(targetSdk 34 要求显式 RECEIVER_NOT_EXPORTED) */
    void registerReceiver() {
        IntentFilter doneFilter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(downloadDone, doneFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            activity.registerReceiver(downloadDone, doneFilter);
        }
    }

    void unregisterReceiver() {
        try {
            activity.unregisterReceiver(downloadDone);
        } catch (Exception ignored) {
        }
    }

    /** 版本号单一来源:运行时读 PackageInfo,菜单/关于/更新比较都用它,避免与 build.gradle 双写漏改 */
    String appVersion() {
        if (appVersionCache == null) {
            try {
                appVersionCache = activity.getPackageManager()
                        .getPackageInfo(activity.getPackageName(), 0).versionName;
            } catch (Exception e) {
                appVersionCache = "0";
            }
        }
        return appVersionCache;
    }

    private long appVersionCode() {
        try {
            PackageInfo info = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : (long) info.versionCode;
        } catch (Exception e) {
            return 0L;
        }
    }

    void checkUpdate(final boolean manual) {
        if (manual) {
            toast("正在检查更新…");
        } else {
            prefs().edit().putLong(KEY_LAST_UPDATE_CHECK, System.currentTimeMillis()).apply();
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
            activity.runOnUiThread(() -> {
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
        new AlertDialog.Builder(activity)
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
            new AlertDialog.Builder(activity)
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
            pendingDownloadId = ((DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(req);
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
            return "加速源 " + (dlSourceIndex + 1);
        }
        return "GitHub 官方";
    }

    private void showDownloadProgress(String version) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(4), dp(24), 0);

        updateText = new TextView(activity);
        updateText.setTextColor(MainActivity.FG_DIM);
        updateText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        updateText.setText("准备下载…");
        box.addView(updateText);

        updateBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        updateBar.setMax(100);
        updateBar.setProgressTintList(ColorStateList.valueOf(MainActivity.ACCENT));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.topMargin = dp(12);
        box.addView(updateBar, barLp);

        updateDialog = new AlertDialog.Builder(activity)
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
                ((DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE)).remove(pendingDownloadId);
            } catch (Exception ignored) {
            }
            pendingDownloadId = -1L;
        }
        // 完成态等待验签的窗口内取消:撤掉待验签登记,避免取消后仍弹安装
        verifyPendingId = -1L;
        if (updateHandler != null) {
            updateHandler.removeCallbacks(verifyRunner);
        }
        dismissUpdateDialog();
        toast("已取消下载");
    }

    private void failDownload() {
        if (pendingDownloadId >= 0) {
            try {
                ((DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE)).remove(pendingDownloadId);
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
     * 下载完成的统一入口:登记待验签 id 并延迟触发。
     * 延迟两个作用:①给 DownloadManager 落盘/改名留缓冲,避免读到半成品文件;
     * ②轮询与广播谁先看到完成都走这里,verifyPendingId 保证同一次下载只验一遍。
     * 期间轮询继续跑:状态已是 SUCCESSFUL、字节不再增长,不会误触卡住判定。
     */
    private void scheduleVerify(long id) {
        if (verifyPendingId == id) {
            return;
        }
        verifyPendingId = id;
        pendingDownloadId = -1L;
        if (updateHandler == null) {
            updateHandler = new Handler(Looper.getMainLooper());
        }
        updateHandler.removeCallbacks(verifyRunner);
        updateHandler.postDelayed(verifyRunner, 1200);
    }

    private final Runnable verifyRunner = new Runnable() {
        @Override
        public void run() {
            long id = verifyPendingId;
            verifyPendingId = -1L;
            if (id >= 0) {
                verifyAndInstall(id);
            }
        }
    };

    /**
     * 下载完成后的安装入口:先验签再安装。第三方加速镜像是不可信通道(HTTPS 只保证到代理的链路,
     * 代理返回什么内容完全由它决定),必须确认下载的 APK 包名一致、版本更高、签名与已装本应用完全相同,
     * 才拉起安装器;内容不符删文件并报错,挡住镜像投毒/返回错误内容。
     * 注意:走到这里说明下载本身已成功,任何"读不到/解不开"都不得按"源失败"换源重下
     * (v2.6 真机踩坑:完成态被误判失败导致整轮重复下载),只允许阻断并给出可操作的提示。
     */
    private void verifyAndInstall(long downloadId) {
        dismissUpdateDialog();
        DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        String path = queryLocalFilename(dm, downloadId);
        Log.i(TAG, "verify start id=" + downloadId + " path=" + path);
        // 路径列拿不到(OEM/权限差异)时,退回应由内容 URI 拷贝:验签只信自己缓存目录里的副本
        String parsePath = path;
        boolean copied = false;
        if (parsePath == null || !hasZipMagic(parsePath)) {
            parsePath = copyToCache(dm, downloadId);
            copied = parsePath != null;
            Log.i(TAG, "path unusable, cache copy=" + parsePath);
        }
        if (parsePath == null) {
            // 连内容都读不到:下载记录在但文件不可达,不删不换源,阻断并提示
            blockWithMessage("下载记录存在但文件读取失败(可能被系统清理或存储权限受限),请重新检查更新,或在下载完成后到文件管理里手动安装。");
            return;
        }
        if (!hasZipMagic(parsePath)) {
            // 内容根本不是 ZIP/APK(代理返回错误页/垃圾):本源不可用,换下一个源
            Log.w(TAG, "no zip magic, source content bad");
            deleteQuietly(path);
            failDownload();
            return;
        }
        String error = verifyApk(parsePath);
        Log.i(TAG, "verifyApk result=" + (error == null ? "OK" : error));
        if (error == null) {
            if (copied) {
                deleteQuietly(parsePath);
            }
            proceedToInstall(downloadId);
            return;
        }
        if (error.startsWith(NOT_VALID_APK)) {
            // 魔数是对的但系统解析器解不开:设备侧解析异常,不是源的问题——保留文件、不换源,阻断提示
            Log.w(TAG, "parse failed on device, keep file");
            blockWithMessage("安装包已下载完成,但本系统无法解析校验(文件已保留在下载目录)。为安全起见不自动安装,可在文件管理中手动安装,或稍后重试。");
            return;
        }
        // 包名/版本/签名不符:所有镜像服务的是同一个 GitHub 文件,换源无意义,直接阻断
        deleteQuietly(path);
        if (copied) {
            deleteQuietly(parsePath);
        }
        new AlertDialog.Builder(activity)
                .setTitle("更新包校验失败,已阻止安装")
                .setMessage("下载的安装包" + error + ",文件已删除。\n\n这可能是下载源内容被篡改,请不要安装;可稍后重试,或在电脑端下载后传到手机安装。")
                .setPositiveButton("知道了", null)
                .show();
    }

    /** 查 DownloadManager 记录的本地文件路径;列缺失/为空返回 null */
    private String queryLocalFilename(DownloadManager dm, long downloadId) {
        try {
            Cursor c = dm.query(new DownloadManager.Query().setFilterById(downloadId));
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        int idx = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_FILENAME);
                        if (idx >= 0) {
                            String p = c.getString(idx);
                            if (p != null && !p.isEmpty()) {
                                return p;
                            }
                        }
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "query path failed: " + e);
        }
        return null;
    }

    /** 读文件头 4 字节判 ZIP 魔数(PK\3\4);读不到返回 false */
    private static boolean hasZipMagic(String path) {
        try (FileInputStream in = new FileInputStream(path)) {
            byte[] head = new byte[4];
            int n = 0;
            while (n < 4) {
                int r = in.read(head, n, 4 - n);
                if (r < 0) {
                    break;
                }
                n += r;
            }
            return n == 4 && head[0] == 0x50 && head[1] == 0x4B && head[2] == 0x03 && head[3] == 0x04;
        } catch (Exception e) {
            return false;
        }
    }

    /** 经 DownloadManager 内容 URI 把已下载文件拷进应用私有缓存:不依赖存储权限与路径列,解析必可达 */
    private String copyToCache(DownloadManager dm, long downloadId) {
        Uri uri = null;
        try {
            uri = dm.getUriForDownloadedFile(downloadId);
        } catch (Exception e) {
            Log.w(TAG, "getUri failed: " + e);
        }
        if (uri == null) {
            return null;
        }
        File dst = new File(activity.getCacheDir(), "update-" + downloadId + ".apk");
        try (InputStream in = activity.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(dst)) {
            if (in == null) {
                return null;
            }
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
            }
            return dst.getAbsolutePath();
        } catch (Exception e) {
            Log.w(TAG, "copy to cache failed: " + e);
            deleteQuietly(dst.getAbsolutePath());
            return null;
        }
    }

    private void deleteQuietly(String path) {
        if (path != null) {
            try {
                new File(path).delete();
            } catch (Exception ignored) {
            }
        }
    }

    /** 下载已成功但无法继续(读取/解析异常):不换源不重下,给出可操作提示 */
    private void blockWithMessage(String message) {
        new AlertDialog.Builder(activity)
                .setTitle("下载完成,但无法自动安装")
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .show();
    }

    /** 校验下载的 APK:包名/版本/签名,全部通过返回 null;文件不是有效 APK 返回 NOT_VALID_APK 前缀(可换源);
     *  其余为内容不符(包名/版本/签名),返回中文原因(应阻断) */
    private String verifyApk(String path) {
        try {
            PackageManager pm = activity.getPackageManager();
            int flags = Build.VERSION.SDK_INT >= 28
                    ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageInfo info = pm.getPackageArchiveInfo(path, flags);
            if (info == null) {
                return NOT_VALID_APK;
            }
            if (!activity.getPackageName().equals(info.packageName)) {
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
                PackageInfo info = activity.getPackageManager()
                        .getPackageInfo(activity.getPackageName(), flags);
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
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            pendingInstallDownloadId = downloadId;
            new AlertDialog.Builder(activity)
                    .setTitle("需要安装权限")
                    .setMessage("安装更新前需允许本应用\"安装未知应用\"(只需授权一次)。\n\n授权后回到本 App 会自动继续安装,无需重新下载。")
                    .setPositiveButton("去授权", (d, w) -> {
                        try {
                            activity.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + activity.getPackageName())));
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
        DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        Uri uri = dm.getUriForDownloadedFile(downloadId);
        if (uri == null) {
            new AlertDialog.Builder(activity)
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
            activity.startActivity(intent);
        } catch (Exception e) {
            toast("无法启动安装界面");
        }
    }

    /** 从"安装未知应用"授权设置回到前台:权限已给且有待装包,直接续装(MainActivity.onResume 调用) */
    void maybeResumeInstallAfterPermission() {
        if (pendingInstallDownloadId < 0) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            return;
        }
        long id = pendingInstallDownloadId;
        pendingInstallDownloadId = -1L;
        toast("已获得安装权限,继续安装…");
        launchInstaller(id);
    }

    /** Activity 销毁时收尾:停轮询、撤待验签、关进度框 */
    void onDestroy() {
        verifyPendingId = -1L;
        if (updateHandler != null) {
            updateHandler.removeCallbacks(verifyRunner);
        }
        dismissUpdateDialog();
        unregisterReceiver();
    }

    private void toast(String msg) {
        activity.runOnUiThread(() ->
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show());
    }

    private SharedPreferences prefs() {
        return activity.getPreferences(Context.MODE_PRIVATE);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                activity.getResources().getDisplayMetrics());
    }
}
