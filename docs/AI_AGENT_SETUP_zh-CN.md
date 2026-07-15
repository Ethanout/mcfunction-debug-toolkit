# AI Agent 安装与连接

[English](AI_AGENT_SETUP.md) | **简体中文**

本文供 AI Agent 或配置可选 MCP 集成的操作者使用。普通的 `#!` 数据包调试不需要 AI 客户端。

## 目标版本

| 组件 | 版本 |
|---|---|
| Minecraft Java | `26.2` |
| Fabric Loader | `0.19.3` 或兼容的新版本 |
| Fabric API | `0.154.2+26.2` |
| Java | `25` |
| Node.js | `18` 或更高 |

## 构建仓库

在仓库根目录运行：

```powershell
npm install
npm run build

cd mods/fabric
.\gradlew.bat build
```

模组 jar 输出到 `mods/fabric/build/libs/`，MCP stdio 入口为 `packages/mcp-server/dist/index.js`。

安装前验证 checkout：

```powershell
npm run typecheck
npm test

cd mods/fabric
.\gradlew.bat check
```

预期检查包括含 10 个工具的 MCP 握手，以及 39 个 Fabric/JUnit 测试。

## 安装 Fabric 模组

把下列 jar 放入同一个 Fabric 26.2 实例的 `mods` 目录：

1. `mods/fabric/build/libs/` 中的 `mc-command-bridge-*.jar`；
2. 适用于 Minecraft 26.2 的 Fabric API。

启动一次实例。模组会在下列位置生成随机令牌：

```text
<instance>/config/mc-command-mcp.token
```

集成服务器或专用服务器运行时，服务端桥接监听 `127.0.0.1:8766`；Fabric 客户端运行时，客户端桥接监听 `127.0.0.1:8767`。二者使用同一个令牌。

切勿提交、记录或暴露令牌，也不要把任一桥接端口绑定或转发到回环地址以外。

## 配置 MCP Host

先构建 TypeScript 包，再配置 MCP Host 启动编译后的 stdio 服务器：

```json
{
  "mcpServers": {
    "mcfunction-debug": {
      "command": "node",
      "args": [
        "C:\\absolute\\path\\mcfunction-debug-toolkit\\packages\\mcp-server\\dist\\index.js"
      ],
      "env": {
        "MC_COMMAND_TOKEN": "mc-command-mcp.token 的完整内容",
        "MC_COMMAND_BASE_URL": "http://127.0.0.1:8766",
        "MC_COMMAND_CLIENT_BASE_URL": "http://127.0.0.1:8767"
      }
    }
  }
}
```

最外层格式取决于 MCP Host；命令、绝对入口路径和环境变量保持不变。

如果 Host 会自行启动已配置的 MCP 进程，不要同时运行 `npm run start:mcp`，否则会产生一个没有客户端连接的第二个 stdio 服务器。仅在手动测试协议时使用：

```powershell
$env:MC_COMMAND_TOKEN = Get-Content ".\mods\fabric\run\config\mc-command-mcp.token" -Raw
npm run start:mcp
```

启动器管理的实例需要替换令牌路径。

## 验证连接

Minecraft 加入世界后，Agent 应按顺序检查：

1. 调用 `mc_status`，要求 `connected=true` 并确认游戏版本。
2. 调用 `mc_client_status`；读取聊天、输入或截图前要求 `connected=true`。
3. 使用 `say bridge-ready` 等无害命令调用 `mc_command_validate`。
4. 记录 `mc_chat`、`mc_debug_events` 和 `mc_debug_diagnostics` 的游标。
5. 执行无害命令，并从记录的游标开始轮询。

端口行为：

- `8767` 属于客户端，因此主菜单中也可用。
- `8766` 只在集成服务器或专用服务器启动后出现。
- 令牌不匹配会返回 HTTP `401` / `unauthorized`。

## 工具职责

| 工具 | Agent 的职责 |
|---|---|
| `mc_status` | 执行命令前检查服务端生命周期 |
| `mc_client_status` | 客户端操作前检查焦点和鼠标状态 |
| `mc_command_validate` | 执行前验证生成的命令 |
| `mc_command_run` | 执行单条命令并检查回调元数据 |
| `mc_command_batch` | 执行有界的有序命令批次 |
| `mc_chat` | 使用 `next_index` 轮询 HUD 聊天 |
| `mc_debug_events` | 使用 `next_id` 轮询渲染后的 `#!` 输出 |
| `mc_debug_diagnostics` | 使用 `next_id` 轮询 reload/运行时诊断 |
| `mc_input_sequence` | 运行有界、按 tick 驱动的交互序列 |
| `mc_screenshot` | 仅在视觉证据有用时抓取 JPEG |

## Agent 操作规则

### 游标与分页

聊天和调试读取均分页。下一次请求把返回的 `next_index` 或 `next_id` 用作 `since`，并在 `more=true` 时继续。若 `dropped=true`，说明保留历史已越过请求游标，应从当前页面重新同步。

### 命令结果

`ok`、`result` 与 `result_reported` 含义不同。有效命令可能以结果 `0` 成功，也可能不提供数值回调。对分叉命令检查 `callback_count`、`success_count` 和 `failure_count`；`result` 是聚合总和。

### 超时

- `timeout_not_executed` 且 `retry_safe=true`：服务端任务未开始。
- `timeout_unknown_outcome` 且 `retry_safe=false`：命令已开始且可能仍会完成，绝不能自动重试。

### 输入

游戏内视角使用 `look`。仅在 GUI 已打开且鼠标已释放时使用 `cursor` 和 `drag`。一个请求最多 200 步、120 秒。失焦、超时和关闭会取消序列并释放按住的输入。

### 安全

Bearer token 代表拥有高权限 Minecraft 命令源。`allow_dangerous` 只是确定性的防误操作护栏，可被间接命令或数据包函数绕过，并非安全边界。

## 集成测试

受版本控制的测试数据包位于 `test-data/datapacks/mc-command-syntax-test`。

安装到开发存档：

```powershell
npm run test:install-datapack
```

打开该存档后运行：

```powershell
npm run test:real
npm run test:screenshot-single-flight
```

第一条命令验证 `#!` 语法、诊断、嵌套函数栈、分页和错误后的继续执行；第二条命令要求三个并发请求产生一次 JPEG 成功和两次 `429 screenshot_busy`。

## 故障排查

### MCP 进程立即退出

服务器使用 stdio，并等待 MCP Host。确认入口使用绝对路径，且 `npm run build` 已生成 `dist` 文件。

### `mc_status` 无法连接

加入世界或启动专用服务器，并确认端口 `8766` 未被占用。

### `mc_client_status` 无法连接

确认 Fabric 客户端正在运行，且端口 `8767` 未被占用。

### `unauthorized`

从当前正在运行的 Minecraft 实例重新读取令牌。MCP 服务器会自动去掉首尾空白。

### GUI 光标操作失败

先打开物品栏、箱子或其他界面。游戏视角移动请使用 `look`。

### 截图返回 `screenshot_busy`

同一时刻只能编码一张截图。等待其完成后重试一次。
