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
import java.util.UUID;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 版本自动更新链路:GitHub Releases 检查 → 镜像轮换下载 → 验签 → 安装引导。
 * 从 MainActivity 拆出(v2.6)。状态切换与 UI 在主线程;网络检查及 APK 拷贝/校验在工作线程。
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
    // 更新流持久化(进程死亡/被划掉后启动对账);只存"下载中/待验签/待安装"期间的状态
    private static final String KEY_PENDING_ID = "update_pending_id";
    private static final String KEY_PENDING_VERSION = "update_pending_version";
    private static final String KEY_PENDING_URL = "update_pending_url";
    private static final String KEY_PENDING_SOURCE = "update_pending_source";
    private static final String KEY_PENDING_ROUND = "update_pending_round";

    private final Activity activity;
    private String appVersionCache;
    private Signature[] ownSignatures;

    private long pendingDownloadId = -1L;
    private String pendingVersion;
    private String pendingUrl;
    // 检查更新防重入:自动检查与手动检查可能并发,叠加会弹两个"发现新版本"
    private boolean checking = false;
    // 进度条目连续查不到的次数:用户从系统侧取消下载后条目会消失,连续两次确认再收尾
    private int dlMissingPolls = 0;
    private AlertDialog offerDialog;
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
    private long verifyingId = -1L;
    private int verifyGeneration = 0;
    private boolean destroyed = false;

    private final Runnable progressPoller = new Runnable() {
        @Override
        public void run() {
            if (updateDialog == null || pendingDownloadId < 0) {
                return;
            }
            DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            Cursor c = null;
            try {
                c = dm.query(new DownloadManager.Query().setFilterById(pendingDownloadId));
                if (c == null) {
                    repost();
                    return;
                }
                if (!c.moveToFirst()) {
                    // 条目消失通常是用户在系统通知/下载应用里取消了任务:这不是"源失败",
                    // 绝不能换源重下(会把用户明确的取消变成流量消耗)。连续两次查不到再确认
                    if (++dlMissingPolls >= 2) {
                        cancelledFromSystem();
                        return;
                    }
                    repost();
                    return;
                }
                dlMissingPolls = 0;
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
                // 卡住检测:仅 RUNNING 状态下字节长时间"完全不变"才算(代理挂起/0 字节)。
                // 判据用 != 而不是 >:断网重连/源不支持 Range 时进度会回退,回退也是"在动",
                // 用 > 判会让回退后的健康下载在 60s 后被误杀换源。
                // PENDING(排队)/PAUSED(等网络/切 Wi-Fi/代理解析)是系统调度,字节必然不动,
                // 必须持续刷新计时戳:否则"等待时长"会累进 RUNNING 的卡住判定,刚恢复就被误杀
                long now = System.currentTimeMillis();
                if (done != dlLastBytes) {
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
            } catch (Exception e) {
                // OEM 定制 DownloadManager 列缺失等异常:不能让它穿出主线程 Handler 直接崩进程
                Log.w(TAG, "poll error: " + e);
                failDownload();
                return;
            } finally {
                if (c != null) {
                    try {
                        c.close();
                    } catch (Exception ignored) {
                    }
                }
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
            if (id != pendingDownloadId) {
                return;
            }
            DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(id))) {
                if (c == null || !c.moveToFirst()) {
                    // 用户从系统下载列表删除的条目由轮询连续确认,不能自动重下
                    return;
                }
                int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    scheduleVerify(id);
                } else if (status == DownloadManager.STATUS_FAILED) {
                    failDownload();
                }
            } catch (Exception e) {
                Log.w(TAG, "completion status unavailable: " + e);
                // 查询异常时保留轮询,避免把失败下载当成可验签的文件
            }
        }
    };

    Updater(Activity activity) {
        this.activity = activity;
    }

    /** MainActivity.onCreate 时注册下载完成广播(targetSdk 34 要求显式 RECEIVER_NOT_EXPORTED) */
    void registerReceiver() {
        sweepStaleCache();
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
        if (checking) {
            // 已在检查中:静默忽略第二次点击(不重复弹 Toast——系统对连续两条 Toast 有竞态,
            // 后一条会被静默丢弃,还可能打出 ToastPresenter 报错)
            return;
        }
        checking = true;
        if (manual) {
            toast("正在检查更新…");
        }
        new Thread(() -> {
            String version = null;
            String apkUrl = null;
            String notes = null;
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(RELEASE_API).openConnection();
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                if (conn.getResponseCode() == 200) {
                    String body = readAll(conn.getInputStream());
                    try {
                        // 用 JSON 解析而不是正则:Release notes(body)里换行/引号都是转义的,
                        // 正则拿不准;顺带把版本号与 APK 地址一并从结构里取
                        JSONObject root = new JSONObject(body);
                        version = normalizeVersion(root.optString("tag_name", ""));
                        notes = cleanNotes(root.optString("body", ""));
                        JSONArray assets = root.optJSONArray("assets");
                        if (assets != null) {
                            for (int i = 0; i < assets.length(); i++) {
                                JSONObject a = assets.optJSONObject(i);
                                if (a == null) {
                                    continue;
                                }
                                String u = a.optString("browser_download_url", "");
                                if (u.endsWith(".apk")) {
                                    apkUrl = u;
                                    break;
                                }
                            }
                        }
                    } catch (Exception e) {
                        // 结构突变时退化到旧正则:只求保住"能检查到更新"这个主功能
                        Matcher tag = TAG_JSON.matcher(body);
                        Matcher apk = APK_URL_JSON.matcher(body);
                        if (tag.find() && apk.find()) {
                            version = tag.group(1);
                            apkUrl = apk.group(1);
                        }
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
            final String v = version;
            final String url = apkUrl;
            final String noteText = notes;
            activity.runOnUiThread(() -> {
                checking = false;
                // 网络请求最长 20s,期间用户可能已退出:销毁后弹对话框会 BadTokenException
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                if (v != null && url != null) {
                    // 节流时间戳在"检查真的成功"后才写:失败(如断网)下次启动会重试,而不是被锁 4 小时
                    prefs().edit().putLong(KEY_LAST_UPDATE_CHECK, System.currentTimeMillis()).apply();
                    if (versionNewer(v, appVersion())) {
                        offerUpdate(v, url, noteText);
                    } else if (manual) {
                        toast("已是最新版本 v" + appVersion());
                    }
                } else if (manual) {
                    toast("检查更新失败,请稍后再试");
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

    /** tag_name → 纯版本号(去 v/V 前缀,只留数字和点);格式异常返回 null */
    private String normalizeVersion(String tag) {
        if (tag == null) {
            return null;
        }
        String t = tag.trim();
        if (t.startsWith("v") || t.startsWith("V")) {
            t = t.substring(1);
        }
        return t.matches("[0-9][0-9.]*") ? t : null;
    }

    /** 更新说明展示清理:去掉 markdown 记号(标题 #、加粗 **、反引号),超长截断加提示 */
    private String cleanNotes(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String s = raw.replaceAll("(?m)^#{1,6}\\s*", "")
                .replace("**", "")
                .replace("`", "")
                .trim();
        if (s.length() > 3000) {
            s = s.substring(0, 3000) + "…\n(完整说明见 GitHub Release 页)";
        }
        return s;
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

    private void offerUpdate(final String version, final String url, final String notes) {
        // 自动检查与手动检查可能先后到达:旧推荐框先关掉,避免叠加
        if (offerDialog != null && offerDialog.isShowing()) {
            try {
                offerDialog.dismiss();
            } catch (Exception ignored) {
            }
        }
        // "更新说明"直接展示 GitHub Release notes(发版必写,见 AGENTS.md 发版规约):
        // 每次升级前用户先看到改了什么;长文本 AlertDialog 自带滚动
        StringBuilder msg = new StringBuilder();
        if (notes != null && !notes.isEmpty()) {
            msg.append("更新说明:\n").append(notes).append("\n\n");
        }
        msg.append("国内网络将自动优先走加速源下载,失败会自动切换;下载完成后会自动弹出安装界面,首次安装需允许本应用\"安装未知应用\"。");
        offerDialog = new AlertDialog.Builder(activity)
                .setTitle("发现新版本 v" + version)
                .setMessage(msg.toString())
                .setPositiveButton("立即更新", (d, w) -> downloadUpdate(version, url))
                .setNegativeButton("暂不", null)
                .show();
    }

    private void downloadUpdate(String version, String url) {
        // 上一个下载流未收尾(重复触发,或持久化恢复后又点了更新):先清掉,避免两个任务并行、旧条目成孤儿
        supersedeActiveDownload();
        pendingVersion = version;
        pendingUrl = url;
        dlSourceIndex = -1;
        dlRound = 0;
        startDownloadFromNextSource();
    }

    /** 清掉进行中/待验签的下载(条目+内存状态+持久化),用于新下载覆盖旧流程 */
    private void supersedeActiveDownload() {
        long id = pendingDownloadId >= 0 ? pendingDownloadId
                : (verifyPendingId >= 0 ? verifyPendingId : verifyingId);
        verifyGeneration++;
        if (id >= 0) {
            removeDownloadQuietly(id);
        }
        pendingDownloadId = -1L;
        verifyPendingId = -1L;
        verifyingId = -1L;
        if (updateHandler != null) {
            updateHandler.removeCallbacks(verifyRunner);
        }
        clearPendingState();
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
            clearPendingState();
            Log.w(TAG, "all download sources exhausted");
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
                    "ZCodeRemote-v" + pendingVersion + "-" + UUID.randomUUID().toString().substring(0, 8) + ".apk");
            pendingDownloadId = ((DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(req);
            dlMissingPolls = 0;
            persistPendingState();
            Log.i(TAG, "download start id=" + pendingDownloadId + " source=" + dlSourceLabel());
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
        // 取消时也要清掉"已下载完成、正在等待验签"的那次条目:用户意图是取消这次更新,
        // 留着条目会在系统通知里躺着,点它还能直接进系统安装器(绕过本 App 的验签引导)
        long vid = verifyPendingId >= 0 ? verifyPendingId : verifyingId;
        verifyGeneration++;
        verifyPendingId = -1L;
        verifyingId = -1L;
        if (vid >= 0) {
            removeDownloadQuietly(vid);
        }
        if (pendingDownloadId >= 0) {
            removeDownloadQuietly(pendingDownloadId);
            pendingDownloadId = -1L;
        }
        if (updateHandler != null) {
            updateHandler.removeCallbacks(verifyRunner);
        }
        clearPendingState();
        dismissUpdateDialog();
        toast("已取消下载");
    }

    private void failDownload() {
        if (pendingDownloadId >= 0) {
            removeDownloadQuietly(pendingDownloadId);
            pendingDownloadId = -1L;
        }
        Log.w(TAG, "source failed: " + dlSourceLabel());
        // 当前源失败:换下一个下载源(镜像 → 官方 → 再来一轮),轮次用尽由 startDownloadFromNextSource 报最终失败
        if (pendingUrl != null) {
            String label = dlSourceLabel();
            toast((label.isEmpty() ? "当前下载源" : label) + " 不可用,切换下载源…");
            startDownloadFromNextSource();
        } else {
            // 源信息缺失(对账数据不完整等):收尾报失败,不能静默卡住
            clearPendingState();
            dismissUpdateDialog();
            new AlertDialog.Builder(activity)
                    .setTitle("下载失败")
                    .setMessage("更新下载中断。请稍后重新检查更新再试。")
                    .setPositiveButton("知道了", null)
                    .show();
        }
    }

    /** 下载条目被用户在系统侧取消/删除:尊重用户意图直接收尾,绝不换源重下 */
    private void cancelledFromSystem() {
        Log.i(TAG, "download entry gone, treated as user-cancelled");
        pendingDownloadId = -1L;
        clearPendingState();
        dismissUpdateDialog();
        toast("下载已被取消");
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
        dismissUpdateDialog();
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
            if (id >= 0 && !destroyed) {
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
        verifyingId = downloadId;
        final int generation = ++verifyGeneration;
        new Thread(() -> {
            DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            String outcome;
            try {
                outcome = inspectDownloadedApk(dm, downloadId);
            } catch (Exception e) {
                Log.w(TAG, "verify failed: " + e);
                outcome = "UNREADABLE";
            }
            final String result = outcome;
            activity.runOnUiThread(() -> {
                if (destroyed || generation != verifyGeneration || verifyingId != downloadId) {
                    return;
                }
                verifyingId = -1L;
                finishVerification(downloadId, result);
            });
        }, "update-verify").start();
    }

    private String inspectDownloadedApk(DownloadManager dm, long downloadId) {
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
            return "UNREADABLE";
        }
        if (!hasZipMagic(parsePath)) {
            if (copied) {
                deleteQuietly(parsePath);
            }
            return "BAD_ZIP";
        }
        String error = verifyApk(parsePath);
        Log.i(TAG, "verifyApk result=" + (error == null ? "OK" : error));
        if (copied) {
            deleteQuietly(parsePath);
        }
        return error == null ? "OK" : "ERROR:" + error;
    }

    private void finishVerification(long downloadId, String result) {
        if ("UNREADABLE".equals(result)) {
            removeDownloadQuietly(downloadId);
            clearPendingState();
            blockWithMessage("下载记录存在但文件读取失败(可能被系统清理或存储权限受限),已清理本次记录,请重新检查更新。");
            return;
        }
        if ("BAD_ZIP".equals(result)) {
            Log.w(TAG, "no zip magic, source content bad");
            removeDownloadQuietly(downloadId);
            failDownload();
            return;
        }
        if ("OK".equals(result)) {
            proceedToInstall(downloadId);
            return;
        }
        String error = result.substring("ERROR:".length());
        // 内容不符(含本机无法解析):所有镜像服务的是同一个 GitHub 文件,换源无意义,直接阻断。
        // 必须 dm.remove 去掉条目和文件——公共下载目录里的文件在分区存储下按路径删大概率无效,
        // 条目留着(通知/下载列表)等于给用户一个绕过本 App 验签流程直接进系统安装器的入口
        removeDownloadQuietly(downloadId);
        clearPendingState();
        if (error.startsWith(NOT_VALID_APK)) {
            Log.w(TAG, "apk parse failed on device, blocked and cleaned");
            blockWithMessage("安装包已下载完成,但本系统无法解析校验,为安全起见已删除下载文件、不自动安装。请稍后重新检查更新重试。");
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle("更新包校验失败,已阻止安装")
                .setMessage("下载的安装包" + error + ",文件已删除。\n\n这可能是下载源内容被篡改,请不要安装;可稍后重试,或在电脑端下载后传到手机安装。")
                .setPositiveButton("知道了", null)
                .show();
    }

    /** 删掉 DownloadManager 条目(连带其下载的文件):分区存储下这是唯一可靠的删除方式 */
    private void removeDownloadQuietly(long downloadId) {
        try {
            ((DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE)).remove(downloadId);
        } catch (Exception ignored) {
        }
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
                    .setNegativeButton("以后再说", (d, w) -> {
                        // 用户明确推迟:清掉持久化状态,下次启动不再自动弹(系统通知里仍可手动安装)
                        pendingInstallDownloadId = -1L;
                        clearPendingState();
                    })
                    .setOnCancelListener(d -> {
                        pendingInstallDownloadId = -1L;
                        clearPendingState();
                    })
                    .show();
            return;
        }
        launchInstaller(downloadId);
    }

    private void launchInstaller(long downloadId) {
        DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        Uri uri = dm.getUriForDownloadedFile(downloadId);
        if (uri == null) {
            pendingInstallDownloadId = -1L;
            removeDownloadQuietly(downloadId);
            clearPendingState();
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
            // 安装器已拉起(之后归系统安装流程):更新流到此收尾,启动对账不再重复询问
            clearPendingState();
        } catch (Exception e) {
            Log.w(TAG, "installer launch failed: " + e);
            pendingInstallDownloadId = -1L;
            clearPendingState();
            new AlertDialog.Builder(activity)
                    .setTitle("无法启动安装界面")
                    .setMessage("已校验的更新包仍在下载目录。可尝试从系统下载列表打开,或重新检查更新。")
                    .setPositiveButton("知道了", null)
                    .show();
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

    /** 持久化"下载中/待验签/待安装"的更新流:进程死亡后下次启动可对账续上 */
    private void persistPendingState() {
        if (pendingDownloadId >= 0) {
            prefs().edit()
                    .putLong(KEY_PENDING_ID, pendingDownloadId)
                    .putString(KEY_PENDING_VERSION, pendingVersion)
                    .putString(KEY_PENDING_URL, pendingUrl)
                    .putInt(KEY_PENDING_SOURCE, dlSourceIndex)
                    .putInt(KEY_PENDING_ROUND, dlRound)
                    .apply();
        }
    }

    private void clearPendingState() {
        prefs().edit()
                .remove(KEY_PENDING_ID)
                .remove(KEY_PENDING_VERSION)
                .remove(KEY_PENDING_URL)
                .remove(KEY_PENDING_SOURCE)
                .remove(KEY_PENDING_ROUND)
                .apply();
    }

    /**
     * App 启动时对账:上次进程死亡/被划掉时挂起的更新流,按 DownloadManager 当前状态续上——
     * 下载中 → 续轮询;已完成 → 续验签安装;已失败/条目消失 → 清状态。
     * 返回 true 表示本次启动已接管一条更新流(调用方应跳过自动检查,避免叠加弹窗)。
     */
    boolean reconcilePending() {
        SharedPreferences p = prefs();
        long id = p.getLong(KEY_PENDING_ID, -1L);
        if (id < 0) {
            return false;
        }
        String version = p.getString(KEY_PENDING_VERSION, "?");
        String url = p.getString(KEY_PENDING_URL, null);
        DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        Cursor c = null;
        try {
            c = dm.query(new DownloadManager.Query().setFilterById(id));
            if (c == null || !c.moveToFirst()) {
                // 条目已不存在(用户在系统侧取消/系统清理):清状态,不重下
                Log.i(TAG, "reconcile: entry " + id + " gone");
                clearPendingState();
                return false;
            }
            int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            pendingVersion = version;
            pendingUrl = url;
            dlSourceIndex = Math.max(-1, Math.min(DL_MIRRORS.length,
                    p.getInt(KEY_PENDING_SOURCE, -1)));
            dlRound = Math.max(0, Math.min(1, p.getInt(KEY_PENDING_ROUND, 0)));
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                // 包已下完但进程死在验签/安装之前:先问一句再装,避免隔了很久一开 App
                // 毫无预兆地弹出系统安装器
                Log.i(TAG, "reconcile: downloaded, ask user id=" + id);
                new AlertDialog.Builder(activity)
                        .setTitle("更新包已下载完成")
                        .setMessage("上次下载的 v" + version + " 更新包已经下载完成,现在安装吗?")
                        .setPositiveButton("立即安装", (d, w) -> scheduleVerify(id))
                        .setNegativeButton("稍后", (d, w) -> clearPendingState())
                        .setOnCancelListener(d -> clearPendingState())
                        .show();
                return true;
            }
            if (status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING
                    || status == DownloadManager.STATUS_PAUSED) {
                Log.i(TAG, "reconcile: resume polling id=" + id);
                pendingDownloadId = id;
                dlMissingPolls = 0;
                dlLastBytes = -1L;
                dlLastProgressAt = System.currentTimeMillis();
                showDownloadProgress(version);
                return true;
            }
            if (status == DownloadManager.STATUS_FAILED) {
                Log.i(TAG, "reconcile: failed source, resume rotation id=" + id);
                pendingDownloadId = id;
                failDownload();
                return true;
            }
            Log.i(TAG, "reconcile: terminal status " + status + ", cleared");
            clearPendingState();
            return false;
        } catch (Exception e) {
            Log.w(TAG, "reconcile failed: " + e);
            clearPendingState();
            return false;
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 清理历史遗留的验签缓存副本(cacheDir/update-*.apk):异常路径/进程死亡可能留下数 MB 垃圾 */
    private void sweepStaleCache() {
        try {
            File[] files = activity.getCacheDir().listFiles();
            if (files == null) {
                return;
            }
            long now = System.currentTimeMillis();
            for (File f : files) {
                String name = f.getName();
                if (name.startsWith("update-") && name.endsWith(".apk")
                        && now - f.lastModified() > 10 * 60 * 1000L) {
                    deleteQuietly(f.getAbsolutePath());
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** Activity 销毁时收尾:停轮询、撤待验签、关进度框 */
    void onDestroy() {
        destroyed = true;
        verifyGeneration++;
        verifyPendingId = -1L;
        verifyingId = -1L;
        if (updateHandler != null) {
            updateHandler.removeCallbacks(verifyRunner);
        }
        dismissUpdateDialog();
        if (offerDialog != null && offerDialog.isShowing()) {
            try {
                offerDialog.dismiss();
            } catch (Exception ignored) {
            }
            offerDialog = null;
        }
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
