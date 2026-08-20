# 模拟器视觉审查流程(改 UI / 发版前必读)

改任何 UI 或发新版前,在本机 Android 模拟器里真实运行并截图,用 Read 工具亲眼确认渲染效果。基准图在 `docs/screenshots/`(v1.1 五张),改动后逐张对照。

## 启动模拟器

```bash
"$LOCALAPPDATA/Android/Sdk/emulator/emulator.exe" -avd zc -no-snapshot-save -no-boot-anim -gpu swiftshader_indirect &
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
"$ADB" wait-for-device
# 等开机完成:轮询 "$ADB" shell getprop sys.boot_completed 返回 1
```

AVD `zc`:android-34 google_apis x86_64,WHPX 加速已验证可用。用完记得关掉模拟器(杀后台任务或 `adb emu kill`)。

## 装包、启动、截图

```bash
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" shell am start -n com.zcode.remote/.MainActivity
sleep 4
"$ADB" exec-out screencap -p > shot.png   # 然后 Read 这张图
```

## 必查画面清单

1. **设置页**(无链接首启,或 `am start -n com.zcode.remote/.MainActivity -a com.zcode.remote.CHANGE_URL` 直达)——对照 `review-setup2.png`
2. **会话页**(预置有效链接后启动)——对照 `review-session.png`
3. **错误页**(预置 `https://nonexistent.invalid/...` 之类的死链)——对照 `review-error.png`
4. **下拉刷新**(会话页顶部 `input swipe 540 500 540 1700 700`,立刻截图)——对照 `review-refresh.png`
5. **桌面图标**(`input keyevent KEYCODE_HOME` 后截图)——对照 `review-launcher.png`

## 预置链接(adb root,免手输)

```bash
"$ADB" root && sleep 2
"$ADB" shell 'cat > /data/data/com.zcode.remote/shared_prefs/MainActivity.xml << "EOF"
<?xml version="1.0" encoding="utf-8" standalone="yes" ?>
<map>
    <string name="url">把URL放这里</string>
</map>
EOF'
"$ADB" shell am force-stop com.zcode.remote
```

注意:URL 里的 `&` 必须写成 `&amp;`(XML 转义),否则链接被截断、连接失败。
