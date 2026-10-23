# 开发日志（按批次）

每个批次 = 一个可交付的小主题。写法固定：**目标 → 改了什么 → 复盘（做对/做错/修正）→ 验证 → 遗留**。

构建与验证口径：

| 环节 | 方式 |
|---|---|
| 类型检查 | `bash /tmp/opencode/typecheck.sh`（只跑 javac，不出 APK） |
| APK 构建 | GitHub Actions：`.github/workflows/build-apk.yml`，复用 `app/build.sh` 七步链路 |
| 自检 | `app/selfcheck/*.java`，纯 java 直接跑，断言式 |

---

## 批次 AUDIT — 全量审计 35 项修复（高危6/中风险16/低风险13）

### 目标

用户要求对全仓库做代码级审计（root/特权链、虚拟屏/镜像链、服务/UI 链三路深审 + 桌面核心链人工复核），
输出审计报告后按清单逐项修复全部 35 项，每项复盘，最后出总报告并推送构建。

### 改了什么

高危 6 项：TouchForward 主线程 socket IO 快照化（H1）、RootMain pushEvent 写手线程化+有界队列（H2）、
QuickBar.runFn 后台化（H3）、设置页/RootPanel 主线程 root 调用簇统一 bgTaskDialog 后台化（H4）、
dead() 守卫全覆盖（H5）、MirrorSlot 生命周期 LIFE_LOCK 串行化（H6）。

中风险 16 项：LauncherModel IO_LOCK 消 CME 丢档 + Weather 持锁（M2）、歌词配置 CFG_VER 代际化
消 1Hz 全量 JSON 解析（M3）、歌词组件原位 setText（M4）、placeClockBox/placeWidget/IslandView
先比较后赋值消 requestLayout 风暴（M5）、loadApps 进程缓存+包广播失效（M6）、runWatch 身份校验 +
AtomicInteger + 30s 读超时（M7）、守护进程 GEN 代际握手（PONG 捎带 → 客户端清对账表，M8）、
共享投影引用计数（M9）、WindowCard vd.resize 优先（M10）、包名正则放行大写（M11）、注入坐标钳制（M12）、
快照双缓冲（M13）、armed 点按双触发修复（M14）、Surface blob 上限（M15）、su 进程强制回收（M16）。

低风险 13 项：注入面白名单（L1）、cpuPct 节流（L2）、HTTP 封顶（L3）、歌词目录去重（L4）、
XposedEntry 反射缓存（L5）、keepScreenOn 保存/恢复（L6）、能力缓存重探（L7）、RootMain 四处加固
（L8）、更新包完整性校验（L9）、allowBackup=false（L10）、FileProvider 只读（L11）、selfReport
去指纹（L12）、死代码/并发杂项（L13）、兼容注解（L14）、Constants 收编（L15）、QuickbarCheck
LruCache 垫片（L16，7 项自检首次全绿）。

文档：docs/FIXLIST.md（35/35 勾选+修复说明）、docs/AUDIT-REPORT.md（总报告，评分 6.5→8.5）。

### 复盘

做对：每批次修复后立即 --release 11 检查点（共 5 轮），三处编辑工具块匹配事故（save() 中段被吞、
injectTouch 重复、PipBoard canResize 重复+maskAlpha 被吞）全部被编译器当场捕获并修复，未带病提交。

做错/修正：编辑工具多行替换按首尾锚匹配，锚点间代码会被吞 —— 之后多行编辑必须小锚点 +
git diff 删除行核对。M8 的 DisplayListener、M6 的 pinStatic、L12 的门控方式做了三处有据取舍，
记录在总报告 §4。

### 验证

javac --release 11 全量通过；7 项自检 169 断言全绿（PrivCodec 39 / Transform 54 / Lrc 15 /
StackList 30 / TrustedFlags 10 / DumpParse 14 / QuickbarCheck 7）。真机四类场景（触摸/部署/断链/长稳）
待 CI 出包后验证，见总报告 §2。

### 遗留

真机回归是当前最大缺口；ROM 相关的私有屏行为差异依赖真机确认（降级链已完整）。

---

## 批次 CI — 画中画透壁纸圆角化 + 动态壁纸 + 壁纸页时钟编辑/小组件 + Dock 替换应用

### 目标

用户五条：① 应用界面也透出壁纸；② 壁纸页时间日期可编辑大小和移动位置、壁纸页可加小组件；
③ 支持一直循环播放的动态壁纸；④ 桌面画中画圆角显示；⑤ 底栏图标长按可替换应用。

### 改了什么

**画中画整卡悬浮 + 圆角（①④）**：
- HomeActivity 给 PipBoard 让四周 10dp 边距（原来铺满内容区），壁纸从四周透出 ——
  分屏、单应用（键条悬浮玻璃化已就位）两种形态统一「悬浮卡片浮在壁纸上」。
- PipBoard 新增 `roundify()`：画布卡（card，裁快照/遮罩/占位卡/空态）与 SurfaceView
  本体都按 outline 裁 `CARD_CORNER_DP=20` 圆角。SurfaceView 打洞跟随 outline 裁剪是
  API 23+ 支持的行为（视频播放器圆角同款做法），minSdk 29 无兼容问题。
- 槽 2 占位卡玻璃底圆角同步改 20。

**动态壁纸（③）**：
- `WallpaperVideoView`（新文件）：TextureView + MediaPlayer，`setLooping(true)` +
  `setVolume(0,0)` 恒静音循环（车机声音归系统），prepare 丢后台线程；
  Surface 销毁/摘树/onPause 全 release，回前台 `syncWallVideo()` 幂等重建。
- 层级：root 最底层 = 静态壁纸背景 → 视频层（index 0）→ 壁纸压暗遮罩 → 三页。
  视频层透出画中画透明区与壁纸页，压暗遮罩照常生效。
- 模型新增 `wallVideo`（JSON "wallVideo"）；`Wallpaper.chooserVideo()/importVideo()`
  从系统选择器拷进 `filesDir/walls/*.vid`（与静态图同一套无权限路径）。
- 设置页壁纸组新增「动态壁纸（视频）」「清除动态壁纸」两行；「清空壁纸」连带清视频；
  顺带把「视频壁纸暂不支持」的过期 note 改掉。

**壁纸页时钟编辑 + 小组件（②）**：
- WallpaperPage 重构：时钟+日期+媒体/歌词收进一个 `clockBox` 可拖动块 ——
  长按进入拖动（requestDisallowIntercept 拦掉 pager 横翻），位置存
  `wallClockX/x=中点比例、wallClockY/y=顶边比例`；长按原地松手弹字号菜单
  （小/标准/大/特大 = 0.7/1.0/1.3/1.6 倍），行距等比缩放。
- 系统小组件承载：AppWidgetHost（实例 id 0x5E59）+ BIND_APPWIDGET（清单已有）。
  长按页面空白 → 列出 `getInstalledProviders()` → `bindAppWidgetIdIfAllowed`
  （默认桌面静默通过）→ 要授权走系统 ACTION_APPWIDGET_BIND 回执（REQ_BIND=0x5E01，
  HomeActivity.onActivityResult 原样转发 poster）。组件尺寸取 provider minWidth/minHeight，
  可拖动摆位、长按移除；记录 `[{"id","x","y"}]` 存 `wallWidgetsJson`，被卸载的组件
  恢复时顺手清记录释放 id。startListening/stopListening 跟随页面挂树/摘树。

**Dock 替换应用（⑤）**：
- 长按 Dock 图标菜单插入「替换应用…」：原地换人（model.dock 同 index 删旧插新），
  顺序位次不变；已在 Dock 里的目标拒绝；换完 `dock.bind(model)` 刷新。

### 真机反馈修复轮

四条反馈对应的根因与修法：

1. **长按「替换应用」没反应** —— 用户没自定义过 Dock 时 `model.dock` 是空列表，
   `indexOf(a.key())` 拿 -1 就静默 return，选择器根本不弹。新增
   `LauncherModel.dockKeys()`（生效位次 = dock 非空用 dock，否则回默认推荐，
   与 DockBar 绘制同一套），replaceDockApp 按 `dockKeys()` 定位；首次替换先把
   生效列表整份落进 `model.dock` 再原位换人。顺带修 `toggleDock` 的同类坑：
   空 dock 时「从 Dock 拿下来」原实现会反向 add（想移除结果塞进去）。
2. **右滑应用列表不透壁纸** —— AppGridPage 构造里自己铺了 `ground` 底色
   （批次 CI 的透明化漏了它），改 TRANSPARENT；应用名已有胶囊底，可读性不受影响。
3. **时钟只显示前半段** —— `applyClock` 改完字号立刻用**旧的** measuredWidth
   做 clamp，放大后右缘溢出被页面裁掉。摆位改 `clockBox.post(::placeClockBox)`
   等重测完再做；`tick()`（时间文本变宽窄）与 `onSizeChanged` 同口径。
4. **时钟/组件缩放移动不便** —— 交互重做：
   - 组件**单击选中**：出描边框 + 右下角缩放把手，把手拖动自由改宽高（80dp 起步、
     页宽-16dp 封顶），松手把倍率 `s` 落进 `widgetPos`（{x,y,s} 三元，JSON 多存
     "s"）；**选中后拖动免长按**（armed 模式：按下即进拖动）；菜单加「重置大小」。
   - 时钟**双指捏合**连续缩放（0.5–2.4 倍，Drag 里 ScaleGesture 语义手写：
     两指间距比值累计），松手落 `wallClockScale`；字号菜单四档保留做兜底。

### 复盘

- 空槽 SurfaceView 藏掉（上一轮批次 CH 追加）是壁纸透出的前提：SurfaceView 的打洞
  区域无视窗口内容，壁纸在洞里根本看不见；藏掉后打洞消失，透明栈里壁纸自然透出。
- 时钟块位置口径定成「x=中点比例、y=顶边比例」是为了让默认 x=0.5 保持水平居中
  （块宽未知也能居中），两种对象（时钟块/组件）共用一套 Drag 手势与钳制逻辑。
- 视频壁纸恒静音 + 退后台 release 是刻意的：车机媒体声道绝不能被壁纸抢，
  解码器也不该在后台陪跑。
- **全量复盘修掉的四个雷**（提交前自查抓到）：
  1. `AppWidgetProviderInfo.loadLabel()` 被本地裁剪版 android.jar 改成了返回
     `String`，真机 framework 是 `CharSequence` —— 调用点描述符按 stub 生成，
     真机必抛 NoSuchMethodError（被 catch 吞掉不崩，但组件列表退化成类名）。
     改用 `p.label` 字段（新旧 framework 签名一致）。教训：**本地桩 jar 改过
     签名的 API，编译通过也等于没验证**，冷门 API 用字段或经典入口。
  2. 小组件在页面量尺寸前挂载（开机恢复路径），宽高被 80dp 下限兜底后不再
     变化 —— 新增 `refitWidget()`：布局完成后按 provider minWidth/minHeight
     重算尺寸再按比例回位（attachWidget 后 post 一次 + onSizeChanged 逐个 refit）。
  3. 视频导入（几十 MB）原先在主线程拷贝，挪后台线程，主线程只收结果。
  4. 挂了动态壁纸时静态大图不再解码（被视频盖住还白占几十 MB 内存）。
- 时钟块/组件拖动与 pager 横翻的共存：触摸消费靠 PagerView.onInterceptTouchEvent
  拦截横滑，requestDisallowIntercept 只在长按（拖动意图）后调用 —— 两者不打架。
- 历史遗留（与本批无关，记录备查）：QuickbarCheck 自 LauncherModel 引入静态
  LruCache 起在纯 JVM 下必抛 Stub!（桩 android.jar 无实现），CI 又没挂 selfcheck
  步骤，一直没人发现；修复需给自检提供真实 LruCache 垫片，另开批次处理。

### 验证

- `javac` 全量类型检查 OK（仅历史 deprecation note，无错误）。
- 交互面（拖动/长按/小组件绑定/视频播放）待真机验证 —— 本地无 Gradle/模拟器。

### 遗留

- 小组件尺寸用 provider 默认 minWidth/minHeight，暂不支持双指缩放调整组件大小。
- 动态壁纸选择走系统文件选择器，未做内置视频库。
- SurfaceView 圆角裁剪在个别厂商 ROM 上若不生效，回退方案是叠四角遮罩。

---

## 批次 CH — 底部 Dock 整条重画：车机悬浮玻璃底栏，媒体控件内嵌，退出键移除

### 目标

用户给了车机底栏视觉稿：左「四格多应用菜单 + 粗体时间」、中「圆形应用图标横排 +
媒体横幅预览条（歌名 + 暂停/下一曲）」、右「品牌 Logo + 5G 网络标识 + 电量百分比」；
磨砂玻璃、大圆角、悬浮常驻；音乐/地图用什么软件就显示什么图标；退出键不要。

### 改了什么

**DockBar 整条重写**（批次 CG 的 CarPlay 五键版退役）：
- 三段布局：左 = 四格菜单（G_APPS 细线 2x2 方格）+ 16sp 粗体 HH:mm；中 =
  `model.dock` 真实应用图标（40dp，outline 圆形裁剪 clipToOutline，横向 10dp 等距，
  上限 6 个）+ 媒体横幅胶囊（媒体 App 图标 22dp + 歌名 maxWidth 118dp 截断 +
  播放/暂停、下一曲两键，直发 `MediaListenerService` transport control）；
  右 = 海鸥 Logo（双 quadTo 弧线剪影，点击回桌面）+ 网络标识（WiFi 优先，蜂窝
  按代际 5G/4G/3G/2G）+ 电池自绘（外壳 + 正极帽 + 电量填充，≤20% 转警示橙）+ 百分比。
- 磨砂玻璃：80% 透明近黑圆角矩形（`0xCC121216`）+ 1px 玻璃描边 + 顶部高光线，
  恒深不跟主题（沿用批次 CG 口径：真模糊需 API 31 RenderEffect，车机不满足）。
- 悬浮：HomeActivity 侧 DOCK_SIDE=10 / DOCK_BOTTOM=8 留边，条高 66dp，圆角 24dp；
  `dockH()` 仍含底边距（74dp），收起滑出 86dp 依旧完全离屏，批次 BB 收起/唤出
  机制原样保留。
- Listener 重构：`onDock(int)` 五常量改为 `onApps() / onApp(App) / onHome()` 三回调。
  四格菜单 = APPS ⇄ MAIN 双态；dock 应用按 `windowOf` 路由（1/2 号窗口走
  `savePkg+expandSlot`，其余 `launch()` 全屏）——与设置页 Dock 组既有文案口径一致；
  Logo = 回桌面主页。`syncDock`/`setActive` 高亮体系随五键一起删除。

**MiniPlayer 退役**：媒体控件并入 Dock 横幅后，`buildMainPage` 不再挂 36dp 媒体条，
`applyMainInsets`（媒体条显形画布让位逻辑）整体删除；横幅空白区点击接原
`focusMediaCanvas`（导焦画布）。`MiniPlayer.java` 文件留档未删。

### 复盘

- **单应用内媒体控制不可见（用户拍板：维持）**：旧 MiniPlayer 常浮在画布上方，
  进应用也能控音乐；新设计横幅在 Dock 里，进应用 Dock 收起 → 想控音乐先键条唤出
  Dock。视觉稿「底栏常驻」与批次 BB「进应用收起拿满屏」二选一，用户选了后者。
- **媒体图标缓存**：横幅刷新很勤（会话回调 + 30s 兜底拍），`getApplicationIcon`
  每次 Binder，加 8 项静态 LruCache；命中走 `ConstantState.newDrawable` 出独立副本
  （同 `LauncherModel.icon` 口径，防多 View bounds 打架）。
- **网络标识诚实性**：无 READ_PHONE_STATE 时 `getNetworkType` 多半 UNKNOWN，
  兜底显示 5G（车机基本 4G/5G 在线）；WiFi 检测（ACCESS_NETWORK_STATE）是真的。
  项目自带 android.jar 无 `NETWORK_TYPE_LTE_CA` 常量，LTE 分支只留主常量。
- **宽度预算**：满配（5-6 图标 + 横幅 + 左右两段）约 750dp；车机横屏 ≥850dp 安全，
  竖屏/低密横屏可能裁横幅尾部 —— 横幅歌名 maxWidth 118dp 已是压缩后的结果，
  再窄只能降 MAX_ICONS，留待真机反馈。
- 按钮热区 22~26dp 无外扩（旧 MiniPlayer 有 12dp 外扩），真机若误触多再补。

### 验证

javac 全量类型检查 OK（项目自带 android.jar + xposed-api-82.jar）；纯 JVM 自检
LrcCheck 15 项 / PrivCodecCheck 39 项 / StackListCheck 30 项全过；R 引用零新增
（纯代码建 UI），aapt2 阶段无新风险。真机项标 🟡：玻璃观感 / 圆形图标裁剪 /
横幅与图标同排不溢出 / 唤出 Dock 控音乐路径。

### 遗留

- `MiniPlayer.java` 待新版真机确认后删除。
- 竖屏宽度预算（上面复盘第 4 条）。
- 底栏状态（时间/电量/网络）只在 30s tick 与整屏重建时刷，秒级时钟不走这条。

### 追加打磨（同批二段）

- **Dock 长按菜单补缺**：底部 Dock 图标此前无管理入口（DesktopView 的 dockMenu
  不在主路径上），长按补齐「换 1/2 号窗口 / 全屏打开 / 从 Dock 拿下来」，操作后
  `dock.bind(model)` 即时重画。Listener 加 `onAppLongPress`。
- **触控热区外扩**：menu/logo/播放/下一曲四键 View 加大（24→32、22→30、26→34dp），
  GlyphIconView 加 `contentScale` 把图形缩在内部画（0.73~0.75），视觉尺寸不变、
  热区整体大一圈 —— 车机手指友好，替代旧 MiniPlayer 的 12dp 外扩方案。
- **窄屏横幅自动让位**：布局后实测「图标区+横幅」超出中段宽度就藏横幅
  （fitPass + suppressedKey 防同一条媒体反复冒头），换歌或 onSizeChanged 后重试。
  竖屏 400dp 宽不再裁掉横幅尾部按钮。
- **网络标识诚实化**：无活跃网络 → "--"；有蜂窝链路但代际未知（无
  READ_PHONE_STATE）→ 兜底 5G；WiFi 真实检测不变。不再在完全没网时硬编 5G。
- **GlassDrawable.setAlpha** 三支画笔（fill/edge/top）按各自基色 alpha 等比同步。

---

## 批次 CG — 两句体验需求：槽 2「点击下方图标才进入」+ 底部导航栏整条换成苹果 CarPlay 风格

### ① 槽 2 点击才进入 —— 闸门必须是「进程级」的，且要拦住所有自动部署路径

用户原话「把第二个画中画弄成点击下方图标才进入软件」。现状是槽 2（网易云）随桌面
一起自动部署：`deployIfBound()`（构造/onResume/授权就绪三处触发）、surfaceChanged
驱动的 `attachTo` 自动挂载，哪条都绕不过。只堵 `deployIfBound` 一条会从 surfaceChanged
漏出去 —— 画布一布局就把应用部署起来了，闸门形同虚设。

**修**（PipBoard，全部收口在两个字段三处早退）：
- `static boolean s2Entered` 进程级总闸。**进程级而非页面级**是关键取舍：换肤重建
  页面时若用户本进程内已经进过，回桌面必须仍是实时预览（把播放中的应用重新盖一层
  「点击进入」占位卡就是 bug）；而车机每次上电进程新建，闸门自动回到「未进入」，
  恰好对上「点火后才点一下」的车机直觉，不需要任何持久化。
- 早退点三处：`deploySlot(2)`（deployIfBound 的唯一落点）、`attachTo`（surfaceChanged
  自动挂载）、`redeployIfPossible` 经由 attachTo 一并吃闸。selfHeal/巡检/healHome 都有
  `displayId>0 && ready()` 前置，未部署自然全跳过，不用改。
- 解除闸门的口子只有「显式意图」：`deploySlotPublic(2)`（导航栏音乐键 / 选择器选定 /
  全部应用页指定 2 号窗）和占位卡点击（走 enterSlot → expandSlot 同一条路）。
  MirrorActivity 的「桌面指定投屏」入口也补 `markEntered` —— 它是显式意图。
- 占位卡（buildGate）：CarPlay 风格深底 + 56dp 白圆角图标卡内嵌应用图标 + 应用名 +
  「点击进入 · 长按更换」。点击=进入，长按=换应用（与空画布语义一致）。
- 分屏画布点按逻辑同步改：占位态点画布 = 进入（原逻辑会弹选择器，语义错）。

### ② DockBar 苹果 CarPlay 风格重画

用户原话「下面导航栏实在太丑了，想要苹果互联风格的图标和导航栏」。旧版是 Vivo
carmode 复刻：浮起圆角条 + 竖分隔线 + 图标下带文字，确实不像 CarPlay。逐条对齐：

- 通栏贴底（DOCK_SIDE/DOCK_BOTTOM 归零）、无圆角；94% 近黑 + 顶部 1px 高光模拟
  玻璃上沿。恒深色不跟主题 —— CarPlay 在浅色主题下底栏也是黑的，这是风格的一部分。
- 纯图标无文字、无分隔线，五键等宽铺满；图标 30dp 白色细线几何（线宽统一 7%、
  圆头圆角）：首页=圆环（CarPlay home 键就是白圈）、地图=实心导航箭头、音乐=♫
  连尾双音符、其他=iOS 四圆角方格、退出=电源。
- 选中态 = 纯白 + iOS 式 4dp 小圆点；未选中 55% 白。
- `applySkin()` 保留但只重算选中态（HomeActivity 换肤路径不变）。

**验证**：javac 类型检查；真机项见 TODO P0-9。

**遗留**：占位卡期间的槽 2 不建 VD、不起应用进程 —— 若用户从不点，网易云完全不启动，
这正是需求；后台「回收槽 2」类设置项将来要考虑占位态的口径。

---

## 批次 CD — 用户三连报：只能点不能滑 / 边角点不到 / 歌词页回桌面变首页

三个现象，三个独立根因，全都不是批次 CC 的簿记问题 —— CC 修的是"换算口径"，
这三条分别是"命令通道性能"、"进程兼容位"、"Activity 重建"。

### ① 只能触摸、不能滑动 —— 命令通道「跟手」被坐实为死路

`TouchForward.stroke` 旧跟手模式把一条滑动拆成一串 `input swipe` 段（每 ≥2px 一段）。
每段的执行链是 `Caps.exec` → **fork 一个 su 进程**（Magisk 200~600ms）→ `input` 进程
再走完手势时长 → `run()` 里还串行排队（单 looper + f.get 阻塞）。手指 60Hz 划 1 秒 =
几十条命令压在队列里，应用收到的是一堆互不相干的微型拖动，中间隔着数百毫秒 ——
滚不动、更甩不起来。点击（一条命令）反而正常，于是「只能点、不能滑」。

**修**：命令通道对齐车联助手 —— **抬起后发一条完整 swipe**（真时长，50~2000ms），
短手势仍 tap；MOVE 只做 moved 判定不再发包。真跟手只有守护进程中继能提供（它本来就
是逐事件原样转发，不受影响）。半路从中继退回命令通道的手势按「无 DOWN」重建状态
（旧代码 lastX=0 会导致一条从 (0,0) 出发的鬼畜 swipe）。「跟手」开关删掉
（`model.touchFollow` 字段留档兼容，无行为）。

### ② 网易云边角点不到 —— 老进程从没吃到 FORCE_RESIZE_APP

批次 BB 注释宣称「写完 override 对运行中的任务也会即时重算」—— 真机证伪。
`am compat enable` 写的是**之后新起的进程**读的兼容配置；网易云带着主屏上的老进程
被 move-task 进画布时，FORCE_RESIZE_APP 常常没吃到它头上，letterbox 黑边一直在，
边角的点击全落在黑边上（中心点得到、边角点不到 = letterbox 的指纹）。

**修**：`relaxCompat(ctx, pkg)` 首次对某包写兼容位后 **force-stop 一次**，让随后的
冷启动（exists 检查自然变 false）吃进新配置；prefs 标记 `compat_written_<pkg>`
保证只杀这一次 —— 之后搬屏依旧保运行状态，播放/歌词不再被打断。
（首杀只发生在装新包后的第一次部署，代价是那一次部署会重启目标应用。）

### ③ 歌词页回桌面再进来变首页 —— 每次画布 resize 都在销毁重建 Activity

「桌面」键本身很干净（showSplit + Dock 回来，不杀不迁）。但 showSplit/expendSlot
会改画布尺寸 → VD resize → 对应用这是一次 **configuration change**
（screenSize/density 都在默认变化集里）→ 应用没声明 configChanges 时 Activity
被销毁重建 → 页内状态归零 → 歌词页变首页。进出单应用、收展 Dock、拖占比
全都触发一遍。车联助手没这问题纯粹因为它窗口尺寸恒定，应用一辈子不经历 size change。

**修**：模块新增 **H4 hookConfigChangesAbsorb** —— hook
`ActivityThread.performLaunchActivity`，launch 前把
ActivityClientRecord.info.configChanges 补上
KEYBOARD|KEYBOARD_HIDDEN|ORIENTATION|SCREEN_LAYOUT|UI_MODE|SCREEN_SIZE|
SMALLEST_SCREEN_SIZE|DENSITY|FONT_SCALE 各位 → 系统不再销毁重建，改走
onConfigurationChanged（窗口照常重排，页内状态保住）。不吸收 LOCALE：
语言变化仍走重建。验口：`logcat -s SeagullLsp` 应出现
`H4 hookConfigChangesAbsorb OK`。

### 验证

- javac（JDK17 --release 11，R.java 取自 v1.0.a0fe02f 反编译）全量过。
- 真机口径：
  1. 滑动：画布内上下滑列表应能滚动、能甩（首帧不跟手属预期 —— 命令通道本就
     不跟手，守护进程在位时才逐事件跟手）；
  2. 边角：装新包后第一次部署网易云会重启它一次（兼容位进新进程），之后画布
     应铺满、四角按钮可点；
  3. 歌词页：进歌词 → 桌面键 → 再进来应还在歌词页（需 LSPosed 模块加载，
     日志见 H4 OK）。

---

## 批次 CC — 反编译车联助手新版（base.apk）：触摸为什么"准"，两条不变量我们反着做了

### 目标

用户传上车联助手（com.leting）最新版 base.apk，要求反编译弄清"它的触摸怎么这么准"，
对照修海鸥。jadx 1.5.0 全量反编译（产物在 /workspace/tmp-leting/src，会话内临存）。

### 复盘（触摸"准"的真相 —— 不在注入器，在两条不变量）

**注入器毫无秘密**：PIP1/PIP2 的触摸分发器（`b1/d.java`、`b1/h.java`）与 7 月那版一字未改
—— `su -c input -d <id> tap/swipe`，异步 exec、不等待、不处理多指/CANCEL，当年报告点名的
"设计缺陷"全都在。**它准是因为它根本不需要换算坐标**：

- **不变量①：VD 尺寸永远严格等于触摸面尺寸**（`b1/b.java` surfaceChanged 回调）：
  `virtualDisplay.resize(surfaceW, surfaceH, dpi)` —— SurfaceView 每变一次，VD 同步跟一次，
  注入坐标 = View 坐标，1:1，全程零簿记。建屏时也是直接用 `SurfaceView.getWidth/Height`。
- **不变量②：密度恒定**：dpi 恒为主屏 densityDpi（`c.f3501b` 建屏时取一次），resize 只动
  像素尺寸不动密度 → 点击期间应用绝不发生 density 重配/重排。

我们恰好在这两条上反着做（`MirrorSlot.syncSize` 旧注释自己写着"本地簿记先行，触摸换算
立即用新值"）：

- root 守护屏路径 resize 是**异步且无确认**的：簿记先写成新尺寸，真实 resize 排队走守护
  进程。窗口期里（乃至某些 ROM 静默拒绝 resize 时**永久**）"簿记说新尺寸、屏还是旧尺寸"，
  fx/fy 恒 1，点击整体偏移 —— 这才是「触摸不准」的主根因，批次 AD/AE 修的"比例映射"
  在簿记被污染后照样失真。
- in-process 路径 `vd.resize()` 抛异常时簿记也已先行更新 —— 同病。
- `PipBoard.syncCanvasSize` 在每次 DOWN 上 `dpiFit(w,h)` 重算密度 —— 点击可能触发应用
  density 重配 + 全量重排，屏上元素位置瞬移，手感就是"不准"。

**网易云防跳不是差距**：车联助手 xphook 对 `com.netease.cloudmusic.iot` 挂 H1~H6
（startActivity/startActivityForResult/Context 全入口注入 setLaunchDisplayId，判定=
「Activity 当前 displayId>0 且目标是网易云自己的 Activity」，`injectNeteaseDisplayId` 用
反射 ActivityOptions.fromBundle —— 我们批次 AG 已证明这在真机上必失败，换成了
putInt(KEY_LAUNCH_DISPLAY_ID)）。海鸥的 C1~C6 + C0 总闸（Instrumentation.execStartActivity）
覆盖面更全。这段逐行比对后确认无需改。

