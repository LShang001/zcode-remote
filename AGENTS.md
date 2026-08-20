# AGENTS.md — ZCode遥控App

> AI Agent 入口文件,平台无关(Claude Code / Codex / Cursor / ZCode 均读此文件)。
> 本文件只包含 Agent 无法从代码自行推断的信息。

## 项目

把 ZCode 桌面端的远程控制网页(`https://zcode.z.ai/remote/...`)封装成安卓独立 App:WebView 全屏、无浏览器 UI、自带桌面图标。网页是第三方的,本仓库只是壳。
**成熟度:个人装机使用。技术栈:纯 Java + compileSdk 34,零第三方依赖,无测试与 linter。**

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
# 启动模拟器做视觉审查(AVD 名 zc,已建好,WHPX 加速可用)
"$LOCALAPPDATA/Android/Sdk/emulator/emulator.exe" -avd zc -no-snapshot-save -no-boot-anim -gpu swiftshader_indirect &
```

## 关键路径

| 路径 | 为什么必须知道 |
|------|---------------|
| `app/src/main/java/com/zcode/remote/MainActivity.java` | 唯一源文件:全部逻辑与 UI 都在这约 450 行里,纯代码布局,不存在 layout XML |
| `gradle.properties` | `android.overridePathCheck=true` 支撑着中文路径构建,删了构建必挂 |
| `docs/screenshots/` | v1.1 五张视觉基准图;改 UI 后逐张对照,防回归 |
| `docs/emulator-review.md` | 视觉审查完整流程(预置链接/必查画面清单);改 UI 或发版前读 |

## 原则

1. **壳的本分** — 网页内的功能与 UI 归 ZCode 官方;壳只做装载、链接管理和移动体验,增强走系统能力(下载管理、外链跳浏览器),保持不注入 CSS/JS 改网页(会随网页更新碎掉)
2. **单文件极简** — 逻辑收进 MainActivity.java,UI 用代码构建,保持零第三方依赖;每加一个依赖先问能不能不加
3. **发版四同步** — `versionCode` +1、`versionName`、设置页 footer 版本(常量 `APP_VERSION`)、GitHub Release(tag `vX.Y` + 上传 APK),一次发版四处同改;漏发 Release 等于用户永远收不到更新
4. **眼见为实** — 改 UI 后在模拟器跑起来截图,用 Read 亲眼看图确认才算完成

## 边界

**绝不修改**:
- `ZCodeRemote.apk` — 发版产物,只能由上面的构建命令覆盖
- `.gradle/`、`app/build/` — 构建缓存,手改了也会被重建冲掉
- 签名配置 — 保持 debug 自动签名;换签名会导致用户手机无法覆盖安装

**修改前必须确认**:
- 升级 AGP(现 8.5.2)/ Gradle(现 8.9)/ compileSdk(现 34)→ 先确认与 JDK 17 及中文路径兼容
- Manifest 新增权限 → 先确认网页功能确实需要(当前仅 INTERNET)

## 踩坑记录

- 中文项目路径触发 AGP "non-ASCII characters" 报错 — 保留 `gradle.properties` 里的 `android.overridePathCheck=true`(来源:2026-08-21 迁移 D 盘实测)
- 远程链接每次新会话都变 — 链接只从剪贴板 / VIEW intent / 设置页动态获取,代码里仅维护正则 `https://zcode\.z\.ai/remote\S*`;硬编码 sid 等于写死一个注定过期的会话(来源:v1.0 设计验证)
- 远程网页是 SPA 内部滚动,`webView.getScrollY()` 恒为 0,不能作为下拉刷新"已在顶部"的判据,否则页内任何下拉都误触刷新重连 — 改用手势起点在屏幕顶部窄条(downY < 高度/6)+ 近乎垂直 + 拉够深三重判定(来源:2026-08-21 v1.2 修复实测)
- targetSdk 34 上动态注册非豁免广播(含 `ACTION_DOWNLOAD_COMPLETE`)必须显式传 `RECEIVER_NOT_EXPORTED`,否则启动即 SecurityException 崩溃;系统服务发出的广播不受 NOT_EXPORTED 影响(来源:2026-08-21 v1.3 模拟器实测)
- GitHub Releases API 匿名请求有约 1 分钟 CDN 缓存 — 刚发完 Release 立刻在 App 里"检查更新"可能拿到旧 latest,稍等重试即可,不是代码 bug(来源:2026-08-21 v1.3 更新链路实测)
- GitHub asset 真实下载走 `objects.githubusercontent.com`,本机网络对它时通时断 — App 里 API 检查成功但 DownloadManager 永远 0 字节时,先怀疑该域被阻断(宿主 `curl -sIL <asset URL>` 可对照);模拟器验证安装链路可用 root 改 downloads.db 把条目置 status=200 并指向 push 进去的 APK(来源:2026-08-21 v1.6 实测)
- 模拟器 `emu network speed` 限速命令会弄丢 guest 默认路由(ip route 无 default),且重启/wipe 前难恢复 — 测完限速记得恢复;真要限速测下载,优先在真机或抓中间态截图为主(来源:2026-08-21 v1.6 实测)
- adb 预置 `shared_prefs/MainActivity.xml` 时 URL 里的 `&` 要写成 `&amp;`,否则链接被 XML 截断(来源:模拟器测试实测)

## 知识沉淀协议

> 本协议在对话中自动生效。不是规章制度——是给你未来会话的自己的**记忆外挂**。
> 遵守这些规则 = 帮未来的自己。1 分钟的记录 = 下次会话省 10 分钟重学。
> 如果此轮触发检查没有值得记录的内容,沉默——不作声比废话强。

| 规则 | 触发条件 | 行为 |
|------|---------|------|
| **P1** | 用户说 "记住"/"记下来"/"沉淀" | 写 `docs/memory/YYYY-MM-DD-<摘要>.md`(3-5 要点),追问是否同步到 AGENTS.md |
| **P2** | 用户纠正错误("不对"/"错了"/"应该是") | 如学到项目特定知识 → 追加到 §踩坑记录:`<错误> — <正确>(来源:<日期>)` |
| **P3** | 自主发现非显然约定 | 追加到 AGENTS.md 最相关节,简短告知用户 |
| **P4** | 同一流程被指导 ≥3 次 | 向用户提议用 **skill-creator-plus** 封装为项目 Skill |
| **P5** | 任务完成 / 用户说 "好了" | 自问"有什么值得记录?"——**答案为空则沉默**,不要为仪式感编造内容 |
