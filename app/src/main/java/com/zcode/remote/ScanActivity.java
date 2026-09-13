package com.zcode.remote;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Camera;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 扫码绑定:用后置摄像头扫描电脑上 ZCode 生成的远程二维码,识别出以
 * https://zcode.z.ai/remote 开头的链接后回传给 MainActivity。
 * 仅依赖 zxing core(纯 Java 解码库,不引入 Android UI 全家桶),预览用 Camera1。
 */
public class ScanActivity extends Activity implements SurfaceHolder.Callback {
    public static final String EXTRA_URL = "scanned_url";
    private static final Pattern REMOTE_URL = Pattern.compile("https://zcode\\.z\\.ai/remote\\S*");
    private static final int REQ_CAMERA = 10;
    private static final int REQ_GALLERY = 11;
    private static final String PREFS = "MainActivity";
    private static final int ACCENT = 0xFF4C8DFF;
    private static final int FG = 0xFFEDEEF0;
    private static final int FG_DIM = 0xFF9AA0A6;

    private Camera camera;
    private SurfaceView surface;
    private SurfaceHolder holder;
    private MultiFormatReader reader;
    private boolean hasSurface;
    private boolean decoded;
    private long lastDecode;
    private int previewW;
    private int previewH;
    // 解码工作线程:预览回调与相册选图都在主线程触发,密集码解码单帧就要几十到几百毫秒,
    // 留在主线程会掉帧、大图卡到 ANR;主线程只做 ROI 拷贝与状态回写
    private HandlerThread decodeThread;
    private Handler decodeHandler;
    private volatile boolean decoding = false;
    // 相册图过大导致 OOM 时给专门提示,而不是笼统说"没识别到"(工作线程写、主线程读)
    private volatile boolean galleryOom = false;
    private AlertDialog cameraErrorDialog;
    // 取景框引用,用于识别状态变色;状态防止"非远程二维码"Toast 每帧刷屏
    private View frameBox;
    private GradientDrawable frameDrawable;
    private boolean showingMismatch = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final int ACCENT_GREEN = 0xFF34C759;
    private static final int ACCENT_RED = 0xFFFF6E6E;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        // 屏幕常亮跟随主设置(与 MainActivity 同一份偏好);默认开,关掉时扫码页也尊重系统休眠
        SharedPreferences sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sp.getBoolean(MainActivity.KEY_KEEP_SCREEN_ON, true)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        reader = new MultiFormatReader();
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        List<BarcodeFormat> formats = new ArrayList<>();
        formats.add(BarcodeFormat.QR_CODE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, formats);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        reader.setHints(hints);

        // 解码工作线程(multi 格式 reader 非线程安全,预览与相册解码都串行在这一个线程上)
        decodeThread = new HandlerThread("qr-decode");
        decodeThread.start();
        decodeHandler = new Handler(decodeThread.getLooper());

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        surface = new SurfaceView(this);
        holder = surface.getHolder();
        holder.addCallback(this);
        root.addView(surface, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 中央取景框(边框颜色随识别状态变化:蓝=扫描中,红=识别到但非远程链接,绿=成功)
        frameBox = new View(this);
        GradientDrawable box = new GradientDrawable();
        box.setShape(GradientDrawable.RECTANGLE);
        box.setColor(Color.TRANSPARENT);
        box.setCornerRadius(dp(12));
        box.setStroke(dp(3), ACCENT);
        frameDrawable = box;
        frameBox.setBackground(box);
        int boxSize = (int) (Math.min(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels) * 0.68f);
        FrameLayout.LayoutParams frameLp = new FrameLayout.LayoutParams(boxSize, boxSize, Gravity.CENTER);
        root.addView(frameBox, frameLp);

        // 顶部标题栏
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(20), dp(28), dp(20), dp(12));
        top.setBackgroundColor(0x66000000);
        TextView title = new TextView(this);
        title.setText("扫码绑定");
        title.setTextColor(FG);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        top.addView(title);
        TextView sub = new TextView(this);
        sub.setText("把电脑上 ZCode 生成的远程二维码对准取景框");
        sub.setTextColor(FG_DIM);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setPadding(0, dp(4), 0, 0);
        top.addView(sub);
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        root.addView(top, topLp);

