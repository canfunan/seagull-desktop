# Technical Design — 组件管理：负一屏卡片 + 壁纸页块（批次 Q）

Updated: 2026-10-19
需求: ./requirements.md

## 架构总览

```
LauncherModel                     NegScreenView                  WallpaperPage
┌──────────────────┐   读写    ┌──────────────────────┐        ┌──────────────────┐
│ negOrder  [key]  │◄──────────│ build 五卡一次        │        │ 背景长按=入口菜单  │
│ negHidden [key]  │           │ relayout() 按序两列   │        │  ├ 添加小组件(原) │
└──────────────────┘           │ 卡长按=菜单(移/隐/管)  │        │  └ 组件管理      │
       ▲                        │ 空白长按=组件管理      │        │ 时钟菜单+组件管理  │
       │ NegCards 纯静态         └──────────────────────┘        │  +隐藏时钟块      │
       └──────────── 序归一/移动/列分配（JVM 自检 NegCardsCheck）  └──────────────────┘
```

零第三方依赖；卡片序/显隐走 LauncherModel 存档（save() 全量落档），序逻辑抽纯静态类供 JVM 自检。

## 模块设计

### 1. NegCards.java（新，纯静态）

- `DEFAULTS = {weather, switches, restrict, dest, log}` —— 默认序按奇偶分列恰好还原现状：偶位左列（天气、限行），奇位右列（快捷开关、目的地、日志）
- `normalize(List<String> saved)` → 去未知 key、缺省卡追加序尾、去重保序
- `move(List<String> order, String key, int delta)` → ±1 交换，越界原样返回
- `filterHidden(List<String> hidden)` → 只留合法 key、去重
- `colOf(int index, int total)` → 交替发牌（A,B,A,B…）左列容量 ⌊n/2⌋，发完剩余全进右列；实现为「末位且 n 为奇数→右列，否则奇偶位」
- `labelOf(String key)` → 中文标签（天气/快捷开关/限行/目的地/日志）
- 全部无 Android 依赖，NegCardsCheck 覆盖 12+ 用例

### 2. LauncherModel 扩展

- 新字段：`public final List<String> negOrder = new ArrayList<>();`（空=未自定义，读侧 normalize）、`public final List<String> negHidden = new ArrayList<>();`
- save()：`o.put("negOrder", JSONArray)` / `o.put("negHidden", JSONArray)`
- load()：`optJSONArray` 逐项读字符串；JSON 异常回落空表（R4.2）

### 3. NegScreenView 改造

- 构造期建五卡视图各一次（字段化：weatherBody/restrictBody/switchBody/destBody/logBody + 各自内容视图），新建 `relayout()`：
  - 全部卡 `removeView` 脱离旧父（先判 parent 非空）
  - 按 `NegCards.normalize(model.negOrder)` 序遍历可见卡，`colOf(i)` 决定进 colA/colB，LayoutParams 复用 card() 原参数
  - 隐藏卡仅脱离不挂回
- 卡片菜单（每卡 `setOnLongClickListener`）：上移 / 下移 / 隐藏 / 组件管理；操作后 `model` 更新 + `save()` + `relayout()`；目的地卡行级长按（删目的地）优先消费，卡级长按落在标题/空白处，互不冲突
- 空白长按：根视图 `setOnLongClickListener` → 组件管理对话框（五卡行，前缀「已显示/已隐藏」，点行切换）
- 组件管理关闭后 `relayout()` + save；开关 2s tick 只改 chip 颜色不重建（审计 M4 纪律不变）
- setActive(true)/refreshAll 不动序，只刷内容——序变化只在用户操作时发生

### 4. WallpaperPage 增量

- 背景长按改入口菜单：`添加小组件…`（原 showAddWidget）/ `组件管理…`
- 组件管理对话框：六块行（时钟/行车/倒数/监控/音乐/G值），前缀显隐状态，点行切换对应 `wallXxxEnabled` + `model.save()` + 对应 `refreshXxxBlock()`；时钟行切到隐藏 = `wallClockEnabled`（新字段，默认 true）
- 时钟块渲染挂 `model.wallClockEnabled` 门：隐藏时 clockBox GONE（农历行随钟走，同门）
- 时钟菜单（点时钟）：追加「组件管理…」「隐藏时钟块」两项，原找回项保留
- 全隐藏兜底：背景长按监听在页面根上，块全 GONE 不影响（R3.4）

### 5. 自检 NegCardsCheck

- normalize：空表回落默认；未知 key 丢弃；缺失追加序尾；重复 key 去重保序；完整表原样
- move：首位上移无操作；末位下移无操作；中间位交换正确；move 后序仍含全部 key
- filterHidden：非法 key 过滤；重复去重；空表合法
- colOf：0→0、1→1、2→0；默认序奇偶 = 现状列布局（weather/restrict 左，switches/dest/log 右）
- labelOf 全 key 覆盖非空
- 跑法沿用垫片架构：sc-all 全量编译（排 shim）→ shim 单独编译 → 运行时 classpath shim 最前

## 权衡与偏离记录

- 负一屏移动 = 菜单上移/下移（对齐全应用 AlertDialog 菜单习惯），真拖拽跨列重排复杂度高收益低，用户语「长按移动位置」由「长按→菜单→上移/下移」满足
- 卡片序两列分配用交替发牌规则（自检时发现纯奇偶位会把日志卡分错列——原版右列 3 卡左列 2 卡，n 为奇数时末位必须落右列，规则已按真实布局修正并加用例锁死），少一个字段、默认序零迁移、重排后自动均摊
- 时钟块新增 hide 开关放 LauncherModel（wallClockEnabled）而非 negHidden —— 两页语义分开，互不污染
- 负一屏卡片视图构造一次复用（reparent），relayout 只动挂载关系，运行态（chip/文本）零丢失
- 显隐清单支持全部隐藏（需求 AC2.3/R3.4：页面留白但空白长按入口永远可用），实现时曾加「至少留一张卡」守卫，与规格冲突后移除

## 测试计划

1. NegCardsCheck（JVM，纯逻辑 12+ 用例）
2. javac 全量 + 12 套自检回归（原 11 + NegCardsCheck）
3. 真机验收：负一屏长按菜单重排/隐藏/找回；重启后布局保持；壁纸页时钟块隐藏后经背景长按找回；两页全隐藏后入口仍可用（DEVTASKS 表第 18 项）
