# AGENTS.md — ZCode遥控App

> AI Agent 入口文件,平台无关(Claude Code / Codex / Cursor / ZCode 均读此文件)。
> 本文件只包含 Agent 无法从代码自行推断的信息。

## 项目

把 ZCode 桌面端的远程控制网页(`https://zcode.z.ai/remote/...`)封装成安卓独立 App:WebView 全屏、无浏览器 UI、自带桌面图标。网页是第三方的,本仓库只是壳。
**目标真机(2026-09-04 用户提供):vivo X200 Ultra —— 6.78" 2808×1260(~454ppi,密度桶 480)、Android 15/OriginOS 5。视觉审查与分辨率适配问题以此机为准,模拟器基准屏向它对齐。**
**成熟度:个人装机使用。技术栈:纯 Java + compileSdk 34,UI 纯代码构建无 layout XML。唯一第三方依赖是 `com.google.zxing:core`(纯 Java 二维码解码核心,扫码用;相机预览自己用 Camera1 写,不引 zxing-android-embedded)。无测试与 linter。**

## 命令

```bash
# 构建并发版(JDK 已装但不在 PATH,必须先 export)
export JAVA_HOME="C:\\Program Files\\Microsoft\\jdk-17.0.20.8-hotspot"
/c/Users/12631/.zcode/tools/gradle-8.9/bin/gradle.bat assembleDebug --console=plain
cp -f app/build/outputs/apk/debug/app-debug.apk ZCodeRemote.apk
```

```bash
# 发 GitHub Release(App 自动更新的检查源,tag 必须 vX.Y 且 asset 为 .apk)
git add -A && git commit -m "vX.Y: ..." && git push
cp app/build/outputs/apk/debug/app-debug.apk /tmp/ZCodeRemote-vX.Y.apk
gh release create vX.Y /tmp/ZCodeRemote-vX.Y.apk --title "vX.Y" --notes "变更要点"
```

```bash
# 启动模拟器做视觉审查(AVD:zc=Pixel6 标准屏;x200=X200 Ultra 近似屏 1260x2808@480,加 -port 5556 可并存)
# 别加 -no-snapshot-save:快照快启实测 4s vs 冷启 19s;退出用 adb emu kill(自动存快照);规范截图/扫码E2E/断网重连的完整流程读 docs/emulator-review.md
"$LOCALAPPDATA/Android/Sdk/emulator/emulator.exe" -avd zc -no-boot-anim -gpu swiftshader_indirect &
```

**模拟器收尾规约**:android-emulator 插件用完从不自动关(设计如此,复用热机),遗留进程会一直吃 2-3GB 内存——**凡本次任务用过模拟器,收尾必须直接关掉,不要问**:`adb emu kill` 后用 `adb devices` 确认清空、`tasklist | grep -i emulator` 确认无残留进程。只关自己用过的那台(serial 对得上),不动其他设备。(来源:2026-09-04 用户指定"先问";2026-09-08 用户改为"以后记得关,用完就关"——免去每轮询问)

## 关键路径

| 路径 | 为什么必须知道 |
|------|---------------|
| `app/src/main/java/com/zcode/remote/MainActivity.java` | 主源文件:WebView 装载、链接管理、菜单面板、历史会话、应用锁、会话码、页面缩放等逻辑与 UI 都在这(约 1750 行),纯代码布局,不存在 layout XML。页面切换是**覆盖层架构**(v2.4):会话层 sessionView(WebView+进度条+FAB)常驻 root 底层只建一次,设置/错误页是叠在其上的 overlayView,返回会话=removeOverlay 不重载;showWeb 三分支(首次构建/换链 loadUrl/同链秒回),webLoadFailed 标志防错误态秒回露内核白页 |
| `app/src/main/java/com/zcode/remote/Updater.java` | 版本自动更新全链路(v2.6 从 MainActivity 拆出):GitHub Releases 检查、镜像轮换下载、进度轮询、APK 验签、安装引导,状态机与阈值全在此类;MainActivity 只持有 `updater` 实例做委托 |
| `app/src/main/java/com/zcode/remote/ScanActivity.java` | 扫码绑定页:Camera1 预览 + zxing core 解码,识别远程二维码回传链接给 MainActivity |
| `gradle.properties` | `android.overridePathCheck=true` 支撑着中文路径构建,删了构建必挂 |
| `docs/screenshots/` | v1.1 五张视觉基准图;改 UI 后逐张对照,防回归 |
| `docs/emulator-review.md` | 视觉审查完整流程(预置链接/必查画面清单);改 UI 或发版前读 |

