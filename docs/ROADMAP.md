# Roadmap

## Completed in 0.2

- Incremental HUD chat capture, including tellraw text.
- Client key/mouse/look/wait input sequences with automatic release.
- Mouse release on window focus loss.
- JPEG viewport screenshots returned through MCP and saved to disk.

## Completed in 0.3

- Human-first `#!` debug directives for context, score and NBT queries.
- Multiline templates, nested source/value lists, repeat/strip behavior and
  bounded numeric formatting.
- Reload-time native syntax validation with physical source locations.
- Native chat components, runtime short errors, bounded structured events and
  reload diagnostics exposed through MCP.
- Shared leaf/character truncation budgets with visible markers.
- Current/synchronous function stack tracking, including scheduled roots.
- Clean client HTTP lifecycle shutdown.
- Monotonic bounded chat/debug cursors with dropped-history detection.
- Whole-request input validation, total timeout and focus-loss cancellation.
- Degree-correct look control and ARGB-correct JPEG screenshots.
- Shared server/client bridge token and hardened HTTP request boundaries.

## Next

- Brigadier completion suggestions.
- Execution context: dimension, position, rotation, `as` and `at`.
- Direct structured state-query tools for entity, block, scoreboard and storage
  in addition to the existing `#!` query path.
- Datapack list and an explicit function-execution convenience tool.
- Arrange/act/assert/cleanup test runner.
- Event stream for reload errors, command feedback and server lifecycle.

## Later

- Configurable deterministic safety policy.
- Optional WebSocket event transport.
- Multi-version capability negotiation.
