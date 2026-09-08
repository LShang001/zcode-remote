# 模拟器视觉审查流程(改 UI / 发版前必读)

改任何 UI 或发新版前,在本机 Android 模拟器里真实运行并截图,用 Read 工具亲眼确认渲染效果。基准图在 `docs/screenshots/`(v1.1 五张),改动后逐张对照。

## 启动模拟器

```bash
"$LOCALAPPDATA/Android/Sdk/emulator/emulator.exe" -avd zc -no-boot-anim -gpu swiftshader_indirect &
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
"$ADB" wait-for-device
# 等开机完成:轮询 "$ADB" shell getprop sys.boot_completed 返回 1
```

- AVD `zc`:android-34 google_apis x86_64(可 root),Pixel 6 规格 1080×2400@420,WHPX 加速。
- AVD `x200`(2026-09-04 建):同镜像,X200 Ultra 近似屏 1260×2808@480、2G 内存;验证大屏布局/悬浮钮安全区时用,加 `-port 5556` 可与 zc 并存。
- **快照快启**:不要加 `-no-snapshot-save`(实测冷启 19s vs 快照 4s);`adb emu kill` 退出即自动存快照;只有要干净冷启动时才加 `-no-snapshot-save`(彻底重置用 `-wipe-data`)。
- 收尾时**直接关掉,不要问**(见 AGENTS.md §命令 模拟器收尾规约):`adb emu kill`,再用 `adb devices` 确认清空。

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
3. **错误页**——对照 `review-error.png`。**用断网法**:会话页上 `"$ADB" shell svc wifi disable; "$ADB" shell svc data disable`,SPA 自身重连请求立刻触发主框架错误 → 壳错误覆盖层;之后 `svc wifi enable; svc data enable` 顺带验证自动重连(覆盖层掀掉 + reload)。**别再用 `nonexistent.invalid` 死链**:宿主机开代理(fake-ip)时它会"解析成功"到 198.18.0.x,WebView 卡握手几十秒都不报错(2026-09-06 实测)
4. **下拉刷新**(会话页顶部 `input swipe 630 300 630 1500 600`,起点必须在屏幕上 1/6 内,立刻截图看进度条)——对照 `review-refresh.png`
5. **桌面图标**(`input keyevent KEYCODE_HOME` 后截图)——对照 `review-launcher.png`
6. **会话秒回**(v2.4 加入):会话页先滑动到中部截图 → ⋮ → 更换链接 → 「返回会话」→ **0.4s 内再截图**,两图滚动位置应像素级一致、无进度条;设置页按返回键也应回会话而非退出
7. **扫码快捷方式**(v2.4 加入):`am start -n com.zcode.remote/.MainActivity -a com.zcode.remote.SCAN_BIND` → ScanActivity 在前台 → 返回取消 → 回到会话页(不白屏)
8. **页面缩放**(v2.8 加入):⋮ → 滚到底部「页面缩放」区,「当前」百分比应显示;点「放大」两档页面应肉眼变大、标签 100%→156%;「重置缩放」回到 100% 且页面恢复原样;冷启动后百分比应保持。真捏合手势只能 `sendevent` 合成(`input swipe` 无效),详见 AGENTS.md 踩坑记录

> 点菜单项/对话框按钮**用 `uiautomator dump /sdcard/ui.xml` 查 bounds 取中心**,别硬编码坐标:菜单头部"当前会话"的 URL 长短会改变菜单高度,真实 208 字符链接让菜单项整体下移约 120px,旧坐标会点错项。菜单可滚动,底部条目要先把菜单滚下去再 dump。

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

## 规范截图(demo mode,2026-09-04 加入)

截图前把动画关零、状态栏固定成标准值,截图干净且可与基准图逐像素对照:

```bash
"$ADB" shell settings put global window_animation_scale 0.0
"$ADB" shell settings put global transition_animation_scale 0.0
"$ADB" shell settings put global animator_duration_scale 0.0
"$ADB" shell settings put global sysui_demo_allowed 1
"$ADB" shell am broadcast -a com.android.systemui.demo -e command enter
"$ADB" shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0930
"$ADB" shell am broadcast -a com.android.systemui.demo -e command battery -e level 80 -e plugged false
"$ADB" shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4
"$ADB" shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false
# 审查结束退出 demo mode:-e command exit
```

## 扫码链路端到端(2026-09-04 实测通过)

模拟器 emulated 相机画面没有可扫内容;virtualscene 贴图**没有控制台命令**(只能 GUI Extended Controls 手动注入),所以自动化验证走**相册选图**路径,覆盖"解码→回传→加载"全链:

```bash
# 1. 用项目 zxing core jar 生成二维码图(gradle 缓存里取 core-x.x.jar,QRCodeWriter 编码 URL)
# 2. 推图并入库——照片选择器只认 media store,且 shell scan_file 建的行 is_pending=1 仍不可见,必须清 pending:
MSYS_NO_PATHCONV=1 "$ADB" push qr-remote.png /sdcard/Download/qr-remote.png
MSYS_NO_PATHCONV=1 "$ADB" shell content call --uri content://media --method scan_file --arg /sdcard/Download/qr-remote.png
ID=$("$ADB" shell content query --uri content://media/external/images/media --projection _id | head -1 | grep -o '_id=[0-9]*' | cut -d= -f2)
MSYS_NO_PATHCONV=1 "$ADB" shell content update --uri "content://media/external/images/media/$ID" --bind is_pending:i:0
# 3. 相机权限预授权,免弹窗打断:
"$ADB" shell pm grant com.zcode.remote android.permission.CAMERA
# 4. UI 驱动:设置页 →「扫码绑定」→「相册选图」→ 选中二维码图
```

判定:WebView 加载回传的链接即全链通过。用桌面端真实二维码时(截图给 zxing 解码出 URL → QrGen 重生成干净码),能连出真实远程控制页("已连接到当前桌面窗口",列出工作区/任务);桌面端已有手机连接时第二个客户端仍可接入。注意壳自绘错误页上**不显示悬浮钮**(设计行为:网页内容态才有),别当成大屏回归 bug。

## 断网→重连(2026-09-04 实测通过)

```bash
"$ADB" shell cmd connectivity airplane-mode enable   # 断网后点「重试」→ 应显示"网络连接失败"友好文案
"$ADB" shell cmd connectivity airplane-mode disable  # 恢复后不要动手 → App 应自动重载(v1.9 网络回调)
```

判定:断网显示友好文案、恢复后错误文案自动从"网络连接失败"变回具体加载结果,即两个特性都通过。
