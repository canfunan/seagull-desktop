# 功能开发清单（2026-10-19 开工）

依据 docs/BACKLOG.md 已拍板 16 项 + docs/RESEARCH.md 方案拆解。执行纪律：每个小功能先调研（官方文档/开源实现/全网）再动手；实现一个复盘一个；全部完成后清单对齐 + 三次大复盘修复 + 推送构建持续监控。

## 批次 G 开机链
- [x] G1 开机自启恢复：BootReceiver（BOOT_COMPLETED/QUICKBOOT_POWERON，exported=true）+ 延迟布屏退避重试（15/30/60s）+ 开关项；adb am broadcast 可测
- [x] G2 场景一键：scenes 配置（slotA/slotB/weight/wallpaper）+ QuickBar 场景卡入口 + 一键布屏（deploy 幂等复用）

## 批次 H GPS 管线
- [x] H1 LocationHelper：权限申请、GPS_PROVIDER 1s 节流、死亡解绑
- [x] H2 车速组件（km/h，丢星 --）
- [x] H3 海拔/航向组件（共用 H1）
- [x] H4 本次行程组件（点火时长+里程，speed>1.5m/s 计入）

> H 批次落位说明：DesktopView 组件条是上代桌面遗留（当前无实例化，仅 LayoutModeActivity 引用 WIDGET_NAMES 常量），车速/海拔/行程三块按 BACKLOG「与时钟/歌词同套摆位缩放」的意图落到壁纸页时钟块同款机制上——一个可拖/可缩放的行车块，三行分别是 H2/H3/H4；开关默认开，无权限时整块缩成「点按开启定位」，隐藏后经时钟块菜单恢复。

## 批次 I 信息组件
- [x] I1 LunarCalendar 单文件查表（1900-2100）+ selfcheck 抽查 30 日期
- [x] I2 日历组件（公历+农历行）
- [x] I3 倒计时组件（目标日配置）
- [x] I4 指南针组件（加速度计+磁力计融合）
- [x] I5 系统监控组件（内存/CPU/存储）

> I 批次落位说明：农历行并入时钟块日期行下方（I2）；倒计时/监控为壁纸页独立块（I3/I5，与时钟块菜单同一开关位找回）；I4 指南针做成行车块的航向兜底源（GPS 失联才补显），独立罗盘盘面在车机场景价值低。

## 批次 J 负一屏
> J 批次落位说明：负一屏为桌面第 0 页（PAGE_NEG，原壁纸/主界面/应用三页顺移，首屏仍主界面）；目的地数据统一走 LauncherModel.NegDest/dests()/setDests()/removeDest()（设置页与负一屏同源）。
- [x] J1 负一屏容器（桌面左滑页，复用滑动基建） — NegScreenView（FrameLayout 双列卡）挂 pager 第 0 页；onPageChanged 驱动 setActive(on)，2s 开关轮询只在负一屏可见时跑（防其他页白烧 root exec，审计 M4 纪律）
- [x] J2 天气卡+限行卡（本地城市规则） — 天气卡点按 Weather.refresh，正文 summary+预报+updatedLabel（model.weatherUpdatedAt）；限行卡 restrictTails "1,6,2,7,3,8,4,9,5,0" 按 周一..周五 两个一组，周末=今日不限行，城市可空；设置页「负一屏」分区配置（toggle+城市+尾号校验 10 位）
- [x] J3 快捷开关（cmd/svc WiFi 蓝牙 + 热点 service call 探号降级设置页） — WiFi/蓝牙/飞行 chip 2s 轮询 isWifiOn/isBluetoothOn/isAirplaneOn 原位改色（M4），toggle 走 SysOps.set*+R.toString() toast；热点按 RESEARCH A5 结论直开系统热点页（service call 交易号随 ROM 漂移，弃探号），amapuri/baidumap 同理弃深链探测
- [x] J4 日志导出卡（RootLog → /sdcard/Seagull/ 带 commit 号） — doExport 拼 RootLog 全文+logcat -d -t 500 -s Seagull*，头部 versionName（含 git 短哈希）；三级回落 /sdcard/Seagull File API → root cp → getExternalFilesDir，导出在后台线程、回执 uiPost
- [x] J5 常用目的地卡（amapuri://route/plan + baidumap://map/direction，GCJ02，<queries>） — 最多 4 条（设置页增删改，负一屏长按删）；拉起 amapuri dev=0（GCJ02 直传）→ baidumap coord_type=gcj02 级联，resolver 探测+<queries> 声明，都没装 toast 提示