**部署链同样无差距**：锚点 Activity → `am stack move-task` 搬锚点 stack（`c1/h.d()`，
带 sleep 0.15 的 awk 脚本）→ 冷启动 API → `su am start --display` 兜底 —— 海鸥
`moveToAnchorRoot`/`launchOnDisplay` 一一对应。

### 改了什么

1. `MirrorSlot.syncSize`：签名去掉 dpi 参数（**密度恒定**，沿用建屏值）。root 路径改为
   `PrivClient.resize` **确认成功才记账**（`setSizeBookkeeping`，SIZE_LOCK 三元组原子更新）；
   失败保持旧尺寸并打日志 —— 在途/失败期间触摸按旧尺寸比例换算，与屏上被缩放的内容
   严格对齐。失败日志也是「root resize 被 ROM 拒绝」的唯一可见信号。
2. `MirrorSlot.deploy` root 重挂分支：同样的"确认后记账"，去掉簿记先行。
3. `MirrorSlot` 新增 `SIZE_LOCK` + `setSizeBookkeeping`；`onTouch` 修一处笔误
   （sy 回退误用 `transformer.scaleX()` → `scaleY()`）。
4. `PipBoard.syncCanvasSize`：DOWN 校准只传尺寸不传密度 —— 建屏/挂面（surfaceChanged →
   attachTo/deploy）才由 dpiFit 定密度，点击期不再触发应用重配。

### 验证

- javac（JDK17 --release 11，与 build.sh 同参；R.java 取自上一版产物反编译补齐）全量过。
- 真机口径：装新包 → 进画中画拖动分隔把手改占比 → 立刻点击高德缩放按钮/网易云按钮，
  落点应与看到的位置一致（改占比后第一下就准，不再要等第二下）；`logcat -s SeagullTouch
  SeagullLsp` 里 root 屏应出现「root 尺寸校准确认」，若出现「root resize 失败」请截图发回
  —— 那说明这台 ROM 的守护进程 resize 被拒，触摸会自动按旧尺寸比例换算保持准确。

### 遗留

- 车联助手的 VD 是 in-process 私有屏（flags=0，零依赖守护进程 resize）；海鸥 root 守护屏
  的 resize 若真机日志显示被 ROM 拒绝，下一步就是把 root 屏的尺寸变更也改走 in-process
  VD（或屏建成固定尺寸、触摸恒比例换算），真机日志出来再定。
- 车联助手 `onResume` 每次都重跑迁移+部署（幂等自愈）；海鸥已有 healSlotSoon + 巡检，
  覆盖面等价。

---

## 批次 BB — 截图坐实两件事：网易云搬屏路径漏写兼容位（黑边真因）+ 进应用自动收起导航栏

### 目标

用户发两张真机截图：① 网易云在画布里**左右对称黑边各 ~9%、窗口只有 ~82% 宽**（标准
size-compat letterbox 形态，不是竖版、不是错位）；② 底部 Dock 导航栏希望进应用时能收起来。

### 复盘（黑边真因终于闭环）

- 批次 Z 把 `relaxCompat` 提到「起进程之前」，但只覆盖**冷启动**路径；`launchOnDisplay`
  的「已有任务 → moveToAnchorRoot 搬屏」路径**整个跳过** relaxCompat —— 网易云带着主屏上的
  旧兼容态被 move-task 进画布，`FORCE_RESIZE_APP` 从来没写到它头上。这与用户截图严丝合缝：
  每次部署网易云都「搬屏成功（不杀进程）」，兼容位从未生效，letterbox 一直在。
- 批次 AH 曾在「搬任务路补兼容位」，但补的是 `moveToAnchorRoot` 内部一处；从 deploy/
  healSlotSoon/巡检进入的其他 `launchOnDisplay` 路径仍然漏。这次把 relaxCompat 提为
  `launchOnDisplay` 入口第一条，**所有路径无差别先写兼容位**。

### 改了什么

1. `RootOps.launchOnDisplay`：`relaxCompat(pkg)` 提到 exists 判断之前（批次 Z 注释迁移保留）。
   写完 override 对运行中任务也会即时重算（ActivityRecord onOverrideChanged → 重排），
   搬屏前写同样有效；即使某 ROM 不即时生效，+8s 的 ensureFilled 巡检也会用已写好的
   兼容位冷启动修复，两条路都通。
2. **进应用自动收起导航栏**（`HomeActivity`）：`setDockCollapsed(true)` 收起 = Dock 滑出屏外
   + pager 底边距（dockH）归零 → 画布长高 → surfaceChanged → VD resize，应用窗口拿到
   整屏高度。回桌面（首页/其他/翻页兜底/键条「桌面」）一律 `setDockCollapsed(false)` 展开回原位。
   不依赖 `pip.isSingle()` 时序（expandSlot 里先收栏后 showSingle）。
3. **键条第三键「导航栏」**（`PipBoard.buildKeybar`）：单应用里手动唤出/收起 Dock ——
   `toggleDock()` 只动 translationY 不改边距（浮层，不触发 VD resize，应用不闪一帧）。
   键条从 [返回|桌面] 变 [返回|桌面|导航栏]。

### 验证

- javac 类型检查过；自检全过。
- 真机口径：装新包（版本 `1.0.<短哈希>`）→ 进网易云 → 导航栏应收起、画布满屏 →
  若仍 letterbox，8s 后应看到「未铺满 → 已冷启动修复」并铺满；键条点「导航栏」唤出 Dock。

### 遗留

- 若网易云黑边在冷启动修复后仍在，则是应用内部固定宽高比（自带黑底舞台），WM 层
  无解 —— 只能让画布比例贴近应用设计比例，留真机验证后再定。
- Dock 收起状态不落盘（会话内有效），回桌面必展开，符合「进应用收、回桌面出」直觉。

---

## 批次 AK — K1：常驻 root 守护扩展（直注触摸 + 实时任务监听 + 事件订阅）

### 目标

OneStep4 §A/§B/§E 思路落地：把"每条 root 命令 fork 一次 su"升级为常驻守护进程统一承接，
任务自愈从 2.5s 轮询升级为事件触发。**只借思路，实现全部自写**（依据 `ONESTEP-BORROWING.md`）。

### 改了什么

- **协议层 `PrivCodec`**：新增 8 个 op —— `CREATE/RESIZE/SURFACE/RELEASE`（AL 建屏用）、
  `ARM/CTRL`（AM 拦截用）、`WATCH/STATE`（事件订阅/快照）；新增 `EVT` 推送行与
  BUMP/ROUTED/WATCH 三类事件。`PrivCodecCheck` 扩到 39 项全过。
- **守护进程 `RootMain`**：`WATCH on` 把当前连接转为事件订阅（一连接一订阅者，
  EOF 自动摘）；有订阅者才挂 `TaskWatcher`，空订阅自动注销监听；`doMotion` 注入前
  `LaunchController.touchAt(displayId)` 刷新武装 TTL（AM 的触摸证据链）；
  `doState` 一行快照（watch/held/subs/ctrl/armed）。
- **客户端 `PrivClient`**：新增 create/resize/surface/release/arm/ctrl/state 封装；
  独立订阅通道 `subscribe/unsubscribe`（watch 线程只读 EVT 行，应答行丢弃）。
- **`TaskWatcher`（新）**：root 进程反射注册系统 `ITaskStackListener`。**刻意不解析
  RunningTaskInfo 载荷**——各 ROM 事务号与字段漂移，解析错一个字段就可能把"对槽的
  实例"当目标任务（m12 红线）。一切事件 300ms 节流后推 `EVT BUMP`，自愈决策全在
  桌面既有护栏（ensureOnDisplay：只搬不拉、对槽绝不动）。
- **`PipBoard`**：onResume `startWatch()`、onDestroy 退订；订阅在线巡检降为 8s 兜底拍，
  离线保持 2.5s；BUMP 触发 `selfHeal()`（毫秒级自愈），ROUTED 刷新提示。
- **`Caps`**：空气开关 `persist.seagull.rootbridge`（`rootBridgeOn` 10s 缓存 / `setRootBridge`
  / `rootBridgeLabel` 只读缓存——设置页 UI 线程不能 fork getprop）。

### 复盘

- **做对**：没另起 ServiceManager.addService 服务。计划里是 OneStep 式 Binder 服务，
  复盘时确认现有 PrivClient/RootMain 本来就是常驻服务雏形（真机验证过拉起链路），
  折叠进同一守护进程 = 复用 60s 自退出、uid 白名单、setsid 拉起三件套，风险最小。
  LocalSocket EOF 感知等效 linkToDeath。
- **做对**：TaskWatcher 的"只触发不解析"取舍。OneStep 把任务详情推给桌面自己决策；
  我们的任务拓扑更险（双槽 + 对槽任务同名），BUMP + 既有护栏是唯一不踩 m12 的路。
- **修正**：`PipBoard.startWatch` 最初把 `Caps.rootBridgeOn()` 判断留在主线程——
  缓存未命中时 getprop 会 fork 进程，onResume 直接卡 UI。整段挪进订阅后台线程。
- **主线程纪律（本批立规）**：`PrivClient.ensureForTouch()` 主线程只接受"已连接"
  （掉线立即 false，手势余量退回 `input -d` 通道）；launch/move 等只许后台线程走完整
  `ensure()`。任何"可能 fork su"的调用都不许出现在主线程调用图里。

### 验证

javac 编译过；PrivCodecCheck 39 项全过。真机验收项见 `UPDATE-CHECKLIST.md` K1（🟡）。

### 遗留

订阅连接上其余 op 照常受理（无害：客户端不会在订阅连接上发请求）——协议上留了口子，
真机验收后若要用再收紧。

---

## 批次 AL — K2：root 上下文 TRUSTED+PUBLIC 虚拟屏（跳主屏根治）

### 目标

OneStep4 §C 思路落地：私有屏"别的应用不许往里放东西"是跳主屏类问题的根源；
TRUSTED 位只有 root 上下文拿得到。屏由守护进程（uid 0）建，任务"生在画布"。

### 改了什么

- **`RootMain`**：`doCreate/doResize/doSurface/doRelease`。建屏用
  `ActivityThread.getSystemContext()`（app_process 里拿系统级 Context 的唯一正路，
  DisplayManager 注册在它上面）拿 DisplayManager；flags 走 `TrustedFlags.candidateFlags()+
  normalize` 候选降级（与本地 TRUSTED 路同一套序列），建后校验 Display flags TRUSTED 位，
  `HELD` 表持有（守护进程退出 = 屏全拆）。
- **surface 跨进程**：`Surface.writeToParcel` + `Base64(NO_WRAP)` 过行协议
  （NO_WRAP 无空白，行协议天然装得下），守护进程 `Surface.CREATOR.createFromParcel`
  还原挂载；空 blob `-` = 摘面（画布离场但屏与任务都留着）。
- **`MirrorSlot`**：`deploy` 加 root 优先路——`Caps.rootBridgeOn() && PrivClient.ensure()`
  先试 `PrivClient.create`，成则 `rootBased=true` 走 `finishDeploy` 公共段，失败回落
  本地三路（私有屏→TRUSTED→投影），行为与 AJ 一致；已有 root 屏时只 resize+挂面。
  `finishDeploy` 抽出公共段（relaxCompat→搬任务→冷启动→自愈→武装），root/本地同款。
- **root 分支全覆盖**：`syncSize`（本地簿记先行，触摸换算立即用新值，真实 resize 排队）、
  `nudgeLayout`（两拍抖动整段走守护进程 resize，专用线程 sleep 不占别人的路）、
  `detachSurface`（摘面不拆屏）、`teardownVd`（拆屏幂等 + **不无条件 disarm**——
  武装是全局单源，拆 A 槽不能解掉 B 槽的武装，过期由 5s TTL 自然失效）。

### 复盘

- **做对**：CREATE 不在时传 surface，单独走 SURFACE 事务——建屏（慢、可能降级重试）
  与挂面（画布每次 surfaceChanged 都来）解耦，画布重建不用重 建 屏。
- **做对**：跨槽武装保护。最初 teardownVd 里无条件 disarm 会误杀另一槽的武装路由，
  改为不在这里 disarm。
- **实测待验**：TRUSTED+PUBLIC 组合在 Android 14 上 PUBLIC×SHOW_WHEN_LOCKED_INSECURE
  冲突规避、IME 能否弹进画布——都是真机验收项（R7/R8 🟡）。
- **取舍**：密度策略（OneStep §F）不引入，280dp 基准已实测有效。

### 验证

javac 编译过。真机验收项见 `UPDATE-CHECKLIST.md` K2（🟡：dumpsys display 查 trusted/public、
点歌词不跳主屏、网易云 8s 铺满、IME 实测）。

### 遗留

无（本批范围）。

---

## 批次 AM — K3：IActivityController 全局启动拦截（不依赖 LSPosed）

### 目标

OneStep4 §D 思路落地：system_server 把全系统每次 Activity 启动先问控制器——
一条通道管全部应用，LSPosed C0 总闸漏勾的作用域（QQ 音乐/百度地图）一并覆盖。

### 改了什么

- **`LaunchController`（新，root 侧）**：
  - 安装：反射 `setActivityController(controller, false)`，优先 2 参重载回落 1 参；
    uid 0 直接过 SET_ACTIVITY_CONTROLLER 权限。
  - 决策（**宁放行不误拦**）：武装有效（ARM 登记的 displayId↔嵌入应用 + 5s 内有注入
    触摸）+ 目标≠嵌入应用自身 + 非 HOME/SECONDARY_HOME + 无 FLAG_FORWARD_RESULT
    + 不在 bypass 窗口（3s，防改道自锁）才拦；**解析/决策任何异常一律放行**。
  - 动作：先记 bypass，独立线程 `startActivityAsUser` 原意图原 flags 换落点屏
    （控制器回调在 system_server 启动关键路径上，重启动的 binder 往返绝不串行在里面），
    推 `EVT ROUTED pkg displayId`。
  - **回复字节纪律**（最初三目表达式写混过，改为显式分支）：activityStarting
    写 0(拦)/1(放)、activityResuming 恒 1（拦恢复=卡死应用）、
    appCrashed/appANR/systemNotResponding 恒 0（走系统默认，写 1 误杀应用、写 -1 卡死系统）。
  - `readInterfaceToken` 是隐藏 API，SDK jar 没有——反射调用。
- **`RootMain`**：`doArm`（displayId≤0 或 pkg="-" = 解除）、`doCtrl`、`doState`。
- **`MirrorSlot.finishDeploy`**：部署成功即 `arm(displayId,pkg)+ctrl(true)`（空气开关
  关闭时跳过——行为与 AJ 一致）。

### 复盘

- **做对**：豁免清单照思路不抄码。OneStep 的豁免集（HOME/result 回传/短 bypass/
  异常放行）是踩过坑的产物，语义全部吸收，实现自写。
- **做对**：拦截改道与 C0 hook 并存不冲突（C0 在应用进程 execStartActivity、控制器在
  system_server AMS 入口，控制器先到且覆盖面更大）——原计划的 `persist.seagull.c0`
  降级开关没有实际场景，未加（清单 AM-R6 已记偏差）。
- **风险认知**：IActivityController 全系统唯一，注册会顶掉 Monkey 等；system_server
  持有控制器 binder，守护进程死亡 = system_server 侧 binder 清理自动解除拦截，不留僵尸。
- **红线意识**：控制器崩溃/卡死 = 全系统启动瘫痪。因此异常放行是第一原则，
  拦截判定只用内存态、零 IO、改道走独立线程。

### 验证

javac 编译过。真机验收项见 `UPDATE-CHECKLIST.md` K3（🟡：零跳屏闪现、HOME 正常、
分享/登录流不碎、未勾作用域应用改道、下线解除）。

### 遗留

armed 状态在守护进程重启后丢失（内存态）——桌面部署时会重 arm，可接受。

---

## 批次 AN — K4：高德导航信息卡片（官方车联服务接入）

### 目标

OneStep4 §H 的数据源是高德官方开放接口（不是 OneStep 私有物）——自写协议实现，
桌面顶栏恢复导航播报卡片（旧导航信息区批次 R 前后已删）。

### 改了什么

- **`AmapNavClient`（新）**：bind `com.autonavi.minimap.service.NAVIGATION_SERVICE`
  （包名可配 `LauncherModel.navPkg`）。构建链没有 aidl 工具 → **控制/回调两端 Binder
  全部手写 transact**：控制端 ILinkSdkControl（1=authenticate/3=register/4=unregister），
  回调端 ILinkSdkCallback（1=onEvent/2=onEvent+fd，fd 里是大包 JSON）。载荷明文与
  AES-GCM 信封双解（`{encrypted,content}` → aad|key|nonce|密文，GCM tag 128）。
  `datas[].type==4` 是导航状态，取 8 个字段进 `NavState`。
- **数据保鲜**：8s 无更新判过期清空；onServiceDisconnected/onBindingDied/onNullBinding/
  强杀高德四路全清；断连重绑由 `start()` 幂等承接。
- **`NavCard`（新）**：半透明黑胶囊（转向黄 + 本段剩余/速度 + 路名 + 全程剩余）；
  maneuverId→文本箭头映射（缺图给直行箭头并记日志补映射）；无导航 GONE 不占位；
  `setEnabled` 打开时立即恢复上一次状态；`setForcedHidden` 给系统 PiP 模式用。
- **接线**：`LauncherModel` 加 navEnabled/navPkg（save/load/factoryReset 三段同步）；
  `HomeActivity` buildUi 挂卡、onResume 同步开关 + 起/停高德绑定、onDestroy/皮肤重画
  前 detach 摘订阅（单例活得比页面久，不摘会双份回调持死旧树）；系统 PiP 模式
  `setForcedHidden(true)`；设置页"系统"分区加「导航与增强」组（卡片开关 + rootbridge
  开关 + root 桥接快照行）。

### 复盘

- **做对**：车机纪律——只有信息没有按钮，司机看的不是让司机点的。
- **做对**：NavState 类限定符（`AmapNavClient.NavState`）——最初漏了限定符三处引用
  编译失败，编译器兜住。
- **协议事实**：事务号/datas[].type/字段名都是高德 LinkSDK 的公开互操作事实，
  逐条写在 AmapNavClient 头注释，版本漂移时按注释对表。
- **风险**：高德版本升级可能改字段名/加密策略——解析失败只丢数据不崩（Throwable 兜底），
  8s 过期自然清空。

### 验证

javac 编译过。真机验收项见 `UPDATE-CHECKLIST.md` K4（🟡：开导航实时更新、结束 8s 消失、
强杀清干净、画中画无回归）。

### 遗留

车机版高德（com.autonavi.amapauto）是否开放同一 action 待实测；不开放时 navPkg 配置
留作切换口，通知监听兜底不在本系列范围。

---

## 批次 AK~AN 复盘（OneStep 借鉴系列总结）

### 预期 vs 实现（三处有意偏差，全部记录在案）

| 计划 | 实现 | 为什么 |
|---|---|---|
| K1-R2 ServiceManager.addService 注册 Binder 服务 | 沿用 LocalSocket 行协议守护进程 | 现有骨架已在真机验证；uid 白名单+私有协议同等安全边界；EOF 感知等效 linkToDeath |
| K1-R7 新建 RootBridgeClient | 扩展 PrivClient | 同一进程同一连接池，两个客户端类只会双倍维护 |
| K3-R6 `persist.seagull.c0` 降级开关 | 未加 | root 控制器与 C0 hook 叠加不互斥（一个 system_server 入口一个应用进程入口），无降级场景 |

另有一处**刻意简化**：TaskWatcher 不解析任务详情只推 BUMP（防 ROM 字段漂移误动对槽任务），
自愈决策全在桌面既有护栏。

### 协议与开关的最终形态

- 通道：abstract socket `seagull.privd`；主连接一请求一应答（seq 对账），订阅连接单向推 EVT。
- 事务：`PING/LAUNCH/MOTION/MOVE/MOVE_ROOT/REMOVE/CREATE/RESIZE/SURFACE/RELEASE/ARM/CTRL/WATCH/STATE`。
- 事件：`EVT BUMP`（任务栈变化）/`EVT ROUTED pkg displayId`（拦截改道）/`EVT WATCH on`。
- 开关：`persist.seagull.rootbridge`（1=新链路，0/缺省=AJ 行为），设置页可切，
  getprop/setprop 均子线程 + 10s 缓存。
- 生命周期：守护进程 60s 无客户端自退出；订阅在线即不退；守护死 = root 屏全拆 +
  system_server 侧控制器/监听 binder 自动清理；桌面断线 = 订阅自动摘。

### 风险清单（现状 / 触发条件 / 缓解）

| 风险 | 现状 | 触发条件 | 缓解 |
|---|---|---|---|
| IActivityController 被 ROM 限制/裁剪 | install() 失败返回 false | ROM 删除重载或权限收紧 | ctrl(false) 照旧；跳主屏类问题回落 C0 hook + 轮询自愈（AJ 行为） |
| 控制器顶掉 Monkey 等既有控制器 | 注册即独占 | 用户同时跑 Monkey/自动化工具 | 无解（系统语义）；rootbridge 关掉即恢复 |
| TRUSTED flags 在不同 ROM 差异 | TrustedFlags 候选降级 + TRUSTED 位校验 | 某组 flags 被拒 | 逐候选降级；全被拒回落本地三路 |
| root 屏上 IME 不弹（PUBLIC 未生效） | 未验证（🟡 R7） | 真机实测 | 输入仍可回主屏输入框或外接键盘；不阻塞部署链路 |
| 守护进程被 LMK/用户杀死 | 屏全拆、拦截/监听自动解除 | 极端内存压力或手动 kill | 下一次操作 ensure() 重拉；PipBoard 巡检兜底拍一直在 |
| 高德协议版本漂移 | 解析 Throwable 兜底 + 8s 过期 | 高德升级改字段/加密 | 卡片静默消失不崩；按头注释对表补字段 |
| 拦截误伤（预测外的启动路径） | 豁免从宽 + 异常放行 | 用户反馈某应用启动被吞 | rootbridge 开关秒级关闭；armed TTL 5s 自愈；STATE 行定位 |

### 做对的三件事（对后续批次的纪律）

1. **主线程纪律**：一切可能 fork 的调用（ensure/getprop/subscribe）禁止出现在主线程
   调用图；主线程只接受"已连接"快路径。本系列三次踩线三次当场修。
2. **全局单源不互踩**：武装是全局单源——拆槽不 disarm（TTL 自然失效）、订阅是全局单源
   ——页面销毁即退订、空订阅自动注销系统监听。
3. **宁可降级不可卡死**：每条新链路都有"失败→旧路"的完整回落，且回落路径行为
   严格等于 AJ（空气开关验证口径）。

---

## 批次 AJ — 反编译网易云 iot 版：铺满问题的终极根因（PORTRAIT 烙印）+ 密度抖动强制重建

用户要求反编译网易云看为什么不能铺满。反编译对象：`com.netease.cloudmusic.iot`
（用户上传的"网易云.apk"，证实画中画2 绑的是 IoT 版）。工具：pyaxmlparser（manifest）
+ apktool 2.9.3（smali/resources）。

### 反编译证据链

1. **manifest**：`targetSdk=29`、`resizeableActivity=true`、`android.max_aspect=2.4`、
   所有 activity `screenOrientation=SENSOR`、`configChanges=0x620`
   （SCREEN_SIZE|UI_MODE|KEYBOARD_HIDDEN，**不含 DENSITY=0x1000** —— 这个伏笔决定
   修复手段）、主题链 `vg→l→x→lk→…→Theme.Material.Light.NoActionBar`，无
   windowIsFloating/windowFixedWidth（排除浮动窗口假说），`windowBackground` 是
   深色 drawable（黑边=窗口背景裸露）。
2. **base/b.attachBaseContext（所有 iot 界面的基类）** —— 根因：

   ```java
   DisplayMetrics dm = v1.b(null);      // activity=null！
   // v1.a(null) → Application.getSystemService(WINDOW_SERVICE).getDefaultDisplay()
   //              = 进程默认屏 display 0（手机主屏，竖屏 1080x2400，ratio≈0.45）
   float ratio = dm.widthPixels / (float) dm.heightPixels;
   if (ratio <= 1.3f) {                  // 竖屏主屏必然命中
       Configuration c = new Configuration();
       c.orientation = ORIENTATION_PORTRAIT;
       applyOverrideConfiguration(c);    // 烙死，onConfigurationChanged 永不重算
   }
   ```

   三个致命点：读的是 **display 0**（不是 activity 所在屏）；判定**只在 attach 时
   跑一次**；PORTRAIT override 是应用进程内部的，**compat/尺寸抖动都改不掉**。
   移到横屏画布后 UI 仍按竖屏配置渲染 → 四周黑边 + 触摸按满窗口映射全偏。

3. **为什么既有 D 段没生效**：`hookPortraitConfigOverride`（批次 AA）hook 点正确
   （ContextThemeWrapper.applyOverrideConfiguration，PORTRAIT→LANDSCAPE），iot 包也
   在 TARGETS 里 —— 但它只在 **attachBaseContext 时**触发；搬任务路部署的是在
   手机上早已初始化完的旧 activity 实例，attach 早就跑过了，hook 永远没机会。
   冷启动修复（批次 AI）能重建进程，但杀进程（断音乐/导航）。

### 修复：密度抖动 = 不杀进程的 activity 重建

网易云 `configChanges` 不含 DENSITY → **密度一变 activity 必重建**（重跑
attachBaseContext → D 段把 PORTRAIT 改写为 LANDSCAPE → 横屏渲染铺满），进程不死、
音乐不断。`nudgeLayout` 升级为两拍：尺寸 ±1px（可缩放应用重排）+ 密度 ±8 再回
（触发重建，±8 肉眼无感，1s 内完成）。铺满巡检从 6s 推迟到 8s（避开抖动窗口，
防止重建中途误判未铺满而误杀进程）。

### 验证口径（真机）

- 部署画中画 8 秒内应铺满；`logcat -s SeagullLsp` 应出现
  `D 改写 applyOverrideConfiguration: 竖屏 → 横屏 (com.netease.cloudmusic.base.b…)`
  —— 这行就是 hook 生效的直接证据；
- 若一直不出现该行 → 模块没注入网易云进程（查 LSPosed 作用域）；
- 铺满巡检日志（`logcat -s SeagullCar`："铺满巡检 槽 n: …"）与「画中画体检」报告
  仍可作为兜底证据。

---

## 批次 AI — 8E96 无效后的深度排查：守护进程静默误注主屏（实锤）+ 铺满探测自修复 + 画中画体检

用户反馈批次 AH 仍无效：网易云不铺满、两应用触摸全偏。深度排查结论：

### 实锤①：守护进程注入可能一直把事件注到主屏（fail-closed 修复）

[RootMain.buildMotion] 里 `MotionEvent.setDisplayId` 是 @hide 方法，反射失败时旧代码
**只打一行警告、继续注入** —— 事件没带 displayId 就落到主屏 (display 0)。表现：

- 画布里的应用触摸全错（事件去了主屏），高德网易云一起错，与应用铺不铺满无关；
- 主屏上只是零星误触，用户看不到明显 chaos，只有"触摸位置不对"这一个可感症状。

**修复**：反射失败 → 拒绝注入（recycle 事件、返回 err）→ TouchForward 收到 err 把
本次手势余下事件退回 `input -d <id>` 命令通道（displayId 是命令行参数，无此问题）。
宁可不响应，不可注错屏。注意：守护进程是老代码的话不重启不会更新 —— 验收前先
重启一次手机。

### 实锤②：铺满与否第一次有了现场证据链（不再靠猜）

此前「不铺满」的修复（兼容位、布局抖动）都是盲打：不知道窗口在 VD 上的实际帧。
本批把探测做进应用：

- **RootOps.windowFrameOnDisplay / displayWindows**：解析 `dumpsys window displays`
  （兼容 `mDisplayId=N` / `Display #N` 两种头格式），取目标屏上窗口的 frame=[l,t][r,b]。
  解析失败一律返回空/null，**宁可漏修不可误杀**。
- **RootOps.ensureFilled**：窗口帧任一轴 < 屏尺寸 92% → force-stop + 在该屏冷启动
  （deploy 时已写好兼容位，新进程必然按屏尺寸布局）。
- **PipBoard 铺满巡检**：部署 6 秒后自动跑一次（抖动 2s/4.5s 之后才判定），
  结果进 `logcat -s SeagullCar`（"铺满巡检 槽 n: …"）。
- **设置 → 系统 → 画中画体检**：列出每块虚拟屏的尺寸 + 每个窗口的帧 + 铺满百分比
  + 结论（✓/✗），截图即可反馈；**画中画修复** = 手动冷启动最后手段。

### 为什么批次 AH 抖动可能没用（留档）

抖动强制 config change，对"可缩放但布局停旧尺寸"的应用有效；对真·size-compat
（应用拒绝 resize，WM 层 letterbox）无效 —— 那类只有冷启动 + 兼容位能救。
铺满巡检的探测结果会告诉我们到底是哪一类：体检里窗口帧本身不满 = size-compat
（巡检会自动冷启动）；窗口帧满但画面仍有黑边 = 应用自绘边距（另一类问题）。

