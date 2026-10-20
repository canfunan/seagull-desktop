# Requirements Document — 音乐控制与直连歌词（批次 R）

Feature Name: music-control
Updated: 2026-10-19
前置：无（底栏/壁纸块通用能力）；R3 直连歌词的完整体验需 lx-music-car 端补丁（另仓库交付）

## Introduction

桌面音乐能力三线合一：底栏媒体横幅补全为迷你播放器（上一曲/进度/封面）；壁纸页音乐块升级为完整播放器块（封面放大/歌词行/上一曲·播放暂停·下一曲）；新增「落雪直连」开放广播协议接收层——播放器推送精确歌词行，秒级替代按歌名搜词，失效自动回落现有管线。全部走标准 MediaSession/广播，零逐家播放器适配。

## Glossary

- **会话**：MediaSession，安卓媒体应用的标准对外接口（主流播放器/蓝牙 AVRCP 都有）
- **直连**：播放器把当前歌词行经广播推给桌面的通道（CARLINK 协议）
- **直连新鲜**：最近 5s 内收到过直连广播
- **控制行**：音乐块内 上一曲/播放暂停/下一曲 三个按钮行
- **Snap**：MediaListenerService 暴露的当前会话快照（pkg/title/artist/playing）

## Requirements

### R1 底栏迷你播放器

**User Story:** AS 车主，I want 底栏媒体条能切歌看进度，so that 开车时不用进应用。

#### Acceptance Criteria

1. WHEN 有媒体会话，底栏横幅 SHALL 显示 上一曲/播放暂停/下一曲 三键；三键经会话 transportControls 下发，本地音乐与任意第三方播放器/蓝牙会话通用。
2. WHILE 会话带时长（duration>0），横幅 SHALL 显示细进度条并按 ~1s 节奏推进（PlaybackState 位置+速度推算，无时长则隐藏不留空）。
3. WHEN 会话 metadata 带封面（位图或 URI），横幅 SHALL 用封面替代应用图标；无封面回落应用图标。
4. IF 横幅加宽后空间不足，SHALL 沿用现有 fitPass 让位纪律（整条隐藏不闪现）。

### R2 壁纸页播放器块

**User Story:** AS 车主，I want 桌面音乐块能直接控制播放并看歌词，so that 桌面即播放器。

#### Acceptance Criteria

1. WHEN 音乐块可见且存在会话，块内 SHALL 出现控制行（上一曲/播放暂停/下一曲），播放态图标跟随。
2. WHEN 音乐块可见，块内 SHALL 显示当前歌词行（~1s 跟随，歌词管线同一份状态）。
3. WHEN 无任何会话，控制行与歌词行 SHALL 收起，块回落现有「本地音乐入口」形态。
4. IF 封面来源（本地内嵌/会话 metadata/直连 URI）任一有效，SHALL 显示并随倍率缩放；点按封面=播放暂停。
5. WHILE 块缩放，SHALL 新增行字号/图标尺寸同步走 applyMusScale。

### R3 落雪直连歌词（CARLINK 开放协议）

**User Story:** AS 车主，I want 落雪的歌词一字不差同步到桌面，so that 不受搜词匹配错误影响。

#### Acceptance Criteria

1. WHILE 直连开启且广播新鲜（≤5s），桌面歌词窗口 SHALL 以直连行为准（翻译行占次位），所有消费方（时钟块/音乐块/菜园/岛/通知）自动生效。
2. IF 直连断流/关闭/行为空，SHALL 立即回落现有歌词管线（本地/在线/播放器 metadata），无残影无空白死锁。
3. WHEN 设置里关闭「落雪直连」，SHALL 完全忽略直连广播（现管线路径不变）。
4. WHEN 直连广播带封面 URI，音乐块/底栏封面 SHALL 可用它解码显示（复用 CoverCache 缓存与负缓存纪律）。
5. IF 配置档无该开关字段，SHALL 默认开启（老用户升级零操作）。

### R4 协议与自检

1. 协议 SHALL 以 docs/CARLINK.md 落档：action、extras 表（line/line_t/pos/st/title/artist/cover）、发送时机、失效语义，供落雪端补丁与第三方播放器接入。
2. 直连接受/新鲜判定/窗口替换逻辑 SHALL 抽纯静态类并通过 JVM 自检（含 间奏空行清显、断流回落、关闭开关、翻译行次位）。
