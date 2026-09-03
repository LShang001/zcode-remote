package com.zcode.remote;

import android.Manifest;
import android.app.Activity;
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

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        surface = new SurfaceView(this);
        holder = surface.getHolder();
        holder.addCallback(this);
        root.addView(surface, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 中央取景框
        View frame = new View(this);
        GradientDrawable box = new GradientDrawable();
        box.setShape(GradientDrawable.RECTANGLE);
        box.setColor(Color.TRANSPARENT);
        box.setCornerRadius(dp(12));
        box.setStroke(dp(3), ACCENT);
        frame.setBackground(box);
        int boxSize = (int) (Math.min(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels) * 0.68f);
        FrameLayout.LayoutParams frameLp = new FrameLayout.LayoutParams(boxSize, boxSize, Gravity.CENTER);
        root.addView(frame, frameLp);

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
        LinearLayout.LayoutParams gLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
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
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
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
                Toast.makeText(this, "需要相机权限才能扫码", Toast.LENGTH_LONG).show();
                finish();
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
            camera.setDisplayOrientation(90);
            camera.setPreviewDisplay(holder);
            camera.setPreviewCallback((data, c) -> onPreviewFrame(data, c));
            camera.startPreview();
        } catch (Exception e) {
            Toast.makeText(this, "无法打开相机: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void onPreviewFrame(byte[] data, Camera c) {
        if (decoded) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastDecode < 180) {
            return;
        }
        lastDecode = now;
        try {
            int w = previewW;
            int h = previewH;
            if (w <= 0 || h <= 0) {
                Camera.Size sz = c.getParameters().getPreviewSize();
                w = sz.width;
                h = sz.height;
            }
            // 竖屏时把 NV21 的 Y 平面顺时针旋转 90°,让二维码正过来供解码
            byte[] rotated = new byte[w * h];
            for (int r = 0; r < h; r++) {
                for (int col = 0; col < w; col++) {
                    rotated[col * h + (h - 1 - r)] = data[r * w + col];
                }
            }
            PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                    rotated, h, w, 0, 0, h, w, false);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            Result result = reader.decodeWithState(bitmap);
            String text = result.getText();
            reader.reset();
            if (text != null) {
                Matcher m = REMOTE_URL.matcher(text);
                if (m.find()) {
                    returnWithUrl(m.group());
                } else {
                    Toast.makeText(this, "未识别到有效的 ZCode 远程链接", Toast.LENGTH_SHORT).show();
                }
            }
        } catch (Exception ignored) {
            // 这一帧没解出二维码,继续等下一帧
            try {
                reader.reset();
            } catch (Exception ignored2) {
            }
        }
    }

    private void returnWithUrl(String url) {
        decoded = true;
        Intent out = new Intent();
        out.putExtra(EXTRA_URL, url);
        setResult(RESULT_OK, out);
        finish();
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
            String url = decodeGalleryImage(data.getData());
            if (url != null) {
                returnWithUrl(url);
            } else {
                Toast.makeText(this, "这张图里没识别到有效的 ZCode 远程二维码", Toast.LENGTH_LONG).show();
            }
        }
    }

    /** 把相册图片解码成二维码内容;识别到远程链接返回它,否则返回 null */
    private String decodeGalleryImage(Uri uri) {
        Bitmap bmp = null;
        try {
            // 先量尺寸,超大图降采样避免 OOM;二维码不需要原始分辨率
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (java.io.InputStream s0 = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(s0, null, bounds);
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
            int w = bmp.getWidth();
            int h = bmp.getHeight();
            int[] pixels = new int[w * h];
            bmp.getPixels(pixels, 0, w, 0, 0, w, h);
            RGBLuminanceSource source = new RGBLuminanceSource(w, h, pixels);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            Result result = reader.decodeWithState(bitmap);
            reader.reset();
            if (result.getText() != null) {
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
            if (bmp != null) {
                bmp.recycle();
            }
        }
        return null;
    }

    private void releaseCamera() {
        if (camera != null) {
            try {
                camera.setPreviewCallback(null);
                camera.stopPreview();
                camera.release();
            } catch (Exception ignored) {
            }
            camera = null;
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
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
