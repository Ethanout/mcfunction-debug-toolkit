# 桥接协议 v1

[English](PROTOCOL.md) | **简体中文**

服务端默认地址：`http://127.0.0.1:8766`

客户端地址：`http://127.0.0.1:8767`

## 端点

| 方法 | 路径 | 鉴权 | 用途 |
|---|---|---|---|
| GET | `/v1/status` | 否 | 版本、连接状态与能力 |
| POST | `/v1/command/validate` | Bearer | 使用实时 Brigadier 命令树解析 |
| POST | `/v1/command/run` | Bearer | 执行一条命令 |
| POST | `/v1/command/batch` | Bearer | 依次执行 1–100 条命令 |
| GET | `/v1/debug/events?since=N&limit=L` | Bearer | 分页读取结构化 `#!` 输出 |
| GET | `/v1/debug/diagnostics?since=N&limit=L` | Bearer | 分页读取 reload/运行时诊断 |

客户端端点：

| 方法 | 路径 | 鉴权 | 用途 |
|---|---|---|---|
| GET | `/v1/client/status` | 否 | 客户端焦点、鼠标状态与能力 |
| GET | `/v1/client/chat?since=N&limit=L` | Bearer | 使用单调游标分页读取 HUD 聊天 |
| POST | `/v1/client/input` | Bearer | 有界的按键/鼠标/视角/光标/拖动/等待序列 |
| POST | `/v1/client/screenshot` | Bearer | JPEG 截图及 base64 图像 |

命令请求接受 `command` 和可选的 `allow_dangerous`。批处理请求接受 `commands`，以及可选的 `stop_on_error` 和 `allow_dangerous`。

响应字段的区别：

- `ok`：语法有效且原版没有明确报告命令失败。未报告结果时仍为 `true`；需要精确数值/成功结果时应检查 `result_reported`。
- `valid`：Brigadier 是否接受语法。
- `result`：Minecraft 的命令数值结果，仅在原版调用结果回调时存在；成功时也可能为零。
- `result_reported`：原版是否调用命令结果回调。一些排队命令（包括没有显式 `return` 的普通函数）可能完成但不报告数值。
- `callback_count`、`success_count`、`failure_count`：聚合分叉命令发出的所有回调。`result` 是全部已报告结果之和；任何回调明确失败都会使 `ok=false`。
- `feedback`：翻译后的命令源消息。
- `feedback_truncated`：已触及 32 条消息/16,384 字符的反馈预算。
- `cursor`：Brigadier 解析错误位置。
- `duration_ms`：服务端线程上的执行时间。

## 安全规则

没有 `allow_dangerous=true` 时，桥接会拒绝若干简单、明确的命令类别：停止服务器，修改 ban/op/白名单，大范围 `kill @a`/`kill @e`，以及 `fill`/`clone`。规则列表有意保持确定性，并位于 `DangerPolicy`。

**这只是防误操作护栏，不是安全沙箱。** 嵌套 `execute run`、数据包函数及其他间接命令可以绕过字符串分类器。持有 bearer token 仍等同于拥有完整权限的 Minecraft 命令源。不要让任一桥接端口暴露到回环地址以外。

服务端线程超时会返回 `timeout_not_executed`（`retry_safe=true`），或返回带 `request_id` 的 `timeout_unknown_outcome`（`retry_safe=false`）。后一种情况表示命令已经开始且可能仍会完成，绝不能自动重试。批处理超时会在观察到取消后于命令之间停止，但无法安全中断正在运行的命令。

客户端输入同样有界：每个请求最多 200 个步骤，每个持续时间最多 30 秒，`total_timeout_ms` 限制为 1–120 秒（默认 120 秒）。请求会在进入渲染线程前完整验证。超时、失焦或客户端关闭都会取消序列并释放所有按住的输入。`look` 步骤的 `yaw_delta` 和 `pitch_delta` 单位为度，客户端桥接会将其转换为原版 `Entity.turn` 单位。`completed_steps` 只统计完全完成的动作/持续时间，被取消的活动长按不计入。

输入步骤：

| 类型 | 重要字段 | 含义 |
|---|---|---|
| `key` | `key`, `action`, `duration_ms` | 移动别名或单字符原版键盘事件 |
| `mouse` | `button`, `action`, `duration_ms`, `interval_ms` | 按下、释放、单击、长按或双击 |
| `look` | `yaw_delta`, `pitch_delta` | 以度为单位的相对游戏视角移动 |
| `cursor` | `action`, 坐标, `coordinate_space` | GUI 中绝对归一化/像素移动或相对像素移动 |
| `drag` | `from_*`, `to_*`, `button`, `duration_ms` | 在两点间平滑拖动 GUI |
| `wait` | `duration_ms` | 不阻塞渲染线程的等待 |

鼠标动作使用原版 `MouseHandler`，单字符按键使用原版 `KeyboardHandler`，因此游戏按键绑定和打开的界面都能收到事件。`cursor` 与 `drag` 要求已打开 GUI 且鼠标已释放。绝对 `coordinate_space` 可为 `normalized`（0 到 1，默认）或 `pixel`；`move_relative` 始终使用像素增量。双击由两组有界按下/释放组成，中间间隔 `interval_ms`（20–1,000 ms）。

截图响应包含 RGB JPEG。Minecraft 26.2 渲染目标像素会先按 ARGB 读取再编码为 JPEG。只有帧缓冲抓取在渲染线程执行；单个有界工作线程进行一次 JPEG 编码。任一时刻只能有一张截图进行中，编码后 JPEG 限制为 16 MiB。

分页聊天/调试端点默认返回 64 项，接受 `limit=1..256`，并会在单页 item JSON 约 2 MiB 前停止。响应返回 `next_*`、`latest_*`、`oldest_*`、`more`、`dropped`、`returned_count` 和 `response_bytes`。下一次请求把 `next_*` 作为 `since`，并在 `more=true` 时继续。`dropped=true` 表示保留历史已经越过所请求游标。单条聊天文本限制为 32,000 字符，并公开 `truncated`。

调试事件响应包含渲染后的纯文本、原生组件 JSON、函数/行源码映射、同步函数栈和截断/错误元数据。过大的原生组件 JSON 会以 `component_omitted=true` 省略，但仍保留有界纯文本后备内容。