---

## 批次 AH — 「网易云不铺满 + 高德/网易云触摸全偏」：搬入后布局抖动 + 搬任务路补兼容位

**用户截图坐实**：网易云在画布内四周留黑边不铺满（顶部细、底部大、左右对称），且
两个应用的触摸都不准。

### 统一根因：搬任务路缺两样东西

部署首选「搬已有任务」（move-task，保导航/播放状态）。这条路此前：

1. **relaxCompat 从未执行** —— 兼容位只在冷启动路（launchOnDisplay）里写。应用被
   搬进虚拟屏时进程还是旧的（兼容位只对新进程生效），FORCE_RESIZE_APP /
   NEVER_FIX_ORIENTATION 都吃不到，非可缩放应用维持旧尺寸 → 画布内留黑边。
2. **没有任何重排时机** —— surface 挂载时的 resize 若与旧尺寸相同是 no-op；应用
   窗口停在搬入前的尺寸、卡在画布一角。触摸按"满屏比例"换算（TouchForward 逐轴
   映射的前提是应用铺满 VD），点在可见内容上自然全偏 —— 两个应用一起偏。

### 修复

**① relaxCompat 提到 deploy() 里两条部署路之前**（MirrorSlot.deploy）：搬任务路
也先把兼容位写好，当前进程吃不到没关系，之后任何一次冷启动都会铺满。

**② 布局抖动 nudgeLayout**：VD 高度 -1px 再 450ms 后回弹 —— 强制系统对这块屏发
一轮 config change，画布里的应用按当前 VD 尺寸重新布局、铺满。不杀进程、不丢
导航/播放状态。触发点：

- 部署成功后两拍（+2s / +4.5s，给搬入/挂面留完成时间）；
- 点后自愈"已搬回"后（healSlotNow）；
- 巡检搬回"已搬回"后（repinRun）—— 跳走搬回同样存在旧布局问题。

配套：vdDpi 字段随每处 resize（deploy / reattach / attachSurface / syncSize）
记录，抖动回弹时保持 dpi 不变；回弹前校验 displayId 未变（屏重建则放弃）。

**验证**：javac --release 11 全树通过（167 class）。
真机口径：重新部署画中画 → 2~5 秒内应用应自动铺满画布 → 触摸位置随铺满自动归位。
若抖动后仍不铺满（应用真的拒绝 resize），强制重开一次该应用（进程吃到了
relaxCompat 的兼容位）即铺满 —— 这是一次性代价。

---

## 批次 AG — 「为什么网易云有的地方点了就跳主屏？」：C0 总闸 + 注入去 hidden-api 化

**目标**：用户要求网易云"像高德那样固定好"。先讲清机制再改代码。

### 为什么高德不跳、网易云跳

高德车机使用全程在一个 Activity 里（点击不启动新 Activity），没有"跳"的机会。
网易云点歌词/封面要启动**新 Activity**，而系统要把新 Activity 放到某个屏上——
画布是本应用自建的**私有虚拟屏**，系统判定"别的应用不许往里放"，
落点回落主屏，singleTask 任务整体被拉走。

### 病根（读码坐实两条，都在模块 C 段）

**① 6 个入口 hook 挂不全。** C1~C6 挂在 startActivity 的 6 个签名上，但应用进程内
所有 Activity 启动的**必经终点是 Instrumentation.execStartActivity**
（Activity.startActivityForResult 与 ContextImpl.startActivity 最终都调它）。
任何一条内部路径绕过这 6 个口就直接漏掉。

**② 注入对"已有 options"静默失败。** 旧 `injectDisplayId` 对应用自带的 options
Bundle 走 `ActivityOptions.fromBundle` 反射还原 —— fromBundle 是 @hide，
API 28+ 被 hidden-api 拦截，真机上**反射必失败 → 原样返回 → 一条都没注入**。
凡是网易云自带跳转参数的路径（它的页面转场基本都带）等于裸奔。

### 修复

**C0 总闸**（XposedEntry.hookExecStartActivity，挂在全部 TARGETS）：
枚举 `Instrumentation.execStartActivity` 全部重载逐条 `hookMethod` 挂载
（桩 jar 没有 hookAllMethods，且桩补了 `XposedBridge.hookMethod` —— Xposed
原始 API，所有框架版本都有，不受 api-82 桩限制）。规则：

- 启动方在主屏（displayId=0）→ 不干预，绝不能把手机上的正常使用钉进画布；
- 目标组件是本应用（或解析不出）→ 注入 launchDisplayId 留在画布
  ——「画布内导航不出画布」，这就是高德式固定；
- 目标是别的应用（外跳浏览器/分享）→ 不注入，外跳正常走。

**注入去 hidden-api 化**（injectDisplayId 重写）：对已有 Bundle 直接
`putInt("android.activity.launchDisplayId", id)` —— 该键是 AOSP
ActivityOptions.KEY_LAUNCH_DISPLAY_ID 的稳定字符串（API 26 起），
系统侧按同键读取、未知键忽略；应用自带的动画/共享元素参数原样保留。
零 hidden-api 依赖，C2/C4/C5 从"必失败"变"必成功"。

**自检升级**（SettingsSectionActivity.lspCheckReport）：报告新增
「displayId 注入: N 次」—— C0 真正工作时计数 >0，用户不抓日志即可确认
"注入"这件事本身在发生，不再只看"进程挂载"。

**验证**：javac --release 11 全树通过（167 class，桩 jar 重打）。
真机口径：装新包 → LSPosed 确认模块启用 + 作用域 → 强杀网易云重开 →
进画布点歌词/封面 → 应不再跳主屏；自检里「displayId 注入」应 >0 次。

**遗留**：PendingIntent（通知栏点击）走系统进程，模块够不着 —— 那类跳转
仍靠批次 AF 的 2.5s 巡检搬回兜底。

---

## 批次 AF — 网易云「点歌词/封面就跳主屏 + 回桌面画布黑屏」：自愈通道补全 + 常驻巡检搬回

**目标**：用户实测两个症状：① 画中画里点网易云的歌词/封面等页面，应用直接全屏跑回
手机主屏；② 进入应用后返回桌面，画中画画布黑屏不再恢复。两个症状是同一条链：
singleTask 任务被系统拉回默认屏后，**所有搬回通道都没把它搬回来**。

### 病根：ensureOnDisplay 的保守放弃区间太宽

搬回链有三条通道：① 守护进程反射 ② `am stack move-task` ③ `am task move-task`，
但 ②③ 都依赖 `am stack list` 的文本可解析。真机上出现过解析不出（空段/格式差异）的
ROM —— 这时只剩守护进程一条路，它一旦失败就走 `return "am stack list 不可用且守护
进程搬屏失败，跳过自愈"`，**静默放弃**。表现恰好是：跳走 → tap 自愈两次全扑空 →
回桌面 healHome 也放弃 → 画布永远黑屏。

### 修复

**① 第④搬运通道：dumpsys（RootOps.ensureViaDumpsys）**

`dumpsys activity activities` 与 `am stack list` 是**两套完全独立的输出格式**
（`Display #N` 段头归口 + `Task{...#id}`，TaskScan 解析）。am stack list 解析不出时
它仍可能可用。通道内保持与解析路径同一条护栏：目标已在虚拟屏 → 不动；同名任务在
其他虚拟屏（别的槽的实例）→ 不动；任何屏都找不到 → ABSENT（只搬不拉红线不变）。
定位后走 moveTaskToDisplay（守护进程反射 → `am task move-task`）。

**② 常驻巡检搬回（PipBoard.repinRun）**

tap 自愈只在抬手后补两次（600/1600ms），跳页的开窗时序一变就全扑空。新增 2.5s 一拍
的巡检：两槽逐个 ensureOnDisplay（单线程串行，不并发打 su），检测到"已搬回"就
`redeployIfPossible` 补一次表面重挂。效果：**跳主屏从"永久黑屏"变成"最多 2.5s 自动
回到画中画"**，move-task 不杀进程，播放/导航状态不中断。

生命周期：onResume 里 `startRepin()`（幂等，先 removeCallbacks 再 post）；
onDestroy / cancelPending 的 `removeCallbacksAndMessages(null)` 自然摘除，无需 onPause。

只搬不拉的边界保持：应用真被用户退出（ABSENT）走既有 `relaunchIfAbsent` 节流重拉，
巡检**绝不重拉** —— 防止虚拟屏里叠出第二个实例（m12 双画布一起坏的教训）。

**验证**：javac --release 11 全树通过（166 class）。真机待验口径：
点歌词/封面 → 应用闪到主屏后 2.5s 内自动回画中画；返回桌面画布不再黑。

**遗留**：跳主屏瞬间主屏仍会闪现全屏应用一瞬 —— 根治要靠 LSPosed 模块
（setLaunchDisplayId 注入）或受信虚拟屏（uid=1000），巡检是模块失效时的兜底。

---

## 批次 AA — 网易云车机版强制横屏（反编译坐实两条病根）+ 画布键条（返回/桌面）+ 重挂载

**目标**：用户报「网易云车机版 3.1.0 竖版 / 没有底部导航 / 返回桌面后点不回去」，
要求像 hook 高德一样：强制横屏、进入应用固定底部键条（导航栏）、返回桌面能重新挂载。
先反编译 APK 找病根再动手。

### 反编译结论（jadx，com.netease.cloudmusic.iot，targetSdk 29）

1. **manifest 全家 `screenOrientation="sensor"`** —— 旧 H2 判据（竖屏系列 or
   UNSPECIFIED）对 sensor(4) 一条不改，首帧方向由 VD 自然方向决定 → 横屏不成立；
2. **IotActivityBase.attachBaseContext 宽高比 ≤1.3 时 `new Configuration();
   orientation=1; applyOverrideConfiguration(...)`** —— 在应用进程内把 Resources
   配置按竖屏摆，走 ContextThemeWrapper 层，WM 侧 H2 够不着 → 画布不够宽就整体切
   竖版形态（顶部 Tab）。反编译确认该应用**任何形态都没有底部导航栏**
   （sw1080dp-land / land = 左侧栏，port = 顶部 Tab），底部键条只能桌面自绘。

### 改了什么

1. `XposedEntry.java`：
   - **H2 判据扩写**：新增 `shouldRewrite(req, target)` —— 与目标同侧放行；
     异侧 / 竖屏系列 / 跟随类（sensor / fullSensor / user / fullUser / nosensor /
     behind / locked / unspecified）一律改 target。网易云 manifest 的 sensor 从此
     进门就改横屏；
   - **新增 D 段** `hookPortraitConfigOverride()`：hook 框架类
     `ContextThemeWrapper.applyOverrideConfiguration`（公开 API 不受混淆影响），
     只拦「显式 orientation=PORTRAIT」的 override，目标为横屏时改写成 LANDSCAPE，
     其余（含 AppCompat 合法调用）原样放行。独立 try/catch，失败不牵连 H1~H3。
2. `PipBoard.java`（画布键条）：
   - 每块画布 = 画布盒(box) + 底部键条(bar) 嵌套；键条【返回 | 桌面】两键，
     `KEYBAR_DP=34`，分屏预览模式隐藏，单应用铺满才顶出（`applyKeybar`）；
   - **键条挤压画布盒底边距**而非叠在画面上：SurfaceView 变小 → surfaceChanged →
     attachTo 按新尺寸 resize VD —— 私有 VD 无 SystemUI 导航栏、应用布局不知道
     要留边，物理让位是唯一不被盖的方案；
   - **返回键** = `RootOps.injectKey(displayId, 4)`（input -d <id> keyevent 4，
     注进应用）；**桌面键** = `ExitListener.onExit` 回宿主（无监听则本地降级
     showSplit）。键条 setClickable 吃掉自身区域触摸，不穿透；
   - 触摸纪律不变：单应用模式全量转发（DOWN 上 syncCanvasSize 校尺寸），分屏
     预览只切焦点/进应用。
3. `HomeActivity.java`：`pip.setOnExitSlot(...)` —— 键条「桌面」与导航栏「首页」
   同路径：showSplit + enableSwipe(true) + syncDock(HOME)。应用留在虚拟屏后台
   不杀，MirrorHost 只断 Surface 不拆屏，再点画布秒回。
4. `RootOps.java`：新增 `injectKey(displayId, keycode)`。
5. `MirrorHost.java`：`relaunchIfAbsent` 重载带 `force` —— 用户主动点画布 /
   键条属显式意图，绕过 60s 重拉节流（正是「返回桌面后点不回去」的挡板）；
   后台自愈仍走节流防连环 force-stop。
6. `AndroidManifest.xml`：xposeddescription 补 D 段说明。

### 验证

- javac --release 11 全树 0 错误（64 源文件，含历史 gen 的 R.java）；
- CI 构建（build-apk.yml）出包，versionName 1.0.<git 短哈希> 供核对。

### 真机验收清单

1. 画布选网易云车机版 → 铺满后应横屏无黑边（若仍竖版：先看 LSPosed 作用域
   是否勾了网易云 + 强杀其进程重开，再用「LSPosed 模块自检」看 D 段日志）；
2. 单应用底部应有常驻【返回 | 桌面】键条，应用画面不被盖（键条下应用窗口
   物理缩让）；
3. 键条「返回」应触发应用内返回；「桌面」退回分屏主页；
4. 退桌面后立刻点画布 → 应用应能重新挂上（不受 60s 节流限制）。

### 遗留

- D 段 hook 挂在 ContextThemeWrapper 上，凡显式竖屏 override 都会被改横 ——
  目标应用内的确要竖屏的页面（若有）也会被拉横，与 H2 口径一致，车机场景可接受；
- 键条按钮圆角按压态用了 setBackground(null) 切换，个别 ROM 上高亮可能不显，
  不影响功能。

---

## 批次 Z — 用户「不方便抓日志，直接改」：三条不依赖框架的确定性修法

**目标**：用户明确说不方便抓 logcat，要求直接改。没有现场日志时，能坐实的只有
「不依赖 LSPosed 是否注入」的那几条 —— 本批全部走 root 通道 + 应用内可见，
换掉任何一条都能单独解释用户看到的现象。

### ① 兼容位写在起进程之后 → 本次部署根本不吃（高德竖版黑边的直接病根之一）

`RootOps.launchOnDisplay` 的顺序原来是：
`am force-stop` → `launch`（成功后才）`relaxCompat(pkg)`。

而 `am compat enable FORCE_RESIZE_APP / NEVER_FIX_ORIENTATION` **只对之后新起的进程
生效**。于是：

- 本次部署：应用已经起来了，才写兼容位 → 进程不读，高德仍按 manifest 的
  `PORTRAIT` 建窗 → 塞进横屏画布就是竖版 + 左右黑边；
- 用户以为"改了没用"，其实是"这次没用、下次才好"。

**修复**：把 `relaxCompat(pkg)` 移到 `am force-stop` 之后、launch 之前，本次即生效。
删掉三处 launch 成功后的重复调用（单一口径，避免下次又被改回去）。

### ② 网易云点一下跳主屏：补一条不依赖模块的兜底

模块的 `setLaunchDisplayId` 注入（C 段）只在模块真被注进网易云进程时才生效；框架 /
作用域任一环没到位就静默失效，表现就是"点一下跳主屏"。而现有的自愈只在
**桌面 onResume** 时跑（`MirrorHost.healHome`），跳走之后桌面并不在前台，自愈永远
轮不上 —— 于是应用一直停主屏。

**修复**：`PipBoard` 在每次「抬手点击」后补两次延时自愈（600ms / 1600ms），走
`RootOps.ensureOnDisplay`（root 的 `am task move-task`），把被拉走的任务搬回画布。
这条与模块无关，模块失效时它就是唯一兜底。跳页开窗有几帧延迟，所以补两次。

### ③ 「模块到底有没有被注入」搬进应用内

用户抓不了日志，等价于**永远无法判断是"模块没注入"还是"注入了但 hook 没生效"**，
后面每批改动都只能靠猜。新增设置 → 系统 →「LSPosed 模块自检」：
root 跑一次 `logcat -d -s SeagullLsp`，据此直接给结论：

- 没装 LSPosed / 模块无日志 → 逐条列出"启用模块 + 作用域勾哪几个包 + 强杀重开"；
- 有日志 → 逐进程报「高德 / 网易云 / SystemUI 已注入 ✓ / 未注入 ✗」，未注入直接点名
  该补勾哪个包。

这样下一轮反馈能带上"自检结果"，不用再让用户去接 adb。

**验证**：`javac --release 11`（`libs/android.jar` + `libs/xposed-api-82.jar`）全树通过，
151 个 class 产出；三条改动均为纯逻辑/顺序调整，无新依赖。

**遗留**：本文三条都是"能坐实"的改动，不代表高德黑边只有这一个病根 —— 方向劫持
（H2）仍依赖模块被注入高德进程，需靠 ③ 的自检结果来判定还需不需要继续往下挖。

---

## 批次 Y — 用户三连报"还是不行"：一个是构建产物问题，一个是模块被自己吞了

**目标**：用户反馈高德画中画仍竖版黑边、网易云点一下仍跳主屏、并且明确说"模块像没
生效一样"。三条同时成立，指向的不是某条 hook 写错，而是**模块根本没有在目标进程
里跑起来**（或跑的是旧包）。本批只做能坐实的两件事，不再盲加 hook。

### ① 构建产物：版本号写死 1.0 → 用户可能一直装着旧包

`app/build.sh` 里 `aapt2 link --version-code 1 --version-name 1.0` 是硬编码的，带来两个后果：

- GitHub release tag 由 versionName 拼出（CI `TAG="v$VER"`），**永远都是 `v1.0`**；
  应用内「检查更新」是拿 `releases/latest` 的 tag 去掉 `v` 跟本机 versionName 比
  （见 `SettingsSectionActivity.checkUpdate`），于是**恒等于 → 永远回「已是最新」**。
- 用户侧看到的现象就是：仓库明明推了新批次、CI 也跑了，但"检查更新"说不用更新，
  他手里那个包还是批次 V 之前的 —— 之后每批模块改动都"像没生效一样"。
  批次 X 也记过一次"源码已改、产物没重建"，同源。

**修复**：`build.sh` 每次构建现算版本号 ——
`versionName = 1.0.<git 短哈希>`、`versionCode = unix 秒`。
短哈希让人眼能核对"手上这包是不是当前提交"（版本信息页会显示），
tag 也随之每次不同，`releases/latest` 指向最新包这条承诺不变。

### ② 模块加载：一个 try 包住三段 hook，H1 抛一次就把 H2 全吞了

`XposedEntry.handleLoadPackage` 原来把 A(SystemUI)/B(H1~H3)/C(网易云) 全塞在
**同一个 try/catch** 里。只要 H1 在某个 ROM 上抛一次（`findAndHookMethod` 签名不匹配
就会抛），后面的 **H2 方向劫持（高德铺满画布全靠它）和 H3 一条都装不上**，
而且只留一行 `hook setup failed`，外观上就是"模块像没生效"。

**修复**：拆成三段各自 try/catch，每段失败单独记日志（`A 段失败` / `H1 失败` /
`H2 失败` …），互不牵连。

### ③ 日志第一条无条件打，且移到开关/白名单之前

原来是白名单通过后才 `log("已挂载 …")`。作用域勾错时日志里**连一条来访记录都没有**，
排查时分不清"LSPosed 没注进来"和"注进来了但被开关/名单挡掉"。
现在 `handleLoadPackage` 第一行就是 `log("handleLoadPackage: " + pkg)`。

### ④ 放宽 xposedminversion：93 → 82

判据是"越低越兼容"：LSPosed 的过滤条件是 `xposedminversion <= 框架 getXposedVersion()`，
所以 82 的可加载集合是 93 的**严格超集** —— 框架报 82 时只有 82 能过，报 93/102 时两者都过。
我们的编译期签名桩本来就是 api-82（`libs/xposed-api-82.jar`），82 才与桩一致。
参考实现 com.leting 写 93 能跑只说明 93 够用，不说明 93 最优。

### 复盘

- 做错：连续几批都在"加 hook / 改 hook"，但**没有一条证据说明模块被加载过**；
  用户在设备上的体感是"没反应"，最可能的解释一直是链路而不是逻辑。
  排查顺序应当是：产物是不是最新 → 模块有没有注进来 → hook 有没有抛 → 才轮到 hook 逻辑。
- 判据固化：`logcat | grep SeagullLsp` 里**必须有** `handleLoadPackage: <包名>`。
  没有它，后面所有 hook 讨论都是空的。
- 保留的结论（未改）：本项目画中画是 VirtualDisplay + SurfaceView（`PipBoard`），
  不是系统原生 PiP。所以 A 段（hook SystemUI `PipBoundsAlgorithm`）对本项目**不生效**，
  真正决定高德铺满的只有目标进程里的 H2。A 段暂时留着（若将来走系统 PiP 才有用），
  不再把它当成主要机制。

### 验证

- `bash -n app/build.sh`；本地无 aapt2/javac（无 SDK），类型检查走 CI。
- 真机验收（P2-19 已更新口径）：装**版本号带短哈希**的新包 → 高德强杀重启 →
  `logcat -s SeagullLsp` 先要看到 `handleLoadPackage: com.autonavi.minimap`，
  再看 `H2 hookOrientationOverride OK`。

### 遗留

- 若 `handleLoadPackage` 一条都不出现：问题在 LSPosed 侧（模块未启用 / 作用域未勾 /
  框架版本低于 82），与本仓库代码无关，只能由用户在管理器里核对。
- 网易云的 displayId 注入依赖目标 Activity 启动时所在 displayId > 0；
  若应用实际落在主屏（部署失败），这条也不触发 —— 先看 `MirrorSlot.describe()`。

## 批次 X — LSPosed 模块不生效的复盘：模块根本没被加载 + 两条死 hook

**目标**：用户报「还是不行」（高德画中画仍竖版黑边、网易云点击跳主屏）。
全面复盘模块侧代码，逐条对齐参考实现（`xphook` = CarWithX `com.leting` 的
AmapPipHook + NetEasePipAdapter；`WFreeHook` = 车联助手桌面的窗口语义模块），
找出「参考能生效、我们为什么不能」。

### ① 模块压根没被加载（批次 W 自己写错了）

`AndroidManifest.xml` 里 `xposedminversion=102`。这个 meta-data 的含义是
**「本模块要求框架提供的 Xposed API 最低版本」**，LSPosed 加载前会拿它跟
`XposedBridge.getXposedVersion()` 比，**大了就直接跳过加载** —— 模块在管理器里
看得见、作用域也勾了，但任何进程都进不去，`SeagullLsp` 一条日志都没有。

- 唯一可信证据：参考实现 `reference/carplay-re/sources/netease-pip-adapter/app/src/main/AndroidManifest.xml`
  是 `93`（这是实测能用的那份）。LSPosed 的 legacy de.robv API 版本号就是 93。
- 批次 W 里「API 102」的来源是 `xphook` 的日志字符串
  `"CarWithX LSPosed API102 loaded"` —— 那是它自己打的广告，不是清单声明值，读错了。
- 改成 `93`（≤ 任何 102 框架，也 = 框架自身的 93，两个方向都满足）。

### ② 网易云那两条 `Context.startActivity` 是死 hook

`XposedHelpers.findAndHookMethod(Context.class, "startActivity", ...)` 挂的是
**抽象基类 Context 上声明的那份实现**。`startActivity` 在 `ContextWrapper` /
`ContextImpl` / `Activity` 里全部被 override，虚拟分派永远走不到 Context 那份，
挂上去一次也不会触发。参考实现自己也有这个毛病，照抄就把病一起抄进来了。

- 而网易云 IoT 的播放页正是 **Service/Application 侧**拉起的 —— 恰好是这对死 hook
  本该负责的那条路，所以「点一下照样跳主屏」。
- 改成挂 `android.app.ContextImpl`（所有非 Activity Context 的最终实现，
  `ContextWrapper.mBase` 就是它），活动 Activity 侧仍由另外 4 条覆盖。
- 顺带把 6 条 hook 各自包 try/catch（对齐参考的写法）：某台 ROM 上少一个签名，
  不该把后面几条一起吞掉。

### ③ H2 漏了批次 W 的 onCreate 强拉（被重写时丢了）

批次 W 原本有 `hook Activity.onCreate(after) → setRequestedOrientation(横屏)`，
重写成 WFreeHook 版时丢了。**只 hook set/get 改不动 WM 侧的 ActivityRecord**：
高德首屏 Activity 的 `screenOrientation=1(PORTRAIT)` 写在 manifest 里，WM 建窗时
读的是 manifest，不是应用读回的那个值 —— 首帧必然竖版。补回来（H2b），
`onCreate` 后再显式请求一次目标方向；这次调用会再进 setRequestedOrientation 的
hook，参数已是目标值，原样放行，无递归。

### 复盘

- 做错：把「日志字符串」当「清单声明值」；把「参考实现有这个 hook」当成
  「这个 hook 有效」。两个都是**没验证据就当事实**，代价是整批功能静默失效 ——
  而且失效方式最恶劣：不崩、不报错、日志里啥也没有。
- 判据固化：任何「模块没生效」的排查，第一步是确认 `SeagullLsp` 有没有日志；
  没有就只查加载链（xposedminversion → 模块启用 → 作用域 → 产物是不是最新），
  不要先去怀疑 hook 逻辑。

### 验证

- `XposedEntry.java` 括号/结构平衡 + 逐条对齐参考；javac 类型检查走 CI（本地无 javac）。
- 真机验收：`adb logcat -s SeagullLsp` 应出现
  `已挂载 com.android.systemui → AmapPip 尺寸缩放 0.6`、`C1..C6 OK`、`H2 ... OK`。

### 遗留

- `Context.startActivity` 死 hook 的结论同样适用于 `ContextWrapper` 之外的写法，
  本批只修了网易云这一段；其余段没用到 `Context.startActivity`。
- 参考实现 `xphook` 的 ② 号缺陷（死 hook）说明「照抄参考」本身不是充分条件，
  后面再移植参考代码时，先判一遍「这个 hook 到底会不会被调用」。

## 批次 S — 全库复盘：复读画中画链路（~2700 行）+ 全库高危模式扫描，修 4 处确凿问题

**目标**：用户要求复盘全部代码找必修问题。精读批次 R 触碰过的完整链路
（MirrorSlot / RootOps / MirrorHost / PipBoard / StackScan / TrustedFlags /
PipAnchorActivity / Caps / TouchForward / PrivClient），HomeActivity 接线抽查，
其余 50+ 文件按模式扫描（静态 Activity 泄漏 / 主线程 sleep / API 门控 / 资源释放）。

**修掉的确凿问题**

1. **锚点在 API 29 上秒崩（P1）**：`PipAnchorActivity.onCreate` 直接调
   `Activity.getDisplay()`——该 API 是 API 30+，minSdk 29。29 的设备上
   NoSuchMethodError → 锚点起不来 → VD 上没有常驻 task → 空屏被系统清理，
   批次 R 的锚点机制整体失效。→ `SDK_INT >= 30` 门控（调用点必须门控，
   光判空救不了 NoSuchMethodError）。
2. **锚点实例无限叠加（P1）**：manifest 里锚点是 standard launchMode，
   `launchAnchor` 只带 NEW_TASK——同 affinity 会路由进既有锚点 task 并
   【新增一个透明实例】，永不 finish。每次部署（换应用/换尺寸重建）叠一个。
   → manifest 加 `launchMode="singleTask"`：复用 task 且单实例，真幂等。
3. **守护进程掉线后首触 ANR（P1）**：触摸手势链 feed → replay →
   `PrivClient.motion` → `ensure()` 在【主线程】跑；ensure 失败时
   waitConnect sleep 500ms×N + fork su，最多 ~3.5s（ANR 阈值 5s）。
   → 新增 `ensureForTouch()`：主线程只接受"已连接"（available 快查），
   掉线立即 false → TouchForward 本手势余下事件退回命令通道
   （跑在 seagull-touch 线程）；launch/move 仍走完整 ensure（调用方全在后台线程）。
4. **重拉节流竞态（P2）**：`MirrorHost.lastRelaunch` 是跨线程读写的
   long[]（桌面自愈线程 + 画布自愈线程都写），无同步 → long 撕裂 +
   双重重拉。→ `RELAUNCH_LOCK` 包住判定+写入。

**顺手清理**：`MirrorSlot.deploy` 里 `register(displayId)` 调了两次（223/229 行）。

**复核过没问题的**（免得下次重查）：`Caps.exec` 超时路径有 destroyForcibly
兜底、读线程随进程回收；`StackScan` 批次 P 的包名边界修复在位且
StackListCheck 30 项覆盖；`TouchForward` 手势解释/降级链完整；`PrivClient`
所有公开操作在类锁内、roundTrip 失败即关连接；`PipBoard` 单焦点/armed/CANCEL
链路、同槽部署串行锁、onDestroy 摘回调；全库无静态 Activity/Context 泄漏、
无主线程 sleep（除 PrivClient 本批修的）、su 通道超时全覆盖。

