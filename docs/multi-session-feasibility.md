# 多会话 / 多窗口 可行性分析(v2.12 时点)

> 需求(2026-09-14 用户提出):同时打开多个链接/多个会话——折叠屏上并排开两个甚至更多窗口,
> 同时控制两台以上电脑。本文只做**可行性分析与路线比较**,不含实现。
> 结论基于官方文档核实(标注依据)+ 对现有代码的实测;不确定项明确列出,未实测不当作事实。

## 一、先说结论

| 路线 | 可行性 | 开发量 | 主要风险 |
|------|--------|--------|----------|
| **A. 同 App 多 task(documentLaunchMode)** | 平台确认可行 | 中 | 依赖 OEM 分屏是否允许"同一 App 选两次";WebView 存储默认共享需 Profile 隔离 |
| **B. App 内自建多窗格(一个 Activity 多个 WebView)** | 技术可行 | 大 | 内存与自愈复杂度;小屏体验差需回退单窗格 |
| **C. PiP 画中画** | 可跑 | 小 | 同时只能一个、窗口小、交互差 —— 不适合做主干 |
| **D. 系统分屏两个不同 App** | 现成 | 0 | 用户可手动做,但两个窗口是两个 App,不是本需求 |

**推荐顺序**:先做 A 的**底座**(多实例状态 + Profile 隔离 + configChanges 补齐),它同时也是 B 的前提;
在真机验证过"同一 App 分屏两次"与"网页端能否同机双会话"之后,再决定要不要做 B 的双窗格 UI。

## 二、前置技术事实(已核实)

### 2.1 同 App 多实例(A 路线的基础)
- `documentLaunchMode="always"` 让同一 Activity 每次启动都新建独立 task,各自出现在最近任务里。
  **前置条件:Activity 必须是 `launchMode="standard"`** —— 当前壳是 `singleTask`,直接冲突,必须改
  (或不在 manifest 声明、改由 startActivity 的 Intent flags 按场景决定,以保住深链单实例语义)。
  依据:developer.android.com activity-element(documentLaunchMode 节)。置信度:确定。
- `resizeableActivity` 在 targetSdk ≥ 24 且未声明时**默认 true**,当前 App(targetSdk 34)本来就允许
  分屏/自由窗口,**不需要改**。依据:同上(resizeableActivity 节)。置信度:确定。
- 同一 App 的两个 task 同屏分屏,平台层无禁令(多窗口管理单位是 task);Android 12L+ 最近任务里
  可对同一 App 的卡片做 Split。**但 OEM 分屏选择器是否允许选中同一 App 两次未知**(国产 ROM 常限制),
  必须真机验证。置信度:平台机制确定;OEM 行为存疑。
- Android 16 对 **targetSdk 36** 的大屏强制(忽略 orientation/resizeable 限制)不波及 targetSdk 34;
  本 App 不受强制条款约束。依据:developer.android.com/about/versions/16/behavior-changes-16。置信度:确定。
- 最近任务控制:`maxRecents` 默认 16(document task 默认 `autoRemoveFromRecents=true`,finish 后自动
  从最近任务消失);`ActivityManager.AppTask.finishAndRemoveTask()` 可主动清理。置信度:确定。

### 2.2 WebView 多实例(C 路线与本路线共用的底座事实)
- 官方支持一个进程创建多个 WebView;**多个 WebView 可能共享同一个渲染进程**,
  `onRenderProcessGone` 会**对每个受影响的 WebView 各回调一次** —— 现有自愈逻辑必须升级成
  "一次崩溃可能同时重建多个实例",且不能一个实例的回调影响另一个还在用的实例。
  依据:developer.android.com/reference/android/webkit/WebViewClient。置信度:确定。
- **同一进程的所有 WebView 默认共享 Cookie 与 DOM storage(localStorage/IndexedDB)** ——
  对"每个 WebView 连不同电脑"是致命的(远程页的 relay 登录态就在 DOM storage 里,两个实例会互踩)。
  **正解:androidx.webkit 1.9.0+ 的 Multi-Profile API**(`WebViewFeature.MULTI_PROFILE`,
  `WebViewCompat.setProfile(webView, name)`,每个 Profile 独立 Cookie/存储)。
  约束:`setProfile` 必须在 WebView **挂到视图树之后、任何其它操作之前**调用;能力必须用
  `WebViewFeature.isFeatureSupported(MULTI_PROFILE)` 运行时检查。
  依据:developer.android.com/reference/androidx/webkit/ProfileStore、WebViewCompat。置信度:共享机制确定、API 确定、本机支持性需实测。
- 内存:官方无量化数据。业界量级:简单页 renderer 约 30–60MB PSS,重型 SPA 80–200MB。
  12–16GB 旗舰上**同时 2 个现实,4 个风险随页面复杂度陡增**(主要风险是后台 renderer 被 LMKD 杀掉
  触发自愈,而不是直接 OOM)。**必须用 `dumpsys meminfo` 实测本项目页面**。置信度:存疑(量级)。