        // 底部提示与关闭按钮
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        bottom.setPadding(dp(20), dp(16), dp(20), dp(28));
        bottom.setBackgroundColor(0x66000000);
        TextView hint = new TextView(this);
        hint.setText("识别成功后会自动打开会话");
        hint.setTextColor(FG_DIM);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        bottom.addView(hint);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams btnsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnsLp.topMargin = dp(14);

        TextView gallery = new TextView(this);
        gallery.setText("相册选图");
        gallery.setTextColor(FG);
        gallery.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        gallery.setGravity(Gravity.CENTER);
        GradientDrawable gbg = new GradientDrawable();
        gbg.setColor(0xCC2A2D36);
        gbg.setCornerRadius(dp(8));
        gallery.setBackground(gbg);
        gallery.setOnClickListener(v -> openGallery());
        // 不锁死 44dp:系统大字体下文字会被裁,minHeight 48dp 同时保证触摸目标达标
        gallery.setMinHeight(dp(48));
        LinearLayout.LayoutParams gLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        gLp.rightMargin = dp(10);
        btns.addView(gallery, gLp);

        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setTextColor(FG);
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        cancel.setGravity(Gravity.CENTER);
        GradientDrawable cbg = new GradientDrawable();
        cbg.setColor(0xCC2A2D36);
        cbg.setCornerRadius(dp(8));
        cancel.setBackground(cbg);
        cancel.setOnClickListener(v -> finish());
        cancel.setMinHeight(dp(48));
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cLp.leftMargin = dp(10);
        btns.addView(cancel, cLp);

        bottom.addView(btns, btnsLp);
        FrameLayout.LayoutParams bottomLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(bottom, bottomLp);

