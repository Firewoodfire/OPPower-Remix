# 协作约定

## 交付流程（每次改动都必须走完，不要等用户催）

用户装了 OPPower 在真机上，拿包的唯一途径是项目网盘。**"构建成功"不算交付完成，"网盘里能点到"才算。**

每完成一版，按这个顺序做到底，中间不要停下来问"要不要传"：

1. 改代码 → `assembleDebug --rerun-tasks`（0 warning）+ `assembleRelease` + `testDebugUnitTest` + `lintDebug`
2. 归档到 `release/<versionName>/`：debug、release-unsigned、release-debugkey
3. 用调试密钥签一份 `release-debugkey`（`apksigner` + `~/.android/debug.keystore`，口令 `android`／alias `androiddebugkey`）
4. **上传两个包到网盘 `OPPower 安装包/`**（`dir_id = KMmXEjJMjFBL`）：`<name>-debug.apk` 与 `<name>-release-debugkey.apk`
   - 走 `tdrive.file_upload` 拿签名 URL，再 `curl --config` 配置文件方式 PUT，最后 `tdrive.file_upload_complete`
   - 用配置文件传参，不要把长签名直接拼进命令行（会遇到 `InvalidAccessKeyId` 和转义问题）
   - **传完删掉上一版**：文件夹里只留当前这一版的 debug + release-debugkey 两个包，不留历史版本，不留 unsigned
   - 根目录不放任何 APK，只放文件夹
5. 更新 `README.md` 版本号、`docs/VALIDATION.md` 追加本节、`release/<versionName>/BUILD.txt`
6. 回复用户：网盘里叫什么、哪个该装、装上后看哪几行

## 网盘目录约定

```text
项目网盘根目录/
├── OPPower 安装包/          ← 只放当前版本的 2 个包
│   ├── OPPower-x.y.z-betaNN-debug.apk
│   └── OPPower-x.y.z-betaNN-release-debugkey.apk
├── AEmulator-Plus-Mod/      ← 与本项目无关，别动
├── 系统文件/                ← 别动
└── 资产库使用指南.pdf        ← 别动
```

根目录不放散落 APK。用户手机上装包的唯一入口是 `OPPower 安装包/`，所以那里任何时候都应该只有「当前版本、能装、该装」的东西。

## 上传什么、不上传什么

- **传**：`-debug.apk`（能装，带日志，排查用）、`-release-debugkey.apk`（能装，3.5 MB，日常用）
- **不传**：`-release-unsigned.apk`。未签名，系统直接拒装，传上去只会让人误以为是可选项。
- **不留**：上一版及更早的所有包。每次上传新版后顺手删掉旧的，文件夹里始终只有 2 个文件。

## 版本与体积基线

| 版本 | debug | release |
| --- | --- | --- |
| beta43 | 38,385,030 | 3,469,116 |
| beta44 | 38,401,414 | 3,469,116 |
| beta45 | 38,417,798 | 3,487,859 |
| beta46 | 38,417,798 | 3,487,859 |
| beta47 | 38,434,178 | 3,504,243 |
| beta48 | 38,434,182 | 3,504,243 |
| beta49 | 38,434,182 | 3,520,627 |
| beta50 | 38,466,946 | 3,520,627 |
| beta51 | 38,483,330 | 3,520,683 |
| beta52 | 39,440,529 | 3,537,067 |
| beta53 | 38,499,718 | 3,537,067 |
| beta54 | 38,483,330 | 3,520,683 |

debug 每版增长约 16 KB 属正常（新增代码 + 内联字符串）。一次涨超过 200 KB 要查是不是误打包了资源。

## 这个项目里踩过的坑，别再踩

