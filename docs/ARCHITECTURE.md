# Architecture

```text
AI client
  -> MCP stdio
packages/mcp-server
  -> authenticated loopback HTTP/JSON
mods/fabric server bridge (8766)  -> Minecraft server thread -> Brigadier
mods/fabric client bridge (8767)  -> Minecraft client thread -> HUD/input/render
```

## Boundaries

- `protocol` owns wire names and TypeScript schemas.
- `mcp-server` translates MCP tools to bridge requests. It does not know Minecraft internals.
- `mods/fabric` owns Minecraft lifecycle, Brigadier parsing and command execution.
- HTTP is bound to `127.0.0.1`; command endpoints require a bearer token.
- Every server operation is scheduled on the server thread with a deadline and
  cancellation gate. A timeout after work starts is reported as unknown outcome.
- Client input sequences are tick-driven, so a long hold does not block rendering.
- Input is fully validated before render-thread scheduling and is cancelled with
  all held keys/buttons released on timeout, focus loss or client shutdown.
- Keyboard and mouse button events enter vanilla `KeyboardHandler` and
  `MouseHandler` through narrow mixin invokers. GUI cursor moves update both the
  GLFW cursor and vanilla's tracked cursor position; drags interpolate per tick.
- Look deltas are protocol-level degrees and are converted to vanilla
  `Entity.turn` units at the client boundary.
- HUD chat is captured when it is inserted into a monotonic bounded buffer; it
  is not derived from vanilla's newest-first display history.
- Debug/chat/diagnostic reads are count- and byte-bounded pages. Command feedback
  is separately bounded.
- Screenshot capture transfers the completed native image to a single-flight,
  single-thread JPEG encoder; pixel conversion and compression do not run on the
  render thread.

The server bridge starts after `SERVER_STARTED` and shuts down during `SERVER_STOPPING`. The client bridge starts with the Fabric client, owns port 8767, and stops during `CLIENT_STOPPING` so its HTTP dispatcher cannot hold the JVM open during normal game shutdown. Both use loopback-only sockets and the same generated token.

Synchronous debug function stacks are keyed by vanilla execution-frame depth.
Entering a function replaces that depth and clears deeper stale entries; a
directive reads the prefix through its current frame. There is no immediate
`finally` pop because vanilla queues function commands after the call instruction
returns.

## Success semantics

Minecraft command success and numeric result are different values. A successful
command may return `0`, and some valid commands invoke no result callback at all.
The bridge therefore aggregates every `CommandResultCallback` invocation,
exposes callback/success/failure counts and a result sum, and never lets the last
fork callback overwrite earlier results.
It validates the Brigadier parse first, then queues that validated context through
Minecraft's public `executeCommandInContext` path. This preserves vanilla queue
semantics while allowing an unexpected handler exception to reach the bridge
instead of being flattened into an unreported result.

Minecraft 26.2 runs the top-level execution queue synchronously. An ordinary
`function <id>` may finish without invoking the root result callback, while an
explicit function `return` reports its value. The HTTP response therefore uses
`result_reported` and omits `result` when vanilla did not provide one; no
asynchronous bridge redesign is needed.
