# Technical Design — 音乐控制与直连歌词（批次 R）

Updated: 2026-10-19
需求: ./requirements.md

## 架构总览

```
lx-music-car（另一仓库）           MediaListenerService              消费方
┌──────────────────┐  CARLINK   ┌─────────────────────┐          ┌────────────────┐
│ LyricModule 行事件 │──广播────►│ Snap/pkg/title/state │──1s──►  │ 底栏横幅        │
│ （R2 补丁后）      │  line/cover│ playPause/prev/next  │          │  ⏮ ⏯ ⏭+进度+封面│
└──────────────────┘            │ meta()/mediaProgress │          ├────────────────┤
                                └─────────────────────┘          │ 壁纸音乐块      │
标准 MediaSession（任意播放器）────► Lyrics.window() 单点出口 ◄───  │  封面+歌词行+控制行│
                                     ▲ 直连覆盖（新鲜时）           └────────────────┘
```

## 模块设计

### 1. CarLink.java（新，纯静态，JVM 自检）

- 协议常量：`ACTION = "com.seagull.carlauncher.CAR_LYRIC"`；extras 键 line/line_t/pos/st/title/artist/cover
- `FRESH_MS = 5000`
- `fresh(long lastTs, long now)` → 直连是否可用
- `override(String[] fallback, int n, String line, String lineT, boolean on, boolean fresh)` → 窗口替换：off/不新鲜 → fallback 原样；on+fresh → n 槽全空、lineT 有值且 n≥2 时占倒数第二、line 占最后（空行=全空清显，间奏语义）
- `coverUsable(String cover, boolean fresh)` → 直连封面是否可尝试

### 2. Lyrics.java 直连层

- 静态 `Direct{ line, lineT, cover, ts }`（volatile），`start()` 里 registerReceiver（appCtx，运行时注册不受隐式广播限制）
- 广播到来：读 extras 写 Direct（ts=now）
- `window(ctx, m, n)` 头部插直连分支（唯一出口，外部直调者自动生效）：
  `m.lxLinkEnabled && CarLink.fresh(ts, now)` → `CarLink.override(...)`；否则原管线
- 不动 advance/pos/对时逻辑——直连是行级覆盖，恢复回落零成本

### 3. LauncherModel + 设置

- 新字段 `public boolean lxLinkEnabled = true`；save/load（`o.put("lxLinkEnabled", …)` / `optBoolean` 默认 true，R3.5）
- SettingsSectionActivity 歌词分区加 toggleRow「落雪直连（广播歌词）」：`LauncherModel.bumpCfg()`（Lyrics cfg 缓存代际失效）+ save

### 4. CoverCache.fromUri

- `public static Bitmap fromUri(String key, String uri)`：LRU 命中→负缓存→后台单线程解码（复用 openInputStream+sample+NEG），get() 的 URI 分支重构为调用同一私有方法
- 键由调用方给（底栏/音乐块用 `title|artist`，直连加 `|carlink` 后缀防串）

### 5. MediaListenerService 增强

- `public static MediaMetadata meta()` → current 控制器 metadata（封面/时长来源）
- `public static float mediaProgress()` → 0~1（PlaybackState 位置+速度×Δt / duration；duration≤0 返回 -1）

### 6. DockBar 底栏（R1）

- 常量 private 去修饰（包内共享给 WallpaperPage）；onDraw 加 `G_PREV` case（G_NEXT 镜像：左竖条+右三角）
- 横幅结构：`mediaIcon + [mediaTitle / 进度条track 3dp] + btnPrev + btnPlay + btnNext`（prev 插在 play 前，next 已有）
- 1s `mediaTick` runnable（bindMedia 可见时 post，GONE 分支/onDetachedFromWindow remove）：
  进度 = `MediaListenerService.mediaProgress()`（<0 隐藏 track）→ fill 宽度；封面 = `CoverCache.get(title, artist, meta())` 有则换图（异步解码下轮补上），无则回落应用图标
- fitPass 量实际宽度，横幅加宽自动让位（R1.4）

### 7. WallpaperPage 音乐块（R2）

- 结构追加：`musLyric`（12sp dim 单行）+ `musCtrl` 横排（GlyphIconView 28dp × 3：G_PREV/G_PLAY|G_PAUSE/G_NEXT）
- `musArt` 22dp → 28dp；点按封面 = playPause（块本体点按仍是进本地音乐）
- `applyMusScale`：musLyric 12*s、musCtrl 图标 28*s、musArt 28*s
- 新增 `Lyrics.Sink`（构造注册 / onDetachedFromWindow 摘除，沿用 islandSink 保活纪律）：每跳更新 musLyric 文本+显隐、控制行播放态图标、无会话时整行收起（R2.3）
- `showRemoteMedia`：CoverCache.get 空 → `Lyrics` 直连封面新鲜则 `CoverCache.fromUri(t+"|"+a+"|carlink", cover)`

### 8. 自检 CarLinkCheck

- fresh 边界（0/等于/超过 5s）；override：关/不新鲜原样、n=1、n=2 翻译位、空行清显、n=5 常规；coverUsable 四象限
- 13 套全绿（原 12 + CarLinkCheck）

## 权衡与偏离记录

- 直连只做行级覆盖，不动 lrc 抓取/对时——落雪补丁没装/断流时桌面行为与今天完全一致（灰度友好）
- 进度条用自绘 3dp track（fill 宽度直接 LayoutParams），不引 SeekBar——避免触摸焦点与拖动块冲突，也省手势处理
- 底栏封面直接复用 CoverCache（LRU2 + 负缓存），底栏与音乐块同键共享解码结果
- 落雪端补丁（LyricModule 行事件处 sendBroadcast）不在本批次，照 CARLINK.md 单独提交其仓库
- G_PREV 补进 GlyphIconView 而非另造控件——同一套白线几何语言

## 测试计划

1. CarLinkCheck（JVM 纯逻辑）
2. javac 全量 + 13 套自检回归
3. 真机验收（DEVTASKS 第 19 项）：底栏三键/进度/封面（本地+任一第三方播放器）；音乐块控制行/歌词行/无会话收起；落雪直连待 R2 补丁装机后验