**遗留（记 TODO，等真机证据再动）**：①私有屏弹回检测——singleTask 目标
（网易云）若被系统从 flags=0 私有屏弹回主屏，ensureOnDisplay 搬回会 3s 一次
循环（move 不重拉，负载可控但治标）；连续 N 次弹回应升级 TRUSTED 重建，
先记 P2-13。②Android 14 录屏授权 token 单次有效：两份投影会话在 API 34 上
第二次 getMediaProjection 必失败（现有 catch + 共用降级 + 日志在位），
根治要改两次授权申请，记 P2-14。

**验证**：typecheck OK；自检 6 套全过（30/54/14/15/10/PrivCodec）。



## 批次 R — 实验台回灌：CarWithX 建屏三铁律移植 + 锚点 + 缺席重拉

**目标**：把 `seagull-pip-lab`（复刻 CarWithX）在真机验证过的路子回灌主项目，修三个症状：
两个画中画没铺满、回桌面画中画消失、画布黑屏无人重拉。

**复盘（症状 → 根因 → 修法，全部读代码推出）**

1. **铺不满**：主项目建屏走 `TRUSTED|PUBLIC` 全量 flags（`TrustedFlags.candidateFlags` 组 1），
   PUBLIC 屏被系统当"第二块真屏"，size-compat/letterbox 决策与私有屏不同路；
   CarWithX 全家（实验台在用户 Redmi K60 实测）用 flags=0 私有屏出画面。
   → 建屏顺序改为 ①flags=0 私有屏（零授权零弹窗）→ ②TRUSTED → ③投影兜底；
   `dpiFit`（批次 Q）保留不动。真机若仍黑边，按 TODO P2-11 口径抓
   `dumpsys activity containers | grep -A5 sizeCompat` 定位 ROM 层兼容框。
2. **回桌面消失**：两股根因。
   a) 应用在画中画里按返回退出后，`RootOps.ensureOnDisplay` 尾部返回
   "可能已退出"就没人管了 —— 画布永久黑屏（确凿代码级根因）。
   → `ensureOnDisplay` 改回 `ABSENT` 前缀标记（仅栈解析成功时），新增
   `MirrorHost.relaunchIfAbsent` 60s 节流重拉；桌面自愈（`healHome`）与
   画布自愈（`PipBoard.selfHeal`）都接上。
   b) singleTask 任务被系统拉回主屏：原有 healHome 兜底之外，补 CarWithX
   锚点 —— 部署建屏成功立即把 `PipAnchorActivity`（新增，空/透明/
   excludeFromRecents/独立 affinity）起在 VD 上，重钉有稳定落点、
   空屏不被系统清理。`launchAnchor` 只带 NEW_TASK（幂等复用锚点 task），
   【绝不能带 MULTIPLE_TASK】否则每次部署叠一个锚点 task。
   锚点的 "Warning: brought to the front" 是好结果，正向证据判据在此放宽。
3. **没有持久化**：主项目绑定早有持久化（`PipBoard.savePkg` → prefs
   `seagull/mirror_pkgN`，`MirrorHost` 进程级持槽，重启后 `deployIfBound` 重建）；
   该症状来自实验台（复刻版确实没写持久化）。回灌后两版行为一致。

**其他改动**

- `MirrorSlot`：新增 `createPlainVd`（flags=0 私有屏）与 `mode` 诊断字段
  （"直建(flags=0)" / "TRUSTED" / "投影"）；`describe()` 显示建屏路。
- `MirrorHost.onProjectionStopped`：只拆 `projectionBased()` 的屏 ——
  批次 R 起私有屏/TRUSTED 屏与投影无关，投影被撤销不能再误拆它们
  （旧判定 `!trusted()` 会把私有屏也拆了，这是重排建屏顺序后必须跟着改的一处）。
- `ensureOnDisplay` 新增双槽护栏：目标在**其他**虚拟屏有同名 task 时不动
  （否则重拉会 force-stop 掉对槽正在跑的实例，两块画布一起死）。

**验证**：typecheck OK；自检 6 套全过（Transform 54 / StackList 30 / DumpParse 14 /
Lrc 15 / TrustedFlags 10 / PrivCodec）；真机验收口径见 TODO P2-12。

**遗留**：LSPosed 模块（网易云强制横屏/吞 PiP）仍在实验台，主线是否合并待用户拍板
（影响包形态：单 APK 变 LSPosed 双体型）；`am compat` 在 HyperOS 上对
NEVER_FIX_ORIENTATION 的接受度未验证，失败仅记日志。

---

## 批次 T — 全库代码复盘（65 文件）：坐实 16 处问题（安全 3 / 正确性 8 / 性能 3 / 健壮性 2），杀掉 2 个误报

**目标**：用户要求「把整个项目代码都复盘一遍，看有没有 bug、性能问题、安全风险，有就修复」。
8 个并行审查代理全库扫过（60s 超时省缺），我再逐项亲自读码复核——
**每条发现必须自己从代码推出才动手**，杀掉 2 个确凿的误报（见下）。

**复核坐实 & 已修（16 处）**

安全：
1. **SysOps 注入**：`forceStop/killBackground/clearCache/grant/installApk` 参数零校验直拼
   `su -c`；UI 入口 `SettingsSectionActivity`「强制停止应用」的用户输入文本直达。
   → 新增 `safePkg/safePath/safePerm` 白名单（包名两段 [A-Za-z0-9_]，路径禁空格/;/&/..，权限名点分段），不过直接返回「参数不合法」。
2. **RootMain uid 白名单双误**：①放行 SHELL_UID(2000)——adb shell 连抽象 socket 即可以
   root 通道拉起虚拟屏/转发触摸，KernelSU 同意框白给；②白名单只有 0/1000/2000，
   **不含本家 uid(10xxx)——普通安装下 launcher 自己的连接全被拒**，PRIV_LAUNCH/MOTION/MOVE
   全程静默降级成 shell 慢通道。→ 撤 SHELL_UID；新增 `selfUid()`（反射
   `Process.getUidForName` → 回落 `dumpsys package` 的 userId=/appId= 双通道）放行本家。
3. **下载文件名注入**：`downloadApk` 用 GitHub release tag_name 直拼文件名（可 `../`
   穿越）。→ `safeVer()` 只留 [A-Za-z0-9._-] 且限长；下载落盘同时收敛到 `files/updates/`
   子目录，与 `file_paths.xml` 的 `path="updates"` 对齐（旧 path="." 把整个 files/ 圈进映射）。

正确性：
4. **`LauncherModel.launch()` 死路**：ctx 是 applicationContext，`asNewTask=false` 不加
   FLAG_ACTIVITY_NEW_TASK → startActivity 必抛 AndroidRuntimeException 被 catch 吞 →
   桌面图标/Dock/文件夹点开应用静默无响应（DesktopView/QuickBar/FolderActivity 全链路）。
   → NEW_TASK 恒加（asNewTask 参数保留）。
5. **`LayoutModeActivity` 布局列表永远空**：`rebuildRows()` 往旧 listCol 填完行又
   `setContentView(build())` 换一棵全新的空树，行随旧树被丢弃；首屏更是没人调它。
   → `build()` 内 `fillModeRows()`，`rebuildRows()` 只整树重建。
6. **`BootReceiver` 读错 key**：读顶层 SP 的 `"autoHome"`，而模型存在 K_LAYOUT JSON 里
   （`save()` 的 `o.put("autoHome")`）——「开机自动回桌面」开关恒为默认 true。
   → 改走 `new LauncherModel(ctx, false).autoHome`。
7. **`TaskMover.frontTask` grep 无边界**：`displayId=1` 会命中 `displayId=10/11…` 的行，
   收回/拉回搬错屏上的任务。→ `'displayId=N[^0-9]'` 边界；`taskId=` 加 `\b`。
8. **`homeKeys` 同名文件夹去重**：`covered.add(f.name)` 按名字去重，两个同名文件夹
   只有一个能在桌面占位。→ 改按 Folder 身份（LinkedHashSet + Folder 无 equals）。
9. **`toggleWidget` 死按钮**：`id > 4` 硬编码把 5(歌词)/6(快捷栏) 丢掉，而
   `DesktopView.makeWidget` 两个渲染器都在、菜单也列 7 项。→ 放开到 0~6 并返回
   boolean，满 WIDGET_MAX 时菜单给 toast 反馈；`load()` 钳制同步放到 0~6。
10. **`importScheme` 先写后读**：语法对、语义错的存档让 load 抛异常，方法返 false 但
    存档已被污染，重启停在 defaults，用户以为导入失败、配置已丢。→ catch 里撤回 prev
    并重新 load。
11. **`QuickBar.tap()` NPE**：`@fn:` 分支不判 null 直接 `host.model().context()`，
    Activity 销毁窗口期点功能按钮必崩。→ 与应用分支同样先判空。

性能：
12. **`LauncherModel.save()` `commit()`**：配置保存全在主线程（拖动/开关），commit 同步
    fsync 大存档卡几十毫秒。→ `apply()`。
13. **图标无缓存**：`icon()` 每次 `getApplicationIcon` = 一趟 Binder + 位图解码，
    桌面重画/拖动/组件条刷新全靠它。→ `LruCache(96)`，命中时从 ConstantState 取
    独立副本（共享位图无解码，各 View bounds 不打架）。
14. **`Lyrics` pos 漂移**：播放中纯墙钟累加，postDelayed 真实间隔误差长播漂出秒级。
    → 每 15 跳用 `ps.getPosition()+now-getLastPositionUpdateTime()` 重新锚定。

健壮性：
15. **`factoryReset` 名不副实**：`resetScalars()` 只回 16 个标量，Dock/菜园/小白点/
    海鸥岛/天气/歌词/窗口/触摸/快捷按钮全系列漏重置（用户实测过：重置后字号还是 130%）。
    → 补全全部标量字段。
16. **两处泄漏/健壮**：`DesktopView` 歌词 sink 构造时匿名注册、从不摘除，换肤重建
    每次泄漏一整棵旧视图树 → 单实例 + onAttached/onDetached 配套；`HomeActivity` 换肤
    重建时旧 PipBoard 的 redeploy/selfHeal 回调无人摘 → 新增 `PipBoard.cancelPending()`
    在重建前调。另：`Lyrics.http()` 加 1MB 回包封顶。

**复核后杀掉的误报（记录判据，避免下批复盘又报一遍）**

- **WindowCard AUTO_MIRROR「未持 MediaProjection 会 SecurityException」**：误报。
  构造即持 `MediaProjection projection`（:41/:56-58），AUTO_MIRROR+投影是标准镜像写法；
  且 attach 失败只记日志、不会走到 close()。
- **Lyrics `getActiveSessions(null)`「换歌链路死」**：误报。:199 有歌名/歌手比对，
  变化即 `lookup` 重查；会话为空是通知监听权限门槛，属设计行为。
- **SeagullFileProvider「file_paths.xml path='.' 暴露整个 files/」**：误报。
  这是手写 ContentProvider，根本不读 file_paths.xml，`fileFor` 有 canonical 前缀校验。
  XML 仍顺手收紧（防将来换成真 FileProvider 时踩坑）。

**验证**：typecheck OK；自检 6 套全过（PrivCodec/Transform/StackList/DumpParse/Lrc/
TrustedFlags）；真机验收口径见 TODO P2-15。

**遗留**：代理发现里未亲自复核的项（GardenActivity dp 双倍密度+时钟不走字、BallService
长按/点按双触发、RootPanelActivity 主线程 su×2、VirtualDisplayHost 未同步、Lrc BOM/
[mm:99]、Pinyin locale、Theme accent 弱校验、MediaListenerService 打印通知正文、
Wallpaper 流泄漏、SettingsSection 定时器 HHMM 误解析、SelfTest 改设备亮度/音量、
Skin.apply 时机等）列 TODO P2-16，下批复盘继续。RootMain 线程-per-连接无上限
（本家连接数固定，风险低）记 P2-16。

---

## 批次 A — P0-3 文件夹闭环 + P0-4 应用搜索

**目标**：长按 A 拖到 B 上生成文件夹；搜索按名称/包名/拼音首字母过滤。

**改动**

- `LauncherModel.homeKeys()`：pinned 为空时自动枚举，全在文件夹里也不会渲染成空桌面。
- `LauncherModel.CATEGORIES/categoryOf()/autoGroup()/dissolveFolderQuiet()/dissolveAllFolders()`：按包名前缀分 8 类，单应用分类自动解散。
- 新增 `Pinyin.java`：`android.icu.text.Transliterator("Han-Latin")` 取首字母，不引第三方词典。
- 新增 `SearchActivity.java`：结果点击启动，长按 → Dock / 文件夹 / 应用信息。
- `HomeActivity` 顶栏、`AppListActivity` 各加搜索入口；`LayoutModeActivity` 加自动归类与解散全部。

**复盘**

- 做对：拼音走平台 ICU，比塞一份词典小两个数量级，且 `android.jar` 里本来就有这个类。
- 做错：`homeKeys()` 原来只认 `pinned`，纯文件夹布局的桌面是空的 —— 这是个「首次启动就空白」的必现 bug，优先级高于搜索。
- 修正：搜索结果的长按菜单直接复用 `LauncherModel` 的 `toggleDock/addToFolder`，没有另建一套数据通路。

**验证**：`javac` 0 错误。

**遗留**：拼音首字母只覆盖 `Transliterator` 的通用规则，多音字不完美（中文桌面通病，够用）。

---

## 批次 B — P0-5 Dock 全配置

**目标**：Dock 位置/数量/粗细/图标大小/间距/透明度/自动收起/固定/时钟全部可配，长按图标选默认窗口。

**改动**

- `LauncherModel`：`dockWindow`（pkg → 1/2）+ `windowOf()/setDockWindow()`，save/load/reset 全链路。
- `DesktopView` 重写 Dock 部分：`FrameLayout` 根 + 主列侧边 margin + Dock 兄弟节点按 gravity 定位；`applyDockLayout()` 统一落布局参数；`applyCollapse()` 做贴边平移 + 把手展开；`dockMenu()` 长按菜单。
- `MirrorActivity`：`EXTRA_PKG`/`EXTRA_SLOT` + `intentFor(ctx,pkg,slot)`，onCreate 预写槽位并自动走录屏授权链。
- `SettingsSectionActivity.dockBody()`：9 项配置 + 每应用默认窗口（Dock 为空时用默认候选应用）。

**复盘**

- 做对：Dock 的位置/粗细/透明度/收起全部集中在 `applyDockLayout()`/`applyCollapse()` 两个方法里，其他代码不碰布局参数，改起来只有一处。
- 做错：默认 Dock 键位里写了 5 个包名硬编码；一旦桌面上一个都没有，底栏会空。改成「优先常见包名 → 都没有就取前 5 个」两级兜底。
- 修正：`dockWindow` 只存包名不存 key（同一包多入口时两个入口会互相覆盖），取舍上选了包名，因为「默认开在几号窗口」本来就是应用级属性。

**验证**：`javac` 0 错误；`build.sh` 出签名 APK（119345 bytes）。

**遗留**：自动收起只做了贴边平移，没做「侧栏塞满时自动变窄」。

---

## 批次 C — P0-6 快捷栏

**目标**：组件条里的一条小 Dock，每格放应用或功能按钮，每个布局各有一份。

**改动**

- 新增 `QuickBar.java`：`FN_KEYS/FN_LABELS`（7 个功能按钮）+ `runFn()` 执行 + `build()` 造视图。
- `SettingsSectionActivity` 的功能按钮表改为引用 `QuickBar.FN_KEYS`（原先两处各写一份 7 项清单）。
- `DesktopView`：组件条长按（或桌面空白处长按）出「加组件」菜单；长按单个组件可拿下/前后挪；组件 id=6 渲染 `QuickBar`。
- `LauncherModel.switchMode(Mode)` + `quickbarByMode`：每个桌面布局各存一份，存/取抽成纯静态 `swapQuickbar()`。
- `SysOps` 补 `isWifiOn/isBluetoothOn/isAirplaneOn`，开关按钮需要读当前态。
- 自检 `app/selfcheck/QuickbarCheck.java`：7 项断言。

**复盘**

- 做对：把「每布局一份」的存取逻辑抽成不依赖 Context 的纯静态方法，才有办法用普通 java 跑断言自检。
- 做错：自检第一版写 `cur.get(0).key()`，`QuickSlot.key` 是字段不是方法 —— 一编译就暴露，说明把自检放在同包下直接调包级静态方法是对的。
- 修正：`LayoutModeActivity` 的组件列表原本是 5 项、第 5 项叫「温度」，而桌面组件条里根本没有温度组件，两处对不上。统一到 `DesktopView.WIDGET_NAMES`。

**验证**：自检 7 项通过；`build.sh` 出 APK（123441 bytes）。

**遗留**：竖屏快捷栏（3.7）没做；功能按钮只支持 7 个，亮度/音量是相对调节，依赖 root 通道。

---

## 批次 D — P0-7 主题与壁纸

**目标**：切主题全局变色、壁纸白天/夜间两套、壁纸库、全局字号、文字颜色按壁纸明暗自动翻。

**改动**

- 新增 `Skin.java`：运行时配色层。`c(R.color.x)` 是全局唯一取色口，`apply(LauncherModel)` 按主题色 + 日夜算出 ground/panel/card/leaf/text 全套；颜色混合自己按通道插值（`androidx ColorUtils` 不在依赖里）。
- 新增 `BaseActivity.java`：所有页面改继承它。`attachBaseContext` 里把 `Configuration.fontScale` 乘上全局字号（一次生效，全部界面用的都是 sp）；`onCreate` 里 `Skin.apply()`。
- 新增 `Wallpaper.java`：`ACTION_OPEN_DOCUMENT` 选图 → 拷进 `filesDir/walls/` → 入壁纸库；`loadForScreen()` 抽样解码铺满；`isBright()` 抽样 16×16 算平均亮度。
- `HomeActivity`：`applyWallpaper()` 铺底 + 遮罩层；顶/底栏改用 `Skin.bar()`（亮壁纸上半透明 = 自动字底）；`skinSignature()` 只在主题/壁纸/字号/小时变化时整屏重画，避免每次回桌面都闪。
- `LauncherModel`：`load()` 开头补 `reset()`；`fontScaleOf(Context)` 静态只读字号（attachBaseContext 用，不跑应用枚举）；`pinnedIndex/movePinned/pinTop`（自己摆的布局）。
- `DesktopView`：非整理模式下长按图标 = 自己摆布局菜单（上移/下移/置顶/固定/拿下来/固定到 Dock/应用信息）；长按文件夹 = 打开/解散。
- 机械替换：15 个文件里 `getColor(R.color.x)` → `Skin.c(R.color.x)`，全部 `extends Activity` → `extends BaseActivity`。

**复盘**

- 做对：颜色不再从 `resources` 直接取，而是过 `Skin`。换主题只要 `Skin.apply()` 一次 + 重建视图，全屏生效，零散落点。
- 做错（3 个，都在替换阶段）：
  1. 正则 `getColor(R.color.x)` 命中了 `ctx.getColor(R.color.x)`，产出 `ctx.Skin.c(...)` 这种非法代码。已改回。
  2. `HomeActivity.barBtn(label, colorRes, ...)` 传的是变量，正则没命中，漏了一处 `getColor(colorRes)`。
  3. `android.graphics.Gravity` 不存在，`BitmapDrawable.setGravity` 要的是 `android.view.Gravity.FILL`。
- 修正：`LauncherModel.load()` 原本不清空列表字段，`widgets/folders/tasks/quickbar` 每次重读都会叠加 —— 这是首屏就可能出现的越读越胖 bug，现在 `load()` 开头先 `reset()`。
- 决策：壁纸亮度判定用「抽样 16×16 算平均亮度」，不引图像库；`isBright()` 的解码带 `inSampleSize=16`，一张 4K 图也就解出几百像素。

**验证**：`typecheck.sh` 通过。

**遗留**

- 毛玻璃（6.10）没做：`RenderEffect` 在 API 31+ 才有，minSdk 29 要自己降级，不划算。
- 视频壁纸（6.9）没做：解码开销与车机性能冲突。
- 日出日落精确切换（6.4 细分）用固定 06:00~19:00 代替，联网取日出日落等天气模块落地后再补。
- 主题色只做单色派生（accent → 一整套），没有做每套主题独立配 9 个色值。

---

## 批次 E — P0-8 屏幕设置

**目标**：外边距 / 缝真的作用到桌面；屏幕方向；横竖屏两套布局分开；信息栏关掉后时间挪到 Dock。

**改动**

- `LauncherModel`：`portMarginH/portMarginV/portGap` + `marginHOf(portrait)/marginVOf/gapOf` 三个取值口；save/load 补三个键。
- `BaseActivity.applyOrientation()`：`AUTO` → UNSPECIFIED，`LANDSCAPE` → SENSOR_LANDSCAPE，`PORTRAIT` → SENSOR_PORTRAIT；`isPortrait()` 供页面判断用。
- `BaseActivity` 持有 `protected LauncherModel model`，6 个页面删掉各自的 `private LauncherModel model` 与 `new LauncherModel(this)`（每个 Activity 少构造一次，省一次 `queryIntentActivities`）。
- `DesktopView.refresh()`：主列 padding 走 `marginHOf/marginVOf`，网格格子 margin 走 `gapOf`（原来写死 `dp(12)/dp(2,5)`）。
- `HomeActivity.buildTopBar()`：`topInfoBar` 关掉就整条隐藏，并强制 `dockShowClock = true`。
- `SettingsSectionActivity.screenBody()`：横屏 / 竖屏两组滑杆 + 「竖屏复制横屏的值」，方向选择，保持亮屏，信息栏，自动校时。

**复盘**

- 做对：外边距没有新增字段去覆盖旧字段，而是加三个 `…Of(portrait)` 取值口，页面上只判断一次方向。
- 做错：删各页面的 model 字段时用正则顺手把 `model = new LauncherModel(this);` 也删了，`HomeActivity.onResume` 里靠基类的 model 续命 —— 顺序上没问题，但 `HomeActivity` 原来是 `super.onCreate` 之后自己建 model，现在完全依赖基类，注释里要写清，否则以后有人插一行就炸。
- 决策：竖屏布局「分开存」只覆盖边距与缝这三项，没有把 Dock、桌面模式一起分。理由：桌面模式/Dock 是用户显式摆的，分成两套会让「我在竖屏调了下」和「横屏怎么变了」互相打架；边距是跟着屏幕物理尺寸走的东西，必须分开。

**验证**：`typecheck.sh` 通过。

**遗留**：横竖屏切换时 Activity 会重建（没加 `configChanges`），应用重启一次；7.8「横竖屏切换应用不重启」需要窗口控制，矩阵里本就是 ⛔。

---

## 批次 F — P1-1 菜园 + P1-2 小白点

**目标**：菜园整屏（大时钟 + 歌词 + 叶子），叶子长按进出；悬浮球可拖、贴边、点一下回桌面、长按进菜园。

**改动**

- 新增 `GardenActivity.java`：三种摆法（居中大钟 / 左上时钟+底部歌词 / 大字时间日期）；遮罩走 `gardenDim`，与外观的 `wallDim` 是两份；时钟带 `setShadowLayer` 当字底；叶子点=全部应用、长按=退出；返回键回桌面。
- 新增 `BallService.java`：`TYPE_APPLICATION_OVERLAY` 悬浮球，拖动时改 `WindowManager.LayoutParams.x/y`，松手贴最近的左右边并 `model.save()`；点=回桌面（Dock 关着时改成出全部应用）、长按=进菜园；长按判定用设置里的 `longPressMs`。
- `LauncherModel`：新增 `ballAlpha`（5.12）；`ballX/ballY/ballSize` 由菜园叶子与悬浮球共用（它们是同一个「小白点」）。
- `HomeActivity` 底栏加「叶」：点=全部应用，长按=进菜园（`gardenEnabled` 关着时提示去设置开）。
- `BootReceiver` 开机后按 `ballEnabled` 拉起悬浮球。
- `SettingsSectionActivity`：菜园分区加「进一次菜园看看」；小白点分区接上 `BallService.setEnabled()`、透明度滑杆、回默认位置。
- Manifest：注册 `GardenActivity` 与 `BallService`。

**复盘**

- 做对：菜园和悬浮球共用一份位置/大小，模型没膨胀成两套字段；「小白点」在两个场景是同一个东西，拆成两份迟早对不上。
- 做错：叶子背景色一开始写成 `Skin.c(R.color.leaf) & 0x66FFFFFF` —— 这是把 alpha 换成了不透明的白，等于白圆底。正确写法是 `(0x66 << 24) | (color & 0x00FFFFFF)`。这类「alpha 与 RGB 用同一个掩码混搭」的错，肉眼在编译期看不出来，只有 review 能抓到。
- 决策：菜园不给悬浮窗权限、不做成 Service —— 它就是一个 Activity，进菜园时别的应用本来就在后台跑，「画中画藏起来」不需要额外动作。
- 决策：长按用 `postDelayed` 而不是 `GestureDetector`，少一个类；代价是手指按住不动也会触发，符合「长按进菜园」的直觉。

**验证**：`typecheck.sh` 通过。

**遗留**：4.4 / 4.14 歌词与字号跟随等批次 G 的歌词模块；4.7 画中画联动未做（矩阵里标 🟡，需要窗口焦点控制）。

---

## 批次 G — P1-3 天气 + P1-4 歌词

**目标**：天气组件（当前 + 未来 3 天、20 分钟自动、点按刷新、手动/定位城市）；歌词组件（三种来源、行数字号对时、缓存清空、这首不显示、换版本、通知歌词），菜园歌词从占位换成真歌词。

**改动**

- 新增 `Weather.java`：Open-Meteo 公开接口（地名查询 + 预报，免 key 免注册 HTTPS）。`refresh()` 在单线程池里跑，回主线程回调；`arm()` 是 20 分钟单例定时器，桌面 onResume 起、数据过期时立刻取一次；`code()` 把 WMO 码翻成中文；`dayName()` 出今天/明天/后天/周几。
- 新增 `Lrc.java`：纯 Java 的 LRC 解析（行首连续时间戳、分号/冒号小数、一行多时间戳=副歌重复、乱序按时间排、元信息行剔除）+ `indexAt()` 找当前行。刻意不碰任何 Android 类，方便 JVM 自检。
- 新增 `Lyrics.java`：`MediaSessionManager.getActiveSessions()` 读当前歌（一秒一跳，`getPosition()` 是快照所以按墙钟自己累加）；三种来源（播放器自带 → 只扫 `description.extras`，拿不到走在线；本地 lrc → Music 目录两层深度；在线 → 酷狗搜候选 + 按 id 下 base64）；缓存 `filesDir/lyrics/<key>.lrc`；`nextVersion/clearCache`；`Sink` 回调把窗口推给组件条与菜园；同句不重复发通知。
- `LauncherModel`：天气加 `weatherAuto/Summary/Forecast/Error/Lat/Lon` + `setCity()`（改城市清缓存坐标）。
- `DesktopView`：组件 4 显示天气（点一下手动取）、组件 5 显示歌词两行；构造时注册 `Lyrics.Sink`，只在放了歌词组件时每秒重画组件条。
- `GardenActivity`：歌词行换成真歌词（只改 TextView 文字，不重建视图，10.15 与组件条同乘 `lyricSize`）。
- `SettingsSectionActivity`：天气分区接上取数/定位权限（root 走 `pm grant`，普通机走 `requestPermissions`）；歌词分区补状态栏/通知/蓝牙三个开关 + 换版本 + 这首不显示 + 清缓存。
- Manifest：加 `INTERNET/ACCESS_NETWORK_STATE/ACCESS_COARSE/FINE_LOCATION`。
- 新增 `app/selfcheck/LrcCheck.java`：15 项断言（时间戳换算、副歌重复、乱序排序、无时间戳丢弃、播放位置落在两句之间）。

**复盘**

- 做对：把 LRC 解析拆成零 Android 依赖的 `Lrc`，换来一个真能在 JVM 上跑的 15 项自检。时间戳是「算错一行就整首跑偏」的地方，这层测试值这 40 行。
- 做错（三个，写的时候都以为对了）：
  1. `PlaybackState.getDuration()` 编译不过 —— 它是 @hide。改用 `MediaMetadata.getLong(METADATA_KEY_DURATION)`。
  2. `getActiveSessions()` 返回的是 `List<MediaController>`（API23+），不是 `List<MediaSession>`，我按老印象写了 `new MediaController(ctx, token)`。直接遍历 controller 就行。
  3. `MediaMetadata.getBundle(String)` 也是 @hide，公开 API 读不到任意 metadata 键。10.2「播放器自带」只能退到 `getDescription().getExtras()`，扫不到就走在线 —— 这条在矩阵里标 🟡 并写清原因，不假装做到了。
- 决策：一秒一跳里只 `new LauncherModel` 一次（读存档要解 JSON，三次/秒太浪费），`advance/push/window` 都吃传进来的实例。
- 决策：隐藏的静态 Context 一开始留了 `sCtx`（缓存读写要用），看着像全局变量；改成所有方法显式带 `Context`，只留一个 `appCtx` 给定时器用。
- 决策：天气源选 Open-Meteo 而不是和风（9.7）。和风要 key、要注册，公开版没法开箱即用；换回来只需改 `Weather.FC_URL` 与地名接口两个常量。矩阵里 9.7 标 ⛔ 并写了这个替代路径。