## 原则

1. **壳的本分** — 网页内的功能与 UI 归 ZCode 官方;壳只做装载、链接管理和移动体验,增强走系统能力(下载管理、外链跳浏览器),保持不注入 CSS/JS 改网页(会随网页更新碎掉)
2. **极简优先** — 逻辑收进 MainActivity(扫码独立成 ScanActivity,更新链路独立成 Updater),UI 用代码构建;第三方依赖目前仅 zxing:core 一个纯 Java 库(解码+编码会话码都用它);每加一个依赖先问能不能不加(扫码用 core+Camera1 而非 zxing-android-embedded 全家桶就是这个原则)
3. **发版四同步** — `versionCode` +1、`versionName`、GitHub Release(tag `vX.Y` + 上传 APK),一次发版三处同改;界面显示的版本号运行时读 PackageInfo,无需人肉同步;漏发 Release 等于用户永远收不到更新
4. **眼见为实** — 改 UI 后在模拟器跑起来截图,用 Read 亲眼看图确认才算完成

## 边界

**绝不修改**:
- `ZCodeRemote.apk` — 发版产物,只能由上面的构建命令覆盖
- `.gradle/`、`app/build/` — 构建缓存,手改了也会被重建冲掉
- 签名配置 — 保持 debug 自动签名;换签名会导致用户手机无法覆盖安装

**修改前必须确认**:
- 升级 AGP(现 8.5.2)/ Gradle(现 8.9)/ compileSdk(现 34)→ 先确认与 JDK 17 及中文路径兼容
- Manifest 新增权限 → 先确认功能确实需要(当前为 INTERNET 联网、ACCESS_NETWORK_STATE 网络恢复重连、REQUEST_INSTALL_PACKAGES 更新安装、POST_NOTIFICATIONS 13+下载通知、CAMERA 扫码;CAMERA 配 `<uses-feature required="false">`,无摄像头设备仍可安装)

## 踩坑记录

