# MCFunction Debug Toolkit

**English** | [简体中文](README_zh-CN.md)

A Fabric debugging mod for Minecraft Java commands, functions, and datapacks.
Without any AI integration, its concise `#!` directives can inspect command
context, scoreboards, and NBT directly from `.mcfunction` files. Its optional
MCP bridge additionally lets AI clients validate and run commands, read chat and
`tellraw`, control the player and GUI, and inspect JPEG screenshots.

> Version `0.1.0` is a development preview targeting Minecraft Java `26.2`.
> Use a test world first; do not begin with an important world or production server.

## Features

- Validate commands against the live vanilla Brigadier tree with exact error positions.
- Run one command or a bounded batch and aggregate every forked callback.
- Incrementally read HUD chat, including `tellraw` and system messages.
- Use short `#!` templates in `.mcfunction` files to inspect context, scores, and NBT.
- Automate movement keys, holds, camera look, GUI cursor, clicks, double-clicks, and drags.
- Release the mouse and cancel active input when the game loses focus.
- Capture JPEG screenshots as both saved files and MCP image content.
- Apply explicit limits to chat, events, feedback, NBT, screenshots, and execution time.

## How it fits together

```text
AI / MCP client
       │ stdio
       ▼
Node.js MCP server
       │ authenticated loopback HTTP
       ├── 127.0.0.1:8766 → server commands and #! events
       └── 127.0.0.1:8767 → chat, input, and screenshots
                         ▲
                     Fabric mod
```

Both ports bind only to loopback and use the same random bearer token.

## Requirements

| Component | Version |
|---|---|
| Minecraft Java | `26.2` |
| Fabric Loader | `0.19.3` or a compatible newer release |
| Fabric API | `0.154.2+26.2` |
| Java | `25` |
| Node.js | `18` or newer |

## Quick start

### 1. Build the mod and MCP server

```powershell
git clone https://github.com/Ethanout/mcfunction-debug-toolkit.git
cd mcfunction-debug-toolkit
npm install
npm run build

cd mods/fabric
.\gradlew.bat build
```

The Fabric jar is written to `mods/fabric/build/libs/`. The Gradle wrapper is
configured with a mirror that is convenient in mainland China. If Gradle cannot
find Java 25, set `JAVA_HOME` first.

### 2. Install the mod

Place both jars in the `mods` directory of the same Fabric 26.2 instance:

1. the generated `mc-command-bridge-0.1.0.jar`;
2. Fabric API for Minecraft 26.2.

Launch the game once. The mod creates:

```text
config/mc-command-mcp.token
```

Do not commit this token, include it in screenshots, or share it with untrusted software.

### 3. Start the MCP server

For the bundled development instance on PowerShell:

```powershell
$env:MC_COMMAND_TOKEN = Get-Content ".\mods\fabric\run\config\mc-command-mcp.token" -Raw
npm run start:mcp
```

For a launcher-managed instance, use that instance's own token file instead.

An MCP client that accepts JSON configuration can use:

```json
{
  "mcpServers": {
    "mc-command": {
      "command": "node",
      "args": ["C:\\absolute\\path\\mcfunction-debug-toolkit\\packages\\mcp-server\\dist\\index.js"],
      "env": {
        "MC_COMMAND_TOKEN": "paste the complete token file contents here"
      }
    }
  }
}
```

Configuration syntax differs between MCP clients, but the entry point is always
`packages/mcp-server/dist/index.js`. After joining a world, ask the AI to call
`mc_status` and `mc_client_status` first.

## MCP tools

| Tool | Purpose |
|---|---|
| `mc_status` | Inspect the server bridge, game version, and capabilities |
| `mc_client_status` | Inspect client connection, focus, and mouse state |
| `mc_command_validate` | Validate without executing |
| `mc_command_run` | Execute one command and return structured results |
| `mc_command_batch` | Execute up to 100 commands sequentially |
| `mc_chat` | Incrementally read HUD chat and `tellraw` |
| `mc_debug_events` | Read successful `#!` output and runtime errors |
| `mc_debug_diagnostics` | Read reload warnings and detailed diagnostics |
| `mc_input_sequence` | Run key, camera, cursor, click, and drag sequences |
| `mc_screenshot` | Capture a JPEG screenshot |