        setContentView(root);

        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initCamera();
            } else {
                // 不再直接关页:相册选图不依赖相机权限,给用户留一条可用路径
                showCameraDeniedDialog();
            }
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        hasSurface = true;
        initCamera();
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int format, int width, int height) {
        // surface 尺寸就绪后按预览宽高比留边显示,避免全屏拉伸把 QR 模块拉成非正方形
        fitSurfaceToPreview();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // onPause 释放了相机,但分屏/小窗/相册返回等场景 surface 不会重建(surfaceCreated 不再回调),
        // 没有这里会出现"预览定格/黑屏且永远扫不出",只能退出重进
        if (!decoded && hasSurface) {
            initCamera();
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        hasSurface = false;
        releaseCamera();
    }

    private void initCamera() {
        if (camera != null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (!hasSurface) {
            return;
        }
        try {
            int num = Camera.getNumberOfCameras();
            int back = -1;
            Camera.CameraInfo info = new Camera.CameraInfo();
            for (int i = 0; i < num; i++) {
                Camera.getCameraInfo(i, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                    back = i;
                    break;
                }
            }
            camera = back >= 0 ? Camera.open(back) : Camera.open();
            Camera.Parameters params = camera.getParameters();
            List<Camera.Size> sizes = params.getSupportedPreviewSizes();
            Camera.Size best = null;
            int target = 1280 * 720;
            int bestDiff = Integer.MAX_VALUE;
            for (Camera.Size s : sizes) {
                int diff = Math.abs(s.width * s.height - target);
                if (diff < bestDiff) {
                    bestDiff = diff;
                    best = s;
                }
            }
            if (best != null) {
                params.setPreviewSize(best.width, best.height);
                previewW = best.width;
                previewH = best.height;
            }
            List<String> focus = params.getSupportedFocusModes();
            if (focus.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            } else if (focus.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            }
            camera.setParameters(params);
            // 回读实际生效的预览尺寸:HAL 未必采纳请求值,按实际值解码才不会拿错 stride
            Camera.Size actual = camera.getParameters().getPreviewSize();
            if (actual != null && actual.width > 0 && actual.height > 0) {
                previewW = actual.width;
                previewH = actual.height;
            }
            camera.setDisplayOrientation(90);
            camera.setPreviewDisplay(holder);
            camera.setPreviewCallback((data, c) -> onPreviewFrame(data, c));
            camera.startPreview();
            fitSurfaceToPreview();
        } catch (Exception e) {
            // 不再直接关页:无摄像头设备/相机被别的应用占用时,相册选图仍然可用
            releaseCamera();
            showCameraErrorDialog(e);
        }
    }

    /** 预览按 buffer 宽高比居中留边显示(竖屏下宽高比 = 短边/长边),消除全屏拉伸造成的模块变形 */
    private void fitSurfaceToPreview() {
        if (surface == null || previewW <= 0 || previewH <= 0) {
            return;
        }
        View parent = (View) surface.getParent();
        if (parent == null) {
            return;
        }
        int vw = parent.getWidth();
        int vh = parent.getHeight();
        if (vw <= 0 || vh <= 0) {
            parent.post(this::fitSurfaceToPreview);
            return;
        }
        float aspect = Math.min(previewW, previewH) / (float) Math.max(previewW, previewH);
        int tw = vw;
        int th = Math.round(vw / aspect);
        if (th > vh) {
            th = vh;
            tw = Math.round(vh * aspect);
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surface.getLayoutParams();
        if (lp.width == tw && lp.height == th) {
            return;
        }
        surface.setLayoutParams(new FrameLayout.LayoutParams(tw, th, Gravity.CENTER));
    }

    /** 相机打不开(无硬件/被占用/权限)时的可操作提示:保留相册入口与重试,不再一关了之 */
    private void showCameraErrorDialog(Exception e) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        // onResume/重试会再次进入这里,先收掉上一个,避免对话框叠加
        if (cameraErrorDialog != null && cameraErrorDialog.isShowing()) {
            try {
                cameraErrorDialog.dismiss();
            } catch (Exception ignored) {
            }
        }
        String detail = e == null || e.getMessage() == null ? "" : "(" + e.getMessage() + ")";
        cameraErrorDialog = new AlertDialog.Builder(this)
                .setTitle("无法使用相机" + detail)
                .setMessage("相机可能被其他应用占用或设备没有可用摄像头。\n\n你仍然可以使用「相册选图」识别二维码截图。")
                .setPositiveButton("重试", (d, w) -> initCamera())
                .setNeutralButton("用相册选图", (d, w) -> openGallery())
                .setNegativeButton("取消", (d, w) -> finish())
                .show();
    }

    /** 相机权限被拒:区分"可再申请"与"已永久拒绝",永久拒绝给去系统设置的入口 */
    private void showCameraDeniedDialog() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        final boolean canAskAgain = shouldShowRequestPermissionRationale(Manifest.permission.CAMERA);
        new AlertDialog.Builder(this)
                .setTitle("需要相机权限")
                .setMessage(canAskAgain
                        ? "扫码需要相机权限,请允许。\n\n也可以直接用「相册选图」识别二维码截图。"
                        : "相机权限已被拒绝。可在系统设置中重新开启,或直接用「相册选图」识别二维码截图。")
                .setPositiveButton(canAskAgain ? "重新申请" : "去设置", (d, w) -> {
                    if (canAskAgain) {
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                    } else {
                        openAppSettings();
                    }
                })
                .setNeutralButton("用相册选图", (d, w) -> openGallery())
                .setNegativeButton("取消", (d, w) -> finish())
                .show();
    }

    private void openAppSettings() {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            Toast.makeText(this, "无法打开系统设置", Toast.LENGTH_SHORT).show();
        }
    }

    private void onPreviewFrame(byte[] data, Camera c) {
        if (decoded || decoding || decodeHandler == null) {
            return;
        }
        long now = System.currentTimeMillis();
        // 轻量节流:解码背压主要靠 decoding 标志,这里只避免空队列也高频入队
        if (now - lastDecode < 120) {
            return;
        }
        lastDecode = now;
        int w = previewW;
        int h = previewH;
        if (w <= 0 || h <= 0) {
            Camera.Size sz = c.getParameters().getPreviewSize();
            if (sz == null || sz.width <= 0 || sz.height <= 0) {
                return;
            }
            w = sz.width;
            h = sz.height;
            previewW = w;
            previewH = h;
        }
        // 直接用相机缓冲的 Y 平面解码,不做像素旋转:QR 码有三个定位符,
        // zxing 原生支持任意朝向(JVM 已验证 0/90/180/270° 均可直解),
        // 每帧省掉 w*h 次像素循环与一次整块分配;取画面中心 70% 区域送解,减少边缘干扰
        int cropW = w * 7 / 10;
        int cropH = h * 7 / 10;
        int left = (w - cropW) / 2;
        int top = (h - cropH) / 2;
        // 相机缓冲会被复用,而解码在工作线程做:必须先把 ROI 逐行拷成连续缓冲再交出去
        final byte[] roi = new byte[cropW * cropH];
        for (int row = 0; row < cropH; row++) {
            System.arraycopy(data, (top + row) * w + left, roi, row * cropW, cropW);
        }
        final int rw = cropW;
        final int rh = cropH;
        decoding = true;
        decodeHandler.post(() -> {
            String found = null;
            try {
                PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                        roi, rw, rh, 0, 0, rw, rh, false);
                Result result = decodeWithFallback(source);
                if (result != null) {
                    found = result.getText();
                }
            } catch (Exception ignored) {
                // 这一帧没解出二维码,继续等下一帧
            } finally {
                try {
                    reader.reset();
                } catch (Exception ignored) {
                }
            }
            final String text = found;
            mainHandler.post(() -> {
                decoding = false;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                handleDecodedText(text);
            });
        });
    }

    /** 解码结果统一回主线程处理:远程链接 → 回传;非远程码 → 提示一次;无码 → 复位状态 */
    private void handleDecodedText(String text) {
        if (decoded) {
            return;
        }
        if (text != null) {
            Matcher m = REMOTE_URL.matcher(text);
            if (m.find()) {
                returnWithUrl(m.group());
            } else {
                setMismatchState(true);
            }
        } else if (showingMismatch) {
            // 从"识别到异物二维码"回到"什么都没识别到":恢复蓝色边框,允许再次提示
            setMismatchState(false);
        }
    }

    /** 先 HybridBinarizer(快、多数场景),失败再 GlobalHistogramBinarizer(对低对比度/暗光更稳)兜底 */
    private Result decodeWithFallback(com.google.zxing.LuminanceSource source) {
        try {
            return reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(source)));
        } catch (Exception e) {
            try {
                reader.reset();
                return reader.decodeWithState(new BinaryBitmap(
                        new com.google.zxing.common.GlobalHistogramBinarizer(source)));
            } catch (Exception e2) {
                return null;
            }
        }
    }

    /** 取景框/Toast 状态:识别到二维码但不是远程链接时边框变红并提示一次;恢复时变回蓝色 */
    private void setMismatchState(final boolean mismatch) {
        // 状态没变化直接返回:同一张异物二维码在取景框里停留时每帧都会走到这里,
        // 不短路会以约 5 次/秒的节奏反复弹 Toast(部分被系统限流丢弃,观感像程序失控)
        if (mismatch == showingMismatch) {
            return;
        }
        showingMismatch = mismatch;
        mainHandler.post(() -> {
            if (frameDrawable != null) {
                frameDrawable.setStroke(dp(3), mismatch ? ACCENT_RED : ACCENT);
            }
            if (mismatch) {
                Toast.makeText(this, "识别到二维码,但不是有效的 ZCode 远程链接", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void returnWithUrl(String url) {
        decoded = true;
        vibrate();
        mainHandler.post(() -> {
            if (frameDrawable != null) {
                frameDrawable.setStroke(dp(3), ACCENT_GREEN);
            }
        });
        Intent out = new Intent();
        out.putExtra(EXTRA_URL, url);
        setResult(RESULT_OK, out);
        mainHandler.postDelayed(this::finish, 250);
    }

    /** 识别成功短震动 30ms,反馈对准瞬间(Android 12+ 用 VibratorManager) */
    private void vibrate() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (vm != null && vm.getDefaultVibrator() != null) {
                    vm.getDefaultVibrator().vibrate(android.os.VibrationEffect.createOneShot(
                            30, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
                }
            } else {
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null) {
                    v.vibrate(30);
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** 相册选图:无需存储权限,用系统图片选择器返回 content uri 后解码 */
    private void openGallery() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            startActivityForResult(intent, REQ_GALLERY);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开相册", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_GALLERY && resultCode == RESULT_OK && data != null && data.getData() != null) {
            // 大图解码(全尺寸 JPEG 采样解码 + 放大 + 二值化)可能到秒级,必须放工作线程,
            // 否则主线程冻结,用户以为死机
            final Uri uri = data.getData();
            galleryOom = false;
            if (decodeHandler == null) {
                return;
            }
            decoding = true;
            decodeHandler.post(() -> {
                final String url = decodeGalleryImage(uri);
                mainHandler.post(() -> {
                    decoding = false;
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    if (url != null) {
                        returnWithUrl(url);
                    } else {
                        Toast.makeText(this, galleryOom
                                ? "图片太大,无法处理,请换一张较小的截图"
                                : "这张图里没识别到有效的 ZCode 远程二维码", Toast.LENGTH_LONG).show();
                    }
                });
            });
        }
    }

    /** 把相册图片解码成二维码内容;识别到远程链接返回它,否则返回 null。
     *  真实远程链接很长(200+ 字符),QR 是高版本密集码,在"二维码只占画面一部分的整窗截图"里
     *  模块偏小,原图直接解经常失败;实测放大 2 倍 + Otsu 二值化让模块边缘锐利后可解出,
     *  因此原图失败后追加一轮放大二值化重试 */
    private String decodeGalleryImage(Uri uri) {
        Bitmap bmp = null;
        try {
            // 先量尺寸,超大图降采样避免 OOM;二维码不需要原始分辨率
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (java.io.InputStream s0 = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(s0, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                // 尺寸都量不到(bounds 解析失败):不冒险全尺寸解码,直接放弃
                return null;
            }
            int sample = 1;
            int maxDim = Math.max(bounds.outWidth, bounds.outHeight);
            while (maxDim / sample > 1600) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            try (java.io.InputStream s1 = getContentResolver().openInputStream(uri)) {
                bmp = BitmapFactory.decodeStream(s1, null, opts);
            }
            if (bmp == null) {
                return null;
            }
            String url = decodeQrFromBitmap(bmp, false);
            if (url == null) {
                // 原图失败:放大 2 倍 + Otsu 二值化重试(密集小模块码的关键补救)
                url = decodeQrFromBitmap(bmp, true);
            }
            return url;
        } catch (OutOfMemoryError oom) {
            // OOM 是 Error 不是 Exception,必须显式兜住:大图 + 放大二值化最坏要几十 MB
            galleryOom = true;
            try {
                reader.reset();
            } catch (Exception ignored) {
            }
        } catch (Exception ignored) {
            try {
                reader.reset();
            } catch (Exception ignored2) {
            }
        } finally {
            if (bmp != null) {
                bmp.recycle();
            }
        }
        return null;
    }

    /** 从一张 Bitmap 解远程链接;enhance=true 时先放大 2 倍(上限 2600px 控内存)再 Otsu 二值化,
     *  专治"二维码只占画面一部分的整窗截图":真实链接 200+ 字符是高版本密集码,原图模块偏小直解常失败 */
    private String decodeQrFromBitmap(Bitmap src, boolean enhance) {
        Bitmap work = src;
        boolean created = false;
        try {
            if (enhance) {
                int maxDim = Math.max(src.getWidth(), src.getHeight());
                float f = Math.min(2.0f, 2600f / maxDim);
                if (f > 1.05f) {
                    // 最近邻放大(不做平滑插值),保留模块硬边缘;二值化后黑白更锐利
                    Bitmap scaled = Bitmap.createScaledBitmap(src,
                            Math.round(src.getWidth() * f), Math.round(src.getHeight() * f), false);
                    if (scaled != null) {
                        work = scaled;
                        created = true;
                    }
                }
            }
            int w = work.getWidth();
            int h = work.getHeight();
            int[] pixels = new int[w * h];
            work.getPixels(pixels, 0, w, 0, 0, w, h);
            if (enhance) {
                int thr = otsuThreshold(pixels);
                for (int i = 0; i < pixels.length; i++) {
                    pixels[i] = luminance(pixels[i]) < thr ? 0xFF000000 : 0xFFFFFFFF;
                }
            }
            if (created) {
                work.recycle();
                work = src;
            }
            RGBLuminanceSource source = new RGBLuminanceSource(w, h, pixels);
            Result result = decodeWithFallback(source);
            if (result != null && result.getText() != null) {
                Matcher m = REMOTE_URL.matcher(result.getText());
                if (m.find()) {
                    return m.group();
                }
            }
        } catch (Exception ignored) {
            try {
                reader.reset();
            } catch (Exception ignored2) {
            }
        } finally {
            if (created && work != src) {
                work.recycle();
            }
        }
        return null;
    }

    private static int luminance(int rgb) {
        return (((rgb >> 16) & 0xFF) * 299 + ((rgb >> 8) & 0xFF) * 587 + (rgb & 0xFF) * 114) / 1000;
    }

    /** Otsu 大津法自动阈值:把灰度直方图分成黑白两类,取类间方差最大的分界(对光照不均比固定 128 稳) */
    private static int otsuThreshold(int[] pixels) {
        int[] hist = new int[256];
        for (int p : pixels) {
            hist[luminance(p)]++;
        }
        int total = pixels.length;
        int sum = 0;
        for (int i = 0; i < 256; i++) {
            sum += i * hist[i];
        }
        int sumB = 0, wB = 0, maxVar = 0, threshold = 128;
        for (int t = 0; t < 256; t++) {
            wB += hist[t];
            if (wB == 0) {
                continue;
            }
            int wF = total - wB;
            if (wF == 0) {
                break;
            }
            sumB += t * hist[t];
            int mB = sumB / wB;
            int mF = (sum - sumB) / wF;
            int d = wB * wF * (mB - mF) * (mB - mF);
            if (d > maxVar) {
                maxVar = d;
                threshold = t;
            }
        }
        return threshold;
    }

    private void releaseCamera() {
        Camera c = camera;
        camera = null;
        if (c == null) {
            return;
        }
        // 三步各自兜底:stopPreview 在边界状态抛异常时,release 仍必须执行,否则相机被泄漏占用
        try {
            c.setPreviewCallback(null);
        } catch (Exception ignored) {
        }
        try {
            c.stopPreview();
        } catch (Exception ignored) {
        }
        try {
            c.release();
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        releaseCamera();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseCamera();
        if (cameraErrorDialog != null) {
            try {
                cameraErrorDialog.dismiss();
            } catch (Exception ignored) {
            }
            cameraErrorDialog = null;
        }
        if (decodeThread != null) {
            decodeThread.quitSafely();
            decodeThread = null;
            decodeHandler = null;
        }
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
