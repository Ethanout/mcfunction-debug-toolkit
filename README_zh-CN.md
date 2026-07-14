# MCFunction Debug Toolkit

[English](README.md) | **简体中文**

面向 Minecraft Java 命令、函数与数据包开发的 Fabric 调试辅助模组。即使不使用
AI，也可以直接用简短的 `#!` 语法观察命令上下文、记分板和 NBT；额外内置的 MCP
桥接能力可以让 AI 执行和验证命令、读取聊天栏与 `tellraw`、操作玩家和 GUI，并
通过 JPEG 截图检查结果。

> 当前版本是开发预览版 `0.1.0`，目标游戏版本为 Minecraft Java `26.2`。
> 请先在测试存档中使用，不要直接连接重要存档或生产服务器。

## 它能做什么

- 用原版 Brigadier 命令树验证命令，并返回准确的错误位置；
- 单条或批量执行命令，汇总 fork 后的所有结果与反馈；
- 增量读取聊天栏，包括 `tellraw` 和系统消息；
- 在 `.mcfunction` 中使用简短的 `#!` 调试模板读取上下文、记分板和 NBT；
- 控制移动键、长按、视角、GUI 光标、单击、双击和拖动；
- 窗口失焦时释放鼠标并取消正在进行的输入序列；
- 截取 JPEG 画面，同时返回文件路径与 MCP 图片内容；
- 对聊天、事件、反馈、NBT、截图和执行时间设置明确上限。

## 组件关系

```text
AI / MCP 客户端
       │ stdio
       ▼
Node.js MCP 服务
       │ 带令牌的本机 HTTP
       ├── 127.0.0.1:8766 → 服务端命令、#! 事件
       └── 127.0.0.1:8767 → 聊天、输入、截图
                         ▲
                    Fabric 模组
```

两个端口只监听本机回环地址。它们使用同一个随机令牌。

## 环境要求

| 项目 | 版本 |
|---|---|
| Minecraft Java | `26.2` |
| Fabric Loader | `0.19.3` 或兼容新版 |
| Fabric API | `0.154.2+26.2` |
| Java | `25` |
| Node.js | `18` 或更高 |

## 快速开始

### 1. 构建模组和 MCP 服务

```powershell
git clone https://github.com/Ethanout/mcfunction-debug-toolkit.git
cd mcfunction-debug-toolkit
npm install
npm run build

cd mods/fabric
.\gradlew.bat build
```

Fabric 模组生成在 `mods/fabric/build/libs/`。项目的 Gradle Wrapper 已配置国内
镜像；如果系统没有自动找到 Java 25，请先正确设置 `JAVA_HOME`。

### 2. 安装模组

把下面两个 jar 放入同一个 Fabric 26.2 实例的 `mods` 文件夹：

1. 本项目生成的 `mc-command-bridge-0.1.0.jar`；
2. 对应 Minecraft 26.2 的 Fabric API。

启动一次游戏。模组会在实例目录生成：

```text
config/mc-command-mcp.token
```

不要把这个令牌提交到 Git、截图分享或发给不可信程序。

### 3. 启动 MCP 服务

开发实例的 PowerShell 示例：

```powershell
$env:MC_COMMAND_TOKEN = Get-Content ".\mods\fabric\run\config\mc-command-mcp.token" -Raw
npm run start:mcp
```

普通启动器实例请把路径换成该实例自己的 `config/mc-command-mcp.token`。

支持 MCP JSON 配置的客户端可以使用：

```json
{
  "mcpServers": {
    "mc-command": {
      "command": "node",
      "args": ["C:\\absolute\\path\\mcfunction-debug-toolkit\\packages\\mcp-server\\dist\\index.js"],
      "env": {
        "MC_COMMAND_TOKEN": "复制 token 文件中的完整内容"
      }
    }
  }
}
```

配置文件格式因客户端而异，但入口始终是
`packages/mcp-server/dist/index.js`。启动游戏并进入存档后，让 AI 先调用
`mc_status` 和 `mc_client_status` 检查连接。

## MCP 工具

| 工具 | 用途 |
|---|---|
| `mc_status` | 查看服务端桥接、游戏版本和能力 |
| `mc_client_status` | 查看客户端连接、焦点和鼠标状态 |
| `mc_command_validate` | 只验证命令，不执行 |
| `mc_command_run` | 执行一条命令并返回结构化结果 |
| `mc_command_batch` | 顺序执行最多 100 条命令 |
| `mc_chat` | 增量读取聊天栏和 `tellraw` |
| `mc_debug_events` | 读取成功的 `#!` 输出和运行时错误 |
| `mc_debug_diagnostics` | 读取 reload 警告和详细诊断 |
| `mc_input_sequence` | 执行按键、视角、光标、点击和拖动序列 |
| `mc_screenshot` | 获取 JPEG 截图 |