## 批次 K 壁纸联动
- [x] K1 天气动态壁纸联动（weather/night 目录约定 + 手动锁定优先 + 失败回落） — WeatherWall（WMO 码→sunny/cloudy/fog/rain/snow 分类，夜间主题优先 night/，SAF 目录树列举放后台线程）；weatherCode 随取数落档（-1=未取）；设置页开关+目录树选择（持久化读授权）；WallpaperVideoView 支持 content:// 源；Weather.onUpdated 静态钩子（onDestroy 摘除）驱动 20 分钟自动对齐；没匹配回落手动动态壁纸

## 批次 L 导航卡片
- [x] L1 导航通知解析骨架（高德/百度包 ongoing 通知 → 正则模板常量 → IslandView 横幅；格式真机探明后填模板，失败静默隐藏） — NavNotiParser（NAV_PKGS 三包 + T_TURN/T_REMAIN 模板常量，真机探明后填改）；MediaListenerService.onNotificationPosted/Removed/重连回放三口接入；IslandView.bind 横幅优先于时钟/歌词；HomeActivity 挂/摘静态监听；正文不进 logcat（批次 V 纪律）

## 批次 M 本地音乐
- [x] M1 音乐扫描：/sdcard/Music File API + U盘 StorageVolume + root /mnt/media_rw 扫描 — MusicLib 三来源去重（公开 Music 目录 File API、getStorageVolumes 可移动卷 getDirectory()（API30+，29 走 root find）、root find 全量兜底）；文件名「歌手 - 歌名」约定对齐 Lyrics；上限 2000 首；后台线程扫描
- [x] M2 播放服务：MediaPlayer + 前台服务 + MediaSession（MiniPlayer 自动联动） — MusicService：队列内存态+index、prepare 在后台线程、AudioFocusRequest、标准 MediaSession（metadata+playbackstate+transport 回调）——MiniPlayer/Lyrics 从 getActiveSessions 自动读到本地会话，零联动代码；前台通知低优先级渠道；终局补丁：队列/曲序/断点秒数落 LauncherModel，重启后播放列表与进度保留（BACKLOG #5 验收项，tryRestore 按路径对扫描结果重挂+起播后 seekTo 断点）
- [x] M3 音乐列表 UI + 队列（选中/播放/切歌） — MusicActivity：播放头（歌名/进度 SeekBar 拖完才 seek/三键）+ListView 全曲目；点行=置队列从该首播；高亮行变化才 notifyDataSetChanged（轮询不全量刷）
- [x] M4 .lrc 联动歌词组件（复用 Lrc 解析器） — 本地播放即标准媒体会话 → Lyrics 现有 localFile（Music 目录同曲同名 .lrc）直接命中；U盘/root 来源不在 Music 目录的，Lyrics 解析链加 MusicLib.lrcFor(title,artist) 兜底（扫描缓存里同曲 lrcPath 读全文）；框架 MediaMetadata 无自定义键（LYRICS 属 androidx），extras 注入路线放弃
- [x] M5 播放列表组件（桌面点歌/切歌） — 壁纸页第五块 musBox（同 Drag/Zoom/placeBlock 生态）：当前曲+队列两行+提示行，点按开 MusicActivity 选歌、长按块菜单（缩放/隐藏）；时钟块菜单加「显示音乐块」，tick 原位刷新（M4 审计纪律）

## 批次 N 手感
- [x] N1 桌面手势（双指下滑锁屏[lockNow/input POWER 真机试]、上滑进画中画、长按拖拽分屏）——HomeActivity.dispatchTouchEvent 只在壁纸页生效：双指各滑 130px 熄屏（root input KEYCODE_POWER，无 root 静默）；单指 420ms 内上甩 160px 进画中画分屏；长按拖拽分屏 PipBoard 触摸区本来就有（onLongPress+onTouch）
- [x] N2 主题包（色板/圆角/玻璃 alpha/描边整体切换）——色板已有 15 预设+自定义；新增 Skin.radiusScale/glassAlpha/outline 三旋钮 + 设置页「主题包」滑条区（圆角 50~150%/玻璃 0~80%/描边开关），模型 themeRadius/themeGlass/themeOutline 持久化，回桌面即时生效
- [x] N3 娱乐组件（G值表；频谱 Visualizer 权限评估后决定）——G值表块（第六块，加速度计低通+峰值，零权限，菜单清零峰值/隐藏/缩放）；频谱弃：Visualizer 要 RECORD_AUDIO，桌面常驻权限成本过高，收益低（真机音频回路还不一定给数据）