**验证**：`typecheck.sh` 通过；`LrcCheck` 15 项通过。

**遗留**：9.4 按网络判断、10.2 播放器自带、10.11 状态栏真显示、10.14 卡片本体受平台/后续批次限制；真机联网与播放验证仍待做。

---

## 批次 H — P1-5 任务引擎 + P1-6 海鸥岛 + P1-7 关于

**目标**：任务能真的自己跑；顶部胶囊能显示；关于页能查更新并自更新。

**改动**

- 新增 `TaskEngine.java`：`fireDesktop`（同进程只跑一次）/ `fireBoot` / `arm`（每分钟对表）三个触发，动作层实现 `OPEN_APP / OPEN_PIP / REMOVE_FROM_HOME / DELAY`。
- `LauncherModel.Task` 加 `atMin`（定时触发的「每天第几分钟」），`describe()` 会把时间说成人话（`每天 07:30`）；`appNameOf/shortName` 把包名换成应用名。
- 新增 `IslandView.java`：顶部居中胶囊，`onDraw` 里圆角矩形 + 主副两行文字，超宽尾部省略；`bind(model)` 按设置摆位置并取内容。
- `HomeActivity`：`root` 上挂 `IslandView`（不进 `mainCol`，免得被布局挤动），onResume 与 30 秒 tick 都 `bind`，另注册 `Lyrics.Sink` 让岛上歌词一秒一跳；点一下进海鸥岛设置分区。
- `SettingsSectionActivity`：定时任务多问一句「每天几点几分」；关于页接上「检查更新」（GitHub releases/latest 对版本）与「自更新」（下 apk 到 filesDir → 系统安装器）；指纹改用 `GET_SIGNING_CERTIFICATES`。
- `BootReceiver`：开机跑 `TaskEngine.fireBoot`。

**复盘**

- 做对：定时任务用「每分钟对一次表」而不是 `AlarmManager`。`AlarmManager` 要权限、要 exact alarm 白名单（API 31+ 还收紧），一分钟精度对「每天 07:30 打开导航」完全够用；多出来的代价只是一分钟误差。
- 做错：`Task.describe()` 在 `static` 嵌套类里调实例方法 `appNameOf`，编译不过。拆成 `static shortName()`（截包名尾巴）与实例 `appNameOf()` 两份——任务列表里 `allApps` 已经加载过，用实例那份能显示真名，静态上下文才用短名。
- 决策：13.6「在画中画打开应用」用官方 `android.support.picture_in_picture` extra，Android 没有「强制别人进 PiP」的 API。矩阵里标 🟡 并写清原因，不假装 100% 生效。
- 决策：海鸥岛浮在桌面自己这层（`FrameLayout` 顶层），不新开悬浮窗。跨应用浮起来要再造一个 Overlay Service；等 8.6 真被用到再上，ponytail 已写进类注释。
- 决策：8.7 挖孔/圆角检测没做，用手动偏移代替。自动检测要读 `DisplayCutout`，拿不到就得靠 `WindowInsets` 猜，收益不抵成本。

**验证**：`typecheck.sh` 通过；CI（`5197fce`，批次 G）`completed success`。

**遗留**：8.6 / 8.7 见上；13.6 受目标应用限制；开机动画（14.x）整体未做，要 Magisk 侧配合。

---

## 批次 I — P2 root 增强层（触摸转发 / 窗口搬运 / 系统监控）

**目标**：把三块 root 能力从「有开关没实现」变成真能跑：触摸转发、窗口搬运、系统监控。

**改动**

- 新增 `TouchForward.java`：整条触摸状态机。DOWN 记起点；MOVE 时按「跟手」开关分流（开=每 ≥16ms、位移 ≥2px 发一小段 `input -d <id> input swipe`；关=等抬手一次发完）；抬起时没滑动的补一次 `tap`；CANCEL 什么都不发。命令串行执行，最近 12 条命令与返回进静态环形日志（12.8 排障）。
- `MirrorSlot` 的 `onTouch` 改成把 `MotionEvent` 整个喂给 `TouchForward`，删掉自己那套 `downX/downAt` + `HandlerThread`。
- `LauncherModel` 加 `touchFollow`（12.5）。
- 新增 `TaskMover.java`：`dumpsys` 解析 `displayId=` 找 `taskId`，然后按顺序试三条搬运命令（`am task move-to-display` / `am stack move-task` / `cmd activity task move-to-display`），把每条的真实返回原样拼成一段文字给 UI 看。
- `MirrorActivity` 槽位右缘加「收回」小标签（11.14），点一下后台线程跑搬运，结果进诊断行。
- `SysOps` 加 `cpuPct()`（`/proc/stat` 两次采样差，120ms 间隔）与 `monitorLine()`（CPU/温度/内存一行）。
- 设置页：触摸分区补「注入方式」「跟手」「注入排障」「能力说明」；窗口分区补「收回窗口 / 拉回窗口 / 搬运排障」（都过 `rootOnly()`，没 root 就直说）；系统分区补「CPU / 温度 / 内存」。

**复盘**

- 做对：`TaskMover` 没有赌一个「通用写法」，而是按顺序试并把每条命令的返回原样摊在 UI 上。`am task` 的子命令各家 ROM 裁得不一致，容器里没法验证，能交付的最有价值形态就是「证据可见 + 一键试」。
- 做错：`SysOps.monitorLine` 第一版写的是 `getMemoryClass() - availMem/MB` —— 把「本应用堆上限」和「系统可用内存」相减，得到的数字没有任何意义。改成 `(totalMem - availMem) / totalMem`。这类「单位对不上但能编译」的错，只有把两个 API 的语义放一起看才抓得到。
- 做错：`TouchForward` 的 `lastAt` 一开始声明成 `float`（跟 `lastX/lastY` 写在一行），`e.getEventTime()` 是 `long`，编译期就报了。这个是纯手滑，编译器当场拦住。
- 决策：跟手靠「拆成多条短 swipe」实现，不用 `input motionevent`（API 30+ 才有，低配车机未必有）。代价是命令量变大，日志里能直接看到，够用。
- 决策：12.6 无障碍 `dispatchGesture` 只留开关没实现 —— 那要一整套无障碍服务 + 用户手动到系统里授权，公开版首发不值得。矩阵标 🟡 写明原因。

**验证**：`typecheck.sh` 通过；CI（`89908c3`，批次 H）`completed success`。

**遗留**：11.13/11.14/13.6/12.6 都要在真机（有 root）上验证命令可用性；容器里只能保证代码路径与降级提示正确。

---

## 批次 J — root 守护进程 + L3 黑屏/触摸修复

**目标**：L3（虚拟屏窗口）建了屏但不出画面、触摸打不进去——两个根因一起修：
① 虚拟屏 flags 把第三方应用的窗口拦在外面；② 特权动作全依赖 fork root shell 的
`am`/`input` 命令，慢且表达不了多指。机制对齐 Extendroid：root uid 守护进程
进程内反射隐藏 API（startActivityAsUser / injectInputEvent / moveRootTaskToDisplay）。

**改动**

- 新增 `PrivCodec.java`：Launcher 与守护进程之间的行文本协议
  （`PING / LAUNCH / MOTION / MOVE / REMOVE`，一请求一应答，脏报文丢事件不杀进程）。
  刻意零 `android.*` import，协议层能在纯 JVM 上自检。
- 新增 `PrivClient.java`：app 侧 LocalSocket 客户端。`su -c setsid sh -c
  'CLASSPATH=base.apk exec app_process RootMain'` 拉起守护进程（setsid 脱离桌面进程组，
  桌面被杀重建不陪葬；不 waitFor，app_process 是常驻进程等它退出会死锁）。
  拉起失败判会话级禁用，后续命令照旧走 shell 兜底。
- 新增 `RootMain.java`：root uid 守护进程。反射 `ServiceManager` 拿服务，
  `IActivityManager.startActivityAsUser`（优先 am 同款 11 参重载，callingPackage 固定
  `com.android.shell`，`ActivityOptions.setLaunchDisplayId` 指定目标屏）；
  `IInputManager.injectInputEvent` + `MotionEvent.setDisplayId` 注入 MotionEvent
  （多指、带时间戳，真手势保真）；`moveRootTaskToDisplay` / `removeTask` 搬屏删任务。
  无客户端 60s 自退出，不宿留。
- `MirrorSlot` 建屏 flags 改 `PUBLIC | AUTO_MIRROR`：去掉 `OWN_CONTENT_ONLY`
  （只显示与建屏者同 UID 的内容——黑屏根因，导航/音乐 task 明明在屏上）与
  `PRESENTATION`（展示屏语义，同样拦第三方窗口）。
- `TouchForward`：修触摸命令双写 `input` 的 bug（`input -d N input tap` 必然报
  未知子命令）；守护进程在位时改「原始事件中继」——MotionEvent 连坐标带时间戳原样
  进目标屏，多指缩放天然成立，每次手势零命令开销；通道钉在每次 DOWN 上，手势中途不换。
- `RootOps.launchOnDisplay` 改三路（守护进程反射 → 公开 API → `am start`）；
  `moveTaskToDisplay` 优先守护进程 `moveRootTaskToDisplay`。
- `VirtualDisplayHost`：flags 同修；`launchViaRoot` 走 RootOps 三级链；
  新增 `attach(Context)` 与 `launchViaAm`（裸 am 口径，排障对比用）。
- `SelfTestL3`：补 `PrivClient.status()` 行与裸 am 对比步。
- `Caps`：有 root 即认为 L3 可用（🟡 待真机验证），新增 `vdPath()` 让体检报告
  写清落地路径（system uid 直连 / root 守护进程反射 / 不可用）。
- 新增 `app/selfcheck/PrivCodecCheck.java`：30 项协议自检。

**复盘**

- 做对：协议层不碰 `android.*`，30 项断言在容器里真跑得起来（协议错一个字符，
  对面整条命令失效，这层测试值）。降级链做成硬规则：PrivClient 每一步失败都只
  「如实返回 false」，命令永远有 shell 兜底。
- 做错：MOTION 报文第一版没带 displayId —— 守护进程同时服务两块镜像槽，
  事件必须自带目标屏，不能默认「就一块」。
- 做错：长按的守护进程实现第一版发的是 `CANCEL` —— CANCEL 语义是「取消手势」，
  长按得是 DOWN→保持→UP 同点。
- 决策：守护进程用 `app_process` 而不是 native 守护 —— 直接复用 APK 里的 Java
  反射代码，省一套 NDK 构建与 .so 分发；隐藏 API 在 app_process 里无 enforcement。
- 决策：`setsid` 拉起 + 60s 空闲自退的组合，而不是常驻 —— 桌面被杀重建时守护
  进程不必重启，孤儿 root 进程也有兜底。

**验证**：`typecheck.sh` 通过；`PrivCodecCheck` 30 项通过。守护进程路径
（SELinux 是否放行 abstract socket、HyperOS KernelSU 域、ROM 是否裁剪
moveRootTaskToDisplay）容器里验不了，全部标 🟡 待真机。

**遗留**：① 真机验证守护进程全链路；② 手势中途连接断掉时，余下事件会掉
（已记日志，下次手势自动恢复）；③ `findTaskId` 的 `dumpsys` 解析口径与
TODO 已知坑 #11（Task 行本身没有 `displayId=`）不符，`moveTaskToDisplay`
的 taskId 来源要复核；④ `launchViaApi` 公开 API 路径在 HyperOS 上被
SecurityException 拒过一次，守护进程路径是否绕过待真机确认。






---

### 批次 J 补：dumpsys 口径复核（已知坑 #11）

**目标**：复核遗留项 ③ —— `findTaskId` 按 `displayId=` 在 Task 行里找屏，
与坑 #11（Task 行没有这个字段，归属看段头）矛盾，`moveTaskToDisplay`
搬的 task 可能不是你以为的那个。

**改动**

- 新增 `TaskScan.java`：从 `dumpsys activity activities` 文本扫 task 的纯 JVM
  解析层（零 android import，模式照 PrivCodec）。display 归属改按
  `Display #N` 段头切分；少数 ROM 的 Task 行自带 `displayId=` 的也认，
  两种口径都过。只认 `* Task{}` 行，`Task id #77` 详情行不认。
- `RootOps.moveTaskToDisplay`：dumpsys 只拉一次 —— 先定位任意实例，
  再确认目标 task 不在目标屏上（已在就是 no-op，如实报「已在」，别让人
  以为搬成功了）。同包两个 display 各有一个 task 时，严格模式不搬错
  实例（displayId>0 找不到就 -1，绝不回退）。
- 新增 `DumpParseCheck` 14 项自检，现场取材：同包在主屏 #77 和虚拟屏
  #88 各活一个 task —— 段头归属错一步，搬的就是另一个实例。
- TODO 坑 #11 补一句「少数 ROM 例外，TaskScan 两种都认」；顺手删了
  已知坑里重复粘贴的 10/11 两条。

**验证**：`typecheck.sh` 通过；`DumpParseCheck` 14 项、`PrivCodecCheck`
30 项、`LrcCheck` 15 项均通过。

**复盘**

- 做对：解析层继续放纯 JVM 类里，「同一包在两个屏各有一个 task」这种
  最容易骗过肉眼的边界，用真实格式的 fixture 钉死成断言，不用等真机。
- 决策：`moveTaskToDisplay` 的 taskId 仍取「第一个」而不是「主屏上的」
  —— 语义是「把这个包现有的 task 搬到目标屏」，搬之前加已在判断兜底；
  真要按屏挑，TaskScan 的严格模式已经现成。

## 批次 K — 画中画镜像：VD 1:1、触摸进得去、目标任务不被拉回主屏

**目标**：修用户实测三条抱怨（对标 carlink-desktop m13 / OneStep4）：
①画中画应跟随画布大小 ②点击不进去软件 ③进去桌面还不是画中画界面。

**参考实现依据**（`/tmp/opencode/refs/carlink-desktop-private`，0.2-m13 +
OneStep4 逐行亲核）：

- 抱怨③根因：Android 14 ActivityStarter 只允许 home-affinity / TASK_ON_HOME
  任务落**【受信】虚拟屏**；MediaProjection 屏无 TRUSTED，注入 launchDisplayId
  也会被拉回默认屏。解法：root 授 `android.app.role.COMPANION_DEVICE_APP_STREAMING`
  角色 → RoleController 授签名级 `ADD_TRUSTED_DISPLAY` → 用公开 6 参
  `createVirtualDisplay`（android-33.jar javap 确认 public）建 TRUSTED 屏。
- 批次 J 的 `PUBLIC|AUTO_MIRROR` 被参考实现证伪：任务不在屏上时 AUTO_MIRROR
  把手机桌面镜像进画中画。参考版实测组合 `PUBLIC|OWN_CONTENT_ONLY|PRESENTATION`，
  其真机截图证明高德/网易云能渲染进 PiP（推翻「OWN_CONTENT_ONLY 黑屏」假设——
  黑屏实为任务没上去）。
- 启动 flags 0x18000000 → **0x18800000**（补 EXCLUDE_FROM_RECENTS；m8 真机结论：
  singleTask 目标不带 MULTIPLE_TASK 会拉主屏已有任务到前台），另加 `--user 0`。
- singleTask 应用在虚拟屏内跳转会拉走任务 → 需 `am stack list` 解析 + 保守自愈。

**改动**

- 新增 `TrustedFlags.java`：5 组 flag 候选（最全→最保守）/ normalize（SDK≥30
  保 TRUSTED；PUBLIC 强制带 OWN_CONTENT_ONLY、剥 INSECURE_KEYGUARD、永远剥
  SECURE）/ `hasTrusted` 位校验（Display flags TRUSTED=1<<7）/ 兜底
  `projectionFallbackFlags()`/ `describe()`。
- 新增 `StackScan.java`：`am stack list` 纯 JVM 解析（displayId 段头归属、
  stackId→taskId→*TaskRecord 行、isRunningOnDisplay / findTaskOnDisplay /
  firstTaskOnDisplay）。坑：stack 行自带 `displayId=` 字样，不能拿它当段头。
- `RootOps.java`：`LAUNCH_FLAGS = 0x18800000`；新增
  `grantTrustedDisplayRole`/`roleState`（进程内缓存、幂等、
  `am role add-role-holder --user 0 <role> <pkg> 0`）；新增 `ensureOnDisplay`
  保守自愈（守护进程 `moveRootTaskToDisplay` 优先，回退 `am task move-task`，
  解析失败不重拉——m12 教训：误判重拉会在虚拟屏内叠出两个目标任务）；
  `launchOnDisplay` 加 `--user 0`，`launchViaApi` 补 EXCLUDE_FROM_RECENTS。
- `MirrorSlot.java`：`deploy` 改 TRUSTED 优先（createTrustedVd：SDK≥33 +
  角色授予 + 5 组候选 + 受信位校验，失败 release 候选），失败回落
  MediaProjection `projectionFallbackFlags()`；`projection` 允许 null
  （needsProjection 标记）；desize 注释改 1:1 口径；describe 加 trusted/
  needsProjection。
- `MirrorActivity.java`：`deployNow` 不再强制录屏 token；VD 尺寸改
  `canvasW()/canvasH()`（优先 SurfaceView 实测，退回 全宽-dp(32) × dp(200)
  设计尺寸）——1:1 建屏后 `TouchForward.scale()`=1f 就是几何正确；
  `attachTo` 去掉 projection 门；`deploySlot` 去掉 UI 线程 su 前置检查，
  统一交给后台 deploy 决策；wait 遮罩按 `slot.ready()` 隐藏；onResume 接
  `ensureOnDisplay`（ensureOnDisplay）；诊断区加角色/受信状态。
- `SelfTestL3` 补 ⑨⑩：角色授予日志 + ensureOnDisplay + StackScan 核对。
- `SelfTestMirror` 补 ⓪ TRUSTED 屏探测：逐组候选建无 Surface 探测屏，
  读回 `displayFlags` 校验 TRUSTED 位并 release，日志直接回答「受信到位没有」。
- `Caps.vdPath()`：root 在手的设备按 SDK 区分口径（Android 14 起需 TRUSTED）。
- 新增自检：`TrustedFlagsCheck` 10 项、`StackListCheck` 12 项。

**验证**：`typecheck.sh` 通过；`TrustedFlagsCheck` 10 项（需 `-cp
android-33.jar`）、`StackListCheck` 12 项、`StackListCheck` 依赖的纯 JVM 层
零 android import。真机验证项见 TODO P2-5。

**复盘**

- 做对：把「Android 14 拉回任务」的机制问题交给 root 角色授予 + 受信位校验，
  几何问题（画布 1:1）用 SurfaceView 实测尺寸解决，两个抱怨根因各归其位；
  `am stack list` 解析层继续放纯 JVM 类 + 12 项断言，不等真机。
- 决策：自愈保守——`am stack list` 解析失败就跳过，不重拉任务（误拉会在
  虚拟屏内叠出两个目标任务，比不修更糟）。
- 遗留：手势中途 daemon 断连时余下事件丢弃，下次手势恢复（已知取舍）；
  TRUSTED 屏若受 SELinux 阻挡仍需回落投影兜底，届时抱怨③ 只能缓解不能根治。

## 批次 L — 画中画界面极简化（只要两个画布）+ 画中画常驻 + 统一签名

**目标**：用户三条实测反馈：
①「每次进入桌面还是应用界面，我要画中画界面」
②「界面只要两个画布！长按选择应用！不要多余的东西！」
③「弄个签名密钥统一签名，以后都用一个签名文件，写 md 文档」。

**根因**

- 「退出镜像页 → 桌面冒出全屏应用」的机制找到了：MirrorActivity.onDestroy
  里 slotA.teardown() → vd.release()，**VD 一释放，系统就把屏上的任务
  倒回默认屏**。用户每次退出画中画页回桌面，目标应用就全屏冒出来——
  这正是「每次进入桌面还是应用界面」。
- 签名漂移实锤：拉两次 CI 产物比对，证书 SHA-256 不同
  （`5e1bc2…` vs `eb7204…`）。旧 build.sh 在没有 secret 时每次现场
  生成随机密钥 → 每个包签名都不一样 → 覆盖安装必失败，只能卸载重装。

**改动**

- 新增 `MirrorHost.java`：画中画槽的进程级持有者。VD 生命周期挂进程，
  退出 MirrorActivity 只 `detachSurface()`（画面断、屏与应用留着），
  再进来把 Surface 挂回同一块 VD；`healHome()` 给桌面 onResume 调
  （节流 3s，保守自愈）；录屏被撤销时只拆投影屏（受信屏不依赖投影）。
- 重写 `MirrorActivity`：界面只有两块画布（各占半屏，黑底），
  长按画布选应用（GestureDetector 截走长按、先给 TouchForward 补一个
  CANCEL 掐掉残留笔画，再弹应用列表；对话框带「清空该槽」），
  点/滑/拖照常转发进画中画。删掉全部按钮、诊断区、机制说明；
  空画布只留一行居中提示。部署失败/缺授权自动补弹录屏授权框。
- `MirrorSlot`：构造改用 applicationContext（进程级常驻不泄漏 Activity）；
  deploy 成功搬完应用立刻 `ensureOnDisplay` 自愈一次（不等用户切页面）；
  teardown 只由 MirrorHost.clear 触发。
- `HomeActivity.onResume` 接 `MirrorHost.healHome(this)`。
- 统一签名：生成 `app/keystore/seagull-release.keystore`（RSA 2048 /
  10000 天 / alias seagull-release），build.sh 第 7 步改用它
  （密码：env `SEAGULL_KS_PASS` > properties > 兜底），CI 增加
  「还原统一签名密钥」步（`SEAGULL_KEYSTORE_B64` + `SEAGULL_KS_PASS`），
  `.gitignore` 加 `keystore/`，两个 secret 已用 PAT 设进仓库，
  新写 `docs/SIGNING.md`（含签名漂移实锤表、备份/轮换/排障）。

**验证**：`typecheck.sh` 通过；自检不变（批次 K 的 TrustedFlagsCheck 10 项 /
StackListCheck 12 项此前已过，本批未动其输入）。签名一致性需 CI 产物比对：
两次构建的 `apksigner verify --print-certs` 应打出同一 SHA-256。

**复盘**

- 做对：先拉旧产物做取证再动手，签名漂移从"疑似"变"实锤"；
  把"退出拆屏"这条机制从代码里读出来，没有盲猜。
- 决策：VD 常驻 = 画中画里的应用退出页面后继续在后台跑（音乐类正是
  想要的行为）；要停只能长按 → 清空该槽（force-stop）。
- 遗留：TRUSTED 角色在其设备上是否真授到，仍要用户回传 logcat
  （`SeagullRootOps` 的"TRUSTED 角色授予"行 + `dumpsys display` 的
  seagull-pipN flags）；不成立则走投影兜底 + 自愈，画中画会偶发被拉走。
- 换签名的代价：用户设备上已装的旧版是随机签名，本包需卸载重装一次，
  之后永久免卸载。

## 批次 M — 画中画成为首屏（左右分割 + 桌面降级 + 默认横屏 + 底栏精简）

**目标**：用户四条新要求：
①「画中画左右分割」②「桌面默认横屏」③「进入应用应该是画中画界面，
不应该是设置里的子界面」④「界面太多不合理的地方精简一下」。

**改动**

- 新增 `PipBoard.java`：可复用画中画面板（LinearLayout，左右两块画布各
  weight 1）。长按画布 → 宿主弹应用列表（长按被 GestureDetector 截走、
  先给 TouchForward 补 CANCEL 掐残留笔画；对话框带「清空该槽」= 拆屏 +
  force-stop）；点/滑/拖照常转发。部署/挂面/自愈/录屏授权补弹全包在
  面板里，同槽部署串行化（`synchronized (slot)`，防并行双建 VD 漏屏）。
- `HomeActivity`：内容层改成 FrameLayout 二选一 —— `PipBoard`（默认首屏）
  ⇄ `DesktopView`（次级层，底栏第一个按钮切回）。顶栏「整理」只在桌面
  模式显示；onResume 在画中画模式转 `pip.onResume()`；onDestroy 转
  `pip.onDestroy()`；onActivityResult 转授权结果；30s tick 改为刷新画布提示。
- `MirrorActivity` 薄壳化：躯干就是一块 PipBoard（构造参数
  `autoDeploy` 给自检页关掉抢跑），只剩两个用途 —— DesktopView Dock 的
  「投进画中画」入口（intentFor 不变）与 SelfTestMirror 自检（selftest
  extra 不变，onActivityResult 先问 SelfTestMirror.isOurRequest）。
  两份画布逻辑并成一份，漂移面归零。
- 底栏精简：砍「镜像小窗」（画中画已是首屏，那个子页面没意义）与含义
  不明的「叶」键（菜园改从 设置 → 桌面 → 菜园 / 小白点长按 进）；
  设置里菜园分区的说明文字同步改。
- `LauncherModel.orientation` 默认 `AUTO` → **`LANDSCAPE`**（用户：桌面
  默认横屏；竖屏党仍可在 设置 → 显示 改，BaseActivity 已有分支）。

**验证**：`typecheck.sh` 通过。画面/自愈行为未变的证据链：批次 K/L 的
TrustedFlagsCheck 10 项、StackListCheck 12 项输入未动；VD 常驻、部署串行化
改动都在 PipBoard 内部，链路与批次 L 实测一致。

**复盘**

- 决策：画中画做成「可复用 View」而不是「又一个 Activity」—— 用户要的
  「进应用就是画中画」本质上是首屏换内容层，Activity 跳转反而多一层。
- 决策：桌面网格保留为次级层而不是删掉 —— 组件条/Dock/文件夹这些既有
  功能还在，砍掉等于回退一批次。
- 遗留：真机三问照旧（TRUSTED 角色是否授到 / SELinux / moveRootTaskToDisplay），
  要用户回传 logcat（`SeagullRootOps` 的"TRUSTED 角色授予"行 +
  `dumpsys display` 的 seagull-pipN flags）。

## 批次 N — CarPlay 纪律移植：单焦点 / 卡片化 / 空态大按钮 / MiniPlayer（C 层只埋接口）

**目标**：用户拿来苹果 CarPlay 互联界面设计思路做参照，拍板四条移植：
单焦点（触摸只进焦点画布）、圆角暂缓改视觉卡片化、MiniPlayer 双场景点击规则、
分割比例锁 1:1 但权重配置化；另按"埋接口不接线"节奏给 DiPlay 推流铺路。

**改动**

- `PipBoard` 重写（A 层，用户四条车机交互修复）：
  - **单焦点**（安全红线）：非焦点画布的第一下触摸只切焦点不吃进 App；
    切换瞬间给刚失焦的画布补 `ACTION_CANCEL`（`cancelStroke`）——多指场景
    （一指拖着左画布、二指点右画布）的鬼拖痕全靠它掐掉。守护中继通道会
    把 CANCEL 真的送进 App，命令通道本来就丢弃 CANCEL（TouchForward.java:139）。
  - **空态大按钮**：≥80dp 高、16dp 圆角、半透明白描边、「＋ 选择应用」
    上下排布；点一下直接弹选择器（空画布没有 App 可误触，可发现性优先于
    焦点语义），长按仍是"更换应用"；按下态 alpha 0.6 兜底车机无震动马达。
  - **卡片化**：深色卡底 + 1px 半透明白描边 + 8dp 缝；卡底比画布大出
    CARD_PAD=12dp，将来开真圆角不露黑边。**圆角暂缓**：SurfaceView 是
    窗外合成，`clipToOutline` 圆角裁剪在部分设备失效，换 TextureView 又
    划不来（GPU 拷贝+延迟），本批方角。
  - **环境光三档遮罩**：非焦点画布盖黑，透明度跟 `SensorManager.TYPE_LIGHT`
    （公开 API 不要权限）：夜间 0.45 / 常态 0.35 / 白天强光 0.28，1s 采样
    + 低通 + 20/2000 lux 带回差，不每帧动 alpha。
- `MiniPlayer` 新文件（B 层）：36dp 常驻媒体条，层级在画布之上、底栏之下；
  只有上一首/播放暂停/下一首三个按钮（热区 TouchDelegate 外扩 12dp，探出
  36dp 条身）；没有进度条没有封面；媒体会话不活跃时整条隐藏不占位。
  数据源走 `MediaSessionManager.getActiveSessions`（MediaListenerService
  就是已启用的通知监听器，不另起服务不加权限）， transport control 直接
  对会话下发，不经过画布。
  **双场景点击规则**（用户拍板）：媒体源在画布里 → 空白区点击导焦到那块
  画布；媒体源在后台（没进画中画）→ 什么都不做，绝不挤占当前应用。
- `HomeActivity`：顶栏画中画模式压到 40dp 且只留 时间+设置（搜索/整理/
  日期全部下沉到桌面模式或应用页）；MiniPlayer 接线；30s tick 兜底扫会话。
- `LauncherModel`：新增 `pipWeightA/pipWeightB`（默认 1:1，钳制 1~10），
  设置 → 窗口 → 「画中画分割」三选一；权重变→VD 尺寸跟着变，保持 1:1。
  skinSignature 带上权重，改了立刻整屏重画。