建议的 AI 测试循环：

1. 调用 `mc_command_validate` 检查语法；
2. 记录聊天与调试事件游标；
3. 调用 `mc_command_run` 或 `mc_command_batch`；
4. 用 `mc_chat`、`mc_debug_events` 读取新增输出；
5. 必要时操作角色或 GUI，并调用 `mc_screenshot` 验证画面。

## 输入序列示例

移动、转向和右键：

```json
{
  "steps": [
    {"type": "key", "key": "w", "action": "hold", "duration_ms": 800},
    {"type": "look", "yaw_delta": 45, "pitch_delta": -10},
    {"type": "mouse", "button": "right", "action": "click", "duration_ms": 80}
  ]
}
```

GUI 光标、双击和拖动：

```json
{
  "steps": [
    {"type": "cursor", "action": "move", "x": 0.5, "y": 0.4, "coordinate_space": "normalized"},
    {"type": "mouse", "button": "left", "action": "double_click", "interval_ms": 100},
    {"type": "drag", "button": "left", "from_x": 0.3, "from_y": 0.6, "to_x": 0.7, "to_y": 0.6, "duration_ms": 500}
  ],
  "total_timeout_ms": 5000
}
```

`cursor` 和 `drag` 只在打开 GUI、鼠标未被游戏锁定时使用。`look` 专门用于游戏内
视角。输入序列最多 200 步；失焦、超时或客户端关闭都会释放正在按住的输入。

## `#!` 数据包调试

在 `.mcfunction` 中可以直接写：

```mcfunction
#! 当前函数={fname}，位置={position:.2f}
#! 分数：{@e num: {"{display_name}: {score:04d}"}, ...}
#! NBT：{storage demo:test values[]: {}, ...}
```

它会像调试版 `tellraw` 一样把结果发给所有玩家，同时写入 AI 可读取的结构化
事件。错误的 `#!` 会在 reload 时警告并跳过，不会破坏函数的其他命令；运行时
错误会在屏幕显示简短信息并继续执行函数。

完整语法、列表 `...`、默认 `/strip`、嵌套格式和数字格式见
[调试指令手册](docs/DEBUG_DIRECTIVES.md)。

## 安全说明

- 端口必须保持在 `127.0.0.1`，不要通过端口映射暴露到局域网或公网；
- 持有令牌的程序相当于拥有高权限 Minecraft 命令源；
- `allow_dangerous` 只是防误操作护栏，不是安全沙箱；
- 超时返回 `timeout_unknown_outcome` 时，命令可能已经执行，禁止自动重试；
- 测试 AI 自动操作时优先使用临时存档，并保留重要世界备份。

## 常见问题

**`mc_status` 连接失败**

确认已经进入单人世界或服务器已启动；端口 `8766` 只有服务端生命周期就绪后才出现。

**`mc_client_status` 连接失败**

确认安装的是客户端+服务端均可用的完整模组，并检查 `8767` 是否被其他程序占用。

**返回 `unauthorized`**

MCP 服务使用的 `MC_COMMAND_TOKEN` 与当前游戏实例的 token 文件不一致。

**GUI 光标步骤报错**

先打开背包、箱子或其他界面。游戏内自由视角应使用 `look`，不是 `cursor`。

**命令超时后不知道是否执行**

检查 `error_code`、`outcome` 和 `retry_safe`。只有 `retry_safe=true` 才能安全重试。

## 开发与验证

```powershell
# TypeScript
npm run build
npm run typecheck
npm test

# Fabric（中文路径会自动使用独立 JUnit 启动器）
cd mods/fabric
.\gradlew.bat check

# 已进入版本化测试存档时
cd ../..
npm run test:real
npm run test:screenshot-single-flight
```

版本化真实测试数据包位于 `test-data/datapacks/mc-command-syntax-test`。安装与测试
说明见 [真实语法测试](docs/REAL_WORLD_SYNTAX_TEST.md)。HTTP 细节和边界见
[协议文档](docs/PROTOCOL.md)，模块职责见[架构文档](docs/ARCHITECTURE.md)。

## 项目结构

```text
mods/fabric/          Fabric 模组与 Java 测试
packages/protocol/    TypeScript 协议类型和校验
packages/mcp-server/  MCP stdio 服务
test-data/            版本化实机测试数据包
scripts/              安装、冒烟与真实回归脚本
docs/                 语法、协议、架构与审计记录
```

本项目不导入或构建旧的 `minecraft-mod-mcp`。

## 许可证

[MIT](LICENSE)