## 批次 O 日常可用度（2026-10-19 拍板，spec: .monkeycode/specs/daily-essential-batch）
- [x] O1 方控媒体键兜底——HomeActivity.dispatchKeyEvent 拦 KEYCODE_MEDIA_* → MediaKeyRouter（本地会话优先→最近活跃→无会话走默认路由），getActiveSessions 复用通知监听器身份
- [x] O2 专辑封面——CoverCache（单线程解码+256px 采样+LRU2）；本地曲 MediaMetadataRetriever 懒提取内嵌封面进 metadata；音乐块背景压暗遮罩+迷你播放器封面位；同键 60s 负缓存
- [x] O3 配置导出/导入——prefs 全键 JSON+meta 包壳落 files/export/（留 5 份轮转），SeagullFileProvider 分享；导入 SAF 限 5MB 先校验后批写，失败零污染；bumpCfg+提示重启桌面
- [x] O4 崩溃自恢复——uncaught 包装器记 RootLog+files/crash/（10 份轮转），同签名 24h 内 3 次置标记；下次启动清标记+画布回落默认布局+一次性提示；负一屏日志导出并入 crash 文件
- [x] 自检：ConfigCheck（导出→导入 round-trip 30+ 键+2 拒绝用例）、CrashSigCheck（签名/窗口/阈值 12+ 用例）

## 批次 P OBD2 车辆数据（2026-10-19 拍板，spec: .monkeycode/specs/obd2-vehicle-data）
- [x] P1 ObdEngine——SPP 连接（SPP_UUID，bonded 过滤）+AT 初始化（ATZ/ATE0/ATL0/ATS0/ATH0/ATSP0/0100 逐条等 '>'）+单命令轮询（010D/010C/0105/0142/012F，0142 失效补 ATRV）+断线 5s 重连+连续 10 轮全失效判离线；ObdParser 纯静态（SAE J1979 公式+错误 token）。HomeActivity onCreate start/onDestroy stop
- [x] P2 设置分区 obd——开关/适配器选择（bonded 列表存 obdMac）/间隔 1/2/3/5s/电压阈值 11.5-12-12.5；BLUETOOTH_CONNECT 运行时权限（复用 REQ 9001，结果处理就是 render，语义一致）；Manifest 三权限此前已在
- [x] P3 块接入——行车块 OBD 车速优先（OBD 角标）+转速/水温行（失效 --，离线收起回落 GPS）；监控块电压行；亏电提醒（低于阈值 60s 本地通知，会话一次，恢复复位）。精刷走 1s 定位事件流，30s tick 兜底（无定位权限分支只有 tick 驱动）
- [x] 自检：ObdCheck（parse 12+ 用例：各 PID 正常值/多 ECU 行/SEARCHING 过渡/NO DATA/?/空串/012F 满格/0142 13.48V/ATRV；isError 全 token）——24 项全绿；11 套自检全绿

## 批次 Q 组件管理（2026-10-19 用户提需求，spec: .monkeycode/specs/widget-manage）
- [x] Q1 NegCards 纯逻辑——DEFAULTS 序（weather/switches/restrict/dest/log，交替发牌分列与 J1 原版零迁移）+normalize（去未知/补缺失/去重）+move 相邻交换+filterHidden+colOf(index,total)；LauncherModel 加 negOrder/negHidden/wallClockEnabled 三字段 save/load
- [x] Q2 负一屏卡片管理——五卡视图构造一次 relayout 复用挂载（偶位左列奇位右列，末位奇数落右列）；卡片长按菜单 上移/下移/隐藏/组件管理；空白长按 = 组件管理清单（点行切显隐，支持全隐藏+空白找回）；目的地行级长按（删目的地）不冲突
- [x] Q3 壁纸页块管理——背景长按升级入口菜单（添加小组件=原行为 / 组件管理）；组件管理六块清单（含时钟块，点行切换+对应 refresh）；时钟菜单追加 组件管理/隐藏时钟块；applyClock 挂 wallClockEnabled 门（农历行随钟走）
- [x] 自检：NegCardsCheck（归一 5 用例/move 边界 4/隐藏清洗 2/列分配 5/标签 valid 3）——20 项全绿；12 套自检全绿