A useful AI testing loop is:

1. validate syntax with `mc_command_validate`;
2. remember the current chat and debug cursors;
3. call `mc_command_run` or `mc_command_batch`;
4. poll `mc_chat` and `mc_debug_events` for new output;
5. interact with the player or GUI and use `mc_screenshot` when visual proof matters.

## Input examples

Move, turn, and right-click:

```json
{
  "steps": [
    {"type": "key", "key": "w", "action": "hold", "duration_ms": 800},
    {"type": "look", "yaw_delta": 45, "pitch_delta": -10},
    {"type": "mouse", "button": "right", "action": "click", "duration_ms": 80}
  ]
}
```

Move the GUI cursor, double-click, and drag:

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

Use `cursor` and `drag` only while a GUI is open and the mouse is released.
Use `look` for the in-game camera. A request may contain at most 200 steps;
focus loss, timeout, and client shutdown release every held input.

## `#!` datapack debugging

Write debug output directly in a `.mcfunction` file:

```mcfunction
#! function={fname}, position={position:.2f}
#! scores: {@e num: {"{display_name}: {score:04d}"}, ...}
#! NBT: {storage demo:test values[]: {}, ...}
```

The result is sent to all players like a compact debugging-oriented `tellraw`
and is also mirrored to structured events for AI clients. Invalid directives
produce reload warnings and are skipped without breaking the rest of the
function. Runtime errors show a short on-screen message and function execution continues.

The full query language, repeating `...`, default `/strip`, nested formats, and
numeric formatting are documented in the
[`#!` directive guide](docs/DEBUG_DIRECTIVES.md) (currently Chinese).

## Security notes

- Keep both ports on `127.0.0.1`; never expose them through port forwarding.
- Software holding the bearer token effectively has a privileged Minecraft command source.
- `allow_dangerous` is an accidental-misuse guard, not a security sandbox.
- If a timeout reports `timeout_unknown_outcome`, the command may already have run; do not retry automatically.
- Prefer disposable worlds and keep backups when testing AI automation.

## Troubleshooting

**`mc_status` cannot connect**

Join a single-player world or start the server. Port `8766` exists only while
the server lifecycle is ready.

**`mc_client_status` cannot connect**

Verify that the complete mod is installed on the client and that port `8767`
is not already in use.

**The bridge returns `unauthorized`**

The MCP process and the current game instance are using different token files.

**A GUI cursor step fails**

Open an inventory, chest, or another screen first. Use `look`, not `cursor`, for
free camera movement in gameplay.

**A command timed out and its outcome is unclear**

Inspect `error_code`, `outcome`, and `retry_safe`. Retry only when
`retry_safe=true`.

## Development and verification

```powershell
# TypeScript
npm run build
npm run typecheck
npm test

# Fabric (the isolated launcher works around Windows non-ASCII path issues)
cd mods/fabric
.\gradlew.bat check

# While the versioned test world is open
cd ../..
npm run test:real
npm run test:screenshot-single-flight
```

The versioned test datapack is under
`test-data/datapacks/mc-command-syntax-test`. See
[real-world syntax testing](docs/REAL_WORLD_SYNTAX_TEST.md),
[the wire protocol](docs/PROTOCOL.md), and
[the architecture](docs/ARCHITECTURE.md) for implementation details.

## Repository layout

```text
mods/fabric/          Fabric mod and Java tests
packages/protocol/    Shared TypeScript protocol schemas
packages/mcp-server/  MCP stdio server
test-data/            Versioned real-world test datapack
scripts/              Install, smoke, and integration scripts
docs/                 Syntax, protocol, architecture, and audit records
```

This project does not import or build the old `minecraft-mod-mcp` project.

## License

[MIT](LICENSE)