- 中文项目路径触发 AGP "non-ASCII characters" 报错 — 保留 `gradle.properties` 里的 `android.overridePathCheck=true`(来源:2026-08-21 迁移 D 盘实测)
- 远程链接每次新会话都变 — 链接只从剪贴板 / VIEW intent / 设置页动态获取,代码里仅维护正则 `https://zcode\.z\.ai/remote\S*`;硬编码 sid 等于写死一个注定过期的会话(来源:v1.0 设计验证)
- 远程网页是 SPA 内部滚动,`webView.getScrollY()` 恒为 0,不能作为下拉刷新"已在顶部"的判据,否则页内任何下拉都误触刷新重连 — 改用手势起点在屏幕顶部窄条(downY < 高度/6)+ 近乎垂直 + 拉够深三重判定(来源:2026-08-21 v1.2 修复实测)
- targetSdk 34 上动态注册非豁免广播(含 `ACTION_DOWNLOAD_COMPLETE`)必须显式传 `RECEIVER_NOT_EXPORTED`,否则启动即 SecurityException 崩溃;系统服务发出的广播不受 NOT_EXPORTED 影响(来源:2026-08-21 v1.3 模拟器实测)
- GitHub Releases API 匿名请求有约 1 分钟 CDN 缓存 — 刚发完 Release 立刻在 App 里"检查更新"可能拿到旧 latest,稍等重试即可,不是代码 bug(来源:2026-08-21 v1.3 更新链路实测)
- GitHub asset 真实下载走 `objects.githubusercontent.com`,本机网络对它时通时断 — App 里 API 检查成功但 DownloadManager 永远 0 字节时,先怀疑该域被阻断(宿主 `curl -sIL <asset URL>` 可对照);模拟器验证安装链路可用 root 改 downloads.db 把条目置 status=200 并指向 push 进去的 APK(来源:2026-08-21 v1.6 实测)
- 模拟器 `emu network speed` 限速命令会弄丢 guest 默认路由(ip route 无 default),且重启/wipe 前难恢复 — 测完限速记得恢复;真要限速测下载,优先在真机或抓中间态截图为主(来源:2026-08-21 v1.6 实测)
- adb 预置 `shared_prefs/MainActivity.xml` 时 URL 里的 `&` 要写成 `&amp;`,否则链接被 XML 截断(来源:模拟器测试实测)
- Git Bash 里 `adb push/pull/shell <unix路径>`(如 `/sdcard/x`、`/data/data/...`)会被 MSYS 当成 Windows 路径转换成 `D:/Program Files/Git/sdcard/...` 导致失败 — 在命令前加 `MSYS_NO_PATHCONV=1`(如 `MSYS_NO_PATHCONV=1 adb push a.xml /sdcard/a.xml`);`screencap -p <设备路径>` 也会因同样原因报 usage,改用 `adb exec-out screencap -p > 本地.png`;注意该前缀只作用于紧随的一条命令,同一脚本里后续 adb 命令要各自加(来源:2026-09-04 v1.8 模拟器实测,同日补逐命令生效细节)
- 系统照片选择器(Photo Picker)只认 media store 索引,`adb push` 图片进去后选择器里看不到("No photos or videos")— 需两步:`content call --uri content://media --method scan_file --arg /sdcard/Download/xx.png` 入库(建的行默认 is_pending=1,选择器仍不可见),再查 `_id` 后按行 URI 清 pending:`content update --uri content://media/external/images/media/<id> --bind is_pending:i:0`(在集合 URI 上 update 会报 "not part of well-defined collection");扫码相册选图的模拟器测试依赖这套(来源:2026-09-04 扫码 E2E 实测,同日真会话联调补全 pending 细节)
- `ConnectivityManager.registerNetworkCallback` 需要 `ACCESS_NETWORK_STATE` 权限,没声明会抛 SecurityException;若回调注册处用 try-catch 吞掉异常,会表现为"回调从不触发"且无任何报错日志 — 网络恢复自动重连不工作时先查 Manifest 有没有这个权限(来源:2026-09-04 v1.9 实测)
- `WebViewClient.ERROR_NETWORK_CHANGED` / `ERROR_INTERNET_DISCONNECTED` 这两个错误码常量在 android.webkit.WebViewClient 里并不存在(写了会编译报错);断网判据用 ERROR_HOST_LOOKUP / ERROR_CONNECT / ERROR_TIMEOUT 即可(来源:2026-09-04 v1.9 编译实测)
- "清除网页数据"只调 `clearCache`+`removeAllCookies` 清不掉 localStorage/IndexedDB — 远程网页的会话/relay 登录态存在 DOM storage 里,必须额外 `WebStorage.getInstance().deleteAllData()`(WebSettings 开了 domStorage/database);另配 `WebViewDatabase.clearHttpAuthUsernamePassword()` 清表单/HTTP 认证(来源:2026-09-04 v1.9)
- 更新包下载默认走 `DL_MIRRORS` 里的 ghproxy 式加速前缀(把完整 GitHub 下载 URL 拼在代理域名后面),失败/20 秒无字节增长自动换源,镜像用尽→官方源→再补一轮才报最终失败 — 这类免费公共代理可用性会变,发现某节点长期 000/404 就从 `DL_MIRRORS` 换掉;新代理先 `curl -r 0-3` 拉头几字节验证:206 + PK 头(`504b0304`)+ `application/vnd.android.package-archive` 才算真能流出 APK(HEAD 200 可能是假象)(来源:2026-09-04 v2.0 模拟器端到端实测)
- 镜像可用性因网络而异,用户真机反馈是最好依据:2026-09-04 用户实测 `gh-proxy.com`(加速源)可正常下载,已调到 `DL_MIRRORS` 第一位;同日宿主机 curl 实测 `mirror.ghproxy.com`/`ghproxy.cc` 已失效,未收录(来源:2026-09-04 v2.2)
- **第三方加速代理下载的 APK 必须验签后才装**:HTTPS 只保证到代理的链路,代理返回什么内容完全由它决定(可投毒/返回错误页)。下载完成用 `getPackageArchiveInfo(path, GET_SIGNING_CERTIFICATES)` 校验 ①包名==本应用 ②versionCode 更高 ③签名集合(SDK28+ `signingInfo.getApkContentsSigners()`,老版本 `GET_SIGNATURES`/`info.signatures`)与已装本应用逐字节一致。文件不是有效 APK(archiveInfo==null)→ 换下一个源重试;签名/包名不符 → 直接阻断弹窗(所有镜像服务同一个 GitHub 文件,换源无意义)(来源:2026-09-05 v2.3)
- zxing 解码 QR **无需旋转相机预览帧**:QR 有三个定位符,原生支持任意朝向,直接把 NV21 的 Y 平面按原宽高喂 `PlanarYUVLuminanceSource` 即可(JVM 实测 0/90/180/270° 全 PASS);每帧旋转 w*h 像素是纯浪费还多一次整块分配。HybridBinarizer 解不出时用 GlobalHistogramBinarizer 兜底,暗光/低对比度/相册选图成功率更高(来源:2026-09-05 v2.3 JVM 验证)
- **相册选图解高版本密集 QR 必须放大+二值化**:真实远程链接 200+ 字符(sid+hash+mid+name),QR 是高版本密集码;用户从相册选的常是"二维码只占画面一小块的整窗截图",模块偏小,**原图直接解(两种 Binarizer 都试)必然失败,JVM 实测放大 1.5x 也失败,2x + Otsu 大津法二值化才成功**(最近邻放大保模块硬边缘,别用平滑插值)。相册路径原图失败后要追加一轮 2x 放大(上限 2600px 控内存)+ Otsu 阈值重试;相机预览是连续帧、模块够大且要实时,不用这套(来源:2026-09-05 v2.3 真实截图 E2E 验证)
- `exported="false"` 的 Activity(如 ScanActivity)用 `adb shell am start -n .../.ScanActivity` 拉不起(SecurityException: not exported),视觉审查这类页面必须从 App 内按钮点进去(来源:2026-09-05 v2.3 模拟器实测)
- AlertDialog 系统控件着色:按钮文字取主题 `colorAccent`,Switch/复选框选中态取 `colorControlActivated`;统一成 App 蓝(`#4C8DFF`)两个 item 都得在 styles.xml 设,只设一个开关仍是系统默认青绿(来源:2026-09-05 v2.3)
- 版本号别在 Java 里维护常量:运行时 `PackageManager.getPackageInfo(pkg,0).versionName` 取,gradle 的 versionName 作唯一来源,否则发版要在代码和 gradle 两处人肉同步、易漏(来源:2026-09-05 v2.3)
- WebView `destroy()` 前先 `((ViewGroup)webView.getParent()).removeView(webView)`:destroy 只清原生资源,仍挂视图树的 WebView 之后收布局/绘制事件会打已销毁内核,偶发崩溃(来源:2026-09-05 v2.3)
- SharedPreferences 里的远程链接带 sid(=控制电脑的凭证),`allowBackup` 必须 false 并配 `res/xml/data_extraction_rules.xml` 禁云备份+设备迁移,否则 sid 随 Google 备份/adb backup 外泄(来源:2026-09-05 v2.3)
- **宿主机开代理(Clash 类 fake-ip)时,模拟器造不出传输层网络失败**:`nonexistent.invalid` 会"解析成功"到 198.18.0.x 且 ping 通,坏端口也被代理接管,WebView 卡握手等超时,壳错误页迟迟不出——emulator-review 里"预置死链看错误页"的方法在此环境下失效。**测壳错误页/自动重连用断网法**:`svc wifi disable; svc data disable` 秒出错误覆盖层,`svc … enable` 触发恢复重连;`sid=乱写` 走的是官方页自己的 HTTP200 错误 UI,壳不干预(来源:2026-09-06 v2.4 模拟器实测)
- 模拟器自动化点菜单/对话框**一律 `uiautomator dump` 查 bounds 取中心,不要硬编码坐标**:菜单头部"当前会话"显示的 URL 长度会改变菜单高度(真实链接 208 字符让菜单项整体下移约 120px,旧坐标会点错项);另外 uiautomator dump 抓不到 Toast(独立窗口不在 view 树),验证这类提示要看副作用而非找文字(来源:2026-09-06 v2.4)
- 更新下载的 stall(卡住)判定:**DownloadManager 的 PENDING/PAUSED 都是"字节必然不动的调度态",必须持续刷新计时戳**,否则等待时长被累进 RUNNING 的卡住判定,刚恢复传输就被误杀换源(v2.4"每个源下载一半即失败"就是这个+20s 阈值太激进,匀速下载 20s≈一半进度;现 60s,来源:2026-09-06 v2.5)
- 判定下载"误杀 vs 真断流"看失败耗时:远小于 stall 阈值就失败 = `STATUS_FAILED` 真断流(换源是正确行为);固定进度点失败且该点≈代理缓冲区大小(如 67KB/20%) = 代理流断。**模拟器(宿主代理 fake-ip)下镜像必真断流,端到端下载验证只能真机做**,模拟器只验证"检查更新→弹窗→发起下载→状态机轮换";验证新下载逻辑可用"伪装版本号"法:sed 临时调低 versionName 构建含修复的包装模拟器,让真 latest 触发更新(测完立即恢复版本号重建)(来源:2026-09-06 v2.5)
- Manifest 声明 `enableOnBackInvokedCallback="true"` 后,Android 13+ **不再回调 `onBackPressed`**,返回手势直接 finish——必须 `getOnBackInvokedDispatcher().registerOnBackInvokedCallback` 接管,且回调里"未消费"分支要自己 `finish()`(覆盖层"返回=回会话"语义否则全丢);12 及以下仍走 onBackPressed,两条路共用一个 handleBack(来源:2026-09-07 v2.6)
- 渲染崩溃自愈的模拟器验证法:`adb shell ps -A | grep sandboxed` 找 `com.google.android.webview:sandboxed_process0` 的 pid,`kill -9` 即触发 `onRenderProcessGone`;自愈成功=App 主进程存活且会话层重建重载(来源:2026-09-07 v2.6 模拟器实测)
- 模拟器 SystemUI 对**连续两条 Toast 有竞态**:前一条未退场时后一条报 "Adding more than one toast window for UID at a time" 被静默丢弃——验证"即时反馈+结果反馈"双 Toast 链路时截图看不到第二条不代表代码没跑,以 logcat(`ToastPresenter`/`CoreBackPreview Window Toast`)为准(来源:2026-09-07 v2.6 实测)
- 动态快捷方式(`ShortcutManager.setDynamicShortcuts`)只在 recordHistory 调用链里刷新的话,**从持久化 prefs 恢复的历史不会同步**(启动路径不经过 recordHistory)——onCreate 需补一次同步;注册情况用 `adb shell dumpsys shortcut` 查(来源:2026-09-07 v2.6 模拟器实测)
- 模拟器默认未录指纹/人脸,`BiometricManager.canAuthenticate()` 返回 NONE_ENROLLED:应用锁在模拟器只能验"无法验证→跳过"降级分支,真认证弹窗要真机验;框架 `android.hardware.biometrics.BiometricPrompt` 为 API 28+,minSdk 26 的两档老系统直接放行不锁死(来源:2026-09-07 v2.6)
- **Android 14+/targetSdk 34 读 `DownloadManager.COLUMN_LOCAL_FILENAME` 直接抛 SecurityException**(系统提示改用 ContentResolver.openFileDescriptor);吞掉该异常会得到 path=null,更新链路把"下载已完成"误判成"源失败"→换源重下循环→最终报下载失败,而文件其实躺在下载目录(v2.6 真机反馈的原样症状)——验签必须先经 `getUriForDownloadedFile`+`openInputStream` 拷进私有缓存再解析;**完成态(SUCCESSFUL)只允许阻断提示,绝不允许换源重下**;验签各步判定看 logcat TAG=`ZCodeUpdater`(来源:2026-09-07 v2.7 模拟器端到端实测,日志实锤 SecurityException)
- **WebView 双指捏合缩放要三件套一起开**:`setSupportZoom(true)` + `setBuiltInZoomControls(true)` + `setDisplayZoomControls(false)`。**`setBuiltInZoomControls` 默认 false**——只开 support 不开 builtin,捏合完全无效且无任何报错(壳从 v1.0 到 v2.7 一直是这个状态,来源:2026-09-08 v2.8)
- **页面缩放别用 `setInitialScale`**:官方文档写明它只对"没有 viewport meta 的页面"生效,而远程页自带 `<meta name="viewport" content="width=device-width, initial-scale=1">`——改用加载完成后按倍数 `WebView.zoomBy()`(来源:2026-09-08 v2.8,AOSP WebView.java javadoc 实查)
- **`WebView.getScale()` 返回的是含设备像素密度的绝对值,不是 1.0**(模拟器 Pixel6 上为 2.625):算缩放必须记一个"本页未缩放基准"(壳里叫 naturalScale)再乘倍数,把百分比直接当 scale 用会算错(来源:2026-09-08 v2.8 日志实测 cur=2.625)
- **`zoomBy` 在 `onPageFinished` 刚回调时静默无效**(内核布局未稳定,不抛异常、scale 不动、无日志);同一个调用稍后从菜单点击却正常。必须发完延迟 ~220ms 校验 `getScale()` 是否到位、没到位就重试(来源:2026-09-08 v2.8 实测)
- **缩放基准不能每次 `onPageFinished` 重测**:重载/换链后 WebView 保留上次缩放,`getScale()` 返回"已缩放"值(实测 4.10),重测基准会让倍数反复叠乘(1.5625 → 页面越来越大)。基准只在 WebView 全新时测一次(来源:2026-09-08 v2.8 实测)
- **下拉刷新必须排除多指手势**:双指捏合时手指也会向下移动,会被"起点在顶部 1/6 + 下拉 112dp"误判成刷新重连;监听 `ACTION_POINTER_DOWN` 置标志后整体跳过(来源:2026-09-08 v2.8)
- **模拟器验证缩放/多点手势只能 `sendevent` 合成**:`input tap/swipe` 走输入管理器、绕过 evdev,`getevent` 抓不到也产生不了多点手势。触摸屏是 `virtio_input_multi_touch_1`(`/dev/input/event2`),坐标 0..32767,用 `ABS_MT_SLOT`(47)+`ABS_MT_TRACKING_ID`(57)+`ABS_MT_POSITION_X/Y`(53/54)双槽张开。缩放是否真生效用 PIL 量测试页固定色块的像素宽比肉眼可靠(来源:2026-09-08 v2.8)
- **模拟器测网页用 `data:text/html,...` 预置 URL**:targetSdk 34 下 `http://127.0.0.1` 明文被拦(错误码 -1),`data:` URL 不受限;带 viewport meta 的本地测试页可完整复现远程页的缩放行为(来源:2026-09-08 v2.8)