## 批次 R 音乐控制与直连歌词（2026-10-19 拍板，spec: .monkeycode/specs/music-control）
- [x] R1 底栏迷你播放器——btnPrev（G_PREV 新图形=左竖条+右三角）插播放键前；标题列改竖排+3dp 自绘进度 track（fill 直接改 LayoutParams.width，无时长 GONE）；mediaTick 1s（横幅 VISIBLE 才 post，GONE/抑制/detach 都 removeCallbacks）——进度=PlaybackState 位置+速度×流逝外推，封面=CoverCache.get（meta artwork 位图+URI 双路），换曲才复位应用图标（coverKey 防闪）；MediaListenerService 加 meta()/mediaProgress()
- [x] R1.5 壁纸音乐块播放器化——封面 22→28dp+点按=playPause；musLyric 12sp 单行歌词（Lyrics.Sink 注册，onDetachedFromWindow 摘除）；musCtrl 控制行 ⏮⏯⏭（GlyphIconView 30dp 热区，本地队列或远端会话存在才露，无会话收起）
- [x] R2 直连接收层——CarLink 纯静态（ACTION/extras 常量+FRESH_MS=5000+fresh/override/coverUsable）；Lyrics.Direct volatile 状态+registerReceiver 随 start() 注册一次；window() 头部单点覆盖（开关+新鲜双门，sink 广播与时钟块等外部直调者全部自动吃到，push 侧零改动）
- [x] R3 直连封面——CoverCache.fromUri(key,uri) 新入口（LRU 命中/60s 负缓存/EX 后台解码），get() URI 分支重构共用 decodeAsync；音乐块 showRemoteMedia 无会话封面时回落直连封面（1.2s 一次性补绘+isAttachedToWindow 守卫）
- [x] 设置——LauncherModel.lxLinkEnabled（默认 true，升级零操作）+歌词分区 toggleRow（bumpCfg+save）
- [x] 协议文档 docs/CARLINK.md（action/extras 冻结表+桌面行为+落雪接桥说明+版本纪律）
- [x] R4 落雪端补丁——lx-music-car fork carlink 分支（CarLinkSender 广播 + setCurrentLyric 钩子 + refreshLyric 拆 isRunPlayer 门 + setLyric 懒建 + pauseLyric 清显，零 JS 改动零 lx 开关依赖）；桌面 CI 加 lx-music-car job：拉 carlink 分支 npm ci+gradle 出包，与海鸥桌面同签（SEAGULL_KEYSTORE_B64），产物 lx-music-car-apk
- [x] 自检：CarLinkCheck（新鲜窗 5/覆盖槽位 12/封面 5）——24 项全绿；13 套自检全绿

