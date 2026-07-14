# AI Agent Setup

This is the installation and connection reference for an AI agent or an
operator configuring the optional MCP integration. Ordinary `#!` datapack
debugging does not require an AI client.

## Target versions

| Component | Version |
|---|---|
| Minecraft Java | `26.2` |
| Fabric Loader | `0.19.3` or a compatible newer release |
| Fabric API | `0.154.2+26.2` |
| Java | `25` |
| Node.js | `18` or newer |

## Build the repository

From the repository root:

```powershell
npm install
npm run build

cd mods/fabric
.\gradlew.bat build
```

The mod jar is written to `mods/fabric/build/libs/`. The MCP stdio entry point
is `packages/mcp-server/dist/index.js`.

Verify a checkout before installation:

```powershell
npm run typecheck
npm test

cd mods/fabric
.\gradlew.bat check
```

The expected checks include an MCP handshake with ten tools and 39 Fabric/JUnit tests.

## Install the Fabric mod

Put these jars in the same Fabric 26.2 instance's `mods` directory:

1. `mc-command-bridge-*.jar` from `mods/fabric/build/libs/`;
2. Fabric API for Minecraft 26.2.

Launch the instance once. The mod creates a random token at:

```text
<instance>/config/mc-command-mcp.token
```

The server bridge listens on `127.0.0.1:8766` while an integrated or dedicated
server is running. The client bridge listens on `127.0.0.1:8767` while the
Fabric client is running. Both use the same token.

Never commit, log, or expose the token. Do not bind or forward either bridge
port beyond loopback.

## Configure an MCP host

Build the TypeScript packages first. Configure the MCP host to launch the
compiled stdio server:

```json
{
  "mcpServers": {
    "mcfunction-debug": {
      "command": "node",
      "args": [
        "C:\\absolute\\path\\mcfunction-debug-toolkit\\packages\\mcp-server\\dist\\index.js"
      ],
      "env": {
        "MC_COMMAND_TOKEN": "complete contents of mc-command-mcp.token",
        "MC_COMMAND_BASE_URL": "http://127.0.0.1:8766",
        "MC_COMMAND_CLIENT_BASE_URL": "http://127.0.0.1:8767"
      }
    }
  }
}
```

The exact outer format depends on the MCP host. The command, absolute entry
point, and environment variables remain the same.

If the host launches configured MCP processes itself, do not also run
`npm run start:mcp`; that would create a second stdio server with no client.
For manual protocol testing only:

```powershell
$env:MC_COMMAND_TOKEN = Get-Content ".\mods\fabric\run\config\mc-command-mcp.token" -Raw
npm run start:mcp
```

Replace the token path for a launcher-managed instance.

## Verify the connection

After Minecraft joins a world, the agent should check in this order:

1. Call `mc_status`; require `connected=true` and confirm the game version.
2. Call `mc_client_status`; require `connected=true` before chat, input, or screenshots.
3. Call `mc_command_validate` with a harmless command such as `say bridge-ready`.
4. Record cursors from `mc_chat`, `mc_debug_events`, and `mc_debug_diagnostics`.
5. Run a harmless command and poll from the recorded cursors.

Port behavior:

- `8767` is available at the main menu because it belongs to the client.
- `8766` appears only after an integrated or dedicated server has started.
- A token mismatch returns HTTP `401` / `unauthorized`.

## Tool responsibilities

| Tool | Agent responsibility |
|---|---|
| `mc_status` | Check server lifecycle before commands |
| `mc_client_status` | Check focus and mouse state before client actions |
| `mc_command_validate` | Validate generated commands before execution |
| `mc_command_run` | Run one command and inspect callback metadata |
| `mc_command_batch` | Run a bounded ordered batch |
| `mc_chat` | Poll HUD chat with `next_index` |
| `mc_debug_events` | Poll rendered `#!` output with `next_id` |
| `mc_debug_diagnostics` | Poll reload/runtime diagnostics with `next_id` |
| `mc_input_sequence` | Run a bounded tick-driven interaction sequence |
| `mc_screenshot` | Capture JPEG only when visual evidence is useful |

## Agent operating rules

### Cursors and pagination

Chat and debug reads are paged. Pass the returned `next_index` or `next_id` as
the next `since` value. Continue while `more=true`. If `dropped=true`, retained
history has advanced past the requested cursor; resynchronize from the current page.

### Command results

`ok`, `result`, and `result_reported` are different. A valid command may succeed
with result `0`, and some commands provide no numeric callback. For forked
commands, inspect `callback_count`, `success_count`, and `failure_count`;
`result` is the aggregate sum.

### Timeouts

- `timeout_not_executed` with `retry_safe=true`: the server task did not start.
- `timeout_unknown_outcome` with `retry_safe=false`: the command started and may
  still finish. Never retry automatically.

### Input

Use `look` for the in-game camera. Use `cursor` and `drag` only with an open GUI
and released mouse. A request is limited to 200 steps and 120 seconds. Focus
loss, timeout, and shutdown cancel the sequence and release held inputs.

### Security

The bearer token grants a privileged Minecraft command source. `allow_dangerous`
is only a deterministic mistake-prevention guard and can be bypassed by indirect
commands or datapack functions. It is not a security boundary.

## Integration tests

The versioned test datapack is under
`test-data/datapacks/mc-command-syntax-test`.

Install it into the development world:

```powershell
npm run test:install-datapack
```

With that world open, run:

```powershell
npm run test:real
npm run test:screenshot-single-flight
```

The first command validates `#!` syntax, diagnostics, nested function stacks,
pagination, and continuation after errors. The second requires one JPEG success
plus two `429 screenshot_busy` responses from three concurrent requests.

## Troubleshooting

### MCP process exits immediately

The server uses stdio and expects an MCP host. Confirm the entry-point path is
absolute and `npm run build` produced the `dist` files.

### `mc_status` cannot connect

Join a world or start the dedicated server. Confirm port `8766` is not occupied.

### `mc_client_status` cannot connect

Confirm the Fabric client is running and port `8767` is not occupied.

### `unauthorized`

Reload the token from the exact Minecraft instance currently running. The MCP
server trims surrounding whitespace automatically.

### GUI cursor action fails

Open an inventory, chest, or another screen first. Use `look` for gameplay camera movement.

### Screenshot returns `screenshot_busy`

Only one screenshot may be encoded at a time. Wait for it to finish, then retry once.