## 知识沉淀协议

> 本协议在对话中自动生效。不是规章制度——是给你未来会话的自己的**记忆外挂**。
> 遵守这些规则 = 帮未来的自己。1 分钟的记录 = 下次会话省 10 分钟重学。
> 如果此轮触发检查没有值得记录的内容,沉默——不作声比废话强。
> **存放位置(2026-09-04 用户指定)**:所有记忆一律落到项目内共享位置——日期笔记进 `docs/memory/`,耐久规则直接进本文件对应节;各 agent 的私有记忆只能作补充索引,不得作为唯一存放处,保证 Claude Code / Codex / Cursor / ZCode 等工具都能读到。

| 规则 | 触发条件 | 行为 |
|------|---------|------|
| **P1** | 用户说 "记住"/"记下来"/"沉淀" | 写 `docs/memory/YYYY-MM-DD-<摘要>.md`(3-5 要点),耐久部分**直接**同步进本文件对应节(不再追问是否同步) |
| **P2** | 用户纠正错误("不对"/"错了"/"应该是") | 如学到项目特定知识 → 追加到 §踩坑记录:`<错误> — <正确>(来源:<日期>)` |
| **P3** | 自主发现非显然约定 | 追加到 AGENTS.md 最相关节,简短告知用户 |
| **P4** | 同一流程被指导 ≥3 次 | 向用户提议用 **skill-creator-plus** 封装为项目 Skill |
| **P5** | 任务完成 / 用户说 "好了" | 自问"有什么值得记录?"——**答案为空则沉默**,不要为仪式感编造内容 |