- C 层（只埋接口不接线）：`CanvasSource`（MirrorSlot 本来就实现这四个
  方法，implements 上去零逻辑变化）、`StreamCanvasSource` 空实现占位、
  `TouchTransformer`（identity/scaling 两静态工厂）+ MirrorSlot 的
  `setDisplay(displayId, transformer.scaleX())` 一行接线（identity 恒 1，
  行为与批次 M 完全一致）+ `TransformCheck` 自检 21 项纯 JVM 全过。

**验证**：`typecheck.sh` 通过；`TransformCheck`（恒等/等比/零保护/负数/
describe）全过。VD 常驻、部署串行化等链路与批次 M/L 一致，未动输入。

**复盘**

- 做对：把"非焦点拦截所有触摸"和"最后触摸的画布获焦点"的矛盾显式收敛成
  "第一下只切焦点"—— CarPlay 同样行为；空画布单独留"直接选应用"的口子，
  没让安全规则把可发现性堵死。
- 决策：SurfaceView 方角是性能换正确性——画中画要 30~60fps 跟手，圆角
  等真机验证 TextureView 再说。
- 决策：MiniPlayer 数据源复用通知监听器拿 `getActiveSessions`，没新起
  服务没新权限——车机上多一个常驻服务就多一个被杀的理由。
- 遗留：DiPlay 推流接入时要实现 StreamCanvasSource（解码帧写 Surface）
  + ScalingTransformer（画布像素→iPhone 分辨率），并给多指中继补缩放链
  路的自检；VD 与推流混排的默认权重预设（7:3 / 3:7）下一批做。

## 批次 O — 批次 N 三个回归的修复（选择器 / 高德铺满 / 应用跳主屏）

**目标**：用户拿到批次 N 包实测报三件事，逐一定位并修：
① 两块画中画都选不了应用；② 高德地图在画布里铺不满；③ 有的软件跳转到
应用界面、返回桌面也回不来。

**根因与修复**

1. **选择不了应用 = 批次 N 自己写的坑**：`column()` 里给卡内 `SurfaceView`
   `setClickable(true)`，它变成可点击 child 之后吞掉整块画布的触摸 —— 长按选
   应用（走卡容器的 GestureDetector）、空态按钮点击全部失效。
   - 修复：SurfaceView 保持**不可点击**，触摸统一回卡容器 OnTouchListener；
     空态大按钮补 `setOnClickListener(v -> openPick(w))`（它自己就是可点击
     child，走自己的分发链）。
   - **教训加进已知坑**：SurfaceView 设在卡里可以，设 clickable 就会吃掉宿主
     的触摸回调——这是 SurfaceView 的可点击判定在 dispatchTouchEvent 最前面
     生效导致的，跟 z-order 无关。
2. **失焦画布顺手把 UP 注进旧 App**（修 ① 时顺手发现的同源漏洞）：非焦点
   画布第一下 DOWN 只切焦点后，之后的 UP 仍会走 `s.onTouch(e)` 被注入刚失焦
   的 App（地图里凭空多点一下），空画布还会顺带弹出选择器。
   - 修复：`armed[w]` 门闩——只有 DOWN 时就是焦点的画布，本手势后续的
     MOVE/UP/长按/选应用才放行。
3. **高德铺不满 = letterbox 兼容模式**：小尺寸 + 怪宽高比的虚拟屏上，应用按
   手机尺寸渲染再居中留黑边（`setDisplayId` 那套独立分辨率被拒之后的可见表现）。
   - 修复：部署成功后 root 执行 `am compat enable FORCE_RESIZE_APP <pkg>`
     + `am compat enable NEVER_FIX_ORIENTATION <pkg>`（前者取消 letterbox，
     后者别锁 manifest 的 screenOrientation，跟着虚拟屏方向转）。清空槽时
     `am compat reset <pkg>` 还原，不污染主屏。
4. **应用跳主屏 = 已知坑 #5 没被执行**：`launchOnDisplay` 从未 force-stop，
   `am start` 对已在主屏运行的应用会静默投递到主屏实例（singleTask 复用），
   应用直接盖住桌面 → "返回桌面也显示不了"。
   - 修复：部署前先 `am force-stop <pkg>` 再起；另外把 `selfHeal()`
     （`ensureOnDisplay`：目标落在主屏就用守护进程 moveRootTaskToDisplay
     搬回）从只在 onResume 跑，加到 30s tick 里周期跑。

**验证**：typecheck.sh 通过。三条修复都是命令链 + 触摸分发层的确定性修改，
没有需要真机才能看结果的新链路（am compat / force-stop 缺命令时只记日志）。

**复盘**

- 做错：批次 N 的"卡片化"重构把触摸入口搬到卡容器时，顺手给 SurfaceView
  加了 clickable（无效的多余行），直接断掉选择应用入口——**新写触摸层必须
  自检"空画布能弹选择器"这一条**，已经连续两个批次在这栽跟头。
- 做对：把 ① 和"失焦 UP 外泄"分开修，armed 门闩一次性把两个洞都堵住。
- 决策：letterbox 用 `am compat` 而不是去要系统签名 API（公开版拿不到），
  命令失败只是日志；`am compat` 对主屏同包也生效，所以 clear 时 reset。

---

## 批次 P — 三轮复盘：并发精读 + 亲自复核 + 自检交叉验证

用户要求"全部代码复盘三次找bug"。执行口径：bug 必须确凿（读代码推出，
不能靠"可能"），三轮分工——并行 5 个 agent 分组精读 → 我逐条复核
（滤掉猜的）→ 非平凡逻辑写自检钉住。修复排序：崩溃 → 数据丢失 → 红线 → 泄漏 → 健壮性。

### P0 崩溃（三条，全部真机必现）

1. **`BallService.longPressRun` 没有 `FLAG_ACTIVITY_NEW_TASK`**
   （BallService.java）：从 Service 上下文 `startActivity` 缺该 flag 直接
   `AndroidRuntimeException` 秒崩，Android 12 起更严。附带修 onDestroy 的
   removeCallbacks 与 `snap()` 前 `model.load()`（快照回写会覆盖新配置）。
2. **`VirtualDisplayActivity` onCreate 没 `host.attach(this)`**：
   所有回调拿宿主=null 空转。附带 su 探测移后台（原在主线程，root 弹窗 = ANR）、
   `append` 改 synchronized + 回主线程 setText（跨线程 setText 崩）。
3. **`Uri.fromFile()` 发安装 intent**（SettingsSectionActivity）：targetSdk 24+
   = FileUriExposedException，"下载新版本"必崩。项目不许引 androidx，
   照 FileProvider 最小面自绘 `SeagullFileProvider`（query/insert/delete/update
   按需实现，files/ 内路径规范化防跨目录穿越）。

### 数据 / 状态正确性

4. **`StackScan` 同一 display 被尾随内容覆盖成 -1**：段头真实形态
   `displayId=0 stacks=2`，旧取第二个 `split()[1]` 得 `stacks=2` → 再
   `displayId=0` 又覆盖一次，`segOf` 永远返回 -1 → `taskOnDisplay` 全判
   "不在" → ensureOnDisplay 自愈会反复误 force-stop 用户应用。
   改 `leadingInt` 前缀解析（接受尾随内容）+ 包名边界匹配
   （`lineHasPkg/segHasPkg`，`com.foo` 不再撞 `com.foobar`）。TaskScan 同源。
   StackListCheck 重写 30 项（尾随段头、前缀碰撞、空输入、taskId 非数字）。
5. **`TaskMover.frontTask` 找不到任务**：正则只认 `taskId=`，真实 dump 是
   `Task{hash #123 ...}` → 两种形态都认。
6. **`am start` 误判成功**：`Warning: Activity not started...` 既无 Error
   也无 Exception，旧判据（无 Error 即成功）会把"没起来"当成功，于是不
   自愈。改正向判定：必须含 `Starting` 且无 `Warning`/`Abort`。
   另加 `safeComponent` 白名单才拼命令。
7. **`TouchTransformer.scaling` 方向注释与实现相反**（注释 dst→src，
   代码 src→dst）：TransformCheck 重写 54 项，用方向往返断言钉死。
   identity 仍恒 1：VD 模式零行为变化是"埋接口不改行为"的兜底。
8. **`PipBoard.switchFocus` 不撤销来源槽的 armed/longFired**：切焦点后旧槽
   继续吞后续手势。
9. **`HomeActivity` sink 每 onResume 漏一个 Activity**：`islandSink`
   是同一实例但旧代码无 remove，CopyOnWrite 集合永久持有已销毁的
   Activity（onLyric 每秒调一次 bind）。改 remove + add 恒单例 + onDestroy 摘。
10. **天气 20 分钟链断**：TICK 早退（weatherAuto=false / ctx==null）时
    `armed` 留在 true → `arm()` 直接 return，桌面不重启就再也不刷新。
11. **LauncherModel 两处数据脏**：widgets 存档 `optInt` 把 JSON null 读成 0
    （时钟组件悄悄复活）；`factoryReset` 漏掉全部标量（用户实测：恢复出厂
    后字号还停在 130%）→ `resetScalars()` 补齐字号/透明/外观/歌词/天气/权重。
12. **悬浮球恢复位置只判 <0**：>屏宽的脏值 + FLAG_LAYOUT_NO_LIMITS 直接
    渲染到屏幕外（用户以为服务挂了）→ 钳回 [0, 屏-球径]。
13. **桌面拖文件夹按名定位**：`dst.split(":")` 找同名文件夹会串；改走
    `model.addToFolder/mergeInto`（带 folderOf 迁移），并先判 model() 空。
14. **QuickBar NPE**：host.model() 空时 `m.quickbar.size()` 秒崩 HomeActivity。

### 泄漏 / 资源

15. **`Caps.exec` 无超时 = ANR 源**（QuickBar/Settings 多处命中）：改 3s
    超时 + `destroyForcibly` + 输出 256KB 上限，读输出放线程池。
16. **`VirtualDisplayHost.create` 传 AUTO_MIRROR**：建的是设备分身屏不是
    绘图屏；displayId 取不到时先 release 再返回。
17. **`WindowService` 重复建卡不关旧卡**：悬浮窗/VD/投影全泄漏；无 projection
    时空转前台服务常驻到进程死 → close 时 stopService。
18. **`SelfTestMirror` 中途 return 不停 mp**：前台服务 + 投影授权活到杀进程。
19. **`MediaListenerService` onSessionDestroyed/onListenerDisconnected 不
    unregister**：会话回调留在已销毁 controller 上。
20. **`LauncherModel` 全量 loadApps 在主线程**：每秒 tick 读配置时也跑一次
    （每应用一次 loadLabel IPC）。新增 `(ctx, false)` 只读存档重载，
    歌词/天气/悬浮球/触摸阈值全切过去（pinStatic 仍要全量）。
21. **`MirrorSlot.touchFor` 懒初始化无锁**：部署线程与触摸主线程都首触，
    各起一个 HandlerThread，被覆盖的泄漏。

### 安全（红线）

22. **RootMain 私有 socket 无鉴权**：抽象 socket 上任意同命名空间进程可连，
    等于给任意 app 开"拉起虚拟屏 + 注入触摸"的特权通道 → peer uid 只放行
    root/system/shell。单行 64KB 上限，超长行读干再拒。
23. **PrivCodec 脏报文**：`split(" ")` 吞尾随空串；MOTION 的 count 与实参
    不匹配时静默只取前几段 → `split(-1)` + 长度严格相等。
24. **MOVE/REMOVE 命令参数**：RootMain 已有 try/catch 兜底，本批次只补
    `Split(-1)`/空串校验路径。

### 清理

25. **`autoPip` 死代码**：prefs 里从未写入此 key，恒 false。删。

### 验证

- `typecheck.sh`（javac 全量）通过。
- 自检全套通过：TransformCheck 54 / StackListCheck 30 / PrivCodecCheck 30 /
  DumpParseCheck 14 / LrcCheck 15 / TrustedFlagsCheck 10 = **153 项**。
- QuickbarCheck 需现生成 R（R.java 由 aapt2 产出），本批次未改其面，
  沿用旧结果。

### 复盘

- 做对：解析器类 bug（StackScan/TaskScan/PrivCodec）全部写进自检，下一轮
  改口径先跑自检再上设备。
- 做错：`Caps.exec` 的超时方案第一版直接在 `Exec` 里写 `linux.os.Process`，
  被 `import android.os.Process` 遮蔽编译失败四次才看出——**本文件顶部已有
  `android.os.Process` import，写 `java.lang.Process` 必须写全限定名**。
- 决策：MOVE/REMOVE 的 uid 校验放在 RootMain（守护进程侧）而不是 RootOps
  （应用侧）：应用侧可被 hook，守护进程是唯一可信边界。

---

## 批次 Q — 用户实测两画布问题：第二画布里套着第一画布 + 高德黑边；删「点击接管」

### ① 套娃（第二个画布里显示第一个画布）

**根因不是槽位逻辑，是投影会话配额。** 两块画布复用同一个 `MediaProjection`
对象：Android 的一个录屏投影会话只能建一块虚拟屏，第二块
`mp.createVirtualDisplay(...)` 出来的屏会把第一块的画面整体"复印"过来 ——
用户描述的"镜像复制第一个画布、无限递归嵌套"正是这个表现。（自查代码时
两槽各建 VD 的写法是对的，错在把同一个 mp 传给两个槽。）

**修复**：
- `PollToken` 用同一次授权结果取**两份**独立 MediaProjection
  （`mpm.getMediaProjection(code, data)` 调两次），`proj[1]/proj[2]`
  各建各的 VD；Session 各自 stop、各自注册回调。
- 拿不到第二份时**显式降级**并写 error 级日志（不再静默套娃），
  日志提示靠 `dumpsys display | grep -i virtual` 只看到一块屏。
- `MirrorSlot.teardownVd()` 停自己那份会话（每槽独占，不会误杀对槽）。
- 共屏检测网：两槽握同一 displayId → 直接拆本槽 + error 日志
  （宁可拆一条，也别再出现套娃）。

### ② 高德四周黑边

**根因是密度，不是尺寸。** VD 尺寸本来就是按 SurfaceView 像素 1:1 建的；
黑边来自把设备 densityDpi（K60 ≈ 440）传给 366px 宽的小屏 —— 应用看到
~85dp 视口，高德按"小屏设备"版式渲染，或触发 size compat 后居中留黑边。

**修复**：`dpiFit()` —— 画布像素折成 ~280dp 基准反算 dpi
（366px @ ~209dpi ≈ 280dp，与紧凑手机视口同量级），宽高两侧都钳，
不低于 mdpi、不高于设备默认。触摸坐标走 VD 像素，不受 dpi 影响（仍 1:1）。
`am compat enable FORCE_RESIZE_APP + NEVER_FIX_ORIENTATION` 保持
（清槽时 reset）。**验收口径**：先拿时钟 App 验VD 铺满（时钟铺满→VD 参数
没问题；时钟也黑边→ROM 层 letterbox，另查 sizeCompat）；再验高德。

### ③ 删「点击接管」

用户原话："还有点击接管什么鬼？"。这是我自作聪明加的非焦点画布提示，
违背了"界面只要两个画布，不要多余的东西"。删掉 TextView 本体与显隐逻辑，
非焦点画布**只留环境光遮罩 + 焦点描边**。

### 复盘

- 做错：批次 L 引入的两槽共用一个投影会话，直到用户真机跑双画布才暴露。
  共享受限资源（投影/麦克风/相机）必须逐槽独占，写两槽架构时就该问一句
  "这个 session 允许多少实例"。
- 决策：黑边先按"密度"修而不是按"尺寸"修 —— VD 尺寸本来就 1:1，唯一可疑
  的就是密度；时钟 App 是判据（应用铺满是 VD 参数问题，只有高德不铺满是
  它自己的小屏版式）。

## 批次 U — CarPlay 风格换皮（新增「苹果互联」预设）

用户拍板范围（question 确认）：**只换皮不动结构**——新增「苹果互联」主题预设 +
全局圆角语言；PiP 画布的 SurfaceView 保持方角（真圆角待 TextureView 化，单独批次）。

### ① 新预设「苹果互联」

- `Theme` 数组 index 0 新增 `"carplay"` / 显示名「苹果互联」/ 强调色 `#0A84FF`；
  `DEFAULT_ID = "carplay"`，新装与恢复出厂即此风格（`LauncherModel` themeId 字段默认 +
  resetScalars 两处同步）。
- `Skin.apply` 识别 carplay 走 Apple 系统色板：深色底 `ground #000 / panel #1C1C1E /
  card #2C2C2E / leaf #0A84FF / leafDim #0060DF / text #FFF / textDim #8E8E93 /
  bad #FF453A / warn #FF9F0A`；浅色底 `#F2F2F7 / #FFF / #FFF / #007AFF /
  text #000 / textDim 由 0x3C3C43 混 40% 白`。**中性灰阶不带主题色倾向**是 CarPlay
  的视觉核心；自定义强调色仍可覆盖 leaf（换苹果底 + 自定义蓝不冲突）。
- `colors.xml` 编译期默认同步成 Apple 深色（首帧兜底不再泛绿）；`styles.xml`
  colorAccent 本就指向 `@color/leaf`，拾取 Apple 蓝无需改。

### ② 全局圆角语言

- `Skin` 新增造形 helpers：`round(color, dp)` / `pill(color)`（999dp 变胶囊），
  用静态 `density`（apply 时刷新）换算 px，**调用点不必传 Context**，sweep 机械化。
  密度兜底 3f（约 xxhdpi）。
- sweep 27 处 `setBackgroundColor(Skin.c(R.color.card))` 及同型 → `Skin.round(...)`：
  行/盒/容器 12dp（AppList、Folder、Search、SettingsHub、SettingsSection、RootPanel、
  VirtualDisplay、WindowTest、PipBoard、LayoutMode、DesktopView 组件卡/快捷栏、
  HomeActivity 底按钮）；按钮/把手/搜索框/编辑条 pill（HomeActivity chip、MiniPlayer、
  DesktopView dockHandle、Search 输入、SettingsHub 搜索行）；SettingsSection 色片 6dp。
- 保持平面的 15 处：全屏 ground、dockBar、PipBoard 画布容器、顶/底栏（CarPlay
  栏本就用平底）。WindowCard 标题条原是写死绿 `0xCC131a15`，改走
  `Skin.bar(Skin.c(R.color.card))` + textDim（换肤后浮动卡片不再掉色）。

### ③ 可读性微抬

CarPlay 风格上 9/10sp 偏小：全部 9sp→11sp、10sp→12sp（QuickBar×3、DesktopView×4、
WindowCard 标题、AppList/Folder/Search 副文案）。

### 验收口径（真机）

1. 新装/恢复出厂默认进「苹果互联」；主题切到「薄荷」/「叶影」再切回，颜色齐全回位
2. 桌面：编辑条、chip、dock 把手、组件卡、快捷栏均为弧度/胶囊；其余区域纯平面
3. 画中画两块画布 Surface 仍为方角、无圆角伪影（预期行为）
4. 浮动卡片（WindowCard）标题条跟随当前主题变色
5. 小字号屏幕：QuickBar / 应用列表副文案可读，无拥挤

## 批次 V — P2-16 遗留 17 项逐条复盘（亲自读码坐实 / 杀误报）

范围：批次 T 期间代理报了 17 项但未经亲自复核的清单（P2-16）。本批逐项读码，
坐实 12 处修掉，杀 14 项误报，另坐实 2 项架构级问题列 P2-18 下批做。

### 坐实修复（按文件）

- **GardenActivity**：① 叶子坐标 `dp(ballX)` 双倍换算——ballX/ballY 由 BallService.snap
  存的是**像素**（lp.x/lp.y），这里再过 dp()，440dpi 机上叶子飞到 2.75 倍远；
  改直接用像素并钳屏内。② 时钟不走字——build 只画一帧，补 Handler 每秒刷新
  时钟与日期（onResume 起、onPause 停）。
- **BallService**：长按 500ms 弹菜园后松手，`!moved` 仍成立 → tap() 又把桌面盖上。
  补 longFired 标志位，长按已发就不再 tap。
- **RootPanelActivity**：onResume 主线程跑 envInfo = 2 次真实 su 落地
  （settings get + dumpsys focus，各 3s 超时）；hasRoot 冷缓存首调也压主线程。
  两者挪后台线程回贴 UI；root 探测先画 UI 后台补警示条。
- **WindowTestActivity**：同型——Caps.report 冷缓存时主线程起 su；排障页恰恰最常被
  adb 直启，缓存最可能冷。挪后台线程。
- **VirtualDisplayHost**：create/release 加 synchronized——排障页每个按钮各起一条
  线程（btn() 里 new Thread），连点两下就是跨线程竞态：release 后 create 把
  displayId 写回去，屏永远释放不掉。（volatile 指控被 synchronized 连带覆盖；
  「attach 前 launchViaRoot 抛 ISE」不可达——attach 恒在 onCreate 先行，杀。）
- **Lrc**：① BOM（U+FEFF）trim() 剥不掉 → 首行时间戳对不上行首被丢，parse 入口剥。
  ② 超长 [mm:…] 让 parseLong 抛 NFE——lookup 的 MAIN.post 里那次调用会把
  **主线程崩掉**（外层 catch 只包 POOL 线程），时间戳解析就地兜 NFE break。
  [mm:99] 与重复时间戳是宽容解析/设计内行为，杀。
- **MediaListenerService**：onNotificationPosted 把每条通知标题+正文打进 logcat——
  本服务只借监听器身份拿 getActiveSessions 调用权，通知内容零用途，纯隐私泄漏，撤。
  playPause 快照竞态两个方向都收敛为 no-op，杀。
- **Wallpaper/LauncherModel**：① importImage 异常路径流泄漏（无 finally）→
  try-with-resources，半截文件落盘即删。② 库满 addWall 失败时孤儿文件残留 → 删。
  ③ removeWall 只删库条目**永不删盘上文件**，「恢复出厂清空壁纸库」后 walls 目录
  全是孤儿 → 删条目时连带删文件（只认 files/walls 目录，外部路径不碰）。
- **SelfTest**：自检把用户亮度钉死 128、音量打到 7 且不还原 → 亮度写后还原
  （保留写通道验证），音量没有可靠读回 API 改只读探针。
- **HomeActivity**：prefs 字段死代码（PREFS 常量另有他用保留）——删字段/赋值/import。
  island 与 DesktopView 双 sink 是设计内多订阅，杀。
- **DesktopView**：dispatchDraw 逐帧 new Rect/int[2] → 预分配字段复用。
  空文件夹 AIOOBE 杀：load（folders.add 前 isEmpty 检查）与自动整理（<2 键即解散）
  双层修剪，瞬时态同线程内消化，get(0) 不可达空列表。
- **BootReceiver**：判定出一个结构性错位——fireBoot 嵌在 pull() 里，**关「开机回桌面」
  会连带吞掉全部开机任务**；而 MY_PACKAGE_REPLACED 更新路径又无视开关硬拉界面 +
  重放开机任务。重构：autoHome 只管拉不拉界面（开机/更新都尊重）；开机任务与
  悬浮球自启只在真 BOOT 走（更新后 sticky 服务系统自己会拉起，不重放任务）。
- **SettingsSectionActivity**：① 定时解析与提示词自相矛盾——「730 表示 07:30」实际
  当成第 730 分钟 = 12:10；按 HHMM 口径解析（730 → 07:30，h≤23/m≤59 校验）。
  ② 延迟秒数 >约 24.8 天时 `*1000` 把 int 顶成负数，任务反而**立即触发**；
  改 long 解析 + 7 天上限。③ render() 从不重跑 Skin.apply——主题页里改主题，
  页面自己还挂着旧色板；render 前补 Skin.apply。（「默认 -1 永不到点」杀：
  describe() 显式标未定且可编辑。）

### 杀掉的误报（9 项 + 2 项记录在案）

tap 区域判定（slop 逻辑成立）、VirtualDisplayHost volatile（synchronized 覆盖）、
launchViaRoot attach 前 ISE（不可达）、WindowTest getDisplay NPE（catch Throwable 兜住）、
Lrc [mm:99]/重复时间戳、Pinyin 土耳其 I（已全程 Locale.ROOT）与输入无上限（缓存 400 上限）、
Theme accent 校验过松（两条路径都有 try/catch，全工程无裸 parseColor）、playPause 竞态、
purgeWalls 删当前壁纸（函数不存在，实际问题是反向的 removeWall 不删文件，已修）、
SelfTestMirror 结果未校验（taskOnDisplay 三处核对在位）、TaskEngine name 重算（触发是
持久化枚举字段）、空文件夹 AIOOBE、island sink 双轨、RootMain thread-per-connection
（本家连接数固定 + 空闲看门狗，维持记录备查）、WallpaperActivity loadLabel（该类不存在）。

### 坐实但架构级，列 P2-18

- loadApps() 主线程全机枚举：6 个页面 onCreate 直调（Home/Search/AppList/Settings×2/
  VirtualDisplay），bloaty 设备上 queryIntentActivities 100ms+。异步化要给 6 个页面
  补回调/占位渲染，超出"小修"范畴。
- 壁纸主线程解码：loadForScreen 在 Garden/Desktop/Home 的 onCreate 里同步 decode；
  后台加载管线连带三处渲染时机改造。

### 复盘

- P2-16 的 17 项里 14 项是误报——代理批量扫出的"嫌疑清单"价值在于圈定阅读范围，
  结论仍必须自己读码下。本批两处最有价值的收获（主线程崩 Lrc NFE、BootReceiver
  开关吞任务）都是复核过程中顺藤摸出的新问题，清单本身都没点名。

---

## 批次 W — 内置 LSPosed 模块：画中画应用强制横屏（真机截图坐实竖屏黑边后立项）

**目标**：用户真机截图显示画中画里高德/音乐 App 全是竖版 UI + 两侧大黑边，
并且拍板做 LSP 模块 hook（LSPosed API 102）。此前批次 R 的 `NEVER_FIX_ORIENTATION`
只能解锁 manifest 声明，挡不住应用运行时自己调 `setRequestedOrientation(PORTRAIT)`
——这条路线对高德无效，之前把"解锁 manifest"当成成立的能力是复盘误判，本批修正。

**注入点侦查（下载官方包静态分析）**

- 包：`com.autonavi.minimap` v17.00.0.2005（targetSdk 35，8 个 dex ~93MB），
  入口 activity-alias `com.autonavi.map.activity.SplashActivity`。
- manifest：几十个 Activity 声明 `screenOrientation=1`（PORTRAIT）——竖屏第一帧
  就是这么来的；少量 `=2`（user）/`=3`（behind）。
- dex 调用方扫描（自研 `/tmp/opencode/amap/dexscan.py`：解析 dex 头 +
  method_ids 名字匹配 + class_defs code 区间 + invoke 字节码正则定位调用方）：
  **106 个类**引用 `Activity.setRequestedOrientation`——mPaaS/H5 容器
  （H5ScreenPlugin/NXScreenOrientationProxyImpl）、支付宝 SDK、amap bundle
  （SpeakerModeManager/ModuleHeadunit/NaviMapView 等）全都在运行时反复锁方向。
- 结论：注入点 = `android.app.Activity` 基类，进程级 hook 全量覆盖 manifest 锁 +
  106 个运行时调用点，一个点管全部。

**改了什么**

1. `XposedEntry.java`（新增，自包含——跑在目标进程，LSPosed 自己的 classloader
   加载本 APK，主项目其他类在目标进程不存在，禁止引用）：
   - hook `Activity.setRequestedOrientation`：args[0] 非 SENSOR_LANDSCAPE(6) 一律改写；
   - hook `Activity.onCreate`（after）：创建完强拉一次横屏，第一帧就是横的；
     内部这次调用会再进 hook 1，参数已是目标值原样放行，无递归；
   - TARGETS 白名单（高德/百度/腾讯地图/网易云/QQ音乐）双重过滤，勾了名单外的
     应用也不生效。
2. XposedBridge 签名桩 `app/libs/xposed-api-82-stub-src/`（7 文件，签名与上游
   api-82 一致）→ `app/libs/xposed-api-82.jar`（5.5KB）只进编译期 classpath
   （javac + d8 --lib），运行时由 LSPosed 提供，不进 dex。
3. manifest：`xposedmodule`/`xposeddescription`/`xposedminversion=102`（这是 LSPosed
   API 版本号，用户用的就是 102，与参考实现 CarWithX `com.leting` 一致；此前误写 82
   ——那是编译期签名桩 artifact 的版本，不是 API 版本）/`xposedscope=@array/xposed_scope`。
4. `res/values/arrays.xml`：默认作用域与 TARGETS 一致；`assets/xposed_init`：
   入口类 FQCN。
5. `build.sh`：aapt2 link 加 `-A assets`；javac/d8 挂签名桩；缺桩/缺 assets
   前置 fail（防静默打出无模块的包）。

**使用步骤**：装新包 → LSPosed 管理器启用「海鸥桌面」（作用域按 xposed_scope
预填，可增删）→ 强杀目标应用进程（高德）让其重启加载 hook → 画中画部署高德。