## 批次 S 统一底栏与独立播放器（2026-10-19 真机反馈三件套，spec: .monkeycode/specs/dock-player-widget）
- [x] S1 底栏迷你播放器权重化——用户反馈「底栏没有小的播放器」：根因是 media 块 wrap-content 挤不过 apps 菜单（fitPass 实测超宽就把整条藏掉）。media 改 weight=1 占满中段剩余宽（恒等于剩余宽，永不超宽）；titleCol weight=1 弹性收缩+去 maxWidth；progTrack 改 MATCH_PARENT；fitPass/suppressedKey/onSizeChanged 机制整体删除
- [x] S2 独立播放器块（壁纸页第七块）——row0[封面 44dp 点=playPause + 列[标题 15sp/来源 11sp]] + 歌词 12sp 单行（Lyrics.window）+ 时间 m:ss/m:ss 右对齐 + 进度条（槽宽×进度）+ ⏮⏯⏭ 36dp；数据源 MediaListenerService.snap（本地/蓝牙/落雪通吃）；空态「未在播放」控制键保留；1s 自驱 tick（onAttached 启 onDetached 停+enabled 门）；可拖/可捏合缩放（0.5~2.4 文本缩放）；换色 PlayerSkin 六预设（白绿蓝橙粉黄单色方案：文本/控制键/进度填充同色，槽恒 33% 透明白），长按菜单尺寸+换颜色+隐藏；组件管理清单第七行；GlyphIconView 加实例级 tint(int)；MediaListenerService 加 mediaPosMs()/mediaDurMs()；LauncherModel wallPlayer 五字段（X/Y/Scale/ColorIdx/Enabled）持久化
- [x] S3 移除画布键条——用户反馈「进入画中画那个底栏去掉，固定桌面的底栏为统一底栏」：PipBoard 玻璃键条（返回/桌面/导航栏）下线（buildKeybar/keyButton/常量/barA/barB/ExitListener/DockToggleListener 全删，keybarReserve 恒 0 = VD 全高，applyKeybar squeeze 恒 0）；Dock 恒显（进单应用不再自动收起）；退回分屏走 DockBar logo onHome（同款语义：showSplit+解锁横滑+导航栏展开）；setDockCollapsed 保留（只剩回桌面展开路径）；RootOps BACK 注入能力随键条一并下线
- [x] 自检：PlayerCheck（预设 10/fmtMs 向量 7）——17 项全绿；14 套自检全绿

## 终局
- [x] 功能清单对齐（本文件逐项核对 + 真机验收项标注）——G-N 共 21 项全部勾选并核对落位说明与复盘行齐备（J-N 复盘行本次补录）；自检 8 项全绿（QuickbarCheck 垫片修复，见下）
- [x] 三次大复盘（并行分组精读 → 亲自复核 → 自检交叉验证）+ 修复全部发现——三组并行精读报 30+ 条，逐条复核后修 19 处（P1 全修），P2 余量记录在案（见复盘记录末尾）
- [x] javac --release 11 + 全部 selfcheck 绿 → commit/push → CI 监控 → APK 交付

### 真机验收项（模拟器/桌面验不了，装机后逐项过）
| # | 项 | 预期 |
|---|---|---|
| 1 | G1 开机自启 | 重启后 ≤90s 内自动布屏（slot1），退避 15/30/60s；QUICKBOOT_POWERON 车机实测 |
| 2 | G2 场景一键 | QuickBar 场景卡一键布屏；重命名场景后旧布局引用不悬空 |
| 3 | H1-H4 行车块 | GPS 1s 刷新、隧道丢星显示 --、行程累计 speed>1.5m/s |
| 4 | I4 指南针兜底 | GPS 失联时航向补显；无磁力计车机 G块照常（N3 拆绑后） |
| 5 | J3 快捷开关 | WiFi/蓝牙/飞行 toggle 在目标 ROM 生效；轮询 2s 原位改色 |
| 6 | J5 目的地卡 | 高德/百度深链拉起、坐标不偏（GCJ02）；都没装 toast |
| 7 | K1 壁纸联动 | SAF 目录树授权持久化（重启后仍有效）；六子目录切换即时生效 |
| 8 | L1 导航卡片 | 高德/百度导航中横幅显示转向/距离；探明句式后回填 T_TURN/T_REMAIN |
| 9 | M1 音乐扫描 | U盘热插拔重扫（现只进页/开块触发）；root find 来源曲目可播 |
| 10 | M2 前台服务 | 通知常驻表现、熄屏续播、音频焦点让行 |
| 11 | N1 手势 | 双指下滑真熄屏（input POWER 亮灭 toggle 语义）；上甩进画中画手感阈值 |
| 12 | N2 玻璃 | 亮色主题+玻璃拉满时卡片文字对比度 |
| 13 | O1 方控键 | 方控播放/上下曲控本地曲与蓝牙曲；无会话时按键行为与改前一致 |
| 14 | O2 封面 | 本地曲/蓝牙曲封面显示；无封面回落纯色不报错 |
| 15 | O3 配置 | 导出→新机导入→重启桌面后布局/主题/目的地齐全；破损文件拒绝且旧配置无恙 |
| 16 | O4 崩溃 | 连续崩溃后重启画布回落默认布局+一次性提示；日志导出含 crash 文件 |
| 17 | P OBD | 配对连接、熄火重连；OBD 车速 vs GPS 真值对比；转速/水温/电压显示；亏电提醒触发 |
| 18 | Q 组件管理 | 负一屏长按重排/隐藏/空白长按找回；重启布局保持；壁纸页时钟块隐藏后经背景长按找回；两页全隐藏入口仍可用 |
| 19 | R 音乐控制 | 底栏 ⏮/进度条/封面；音乐块歌词行+控制三键+封面点按暂停；落雪直连行变化同步到时钟块/音乐块/媒体卡；断流 5s 后回落原管线；关闭直连开关行为复原 |
| 20 | S 统一底栏+播放器块 | 底栏横幅在中段剩余宽内自适应（长标题省略不消失）；壁纸页第七块播放器：拖动/捏合缩放/换色/隐藏/找回，时间进度随播放走；单应用铺满时画布无键条、DockBar 恒在，logo 退回分屏 |

