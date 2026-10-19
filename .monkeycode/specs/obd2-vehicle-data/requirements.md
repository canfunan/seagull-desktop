# Requirements Document — OBD2 车辆数据（批次 P）

Feature Name: obd2-vehicle-data
Updated: 2026-10-19
前置：真机有 ELM327 蓝牙头子（经典蓝牙 SPP），车机蓝牙系统设置已配对

## Introduction

经 ELM327 蓝牙适配器读取车辆总线数据（真车速/转速/水温/电压/油量），替代 GPS 估算，并解决驻车电瓶亏电痛点。零依赖：协议实现自写（公式为 SAE J1979 标准事实），初始化序列与错误 token 参考开源项目 pires/obd-java-api（MIT）与 obd2_kit 的公开协议清单。

## Glossary

- **ELM327 适配器**：插在车 OBD-II 口的蓝牙设备，把车辆总线翻译成 AT 指令/文本响应，以经典蓝牙 SPP 串口暴露
- **PID**：SAE J1979 定义的参数编号（如 010D=车速），Mode 01 请求返回 "41 <pid> <数据字节>"
- **错误 token**：ELM327 的失败应答文本（NO DATA / UNABLE TO CONNECT / CAN ERROR / BUS INIT:...ERROR / ? / STOPPED）
- **数据快照**：ObdEngine 暴露的 static volatile 状态对象，UI 各块 1s tick 自取（仿 MusicLib 模式）
- **OBD 在线**：SPP 连接建立且 AT 初始化完成、最近 5s 内有过有效响应

## Requirements

### R1 连接管理

**User Story:** AS 车主，I want 点火后桌面自动连上 OBD 头子，so that 上车即见真车数据。

#### Acceptance Criteria

1. WHILE 设置里 OBD 开关为开且适配器已选定，ObdEngine SHALL 维持一条 SPP 连接，断线后按 5s 间隔自动重连。
2. WHEN 连接建立，ObdEngine SHALL 依次发送 ATZ、ATE0、ATL0、ATS0、ATH0、ATSP0、0100 完成初始化，逐条等待 '>' 提示符。
3. WHEN 初始化完成，ObdEngine SHALL 用 ATRV 或 0142 判定 ECU 响应并把状态置为在线。
4. IF 蓝牙总开关为关或所选适配器不在已配对列表，ObdEngine SHALL 保持离线状态并在设置页显示可读原因。
5. WHEN 用户更换适配器或关闭开关，ObdEngine SHALL 断开旧连接并停止轮询线程。

### R2 轮询与解析

**User Story:** AS 车主，I want 车速转速等数值实时跟车，so that 行车数据可信。

#### Acceptance Criteria

1. WHILE OBD 在线，ObdEngine SHALL 按用户设定间隔（默认 2s）单命令排队轮询：010D、010C、0105、0142、012F，同一时刻最多一条未决命令。
2. WHEN 收到含 41 帧头的响应，解析器 SHALL 按 SAE J1979 公式换算：车速=A，转速=(A*256+B)/4，水温=A-40，电压=(A*256+B)/1000，油量=A*100/255（%）。
3. IF 响应为错误 token 或空，解析器 SHALL 将对应 PID 置失效并继续队列下一条，轮询循环保持运行。
4. WHEN 某连续 10 轮全部 PID 失效，ObdEngine SHALL 判定离线并触发重连。
5. WHEN 响应包含多 ECU 行（7E8 等前缀）或 SEARCHING/BUS INIT 过渡文本，解析器 SHALL 跳过无效行只取 41 数据行。

### R3 数据消费

**User Story:** AS 车主，I want 行车块显示真车速/转速/水温，so that 桌面成为行车仪表。

#### Acceptance Criteria

1. WHILE OBD 在线且 010D 有效，行车块车速 SHALL 显示 OBD 车速；OBD 离线或失效 SHALL 回落 GPS 车速。
2. WHILE OBD 在线，行车块 SHALL 增示转速与水温行；对应 PID 失效时该行显示 --。
3. WHILE OBD 在线，监控块 SHALL 增示电压行（如 "电瓶 12.4V"）。
4. WHEN OBD 离线，行车块与监控块 SHALL 收起 OBD 增行并回落 GPS 显示。

### R4 电压提醒

**User Story:** AS 车主，I want 电瓶电压过低时收到提醒，so that 驻车用电不趴窝。

#### Acceptance Criteria

1. WHEN 电压读数连续 60s 低于用户阈值（默认 12.0V），ObdEngine SHALL 发一条本地通知「电瓶电压偏低」，同一会话最多提醒一次。
2. WHEN 电压回升到阈值以上，提醒状态 SHALL 复位允许下次触发。
3. IF 电压 PID 与 ATRV 均失效，提醒逻辑 SHALL 静默跳过。

### R5 设置

**User Story:** AS 车主，I want 在设置里选适配器调参数，so that 多车/多头子场景可用。

#### Acceptance Criteria

1. WHEN 用户打开「车辆数据」设置分区，页面 SHALL 提供：启用开关、适配器选择（系统已配对蓝牙设备列表）、轮询间隔（1s/2s/5s）、电压阈值（11.5/12.0/12.5V）。
2. WHEN 用户首次开启 OBD 开关，系统 SHALL 申请 BLUETOOTH_CONNECT 运行时权限；拒绝后设置页 SHALL 显示权限说明。
3. WHEN 任一设置变更，变更 SHALL 持久化进 LauncherModel 并即时作用于 ObdEngine（间隔/阈值热生效，适配器变更触发重连）。

## 备注

- 频谱/G值等其他块与本批次无关；G 值块继续走加速度计。
- 真机验收项：配对连接、熄火重连、车速真值对比 GPS、电压提醒，全部并入 DEVTASKS 终局表。
