# CARLINK 歌词直连开放协议 v1

任何 Android 播放器（不限于落雪音乐）都可以把当前歌词行实时推给海鸥车机桌面，
桌面在歌词窗口做行级覆盖。协议是普通隐式广播，零依赖、零权限。

## 接入方式

播放器在**歌词行变化时**（不是每帧、不是定时轮询）发一条广播：

```java
Intent i = new Intent("com.seagull.carlauncher.CAR_LYRIC");
i.putExtra("line", "当 前歌词行文本");        // 必带；空字符串 = 间奏/无歌词（桌面清显）
i.putExtra("line_t", "翻译行文本");          // 可选；有翻译歌词时带
i.putExtra("pos", 81234L);                  // 可选；播放位置毫秒（long）
i.putExtra("st", 1);                        // 可选；1=播放中 0=暂停（int）
i.putExtra("title", "歌曲名");              // 可选；用于封面键与显示
i.putExtra("artist", "歌手");               // 可选
i.putExtra("cover", "content://...");       // 可选；封面 URI 或本地路径
getContext().sendBroadcast(i);
```

要点：

- **行变化才发**。副歌重复行、同一行停留期间都不要再发，桌面按行覆盖。
- **`line` 为空串 = 清显**。间奏、纯音乐、找不到歌词时发空行，桌面把歌词区域清空，
  避免残留上一首的句子。
- **封面只在变化时带**。没有封面就省略该 extra，桌面沿用 MediaSession 封面。
- `cover` 支持 `content://`、`file://`、`http(s)://`、本地绝对路径；桌面单线程解码，
  LRU 缓存 60s 负缓存，播放器无需自己做缩略图。

## 桌面侧行为

- 新鲜窗 **5 秒**：超过 5 秒没收到新广播，桌面判定断流，自动回落到
  MediaSession + 本地 LRC 抓取的原管线（对时、翻译、版本轮换照旧）。
- 覆盖发生在歌词窗口出口的单点上：底栏迷你播放器歌词、壁纸音乐块歌词行、
  时钟块歌词、媒体卡片、通知歌词全部同时吃到直连行。
- 槽位约定：窗口 n 行时，当前行放最后一槽，翻译行（若有且 n≥2）放倒数第二槽，
  其余槽为空串。消费方永远把最后一槽当当前行。
- 设置里「歌词 → 落雪直连（广播歌词）」可整体关闭（默认开）。

## 落雪音乐（lx-music-car）接入说明

落雪端在 native 的 `LyricModule` 已有逐行回调（`setLyric(lyric, translation, roma)`
与 `play(time)`），接桥时只需在行回调里组装上面的广播发出即可；翻译开关跟随
`toggleTranslation` 状态。该补丁落在落雪仓库侧，由落雪 fork 的 CI 出包。

## 版本

- v1（2026-10）：初始协议。extra 名、action 名向后兼容冻结；
  新 extra 一律可选，老桌面忽略不认识的 extra。