### 2.3 折叠屏与配置变化
- 未声明在 `configChanges` 的变化 → **Activity 重建**。官方折叠屏指南:"An app stops and restarts as it
  transitions from one screen to another when a device folds or unfolds."
  当前壳只声明 `orientation|screenSize|keyboardHidden`;展开/折叠带来的 `screenLayout`、
  `smallestScreenSize`(可能还有 `density`)变化未声明 → **重建 = WebView 整页重载、覆盖层状态清零、
  缩放基准重置**。官方明确:处理多窗口相关配置变化要用 `screenLayout` + `smallestScreenSize`。
  依据:activity-element(configChanges 节)、learn-about-foldables。置信度:机制确定;?vivo 具体触发哪些配置需真机 log 实测。
- 修法很便宜:**补 `screenLayout|smallestScreenSize`(及 `density`)到 configChanges**,无论做不做多会话都该做
  —— 折叠屏用户展开手机不该丢掉会话。
- Jetpack WindowManager(`FoldingFeature`/`WindowLayoutInfo`,纯 Java 可用;`currentWindowAdaptiveInfo()`
  是 Compose 专用)能提供铰链位置与折叠姿态,是做"展开时左右各一台电脑"最权威的判断源,但**要加依赖**,
  与本项目"极简优先/每加依赖先问能不能不加"的原则冲突 → 只在确定做双窗格时才引入。
  依据:developer.android.com/reference/androidx/window/layout/FoldingFeature。置信度:API 确定。

### 2.4 其它路径
- **PiP**:WebView 在 PiP 里能正常绘制,但要 `supportsPictureInPicture="true"` 且 configChanges 必须补
  `screenSize|smallestScreenSize|screenLayout|orientation`;同一时刻只有一个 PiP 窗口、交互受限
  → 只适合"顺带盯一眼第二台电脑",不做主干。依据:picture-in-picture 指南。置信度:确定。
- **OriginOS 应用双开/分身**:是"克隆整个 App",由 OEM 白名单控制(第三方无法申请),分身间数据不共享,
  App 自己感知不到 → **不作为方案**。置信度:存疑(需真机实查设置里的可用列表)。

## 三、两条主路线的取舍

### 路线 A:同 App 多 task(依赖系统分屏/自由窗口摆放)
- 开发量:中。manifest 改 launchMode/documentLaunchMode + 每实例自持状态 + prefs 按实例隔离 +
  Profile 隔离 + `setTaskDescription` 区分最近任务条目。
- 白送:窗口拖拽、最大化、与其它 App 混搭、折叠/展开由系统托管。
- 风险:① OEM 分屏选择器可能不允许同一 App 选两次;② 现有覆盖层架构与 `singleTask` 要动,
  且深链(微信点链接)、剪贴板采纳、"当前电脑"这些单会话语义都要重新定义"哪个实例响应";
  ③ 最近任务出现多条同名条目,需要命名。

### 路线 B:App 内自建多窗格(一个 Activity,N 个 WebView)
- 开发量:大。会话从"单例字段"变成"可复制的会话单元"(现有代码里会话层字段被引用 97 处,
  是这次重构的主要工作量);每窗格独立 Profile/状态/错误覆盖层/自愈;焦点与输入路由;
  小屏必须回退单窗格;内存降级策略(打开 3 个以上时提示或拒绝)。
- 白送:完全自主可控,不受 OEM 分屏限制;是**唯一能保证"折叠屏展开时左右各一台电脑并排"**的形态;
  可以实现"一台电脑一个窗格 + 一键切换布局"这类自研体验。
- 风险:内存与渲染进程被杀的复杂度;自愈要处理"N 个 WebView 同时回调";小屏没有价值。

## 四、必须先验证的不确定点(按优先级)

1. **网页端能否同机开两个独立远程会话**(服务端限制是整件事的前提)——先用两个浏览器/两个 WebView
   各开一个会话试;本机 Cookie/存储隔离解决不了服务端策略。
2. **本机 WebView 是否支持 MULTI_PROFILE**(一行 `isFeatureSupported` 日志即可)。
3. **vivo X200 Ultra 上分屏选择器是否允许同一 App 选两次**;OriginOS「应用分身」是否包含本 App。
4. **折叠展开实际触发哪些 config**(`am log`/日志实测,决定 configChanges 补什么)。
5. **双/四 WebView 的 PSS 与 sandboxed_process 数量**(`dumpsys meminfo`),决定窗格数上限与降级阈值。
6. 后台被杀的**多实例 onRenderProcessGone 自愈**能否正确处理"一次崩溃 N 个回调"。

## 五、建议的落地顺序

1. **现在就做(零依赖、对折叠屏用户直接有收益)**:
   - configChanges 补 `screenLayout|smallestScreenSize|density`(折叠不重建、不丢会话);
   - 最近任务条目 `setTaskDescription`(如果做多实例,这条必做;不做也无害)。
2. **下一步(小步验证)**:加一个"在另一窗口打开"的实验入口,用 documentLaunchMode 起第二个实例,
   实测 A 路线全链路(分屏是否允许、两实例存储是否互踩、Proxy 隔离)。这一步不改现有单会话体验。
3. **只有验证通过且确实需要"并排同时看"再投入 B**:把会话层重构为可复制单元 + Profile 隔离 +
   多窗格布局,面向折叠屏/平板。此步之前所有结论都算预研。

## 六、明确不做

- PiP 作主干(只能一个、交互差);
- OEM 应用分身作方案(白名单不可控、数据不共享);
- 依赖 desktop windowing 的默认开启(无权威文档,vivo 大屏状态需实测;targetSdk 34 也不被强制)。
