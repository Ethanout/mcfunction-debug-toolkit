# MCFunction Debug Toolkit

**English** | [简体中文](README_zh-CN.md)

A Fabric debugging mod for Minecraft Java commands, functions, and datapacks.
Add `#!` lines to `.mcfunction` files to inspect the real execution context,
scoreboards, and NBT. The optional MCP bridge also lets AI agents run commands,
read output, operate the game, and inspect screenshots.

> Development preview for Minecraft Java `26.2`.

Replace a verbose state dump with:

```mcfunction
#! player: \{
#!   name: {@s},
#!   health: {entity @s Health: .1f},
#!   position: [{position:.2f}],
#!   inventory: [{storage demo:showcase inventory[]: {}, ...}],
#!   stats: \{kills: {@s kills}, deaths: {@s deaths}\}
#! \}
```

It immediately becomes this in chat:

![Colored debug output in Minecraft chat](docs/assets/debug-output.png)

## Example

```mcfunction
# Current execution context
#! [{fname}] {@s} is at {position:.2f}, facing {rotation:.1f}
#! selected targets={@e[tag=target]}
#! dimension={dimension} anchor={anchor} stack={fstack}

# Single scoreboard values
#! own score={@s points}
#! global fake player={#total stats}

# Dynamically list every selected score
#! all scores={@e[tag=test] points: {"{display_name} [{holder}]={score:04d}"}, ...}

# Read a unit vector from Storage
#! direction={storage demo:debug direction[]: {value:.4f}, ...}

# Read entity UUID and health
#! own UUID={entity @s UUID}
#! own health={entity @s Health}
#! nearest target={entity @e[tag=target,sort=nearest,limit=1] UUID}
#! nearest target health={entity @e[tag=target,sort=nearest,limit=1] Health}

# Block-entity NBT
#! chest items={block ~ ~ ~ Items[]: {}, ...}

# Group values in pairs; /no_strip keeps unused template text
#! groups={storage demo:debug values[]: {}, {}, ... /no_strip}

# Consecutive #! lines join while braces remain open
# The inner ... repeats per coordinate and the outer ... per entity
#! player positions={
#! entity @a Pos[]: {{entity}: {}, ...}\n ...
#! }

# Literal braces and newline
#! raw=\{{storage demo:debug values[]: {}, ...}\}\nanchor={anchor}
```

When the function runs, the result is sent to every player as a native
Minecraft text component. Invalid `#!` lines produce reload warnings and are
skipped without breaking other commands. Runtime failures show a short message
and retain the complete diagnostic.

Without MCP, this is a human-facing debugging mod. With MCP, an AI can also
validate and execute commands, incrementally read chat and debug events,
control movement and GUIs, and capture JPEG screenshots.

```text
Run demo:setup and read the new #! output. If it has no errors, walk forward
for half a second, right-click, then take a screenshot and explain what changed.
```

## Documentation

- [Changelog](CHANGELOG.md)
- [Install the mod and connect an AI agent](docs/AI_AGENT_SETUP.md)
- [Complete `#!` syntax and formatting guide](docs/DEBUG_DIRECTIVES.md)
- [MCP and HTTP protocol](docs/PROTOCOL.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Real-world datapack tests](docs/REAL_WORLD_SYNTAX_TEST.md)

## Safety

- Use disposable worlds for automation and keep backups.
- Keep bridge ports on `127.0.0.1`.
- Treat the bearer token like privileged command access.
- `allow_dangerous` is a mistake-prevention guard, not a security sandbox.
- Never automatically retry `timeout_unknown_outcome`; the command may have run.

## License

[MIT](LICENSE)