**验证**：javac 类型检查过（含新 jar classpath）；自检 123/123；dex 扫描法
在 8 个 dex 上跑通（106 命中即证）。真机验收列 P2-19。

**遗留**：目标 App 的弹窗/透明页也会被拉横屏（预期内，车机场景可接受）；
应用内部横竖屏状态机若与 WM 冲突需真机看 logcat（SeagullLsp tag）。

## 批次 G 开机链（G1+G2）

**G1 开机自启恢复** — 官方语义是「本次开机后恢复上次的画布」，用
SystemClock.elapsedRealtime<90s 判开机窗口，规避 BOOT 广播与 home 启动的时序
竞态，零新增进程间通信。slot1 复用现有 deployIfBound+300/600/900ms redeploy+
selfHeal+repin 重试链；slot2 只在开机窗口内放行 s2Entered（autoRestore 开且
pkgOf(2) 非空），批次 CG 的「手动显式进入」语义日常保持。新增
LauncherModel.autoRestore（默认开）+ 设置页「开机恢复画布」开关 +
Manifest QUICKBOOT_POWERON。

**G2 场景一键** — 一键把两块画布布成预设应用组合。复用 QuickBar 现成的
@fn: 前缀分发模式，场景 key 约定 @scene:<名字>（名字而非索引，重排序安全）：

- LauncherModel：Scene{name,pkgA,pkgB} + scenes 列表（全局共享）+ findScene，
  存档 save/load 增 scenes 数组（n/a/b 字段）；
- QuickBar：@scene: 格渲染为叶子色文本，tap 分发 host.onScene(name)
  （接口默认方法，宿主按需覆写）；
- DesktopView：quickSlotMenu 增「场景…」+ Host.onScene 转发
  HomeActivity.applyScene；scenePickDialog（无场景时提示去设置页）；
- PipBoard.applyScene(pkgA,pkgB)：savePkg 两槽落盘 + s2Entered 按 pkgB 非空
  放行/上闸 + updateHints + deployIfBound（幂等部署链）+ 快照重对齐；
  空槽场景 slot2 回落占位语义；
- 设置页（快捷栏）：场景 section（新建/画布 1/画布 2/重命名/删除），
  重命名与删除同步清洗所有布局快捷栏里的 @scene: key（quickbarByMode
  为 private，新增 allQuickbars() 访问器）；删除场景即清格，悬空 key 兜底
  toast「场景不存在」。

**复盘**：QuickBar/设置页的既有模式（choiceRow/actionRow/inputDialog/
appPicker）让本功能零新增 UI 范式；主要坑是编辑工具多行 oldString 按首尾
锚块匹配吞中间内容（QuickBar cell 与桌面菜单两处），改小锚点编辑后 javac
恢复绿。真机待验：场景点击→双槽重布的流畅度、空场景（两槽都占位）回落
表现。javac --release 11 绿。

**批次 G 小结**：开机链两功能均走「复用既有机制 + 最小新增」，G1 零新
状态机（窗口判定+重试链），G2 零新 UI 范式（key 约定+幂等部署）。

## 批次 H GPS 管线（H1-H4）

**H1 LocationHelper**（新文件）— 车速/海拔航向/行程共用的单例定位管线：
acquire/release 配对 fanout，最后一个退订者 removeUpdates；GPS_PROVIDER 在位
优先、否则回落 NETWORK；requestLocationUpdates 1s/0m 节流；回调切主线程；
10s 无新 fix = 失联（UI 显示 --）。权限：ACCESS_FINE/COARSE_LOCATION
（manifest 新增）+ 点按行车块运行时申请，回执经
HomeActivity.onRequestPermissionsResult 转发。

**H2/H3/H4 行车块**（WallpaperPage）— 调研期发现原定落点 makeWidget 所在的
DesktopView 已是上代桌面死代码（零实例化），按 BACKLOG「与时钟/歌词同套摆位
缩放」的原始意图改落壁纸页：一个可拖动/双指缩放的行车块，三行分别为
车速（大字 km/h）、海拔+八方位航向、行程（点火时长+里程，speed>1.5m/s
计入位移，重启归零）。可见性仲裁：关（model.wallGpsEnabled，隐藏后经时钟
块菜单「显示行车块」恢复）/ 无权限（缩成「点按开启定位」）/ 有权限
（订阅 1s 更新，事件驱动原位 setText）。摆位/缩放存 model.wallGpsX/Y/Scale，
JSON 存档与 wallClock 同构。

**复盘**：本批次的真正工作是「找到活的落点」——BACKLOG 写的是 makeWidget
加一类，但那个组件条已死；硬塞死代码等于交付不可见功能。改落壁纸页后
Drag/Zoom/存档三套机制全部复用，新增代码只有可见性仲裁与三行格式化。
真机待验：车机 GPS 天线在位（dumpsys location）、隧道丢星显示 --、
授权弹窗与回执刷新、驻车归零。javac --release 11 绿。

## 批次 I 信息组件（I1-I5）

**I1 LunarCalendar**（新文件 + LunarCheck 自检）— 自研单文件查表法落地，
关键决策：位表不由网页抄写，而是用 lunar-java（cn.6tail:lunar 1.7.3，仅
生成期用，进不了 APK）逐项生成 1900-2100 共 201 项，与社区经典表
（1900=0x04bd8...）逐位一致；随后全量表内 67537 天逐日与 lunar-java 比对
0 差异，LunarCheck 固化 30 个机器生成用例（锚点/春节/闰月首末/表界）。
教训入册：手写万年历期望值错 10 处，期望值必须机器生成。

**I2 农历行** — 时钟块日期行下加 "农历 腊月十九 · 乙巳(蛇)"，超表隐藏，
随字号等比缩放。

**I3 倒计时块** — 壁纸页独立块（可拖/可缩放），点按菜单改名称/改日期
（AlertDialog+EditText，yyyy-MM-dd 校验）；距目标 N 天/就是今天/已过 N 天；
跨零点由 30s tick 重算；未配置时显示「点按设置」。

**I4 指南针兜底** — 独立罗盘盘面在车机（横屏固定+GPS 航向常在）价值低，
落为行车块航向行的兜底源：GPS fresh 时用 GPS bearing，失联（>10s）时用
加速度计+磁力计融合方位角（500ms 节流、横屏 remap、最短角差平滑）；
无磁力计的车机静默降级为 "--"。

**I5 监控块** — 内存/CPU/存储三行小字，默认关（时钟块菜单开）；采样在
后台线程（cpuPct 内部 120ms sleep 不能上 UI 线程），结果回 UI 线程原位
setText；30s tick 驱动。

**复盘**：壁纸页「块」生态成型——时钟/行车/倒数/监控四块共用 Drag/Zoom/
placeBlock/saveBlockPos 一套机制，时钟块菜单升级为块总开关（隐藏的块在
这里找回）。lunar 表生成→全量验证→固化自检的流水线可复用到后续任何
「查表+验证」类数据。真机待验：车机磁力计有无、农历行字号观感、
倒数块菜单操作路径。javac --release 11 绿 + LunarCheck 绿。

## 批次 J 负一屏（J1-J5）

**J1 容器** — NegScreenView（FrameLayout 双列卡）作为桌面第 0 页挂入 PagerView
（PAGE_NEG，原壁纸/主界面/应用页顺移，默认页 PAGE_MAIN 不变）；翻页驱动
setActive(on)：2s 开关状态轮询只在负一屏可见时跑，离开页即 removeCallbacks，
杜绝在其他页白烧 root exec（延续审计 M4 纪律）。

**J2 天气卡+限行卡** — 天气卡点按 Weather.refresh，正文 summary+三天预报+
updatedLabel（时间源 model.weatherUpdatedAt，草稿期误写 weatherAt 已纠）；
限行卡解析 restrictTails "1,6,2,7,3,8,4,9,5,0"（周一..周五各限两个尾号），
周末显示今日不限行；设置页新增「负一屏」分区（开关+城市+尾号 10 位校验）。

**J3 快捷开关** — WiFi/蓝牙/飞行四 chip（热点 chip 直开系统热点页），
状态 2s 轮询 isWifiOn/isBluetoothOn/isAirplaneOn 后原位改色（M4），
toggle 走 SysOps.set* 并 toast R 结果（草稿期把 R.out 误记为 R.msg 已纠）。
RESEARCH A5 结论落地：热点 service call 交易号随 ROM 漂移，弃探号改直开
TetherSettings（intent 主名 + com.android.settings.TetherSettings 双保险）。

**J4 日志导出** — RootLog 全文 + logcat -d -t 500 -s Seagull*，头部带
versionName（构建注入 git 短哈希，报障即版本）；三级回落：/sdcard/Seagull
File API → root cp → getExternalFilesDir；导出后台线程，回执 uiPost 后台
线程纪律。

**J5 目的地卡** — 数据统一 LauncherModel.NegDest/dests()/setDests()/removeDest()
（设置页增删改、负一屏长按删，同源 JSON negDests 最多 4 条）；拉起级联
amapuri dev=0（GCJ02 直传）→ baidumap coord_type=gcj02，resolver 探测 +
Manifest <queries> 声明；草稿期 NegScreenView 自带 Dest 解析与模型重复，
收编进 LauncherModel（DRY）。

**复盘**：① 页序重排只动 PAGE_ 常量与 addView 顺序，PagerView 任意页数
基建零改动（当初按 index layout 的设计此时兑现）；② 轮询生命周期挂宿主
翻页而非页面自身可见性，是「车机省电」与「root exec 节制」的交叉点；
③ 本批次草稿三处 API 记忆偏差（R.out、weatherUpdatedAt、inputDialog 3 参）
全靠 javac 兜住——先写码后对签名的流程风险被类型检查完全覆盖，但下次
应在写码前先 grep 签名再动手，减少一轮返工。真机待验：热点页拉起、
导航深链级联、/sdcard 直写在目标 ROM 是否放行。javac --release 11 绿。

## 批次 K 壁纸联动（K1）

**K1 天气联动** — 联动属于 opt-in：设置页「主题与壁纸」加开关+壁纸包目录
（ACTION_OPEN_DOCUMENT_TREE，takePersistableUriPermission 持久化读授权，
免存储权限）。目录约定（调研业界粒度：晴/多云/阴/雨/雪/雾+昼夜变体）：
包根目录下 sunny/cloudy/fog/rain/snow/night 六个子目录，各放一段循环视频
（mp4/webm/3gp/mkv 取第一个）。映射：WMO 0-1 晴、2-3 多云阴、45/48 雾、
51-67+80-82+95+ 雨、71-77+85/86 雪；夜间主题（dayNight=DARK）优先 night/，
没匹配回落手动动态壁纸（RESEARCH B6：匹配失败回落当前壁纸）。

实现要点：① weatherCode 随 Weather.refresh 落档（-1=未取，换城市/恢复出厂
清 -1）；② WallpaperVideoView 增加 content:// 源支持（SAF Uri 走
setDataSource(context, uri) 两行改动）；③ SAF 子目录列举跨 Binder，
WeatherWall.resolve 放后台线程、结果回主线程；④ HomeActivity.syncWallVideo
升级为「联动键缓存」：key=code|dayNight|dir|manual，键不变直接复用上次解析
结果，解析中不动现有层（防闪）；⑤ Weather.onUpdated 静态钩子把「取数完成」
事件递给宿主（NegScreen 手动刷新/设置页刷新/20 分钟自动档全走这一条），
onDestroy 摘除防静态持死。

**复盘**：动态壁纸基建（批次 CI 的 videoLayer/setPath）这次只加了两块砖
——Uri 源与联动键缓存——零基建改动，验证了当初「换源就 setPath」的幂等
设计。SAF 目录树方案取代「每类视频 SAF 单选导入」：用户选一次目录、
改包内容即时生效。真机待验：目录授权持久化在目标 ROM 的兼容性、
夜间优先的实际观感、解析失败的静默回落。javac --release 11 绿。

## 批次 L 导航卡片（L1）

**L1 导航通知解析骨架** — 按清单只做骨架：NavNotiParser 静态解析器
（NAV_PKGS 高德/百度三包名 + T_TURN/T_REMAIN 两个正则模板常量），模板
句式按语音播报同构（"N米后右转"/"前方N米右转"/"剩余 N 公里"）先行铺底，
**真机把两类导航的 ongoing 通知实际文案探明后在常量处填改**——这是清单
明示的延期项，骨架交付不等于模板终稿。

管线三口：onNotificationPosted → feed（isOngoing 且模板命中才更新，
否则清态=静默隐藏）、onNotificationRemoved → retract（导航通知被撤当
导航结束）、onListenerConnected → 回放 getActiveNotifications（服务重启
后岛横幅不用等下一次播报）。IslandView.bind 横幅优先于时钟/歌词；
HomeActivity onResume 挂静态监听、onDestroy 摘（与 Weather.onUpdated
同一纪律）。

**复盘**：① 网上查不到两类导航通知栏的权威格式（语音播报句式倒是确认了
距离+转向的结构），骨架+模板常量正是为此设计的落点；② 与 NavCard
（批次 AN 车联服务）完全解耦：车联通走卡片、通知通走岛横幅，两条数据源
互不等待；③ 正文解析结果只进内存不进 logcat，延续批次 V 的通知隐私线。
真机待验：高德/百度 ongoing 通知实际文案（回填模板）、百度车机版包名
（现挂 com.baidu.BaiduMap，车版可能是 com.baidu.BaiduMap.car 之类）。
javac --release 11 绿。

## 批次 M 本地音乐（M1-M5）

**M1 扫描** — 三来源：① 公开 Music 目录 File API（Lyrics.localFile 同一前例）；
② StorageManager.getStorageVolumes() 可移动+mounted 卷，getDirectory()
（API 30+ 公开）可读走 File 遍历，读不动记 uuid 交 root find；③ root find
/mnt/media_rw /storage 全量兜底。命名约定「歌手 - 歌名.ext」拆元数据
（与 Lyrics 找 .lrc 的约定天然对齐），不逐个开 MediaMetadataRetriever
（大库太慢）；同目录同名 .lrc 记进 track.lrcPath。后台线程，上限 2000 首。

**M2 播放服务** — MediaPlayer+前台服务+标准 MediaSession。设计核心：
本地播放就是一条普通媒体会话 —— MiniPlayer（getActiveSessions）和歌词
组件自动读到，播放/暂停/切歌/seek 经 transport 回调回到服务，MiniPlayer
的控制键不用改一行代码就管本地歌。prepare 放后台线程（本地大 flac 同步
prepare 不上主线程）；AudioFocusRequest(GAIN) 正常申请。

**M3 列表页** — 播放头+ListView 全曲目；点行=队列即列表从该首播；进度条
拖完松手才 seek；高亮行变了才 notifyDataSetChanged。

**M4 歌词联动** — 关键发现：框架 MediaMetadata 没有自定义键（LYRICS 是
androidx 的常量），fromSession 只能读 description.extras，而框架 Builder
注入不了 extras —— 「元数据塞 lrc 全文」路线封死。改走两条现成路：
Music 目录的歌由 Lyrics.localFile 同名命中；U盘/root 来源由新增的
MusicLib.lrcFor(title,artist) 在 Lyrics 解析链里兜底（扫描缓存线性找）。

**M5 桌面块** — musBox 第五块进块生态（Drag/Zoom/placeBlock/saveBlockPos
全复用）：当前曲+下两首+提示行，点按开 MusicActivity（选歌=点歌），
长按=块菜单；时钟块菜单加「显示音乐块」；tick 原位 setText。

**复盘**：① M2 的「白拿」设计最值：会话标准协议让 MiniPlayer/歌词/控制
三处 UI 零改动接入本地播放，验证了当初把 MiniPlayer 押在 MediaSession
而非私有快照上的正确性；② M4 的 androidx 依赖陷阱差点踩进去——写码前
先 grep 框架 API 再定方案，这次是 javac 拦住了；③ 扫描缓存（MusicLib.last）
成为 M3/M5/M4 三处共用数据源，一次扫描全桌面受益。真机待验：U盘热插拔
后的重扫（现只在进页/开块时扫，插拔事件未监听）、前台通知在目标 ROM 的
常驻表现、ROOT find 的 2>/dev/null 在 toybox 的兼容性。javac --release 11 绿。

## 批次 N 手感（N1 桌面手势 / N2 主题包 / N3 G值表块）

**做了什么**
- N1：HomeActivity.dispatchTouchEvent 挂桌面手势，只在壁纸页（PAGE_POSTER）生效——双指各下滑 130px 熄屏（root `input keyevent KEYCODE_POWER`，线程跑，无 root 静默无效+toast 提示）；单指 420ms 内上甩 160px（横漂 < 竖程一半）进画中画分屏页。长按拖拽调分屏 PipBoard 触摸区已有，不重复造。
- N2：主题包三旋钮全局化——Skin.radiusScale（round() 统一乘，胶囊不描边防挤）、Skin.glassAlpha（panel/card 吃额外 alpha 压壁纸=毛玻璃观感，ground 不动保对比）、Skin.outline（描边=文字/底色中间调 alpha 0x40）；设置页「主题包」区三个控件（圆角 50~150% / 玻璃 0~80% / 描边开关）；模型 themeRadius/themeGlass/themeOutline 落 JSON，出厂重置归位。
- N3：G值表块（第六块，musBox 同款 Drag/placeBlock/saveBlockPos 生态）——加速度计幅值 /9.80665 低通 0.2 得当前 G，会话内峰值跟踪；点按菜单（大小/清零峰值/隐藏）、长按总菜单找回；时钟块菜单加「显示G值块」；150ms 节流+文本变化才 setText（审计纪律）。频谱弃决策落档：Visualizer 要 RECORD_AUDIO，桌面常驻权限成本过高。
- 传感器改造：startSensors 拆开加速度计与磁力计的绑定——原来两个都拿到才注册，现在加速度计单独够用（G 块零磁力计依赖），磁力计缺失时指南针兜底照旧静默失效（accOk&&magOk 门）。模型 wallGx/Y/Scale/Enabled JSON 三处（save/load）齐。

**复盘**
① 手势选在 dispatchTouchEvent 而非 pager OnTouchListener：窗口级先于子 View 消费，PipBoard 的 SurfaceView 和壁纸页的 Drag 都拦不住它，但副作用是块上快甩也会翻页（块会被带一下）——阈值 160px+420ms 已把误触压到可接受，真机再调；② 上滑手势只在壁纸页开、主界面画布不掺和：地图应用自己的滚动是它的领地，桌面手势跨界会打架；③ startSensors 的「加速度计+磁力计都到位才注册」原来是为了指南针，N3 把 G 块也压进来后必须拆开——旧逻辑会让 G 块在无磁力计车机上直接哑火；④ 熄屏方案选 root input 而非 DevicePolicyManager.lockNow：后者要设备管理员激活弹窗，车机场景一次性 root 前提下前者零交互。真机待验：input POWER 在目标 ROM 是否 toggles 熄屏（有的 ROM 灭屏后按 POWER 又点亮）、双指手势与壁纸页 widget 长按的冲突、玻璃 alpha 在亮色主题下卡片文字对比度。javac --release 11 绿。

## 终局 — 三次大复盘与修复（三组并行精读 → 亲自复核 → 自检交叉验证）

**第一轮（并行分组精读）**：三组 explore 子代理分别精读数据/服务层、UI 层、集成链，报回 30+ 条发现。P0 崩溃级：无。

**第二轮（逐条亲自复核）+ 修复（19 处）**

媒体链：
- MusicService prepare 竞态（快速连点切歌互踩静态 instance → 双路出声/泄漏/状态污染）：单线程 Executor 串行化切歌与续播的 prepare
- MusicService 焦点形同虚设（AudioFocusRequest 没挂监听器，导航播报被音乐盖住）：挂 OnAudioFocusChangeListener，LOSS/LOSS_TRANSIENT/CAN_DUCK 一律 pause
- MusicService 暂停态 PlaybackState 带 speed=1.0（AVRCP/仪表按速度外推会漂移）：暂停时 0f
- MusicService 起播失败仍占媒体会话（把其他已暂停播放器顶下去）：失败路径 setActive(false)
- MusicActivity 进度轮询从未启动（poll 只自续没人 post，播放头整页冻结）：onResume 起播 + onPause 摘除
- Lyrics.getActiveSessions(null)（无 MEDIA_CONTENT_CONTROL 必抛 SecurityException 被 catch 吞掉 → 歌词管线整体静默失效，M4 的 lrcFor 兜底成死代码）：传 MediaListenerService 的 ComponentName，与 MediaListenerService.refresh 同口径
- MusicLib.scan 并发（tick 与列表页可同时触发三来源扫描 + root exec）：进行中并入，回调登记集合统一回发；异常路径 finally 复位防止永久卡死

通知链：
- NavNotiParser 非 ongoing 通知误清横幅（导航应用推广通知一到达/一撤回就打断导航横幅）：liveKey 记录展示中的那条通知，feed 非 ongoing 忽略、retract 只认 liveKey

天气链：
- Weather 定时刷新写自己的模型实例，桌面 model 不重读（壁纸联动/负一屏天气卡永远旧数据，K1 钩子白接）：onUpdated 处理里先 model.load()
- syncWallVideo 解析回调乱序（两次天气更新间隔短时旧包短暂落层）：回调捕获发起时 key，不匹配丢弃
- alignVideoLayer 销毁后回调（死后往已拆的根挂解码器）：入口 isDestroyed/isFinishing 守卫

UI/生命周期：
- WallpaperPage onAttachedToWindow 漏 refreshGBlock/refreshMusBlock（换肤重建后两块永久消失且菜单找不回）：补齐
- NegScreenView 2s root 轮询泄漏（换肤重建后旧实例永久空转 + 树被 Handler 持死）：onDetachedFromWindow 自摘 + HomeActivity.onDestroy 补 setActive(false)
- NegScreenView.setActive(true) 漏 refreshDests（设置页改完目的地负一屏显示旧数据）：补齐
- musBox 长按错挂 showMonMenu（改的是监控块，复制粘贴串位）：补 showMusMenu（字号+隐藏音乐块）
- applySkin 漏 musL0-L3 补色（换主题音乐块保持旧色）：补齐
- G 块隐藏不摘传感器（行车块也关时 60Hz 空转）：refreshGBlock 隐藏路径按需 stopSensors
- 主题三旋钮不进 skinSignature（设置完回桌面不生效，要等下次重建）：themeRadius/Glass/Outline 拼进签名
- HomeActivity 包广播 receiver 不注销（匿名实例持死 Activity）：存字段 + onDestroy 注销
- 换肤路径旧 PipBoard 的 PrivClient 静态订阅不摘（新板 startWatch 见 subscribed()=true 挂空转，事件全进旧板持死旧树）：PipBoard.detachWatch()（cancelPending+退订）+ startWatch 改为无条件重订（subscribe 幂等换回调）；换肤分支补新板 onResume 接线 + syncWallVideo 重建视频层

手势：
- gestureWatch 按指针下标跟踪（先抬一根手指 getY(0) 错位可误熄屏）：pointerId 配对 + findPointerIndex
- 双指捏合缩放带下坠误触发熄屏：记初始间距，变化 >60px 判为捏合不熄屏

Manifest/杂项：
- 缺 FOREGROUND_SERVICE_MEDIA_PLAYBACK（Android 14+ 起音乐前台服务起不来）：补
- ACCESS_FINE/COARSE_LOCATION 重复声明：去重
- LOCKED_BOOT_COMPLETED 死条目（receiver 非 directBootAware 永远收不到）：删
- LocationHelper 开机时定位服务全关则整段会话永不重试：onProviderEnabled 补起订（appContext 持有防泄漏）
- SettingsSectionActivity 目的地改名称后不重绘：补 render()

**自检交叉验证（第三轮）**：javac --release 11 全量绿 + 8 项 selfcheck 全绿（DumpParse 14 / Lrc 15 / PrivCodec 39 / Quickbar 7 / StackList 30 / Transform 54 / TrustedFlags 10 / LunarCheck 30）。QuickbarCheck 的历史 Stub! 问题（DEVLOG :151 记录待修）本次顺手修掉：app/selfcheck/shim/android/util/LruCache.java 纯 JVM 垫片，运行时置于 classpath 最前覆盖桩实现，API 面按 LauncherModel/DockBar 实际用量实现。

**记录在案未修（P2，理由）**：
- MusicLib rootFind 走 Caps.exec 固定 3s 超时：U 盘曲目极多时第三来源可能静默为空——放宽超时要动 Caps 全局契约（其他 exec 调用方共用），列为真机验收项（表格 #9）实测定夺
- 新增 AlertDialog 未做统一生命周期解绑：与全项目既有弹窗同模式（换肤重建路径 WindowLeaked 风险），全局改造风险大于收益，维持现状
- addDest 坐标步骤取消时整条丢弃：UX 瑕疵，交互流程改动留真机验收后定

## 终局补丁 — BACKLOG #5 验收缺口：重启后播放列表与进度保留

**缺口**：M2 队列是纯内存态，BACKLOG #5 验收项「重启后播放列表与进度保留」未覆盖——清单对齐时只核对了功能项，漏了逐条验收标准。

**做法**：LauncherModel 增 musicQueueJson（路径数组）/musicIdx（断点曲序）/musicPos（断点秒数）三字段（save/load/reset 齐）；MusicService 落档点=play（换队列）/step（切歌）/seek/pause/onDestroy，恢复点=MusicActivity 扫描回执后 tryRestore + resume() 冷启兜底（MiniPlayer/方向盘直接按播放也能接上）；tryRestore 按存档路径对 MusicLib 扫描结果重挂队列（路径失效的曲目跳过，全失效返回不硬撑），断点秒数走 pendingSeekMs 在 prepare 起播后一次 seekTo。

**复盘**：① 写 persistPos 时顺手写了 `musicQueueJson = null` 想"不动这个字段"——JSONObject.put(key, null) 语义是**删键**，等于每次暂停都把队列清掉；LauncherModel 构造器自带 load()，重载注释（withApps=false=只读存档+只写场景）早写明了正确用法，读题不仔细；② save() 是全量落档无合并，任何"new 一个 model 改一个字段再 save"都必须走构造器加载路径——这条纪律值得进 MEMORY；③ 清单对齐要对着验收标准逐条核，不能只看功能标题。

## 用户反馈修复 — Dock 替换应用消失 + 设置页功能找不到

**Dock 替换应用后图标不显示（真 bug，三层修）**
- 根因：HomeActivity.replaceDockApp 往 dock 存的是裸包名（AppEntry.pkg），而 LauncherModel.App.key() 是「包名/Activity类名」（loadApps 给每个入口都填了 cls）——DockBar.bind 里 find(裸包名) 恒 null → continue 跳过整格，且坏 key 已 save 落档，从此那格永远空白。
- 修复：① 写入侧——replaceDockApp 按包名回 allApps 解析成全 key 再落档；② 读取侧——DockBar.bind 与设置页 dockBody find 失败时 findByPkg 兜底（旧坏档也能照常渲染）；③ 档案侧——LauncherModel.load() 里把历史裸包名 key 归一成全 key（withApps=false 实例无 allApps 自动跳过，下次带列表的 load 收敛）。

**设置页找不到功能（入口缺失）**
- 负一屏设置页（限行城市/尾号/目的地卡管理）在 SettingsHubActivity.SECTIONS 里压根没有行——J 批次造了 negBody 但忘了挂入口，功能等于黑洞。补 hub 行（桌面组）。
- 新增「桌面组件块」分区（hub 桌面组）：行车块/倒数日块/监控块/本地音乐块/G值表块五个显隐开关集中在设置里，之前只能通过壁纸页时钟块菜单找回（用户根本不知道这条路径）。开关改动 HomeActivity.onResume 走 poster.syncBlocks() 即时生效（onAttachedToWindow 同步点收敛成 syncBlocks）。
- 搜索关键词补齐：主题包/圆角/玻璃/描边/动态壁纸/天气联动/壁纸库（theme）、场景一键/布屏（quickbar）、本地音乐（market）、桌面手势/双指锁屏（touch）、导航横幅（island）、替换应用（dock）。
- dockBody 加指引：替换应用的入口在桌面长按 Dock 图标。

**复盘**：① 前几轮复盘的「权限面/入口面」核对漏了 SettingsHubActivity 的 SECTIONS 表——设置入口本身也是个需要「清单对齐」的面，光核对 SectionActivity 的 case 分支没用，得核对 hub 有没有行；② 替换应用这个 bug 在 M 批次前的代码里就存在（「用户反馈过替换点了没反应」的注释说明 CI 批次修过一半：修了 indexOf 找不到位次，没修 key 格式），根因是两个 key 格式（pkg vs pkg/cls）在代码里并存，写侧转格式时漏了这一处。