- **不要把整个 `UiState` 传进页面**：它每秒被整体替换一次（电池遥测），传 `state.value` 等于让那个页面的整棵组合树每秒重排一遍。用 `OPPowerApp` 里的 `slice(state) { it.xxx }` 切成窄状态，页面只订阅自己真正用到的那几片。
- **`LazyColumn` 的顶距要走 `contentPadding`**：折叠顶栏时 `topInset` 每帧都在变，挂在 `Modifier.padding` 上会改测量约束、每帧重测整个列表。
- **`drawWithCache` 里别做排版**：`textMeasurer.measure()` 这类活放进去等于每次重组都重排一遍，挪到 `remember` 里。
- **网盘上传签名**：`file_upload` 返回的 `Authorization` 必须拼在 **URL 的 query** 上（`?q-ak=...&q-signature=...`），`x-cos-security-token` 仍要放在请求头里。放请求头会一直 `SignatureDoesNotMatch`，这个签名串我也没能离线复算出正确的组合，别浪费时间试。
- **`CollapsingTopAppBar` 的标题槽只有 `title: String`**：没有 `navigationIcon`、没有内容槽，返回键必须做成浮层。另外必须显式传 `rememberCouiSearchBarState(false)`，否则 `active` 恒 false 时标题会走搜索态的平移分支。
- **顶栏浮层里不能用 `TopAppBarButton`**（beta53 定位到的设置闪退真凶）：它组合期 `checkNotNull(LocalCouiToolbarBackdrop.current)`，而这个 local 是 `internal`、只由 `TopAppBarSurface` 在顶栏子树里提供。浮层在 Scaffold 顶栏之外，必然抛 `IllegalStateException: 顶栏按钮必须位于顶栏效果作用域内`。beta54 起大标题页改用 `LargeTopAppBar` 自带的 `navigationIcon` 槽，返回键和淡出标题一起解决。
- **`CollapsingTopAppBar` 按 `hasSearchBar` 分两支**：不传 `searchBar` 时是「24sp 缩放 + 标题钉住」，传了才是 ColorOS 的「40sp + 淡出」。顶栏底色"不透明"、标题"字号不对"都是走错分支的症状，别去改 `TopAppBarSurface`。
- **顶栏标题的 `Typeface` 是 `sans-serif-semibold`**：系统内置族名，拿不到 OPPO Sans，大标题会退化成合成加粗。字号可以改：`LargeTopAppBar(expandedTitleStyle = TextStyle(fontSize = 48.sp))`，`withTitleOverrides` 会保留库的 fontFamily/字重；但字体族本身换不掉。
- **COUI 的 `CouiDialogAction`**：`style = Primary` 必须同时给 `colors`，否则 `init` 里 `require` 抛异常，**在组合期抛**，表现是"点一下就闪退"。一律用 `ui/CouiKit.kt` 里的 `couiPrimaryAction()`，不要手写。
- **COUI 的 `NavigationBar`** 只能放在 `Scaffold` 的 `bottomBar` 栏位，它要取 `LocalCouiBackdrop` 做玻璃采样，取不到会 `checkNotNull` 崩。
- **底栏两侧的圆形按钮不要自己拼**：`NavigationBar` 原生有 `leftIcon/leftVisible/onLeftClick` 与 `rightIcon/rightVisible/onRightClick` 两组附件球（56dp、独立玻璃材质、与中间胶囊有融合动画）。自己用 `Box` 叠两个 `Fab` 会拿不到同一份 backdrop 采样，融合动画也没有。
- **跳系统设置一律包 `runCatching`**：`ACTION_ACCESSIBILITY_SETTINGS` / `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` / `ACTION_MANAGE_OVERLAY_PERMISSION` 在精简 ROM 上可能没有对应 Activity，`startActivity` 抛 `ActivityNotFoundException`，而它发生在点击回调里，等于一次闪退。统一走 `GeneralSettingsPage.kt` 里的 `openSystemPage()`。
- **温度轴的刻度数由画面高度决定，不由数据跨度决定**：按 1°C 一格排，17°C 跨度就是 18 行 7sp 的字，在 190dp 的曲线框里直接叠字。`resolveTemperatureAxis` 现在按「目标 5 格」反推步长再吸附到 1/2/5/10，`ChartCard` 另有一层像素级抽稀兜底。
- **`AllowedCommands` 的正则必须两端锚定**。`execute()` 把整串交给 `sh -c`，没有 `^…$` 时 `; reboot` 这类拼接串能通过。改白名单时同步改 `AllowedCommandsTest`。
- **`/proc/<pid>/cmdline` 存的是完整路径**，不是裸进程名。判断进程存活要比 basename 子串，且只取 argv[0]，否则会把诊断自身执行的 `sh -c ...` 数进去。
- **`--rerun-tasks` 才看得见全部警告**。增量编译会掩盖 warning，"0 warning"的结论只能来自完整重编。
- **`OPlus Sans` 的小写是 `oplussans`**，不是 `oplusans`——两个 s 相连没有分隔符。按 `oplusans` 写匹配会永远失败，且读代码看不出来，只能让测试把实际值打进断言消息。
- **COUI 的 `CouiCollapsingAppBarState` 构造与 `offsetPx` 都是 `internal`**，应用侧碰不到。想让 `LargeTopAppBar` 滚到底后不留紧凑栏：传 `collapsedHeight = 0.1.dp`（库 `require(collapsedHeight > 0.dp)`，传 0 直接崩），标题用 `Modifier.graphicsLayer { alpha = 1f - state.collapsedFraction }` 跟着淡出。`collapsedFraction` 是公开的，在 graphicsLayer 里读只触发重绘。
- **设置页搜索框放正文顶部**：beta52 曾误判 search 槽是闪退原因（它内部确有若干依赖测量时序的 `checkNotNull`），真机栈证明不是——真凶是上一条的浮层按钮。search 槽本身能用，只是正文顶部少一条时序依赖。判断 bug 时先要栈，别靠 diff 猜。
- **`TextFieldBuffer.placeCursorBeforeCharAt` 不接受空串位置**：`BasicTextFieldState.edit { replace(0, length, ""); placeCursorBeforeCharAt(0) }` 抛 `IndexOutOfBoundsException`，点「清空搜索」就走这条。改成 `selection = TextRange(value.length)`，空串也合法。
- **崩溃记录只放在「设置 → 数据管理」**（beta54 起按用户要求）：`CrashRecorder` 仍在后台写盘，但**启动时不要弹提示条**——闪退修好之后每次启动都弹一条旧记录只会烦人。`CrashRecorder.markLocation()` 仍会把「崩在哪一页」写进栈里，需要时去数据管理页复制。
- **字体不打包**。一个字重的中文字形约 9 MB，四个字重就是 36 MB，release 包会从 3.5 MB 涨到 40 MB。走 `OppoFont` 读 `/system/fonts`，失败自动回落。
- 容器里没有 `/dev/kvm`，UI 观感和充电实测**只能真机确认**，不要在交付说明里写成"已验证"。

## 沟通口径

- 版本号说清是哪个包，别只说"新版"。
- 说明哪些结论是编译/测试验证过的，哪些只能在真机上验。
- 修 bug 时如果上一版判断错了，直接说错了什么、错在哪，不要糊过去。