## 复盘记录
（每个功能完成后在此追加一行：功能 — 复盘结论）
- G1 开机自启恢复 — 用 SystemClock.elapsedRealtime<90s 窗口判定替代 BOOT 广播标记，规避 receiver 与 home 启动竞态；slot1 复用现有 deployIfBound+selfHeal 重试链零新增机制；slot2 仅开机窗口放行（批次 CG 手动门槛保持）；新增 autoRestore 开关（默认开）与 QUICKBOOT_POWERON action。javac 绿。
- G2 场景一键 — 调研期发现 QuickBar 已有 @fn: 前缀分发模式，场景沿用同构 key 约定 @scene:<名字>（名字而非索引，重排序安全）；编辑工具多行锚块匹配吞码两处（QuickBar cell/桌面菜单），改小锚点后恢复。场景存档全局共享（scenes 数组），重命名/删除时同步清洗所有布局快捷栏的引用 key（quickbarByMode 是 private，新增 allQuickbars() 访问器）；PipBoard.applyScene 走 savePkg+s2Entered+deployIfBound 复用幂等部署链，空槽场景 slot2 重新上闸（批次 CG 语义）；删除场景即清格，悬空 key 兜底 toast「场景不存在」。javac 绿。
- H1 LocationHelper — 单例 fanout（acquire/release 配对，最后一个退订即 removeUpdates，对齐歌词 sink 纪律）；GPS 在位用 GPS、否则回落 NETWORK；1s/0m 节流；回调统一切主线程；10s 内无新 fix 视为失联（隧道显示 --）。javac 绿。
- H2/H3/H4 行车块 — 调研期发现 makeWidget 所在的 DesktopView 已是死代码（当前零实例化），三块 UI 按原意（与时钟同套摆位缩放）落到壁纸页时钟块同款 Drag/Zoom 机制：一个块三行（车速/海拔航向/行程），摆位缩放存 model.wallGps*；无权限=缩成「点按开启定位」（点按申请权限，回执经 HomeActivity.onRequestPermissionsResult 转发 refreshGpsBlock）；隐藏后经时钟菜单恢复；1s 定位事件原位 setText（审计 M4 纪律）；行程=进程级点火时长+speed>1.5m/s 位移累计，重启归零（BACKLOG #14）。合并为单块而非三个独立组件：壁纸页摆位机制按块计，三块独立可拖反而拥挤，J1 负一屏如需拆分再拆。javac 绿。
- I1 LunarCalendar — 位表由 lunar-java 1.7.3 逐项生成（比抄网页表可靠，1900=0x04bd8 与经典表逐位一致）；全量表内 67537 天逐日比对 0 差异后，LunarCheck 钉 30 个机器生成用例。教训：第一轮手写期望值 10 处错（2020 闰四月起点、2057 闰月等全记错），机器生成期望值才可信；锚点前（1900-01-31 之前）超界返回 null。javac 绿 + LunarCheck 绿。
- I2 农历行 — 时钟块 date 行下加 lunarRow（"农历 腊月十九 · 乙巳(蛇)"），超表隐藏；restackClockBox 等比缩放同步。javac 绿。
- I3 倒计时块 — 壁纸页块 + 点按菜单配置（改名称/改日期/大小/隐藏），日期手动解析+校验（失败 toast），跨零点由 30s tick 重算；cdDays 用本地零点差避免时区坑。javac 绿。
- I4 指南针兜底 — 车机场景 GPS 航向是主源，磁力计+加速度计只做失联兜底（融合=优先级仲裁而非盘面）：500ms 节流、横屏 remapCoordinateSystem(AXIS_Y, AXIS_MINUS_X)、±180 最短角差指数平滑；无磁力计静默降级。javac 绿。
- I5 监控块 — 内存（ActivityManager）/CPU（SysOps.cpuPct，120ms 采样）/存储（StatFs /data）三行；采样放后台线程（cpuPct 的 sleep 在 UI 线程会卡帧），结果 uiPost 原位 setText；默认关，时钟菜单开。javac 绿。
- J1 负一屏容器 — pager 第 0 页方案复用现成滑动基建零新轮子；onPageChanged 驱动 setActive 让 2s 开关轮询只在可见时跑。javac 绿。
- J2 天气卡+限行卡 — 时间源统一 model.weatherUpdatedAt（取数落档）而非另起时钟；限行尾号 10 位校验前置，周末免检；配置与负一屏同源 LauncherModel。javac 绿。
- J3 快捷开关 — 轮询原位改色（M4 纪律）；热点 service call 交易号随 ROM 漂移弃探号直开系统页（RESEARCH A5）；toast 文案走 R.toString() 免硬编码。javac 绿。
- J4 日志导出 — 三级回落（/sdcard/Seagull File API → root cp → getExternalFilesDir）覆盖有/无 root 与 scoped storage；导出后台线程，回执 uiPost；头部带 versionName+git 短哈希便于回传定位。javac 绿。
- J5 目的地卡 — GCJ02 直传（amapuri dev=0 → baidumap coord_type=gcj02 级联），resolver 探测+<queries> 声明；数据统一 LauncherModel 后设置页与负一屏零同步代码。javac 绿。
- K1 壁纸联动 — opt-in 默认关；SAF 目录树（六子目录约定）取代逐目录 SAF 单选，改包内容即时生效；WMO 码映射表按官方分组；解析失败静默回落手动壁纸；Weather.onUpdated 静态钩子让设置页/负一屏/自动档三路径同源对齐。javac 绿。
- L1 导航卡片 — 通知正文不落 logcat（批次 V 纪律）；骨架+模板常量结构为「真机探明格式再回填」设计，探明前静默隐藏；与 NavCard（车联服务）双数据源解耦互不等待。javac 绿。
- M1 音乐扫描 — 三来源级联（公开 Music → StorageVolume → root /mnt/media_rw）+ 去重；「歌手 - 歌名」命名约定对齐 Lyrics 拆元数据；上限 2000 防卡死；同目录 .lrc 记 lrcPath 供 M4。javac 绿。
- M2 播放服务 — 押注标准 MediaSession：MiniPlayer/歌词/控制从 getActiveSessions 白拿联动，零专用代码；prepare 后台线程；AudioFocusRequest(GAIN)；播完循环。javac 绿。
- M3 音乐列表 UI — SeekBar 拖完才 seek（拖动中不打断播放）；高亮行变化才 notifyDataSetChanged（轮询不全量刷）。javac 绿。
- M4 lrc 联动 — localFile（Music 同目录）→ MusicLib.lrcFor（扫描缓存 lrcPath）双路兜底；框架 MediaMetadata 无自定义键（LYRICS 属 androidx），元数据注入弃。javac 绿。
- M5 播放列表块 — 第五块全走块生态（Drag/Zoom/placeBlock/showMonMenu 复用）；ensureMusicScanned 防重扫；tick 原位刷新。javac 绿。
- N1 桌面手势 — dispatchTouchEvent 窗口级先于子 View，PipBoard/Drag 拦不住但阈值 160px+420ms 压误触；只在壁纸页生效，主界面画布手势不掺和；熄屏选 root input POWER（DevicePolicyManager 要管理员激活弹窗，车机 root 场景前者零交互）。javac 绿。
- N2 主题包 — 三旋钮全局化（round() 统一乘 radiusScale；panel/card 吃 glassAlpha，ground 不动保对比；描边=中间调 0x40 淡边）；胶囊不描边防挤。javac 绿。
- N3 G值表块 — 加速度计幅值低通+会话峰值；150ms 节流+文本变化才 setText；startSensors 拆开加速度计/磁力计绑定（旧逻辑无磁力计整机哑火，G 块零磁力计依赖）；频谱弃（RECORD_AUDIO 成本）。javac 绿。
