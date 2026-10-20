# 技术设计：批次 S

## S1 DockBar 权重化

- center = apps(wrap) + media(wrap) → 改 media 为 `weight=1`（`LinearLayout.LayoutParams(0,-2,1f)`，leftMargin 保留）：media 宽度恒等于中段剩余宽，fitPass 的「超宽整条藏」机制失去存在意义 → 删 fitPass/suppressedKey/onSizeChanged 复位逻辑（mediaTick 的 VISIBLE 守卫保留）。
- media 内部：icon(22 wrap) + titleCol(weight 1, minWidth 0) + ⏮⏯⏭（34 wrap）。title 去掉 maxWidth，单行截断由权重宽度天然约束；progTrack 宽度改 MATCH_PARENT（titleCol 宽度已被权重定死），progFill 仍按 progTrack.getWidth() 比例改宽。
- 播放态语义不变：有会话才 VISIBLE（GONE 时 apps 独占中段）。

## S2 播放器块（WallpaperPage 第 7 块）

- 构建区照 musBox 模式：playerBox(VERTICAL) = row0[cover 44dp(点=playPause) + col[标题 15sp、来源 11sp dim]] + lyric 12sp 单行 + time 10sp 右对齐 + progTrack(3dp, MATCH_PARENT) + ctrl 行[⏮⏯⏭ 36dp]。
- 数据：`MediaListenerService.snap()/meta()/mediaProgress()/mediaPosMs()/mediaDurMs()` + CoverCache.get（直连封面回落走 showRemoteMedia 同款 fromUri 一次性补绘）；歌词走 Lyrics.Sink（构造注册，onDetachedFromWindow 摘除）。
- 1s tick：Runnable 自驱（onAttachedToWindow 启 / onDetached 停 + enabled 门），刷新进度/封面/播放态/时间行；标题/来源随 snap 变化。
- 缩放：applyPlayerScale(s) 等比字号 + placeBlock 复摆（同 applyMusScale）；Drag 管线照抄 musBox 9 参签名。
- 颜色：PlayerSkin 纯静态（NAMES/COLORS 6 预设 + clamp + fmtTime）；accent 应用到标题/来源/歌词/时间/控制键/进度填充。GlyphIconView 增实例级 `tint(int)`（paint 字段实例化，默认仍 DockBar.INK）。
- 空态：无会话 → 封面 GONE、标题「未在播放」、来源「点按键控制本地或蓝牙音乐」、时间行空、进度隐藏。
- LauncherModel：wallPlayerEnabled(false)/wallPlayerX/wallPlayerY/wallPlayerScale(1f)/wallPlayerColorIdx(0)，save/load 照 wallG 组。
- 组件管理清单加第 7 行「播放器块」→ refreshPlayerBlock()；syncBlocks 追加。
- 自检 PlayerCheck：预设名/色数组等长且非空、clamp 边界、fmtTime 向量（0/59.9s/65s/负数/1h）。

## S3 PipBoard 键条移除

- createCard 里 buildKeybar/addView(keybar) 删除（barA/barB 恒 null，applyKeybar 判空已兼容）。
- keybarReserve() → 0（fixedVdSize 恢复全高）；applyKeybar squeeze 恒 0、bar 行恒 GONE（判空短路）。
- buildKeybar/keyButton/KEYBAR_DP/KB_MARGIN_* 全删；HomeActivity 的 onCanvasHome/onCanvasNav 注入保留（空转无害，后续可复用）。
- 返回键能力随条消失：VD 内应用自带返回 UI 可用；退出应用 = DockBar logo。DEVLOG 记录该能力取舍。

## 兼容/风险

- S1 权重化后横幅视觉从「紧凑胶囊」变「中段满宽条」——玻璃底 + 左对齐内容，可接受；真机复测宽度自适应。
- S2 第七块与 musBox 控制行并存：音乐块保留队列视角，播放器块专注控制，两者共用同一数据源与控制入口（MediaListenerService 静态），状态天然一致。
- S3 fixedVdSize 变化 → 已部署应用的 VD 首次 resize（resizeableActivity 判定逻辑原样生效，不可缩放应用走快照预览老路）。