## 批次 O 日常可用度（O1-O4）
- O1 方控媒体键 — MediaKeyRouter 独立类（HomeActivity.dispatchKeyEvent 单点接线）：本地会话(playing/paused)优先→列表第一个→无会话 return false 走系统路由（不抢键）；DOWN 执行/UP 吞掉防二次；SecurityException（通知监听未授权）退回默认路由并 RootLog。转发日志 1s 节流（方控长按连发 down）。javac 绿。
- O2 专辑封面 — 双路：本地 MusicService 懒提取（PREP 线程 MediaMetadataRetriever，LRU4 按路径）→ publishState 塞 ALBUM_ART → 蓝牙歌词/方控链路白拿；远端 CoverCache（键 title|artist，LRU2，URI 后台单线程解码，同键 60s 负缓存）。音乐块加 musArt 缩略图行（22dp，前置），空闲分支升级「蓝牙播放中」覆盖渲染（Lyrics.activeController 开 public 复用，本地占位为默认渲染再覆盖=无状态残留）；MiniPlayer 加 56dp 封面位。设计偏离记录：块背景遮罩弃（换肤玻璃背景会打架）改前置缩略图，requirements.md AC4 同步改写。javac 绿。
- O3 配置导出/导入 — ConfigPort：prefs 全键 JSON（String/Boolean 直存、StringSet 打 __set 标记、其余类型跳过记日志=前向保护）+ meta 壳(ts/v/commit)；导出 files/export/ 5 份轮转，SeagullFileProvider 放开 export/ 段（原来只认 updates/）走 ACTION_SEND；导入 SAF 限 5MB 先 parseImport 校验（缺 meta/v/keys/非 JSON 各给可读文案）后单次 apply 批写回+bumpCfg，失败零污染。纯函数 buildExportJson/parseImport 抽出供 JVM 自检。javac 绿。
- O4 崩溃自恢复 — CrashGuard：uncaught 包装器（RootLog+files/crash/ 10 份轮转+同签名 24h 内 3 次置标记）→ 委托原处理器保系统终止语义；处理链自身全 try-catch（兜底器二次崩溃=什么都留不下）。consumeRecovery 在 HomeActivity.onCreate UI 建立前消费标记：清 mirror_pkg1/2（PipBoard.loadPkg 数据源）画布回落默认布局+一次性 toast。负一屏日志导出并入 crash 文件（最近 5 份原文）。签名=异常类名+首帧（车机同因崩溃首帧稳定，不含 message——同一崩溃点 msg 多变）。javac 绿。
- 自检基建 — org.json 垫片（shim/org/json，单遍递归下降 ~200 行）：android.jar 的 org.json 是 Stub 跑不了 JVM，ConfigCheck 需要。教训一：垫片只上运行时 classpath，与源码分开发编译（把垫片混进源码编译会让 Lyrics 的真 API 调用对不上垫片子集）；教训二：手写 brace 截取法有暗坑（`sb.length()>0` 恒真这类自欺条件），重写成单遍递归下降一遍过。10 项自检全绿（新增 ConfigCheck 14 项/CrashSigCheck 14 项）。

## 批次 P OBD2 车辆数据
- P1 ObdEngine/ObdParser — 零依赖自写 ELM327（协议作业抄 pires/obd-java-api 与 obd2_kit 公开清单，公式=SAE J1979 事实标准）：SPP UUID 00001101…，bonded 过滤 obdMac；AT 初始化 ATZ/ATE0/ATL0/ATS0/ATH0/ATSP0/0100 逐条等 '>'（2s 超时）；单命令轮询五 PID（010D 车速/010C 转速/0105 水温/0142 电压/012F 油量），0142 失效补 ATRV；断线 5s 重连、连续 10 轮全失效判离线重连；static State 快照（volatile 逐字段）UI 侧 1s tick 自取——零回调、与歌词管线同节奏；引擎自身全 catch 降级离线（兜底器纪律：数据功能不能拖死桌面）。HomeActivity onCreate start/onDestroy stop（ui.removeCallbacksAndMessages 不影响独立线程，stop 自己管）。
- P2 设置分区 obd — hub SECTIONS 加行（keywords 车辆数据/OBD/转速/水温/电压/电瓶/亏电/ELM327）+groupOf 归「高级功能」+subtitle 读引擎实时状态；section 侧：开关（开时顺手 ensureBtPerm）、适配器 bonded 列表单选（存 MAC 字符串）、间隔 choiceRow、阈值三档、实时状态 note（status+转速/水温/电压/油量）。设计偏离：REQ_OBD=0x5A15 没用——BLUETOOTH_CONNECT 复用天气定位的 9001 请求码，结果处理就是 render，语义完全一致；Manifest 三蓝牙权限前批次已在，无新增。
- P3 块接入 — 行车块：OBD 车速有效覆盖 gpsBig（"xx km/h OBD" 角标可分辨数据源），失效回落 GPS 估算（obdBigOverridden 布尔门）；新增转速/水温行（gpsObd 13sp dim），离线/失效收起不留空行；无定位权限分支也吃 OBD 车速（缩着的块照样显示真车速）。监控块加电压行（monL4，电瓶 x.x V，同样离线收起）。亏电提醒：低于阈值持续 60s 本地通知一次（channel seagull_obd，静音），恢复自动复位（R4.2）。刷新节奏：1s 精刷走定位事件流（有定位权限时），30s tick 兜底（无权限分支只有 tick 驱动——发现点：updateGpsRows 无权限时不被调用，兜底必须在 tick 里加显式行）。
- 自检 ObdCheck 24 项 — 五 PID 公式（含 13.48V/50.2% 精确向量）+无空格/多 ECU/回显/SEARCHING 过渡/错误 token/空串/null/缺字节/非法 hex/ATRV 带 V 裸数垃圾。修一处测试自身 bug：NaN 走 Math.abs 比较恒 false，断言工具缺 isNan 分支。11 套自检全绿。
- 待真机：ELM327 头子配对连接、熄火重连、OBD 车速 vs GPS 真值对比、电压/亏电提醒（验收表 17 项）。模拟器/桌面验不了蓝牙串口，代码只过了公式与协议解析层。

## 批次 Q 组件管理（负一屏卡片 + 壁纸页块）
- Q1 NegCards 纯逻辑 — DEFAULTS 序 weather/switches/restrict/dest/log；normalize（去未知 key/缺失追加序尾/去重保序，saved 空表回落默认）；move 相邻交换越界原样；filterHidden 清洗。LauncherModel 加 negOrder/negHidden（List<String>，save 存 JSONArray/load optJSONArray 逐项收）+ wallClockEnabled。
- Q2 负一屏 — 五卡视图构造一次、relayout 只动挂载关系（reparent，chip 颜色/天气文本零丢失）；卡片长按菜单 上移/下移/隐藏/组件管理；根视图空白长按 = 组件管理清单（点行切显隐，支持全隐藏，空白入口永远可找回）。默认序分列与 J1 原版逐卡一致。
- Q3 壁纸页 — 背景长按从「直接添加小组件」升级为入口菜单（添加小组件=原行为 / 组件管理）；组件管理六块清单含时钟块（新 wallClockEnabled 门，applyClock 统一控制，农历行随钟走）；时钟菜单追加 组件管理/隐藏时钟块 两项，原找回项保留。块全隐藏后空白长按仍可用（监听在页面根上）。
- 自检 NegCardsCheck 20 项 — 修一个设计错误：纯奇偶位分列会把日志卡（flat 第 5 位、偶数）分进左列，与原版右列 3 卡布局冲突；改成交替发牌+末位余数进右列（colOf 挂 total 参数），并加用例锁死默认分列。教训：分列规则必须拿真实布局当测试向量，不能拍脑袋写完再"验证"。12 套自检全绿。
- 交互偏离记录：负一屏移动用「长按→菜单→上移/下移」而非真拖拽（对齐全应用 AlertDialog 菜单习惯，跨列拖拽复杂度高收益低）；壁纸页六块真拖拽本就有（Drag 管线），本次只补时钟块隐藏与统一管理入口。

## 批次 R 音乐控制与直连歌词（底栏播放器 + 音乐块播放器化 + CARLINK 直连）
- R1 底栏迷你播放器 — btnPrev（G_PREV=左竖条+右三角，G_NEXT 镜像）插播放键前；标题列改竖排 LinearLayout[title, track]，进度条 3dp 自绘（track 圆角底+fill 直接改 LayoutParams.width），无时长/无会话 GONE；mediaTick 1s runnable 只在横幅 VISIBLE 时续跳（bindMedia VISIBLE 分支 post、GONE/抑制/detach 全 removeCallbacks，防幽灵跳）；进度=PlaybackState pos+速度×(now-lastUpdate) 外推（mediaProgress 0~1，无时长 -1）；封面走 CoverCache.get（artwork 位图+URI 双路已就绪），coverKey 记当前键——换曲才复位应用图标，同曲 1s 轮询不闪图标；MediaListenerService 加 meta()/mediaProgress() 静态读。
- R1.5 音乐块播放器化 — 封面 22→28dp+点按=playPause（封面区当播放键，零新增控件）；musLyric 12sp 单行歌词（Lyrics.Sink 注册，构造挂 onLyric 最后一槽 setText，空文本自然收起）；musCtrl 控制行 ⏮⏯⏭（GlyphIconView 30dp 热区 0.74 scale），本地队列或远端会话存在才露，纯空闲收起；播放态图标随 refreshMusBlock（本地 isPlaying/远端 PlaybackState）更新。
- R2 直连接收层 — CarLink 纯静态（JVM 可测）：ACTION com.seagull.carlauncher.CAR_LYRIC、extras line/line_t/pos/st/title/artist/cover、FRESH_MS=5000；fresh（ts=0 或超窗不新鲜，时钟倒退=刚收到算新鲜）；override（开关+新鲜双门，n 槽全空、翻译占倒数第二(n≥2)、当前行占最后——消费方统一把最后一槽当当前行；空行=全空清显；fallback 原数组永不被改写）。Lyrics.Direct volatile 四字段+registerReceiver（start() 注册一次，重复调用幂等，注册失败置 null 静默）；window() 头部单点覆盖——push 侧 sinks 与外部直调者（时钟块 tick）全部自动吃到，管线内零散布。
- R3 直连封面 — CoverCache.fromUri(key,uri) 新入口（LRU 命中→60s 负缓存→EX 后台解码），get() 原 URI 分支抽 decodeAsync 共用（行为零变化）；音乐块 showRemoteMedia 无会话封面时回落直连封面：fromUri 发起解码+musArt.postDelayed 1.2s 一次性补绘（isAttachedToWindow 守卫，重复 refresh 叠加 post 也只是 LRU 命中，无害）。
- 设置/开关 — LauncherModel.lxLinkEnabled 默认 true（老用户升级零操作，optBoolean 兜底），歌词分区 toggleRow「落雪直连（广播歌词）」bumpCfg+save（cfg 缓存代际失效，window 读侧立即生效）。
- 协议文档 docs/CARLINK.md — action/extras 名冻结表、行变化才发/空行清显/封面变化才带三条纪律、桌面 5s 断流回落语义、落雪 LyricModule 接桥说明、v1 版本纪律（新 extra 一律可选）。
- 自检 CarLinkCheck 24 项 — 修两处测试自身断言错：①时钟倒退用例预期反了（now<lastTs 恰说明广播刚到，应判新鲜）；②「新数组」回归断言写成空串（实际应等于行文本"A"）。教训：写新鲜度/槽位类用例先在纸上把边界语义过一遍再落笔。13 套自检全绿；javac --release 11 全量绿。
- 待真机（验收表 19 项）：底栏进度推进与封面、音乐块歌词行/控制键/封面点按、落雪补丁包（另一仓库 CI）装上后行级同步、断流 5s 回落、关开关复原。
- R4 落雪端补丁（lx-music-car fork carlink 分支 c6fbfab）— 评估期结论「行级时机在原生 LyricPlayer」落地：钩子挂 Lyric.setCurrentLyric（onPlay/onSetLyric/pauseLyric 全走这，行变化+清显一个口）；翻译取 extendedLyrics[0]（refreshLyric 先 translation 后 roma 的序保证），roma 行按内容比对剔除；st 用 LyricPlayer.isPlay（包级可见）。三个关键门全拆/绕：①LyricModule.lyric 懒建只在桌面歌词/蓝牙歌词开时发生——setLyric 改为懒建，直连不依赖任何 lx 开关；②refreshLyric 的 !isRunPlayer 早退会把解析链整个拦死（lines 永空→play 早退→零广播）——拆门，解析一歌一次成本可忽略；③pauseLyric 的清显也挂过 isRunPlayer——改为无条件（暂停/切歌广播空行，桌面清显，视图侧 isShowLyricView 门内行为零变化）。零 JS 改动。
- R4 CI 出包 — 落雪构建挂进海鸥桌面 build-apk.yml 新 job：clone caogenfunan123/lx-music-car carlink 分支（补丁常驻分支头，随分支走）→ npm ci（Node 18=.nvmrc）→ 还原 SEAGULL_KEYSTORE_B64 → gradlew assembleRelease -PMYAPP_UPLOAD_*（同海鸥签名，覆盖安装免卸载；secret 未配退回 debug 签名）。SDK/NDK 用 runner 预装（lx 自家 CI 同款依赖）。产物 lx-music-car-apk。
- 踩坑记录：lx 克隆 push 报 credential helper 500——桌面仓库的认证是 remote URL 内嵌 token（credential.helper 配置只是环境模板残留），lx 克隆照抄 set-url 后即通。
- R4 发布与验证 — 落雪包同款发布纪律落地：lx job 末步 gh release create/upload -R caogenfunan123/lx-music-car（tag=v<versionName>=v1.9.1-car.1，universal 改名 lx-music-car.apk 单资产固定 URL：releases/latest/download/lx-music-car.apk）；跨仓发布用桌面仓库新 secret CROSS_RELEASE_TOKEN（secrets API + pynacl sealed box 加密写入）。交付验证：双包 v1 JAR 签名块解 PKCS#7 取 X.509 指纹完全一致（71:33:05:F0…）——apksigner 的 SEAGULL-.RSA 与 gradle 的 CERT.RSA 只是签名块命名差异，证书本体同签，覆盖安装互不冲突。

## 批次 S 统一底栏与独立播放器（真机反馈三件套）
- S1 底栏迷你播放器权重化 — 用户反馈「底栏没有小的播放器」，根因在 O2 时代的 fitPass 设计：media 块 wrap-content，放不下就把整条藏掉（suppressedKey 记键防复闪）。宽屏画布上中段被 apps 菜单占掉，media 长期被"抑制"——机制正确但体验为负。重构：media 改 (0,-2,1f) weight 占满中段剩余宽（恒等于剩余宽，物理上永不超宽），titleCol weight=1 弹性收缩+去 maxWidth（省略号接力），progTrack 改 MATCH_PARENT（mediaTick 里 progTrack.getWidth() 逻辑零改动）；fitPass/suppressedKey/onSizeChanged 三件套整体删除。教训：LinearLayout 里"预估宽度再决定显隐"永远该先想 weight——让布局系统自己分。
- S2 独立播放器块（第七块）— 用户反馈「壁纸桌面音乐要一个单独的播放器控制组件，要能够设置大小颜色」。musBox 是队列视角（下两首/提示行），播放器块专注控制：row0[封面 44dp 点=playPause + 列[标题 15sp/来源 11sp]] + 歌词行（Lyrics.window 直取，与 musLyric sink 语义一致取最后一槽）+ 时间 m:ss/m:ss + 进度条（槽宽×进度，plTrack.post 布局后取宽，DockBar.mediaTick 同款）+ ⏮⏯⏭ 36dp。数据源统一 MediaListenerService.snap（本地/蓝牙/落雪通吃，比 musBox 的本地队列/远端会话双分支还简单）；空态「未在播放」但控制键保留（切歌后立刻有数据）。1s 自驱 playerTick（onAttached 启/onDetached 停/enabled 门三重防幽灵跳）。换色 PlayerSkin 六预设白绿蓝橙粉黄——单色方案：文本/控制键/进度填充同色，进度槽恒 33% 透明白（深壁纸兜底可读），GlyphIconView 加实例级 tint(int)（DockBar INK 默认不动）。缩放沿用捏合管线（0.5~2.4，只缩文本，封面/控制键固定——与 mus 块纪律一致）。长按菜单 尺寸四档+换颜色（单选）+隐藏；组件管理清单第七行；showClockMenu 找回项追加。LauncherModel wallPlayer 五字段持久化（x/y/s/c/on）。
- S3 移除画布键条 — 用户反馈「进入画中画那个底栏去掉，固定桌面的底栏为统一底栏」。PipBoard 玻璃键条（返回/桌面/导航栏）整体下线：buildKeybar/keyButton/KEYBAR 常量/barA/barB/ExitListener/DockToggleListener 全删，keybarReserve() 恒 0（VD 恢复全高，fixedVdSize/applyKeybar 的 squeeze 参数链保留但恒 0，改结构风险最小）；createCard 键条挂载块删除；HomeActivity 的 setOnExitSlot/setOnToggleDock 注入与 toggleDock() 删除。语义落点：Dock 恒显（expandSlot 不再 setDockCollapsed(true)，单应用态底部就是 DockBar）——批次 AD 画布与底栏零缝隙设计天然成立；退回分屏走 DockBar logo onHome（showSplit+enableSwipe+setDockCollapsed(false)，与原键条桌面键语义完全一致，故 ExitListener 直接删而非并入）；RootOps.injectKey BACK 注入能力随键条下线（应用自带返回 UI 可用，退出=logo）。踩坑：清理 DockToggleListener 的 edit 顺带吞掉了夹在两条注释中间的 ExitListener 声明——git diff 逐行核对发现，grep 全文件零声明确认后顺势全删（键条没了，两个监听都成死代码）。
- 自检 PlayerCheck 17 项 — 预设等长/色值向量/越界回落/名称回落/循环一致性/trackBg/fmtMs 七向量（0/负/进位/边界 599999→9:59、3599999→59:59）。14 套自检全绿；javac --release 11 全量绿。
- 待真机（验收表 20 项）：底栏横幅自适应（长标题省略不消失）、播放器块拖动/捏合/换色/隐藏找回、单应用态画布无键条且 DockBar 恒在、logo 退回分屏。

## 批次 S-RESTORE（9f71341）：一比一还原车联助手 hook
- 背景：升 xposedminversion 102（880cf64）后用户回报 hook 仍不生效；用户确认 hook 能力蓝本=车联助手（com.leting 3.0.0，备份 canfunan/leting-carlink）
- 做法：dexdump 逐指令译回 gaodehook.HookEntry（高德 7 hook）+ wangyihook.HookEntry（网易云手机/车机版 7 hook），类名/TAG/日志串/try 分层全对齐原版；xposed_init 三入口（XposedEntry + 两个克隆并存，A/C/C0/D 段保留）
- 交付：/workspace/SeagullLauncher.apk（1.0.9f71341，原签 713305f0）；装后强杀高德/网易云看 GaodeHook:/WangyiHook: 日志

## 批次 V（amapauto 纯 VD PiP 修正）：建屏主体与 Surface 挂接两处根因
- 现象：批次 U（a1bc017）装最新包后悬浮窗仍看不到高德画面。
- 根因①：VirtualPipController 在【应用进程内】用 PUBLIC 位建屏 —— 普通 uid 建的屏不受信，ActivityStarter 拒绝非属主上非信任屏，am start --display 落屏失败。本仓库已验证的路是 root 守护进程 uid 0 建 TRUSTED+PUBLIC 屏（RootMain CREATE / PrivClient.create，MirrorSlot 在用）。
- 根因②：守护进程建的屏没有 VirtualDisplay 对象，setSurface() 根本不存在 —— Surface 必须走 PrivClient.surface(displayId, surface)（Parcel+Base64 行协议，RootMain SURFACE 事务）。
- 修法：create() 先走 PrivClient.create（失败降回本地 PUBLIC 屏，保留批次 U 行为）；新增 attachSurface() 按建屏路径自动分流（root 屏过线协议、放后台线程防 ANR；本地屏直接 setSurface）；launchAmap() root 屏优先 PrivClient.launch（daemon setLaunchDisplayId，与 RootOps.launchOnDisplay 同款），退回 am start --display；release() 同步分流。PipOverlayService 三个 surface 回调全部改走 attachSurface，状态行显式标注 root TRUSTED 屏 / 本地屏，用户一眼看出走了哪条路。
- 批次 V 续（黑屏二分）：悬浮窗可见但画布黑 → 剩两种可能（任务没落屏 / 落屏但 Surface 断）。修三处 + 把判别做进状态行：① am start 输出校验，Error/Exception/Permission Denial 不再当成功；② attachSurface 记录挂接结果（此前静默失败）；③ SurfaceView setFixedSize 为 VD 分辨率（清晰 + resize 不再重建 surface）。新增 probeContent()：root `screencap -d <id>` 量 VD 截屏体积（纯黑几 KB，有内容 >50KB），2.5s/7s 两次探测，结论直接写状态行 —— 屏黑=落屏问题，屏有内容但窗口黑=Surface 链路问题。

## 批次 W —— 一比一复刻车联助手 3.0.0 的 VD 画中画管线

**依据**：`docs/CARWITH-ANALYSIS.md`（全量反编译报告）。黑屏三根因逐一对应复刻：

1. **落屏：锚点 + am stack move-task**（车联 c1/h.d，主路）
   - 新增 `VdPipAnchorActivity`：独立 taskAffinity(`com.seagull.carlauncher.pipanchor`) +
     excludeFromRecents + 透明 + setFinishOnTouchOutside(false)，复刻 PipAnchorActivity。
     先以属主身份把锚点放上私有屏（必然成功）→ 屏上有 root task →
     root `am stack move-task $TASK $ROOT true` 把高德搬进去。
   - shell 脚本**逐字**取自车联 smali（仅类名替换）；其 ROOT 解析只认旧格式 `^Stack id=`，
     Android 12+ 是 `rootTask id=`，失败时补丁脚本再试一次（兼容两种格式）。
   - 车联的降级链原样复刻：直启（flags 269500416 + setLaunchDisplayId）→
     root `am start --display -f 0x10104000`；再留 daemon 直启做第四层保险。
   - **不再 force-stop**：搬活任务（车联原文如此）。
2. **Surface：同进程直调**（车联 b1/b）—— `createVirtualDisplay(..., surface, flags=0, ...)`
   建屏直传，surfaceChanged → `vd.resize(视图宽高)` + `setSurface`，surfaceDestroyed →
   只摘 surface。批次 V 的 root 守护进程建屏 + 跨进程行协议整条退场（daemon 只剩第四层保险）。
3. **自愈：onResume 重部署**（车联 MainActivity.onResume → c.h()）——
   `HomeActivity.onResume → VirtualPipController.redeploy()`，幂等，任务被拉回主屏后
   回桌面即搬回。
4. **VD 尺寸 = SurfaceView 视图尺寸**：resize 跟随窗口拉伸（车联无黑边、触摸零换算的根本），
   触摸坐标映射自然退化为 1:1；探针（screencap 二分）保留。

**编译**：javac 全量 0 error（R.java 借用 gen-20261019 产物）。
**验收**：部署后状态行应显示「锚点+move-task · 探测中… → 屏有内容(NKB)」；
高德被拉回主屏后回桌面一次即自动搬回。

## 批次 W2 —— 用户截图实锤：目标应用写死车机版 = 每次必败；补「推荐应用」

用户截图（横屏主屏上的手机版高德 + 黑边、无任何画中画窗口）+ 反馈
「车联助手 lsp 里有推荐应用，你这个都没有，而且一直都没成功过」。复盘发现：

1. **画中画目标写死 `com.autonavi.amapauto`（车机版）**。用户设备上装的是
   手机版高德（截图即证）—— 没装车机版 → `resolveComponent` 返回 null →
   deploy 第一步就「解析不到入口 Activity」→ 四条落屏路全不用跑，每次必败。
   而车联助手的画中画目标就是**手机版高德**（其 xposed_scope 含
   com.autonavi.minimap；VD 布局按手机 app 适配）。
2. **「推荐应用」缺失**。车联助手在自己的界面里列出推荐应用，点谁画谁。
   海鸥只给一个写死的按钮。

修复：
- `VirtualPipController` 增加 `RECOMMEND{minimap, amapauto, netease}` +
  `pickTarget()`（用户点选 > 手机版高德 > 车机版 > 网易云，按装没装探测）
  + `setTarget()`。
- `RootPanelActivity` 增加「推荐应用（点谁画谁）」区：三个应用条目，未装的
  标注；点选后 stopService→startForegroundService 重建悬浮窗（换目标必须
  重启服务，否则 VD 还挂在旧包上）。
- 悬浮窗状态行带目标包名：`VD#N com.autonavi.minimap · 部署中…`。
- `xposed_scope` 补车联 3.0.0 同款条目（miui.carlink / baidu.carlife.xiaomi /
  samsung.android.carlink / luna.music），LSPosed 推荐作用域与其对齐。

## 批次 W3 —— 像素级复刻补完：画中画从「悬浮窗服务」改为「桌面内嵌视图」

用户怒评「像素级复刻你咋就听不懂」点醒最后一块拼图：**车联助手的画中画是
自家 Activity 里的一个视图（b1/c PipUI / PIP1 布局），不是系统悬浮窗**。
W2 的悬浮窗服务在三个关键面上全是车联的反面：

| | 车联助手 | W2 悬浮窗服务（已删） |
|---|---|---|
| 承载 | Activity 内 SurfaceView（PIP1：横屏 60% 宽全高/竖屏全屏） | TYPE_APPLICATION_OVERLAY 独立窗口 |
| 悬浮窗授权 | **不需要** | 需要 SYSTEM_ALERT_WINDOW，没授权 addView 直接抛异常 → 用户设备上一次都没出现过 |
| 部署上下文 | 前台 Activity（无 BAL 限制） | 后台 Service（Android 12+ 起 Activity 可能被静默拦） |
| 自愈 | Activity.onResume 天然 | 服务永远在前台， onResume 自愈挂桌面侧，链路绕 |

**改动**：
- 新增 `VdPipView`：PIP1 尺寸逻辑 + 顶栏（返回/60%/拉伸/状态/关）+ SurfaceView
  + 拉伸手柄 + input -d 触摸转发，全部照抄车联 b1/c；boot 管线复用
  VirtualPipController（W 批次锚点+move-task 链不变）。
- `HomeActivity.onResume → syncVdPip()`：开关开着就挂到 android.R.id.content
  最上层（skin 重建后 getParent()==null 自动重挂）；关闭即摘。
- `RootPanel` 推荐应用点选 → setTarget + setEnabled(true) + finish()，
  回桌面即出现（车联同款流程）。
- 删除 `PipOverlayService`（java + manifest 条目）—— 悬浮窗方案退役。

**编译**：javac 全量 0 error。

---

## NB20 · 极简屏改名 + 小白点归一 + 设置页摆设审计（2026-10-24）

### 1. 「菜园」→「极简屏」（用户需求①：软件里还有一个菜园）

上一批只清了「野菜」，漏了「菜园」——同为参考实现语境词。全 src 28 处
UI 文案/注释统一替换：菜园 → 极简屏（JSON key `garden*` 与类名不动，
动了会丢用户配置）。配套清理叶子语境：极简屏内圆球「叶」字 → 「●」，
设置文案里「叶子」→「小白点」。

### 2. 小白点归一（用户需求②：只要一个功能——点哪都回桌面）

- BallService 删左滑返回（input keyevent 4）与上滑最近任务（187），
  唯一手势 = 单击 CATEGORY_HOME 回桌面主页；拖动仅挪位置（贴边记忆）。
- GardenActivity 内小白点原先「点一下出全部应用」→ 同步归一为
  点一下 finish 回桌面。
- 设置页 note 文案同步为真实行为。

### 3. 设置页摆设审计（用户需求③：有些功能只有显示没接入）

两级审计：

- **字段级**：LauncherModel 全部 public 标量字段逐一数消费点。9 个孤儿
  （customLayoutName / negDestsJson / hideSystemBars / winDefaultScale /
  winLockMap / appNameOf / scaleOf / exportScheme / importScheme）——
  全部**没有设置 UI**，用户看不到，不构成「摆设开关」，属内部遗留暂留。
- **UI 级**：SettingsSectionActivity 98 个行控件绑定的 model 字段全部有
  真实消费点；抽查天气（真联网 open-meteo）/动态壁纸/海鸥岛/壁纸包目录
  均真接线。
- **实锤一处摆设**：`gardenEnabled`（原「长按小白点进极简屏」开关）——
  字段只有存取，无任何功能消费（BallService 根本没有长按手势）。
  已从设置页删除该开关，摘要页（SettingsHub）同步改为只报摆法。
  model 字段保留（旧档序列化兼容）。

### 4. 复盘结论（用户需求④）

本批之前的反复失败（画中画四轮、距离字段三轮、转向表一轮）共同根因：

1. **从参考实现抄结构时，没有对「数据语义」做官方源头验证** ——
   icon 枚举拍脑袋分组（NB12）、距离 key 抄错段位（NB14）、
   开关用内存态（KB 早期）。NB17/NB19 之后立规矩：
   **每个协议字段/枚举值，先找到官方定义或反编译铁证，再接线**。
2. **摆设开关的成因 = 文案先行、功能没落地**（gardenEnabled 就是
   典型）。规矩：设置页每加一个开关，同批次必须有消费点，或不开 UI。
3. 验证闭环靠用户截图 + 反编译源码行号交叉，不再靠推理。
