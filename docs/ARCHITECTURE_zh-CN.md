# 架构

[English](ARCHITECTURE.md) | **简体中文**

```text
AI 客户端
  -> MCP stdio
packages/mcp-server
  -> 经过身份验证的本机回环 HTTP/JSON
mods/fabric 服务端桥接 (8766) -> Minecraft 服务端线程 -> Brigadier
mods/fabric 客户端桥接 (8767) -> Minecraft 客户端线程 -> HUD/输入/渲染
```

## 边界

- `protocol` 负责线上字段名和 TypeScript schema。
- `mcp-server` 把 MCP 工具转换为桥接请求，不了解 Minecraft 内部实现。
- `mods/fabric` 负责 Minecraft 生命周期、Brigadier 解析与命令执行。
- HTTP 只绑定 `127.0.0.1`；命令端点需要 bearer token。
- 每个服务端操作都带截止时间和取消门控，并调度到服务端线程。工作开始后的超时会报告为结果未知。
- 客户端输入序列按 tick 驱动，长按不会阻塞渲染。
- 输入在调度到渲染线程前会完整验证；超时、失焦或客户端关闭会取消输入并释放所有按键和鼠标按钮。
- 键盘和鼠标按钮事件通过窄 mixin invoker 进入原版 `KeyboardHandler` 与 `MouseHandler`。GUI 光标移动同时更新 GLFW 光标和原版记录的位置；拖动按 tick 插值。
- 协议中的视角增量使用角度，在客户端边界转换为原版 `Entity.turn` 单位。
- HUD 聊天在写入单调有界缓冲时捕获，而不是从原版“最新优先”的显示历史推导。
- 调试、聊天和诊断读取同时受条目数及字节数限制；命令反馈另有独立限制。
- 截图完成原生图像抓取后交给单并发、单线程 JPEG 编码器；像素转换和压缩不在渲染线程运行。

服务端桥接在 `SERVER_STARTED` 后启动，在 `SERVER_STOPPING` 时关闭。客户端桥接随 Fabric 客户端启动并占用 8767 端口，在 `CLIENT_STOPPING` 时停止，避免 HTTP 调度器妨碍 JVM 正常退出。二者只监听回环地址并使用同一个随机令牌。

同步调试函数栈以原版执行帧深度为键。进入函数时替换当前深度并清理更深的过期项；指令读取到当前帧为止的前缀。这里不能立即在 `finally` 中弹栈，因为原版会在函数调用指令返回后才把函数命令放入队列。

## 成功语义

Minecraft 命令成功与数值结果是两个不同概念。成功命令可能返回 `0`，部分有效命令也完全不调用结果回调。桥接会聚合全部 `CommandResultCallback`，公开回调数、成功数、失败数和结果总和，绝不让分叉命令的最后一次回调覆盖之前的结果。

桥接先验证 Brigadier 解析，再通过 Minecraft 公共的 `executeCommandInContext` 路径将已验证上下文入队。这样既保留原版队列语义，也能让意外的处理器异常传到桥接层，而不是被压成未报告结果。

Minecraft 26.2 会同步运行顶层执行队列。普通 `function <id>` 可能结束但不触发根结果回调，显式函数 `return` 则会报告返回值。因此 HTTP 响应使用 `result_reported`，原版没有提供结果时省略 `result`，无需把桥接重构成异步模型。
