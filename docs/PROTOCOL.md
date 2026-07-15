# Bridge protocol v1

**English** | [简体中文](PROTOCOL_zh-CN.md)

Default address: `http://127.0.0.1:8766`

Client address: `http://127.0.0.1:8767`

## Endpoints

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/v1/status` | No | Version, connection and capabilities |
| POST | `/v1/command/validate` | Bearer | Parse against the live Brigadier tree |
| POST | `/v1/command/run` | Bearer | Execute one command |
| POST | `/v1/command/batch` | Bearer | Execute 1-100 commands in sequence |
| GET | `/v1/debug/events?since=N&limit=L` | Bearer | Paged structured `#!` output |
| GET | `/v1/debug/diagnostics?since=N&limit=L` | Bearer | Paged reload/runtime diagnostics |

Client endpoints:

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/v1/client/status` | No | Client focus, mouse and capabilities |
| GET | `/v1/client/chat?since=N&limit=L` | Bearer | Paged HUD chat with monotonic cursor |
| POST | `/v1/client/input` | Bearer | Bounded key/mouse/look/cursor/drag/wait sequence |
| POST | `/v1/client/screenshot` | Bearer | JPEG screenshot and base64 image |

Command requests accept `command` and optional `allow_dangerous`. Batch requests accept `commands`, optional `stop_on_error`, and optional `allow_dangerous`.

Responses distinguish:

- `ok`: whether syntax was valid and vanilla did not explicitly report command
  failure. It is `true` for an unreported result; inspect `result_reported` when
  the exact numeric/success result matters.
- `valid`: whether Brigadier accepted the syntax.
- `result`: Minecraft's numeric command result, present only when vanilla
  invoked the result callback; it may be zero on success.
- `result_reported`: whether vanilla invoked the command result callback. Some
  queued commands, including an ordinary function without explicit `return`,
  can complete without reporting a numeric result.
- `callback_count`, `success_count`, `failure_count`: aggregate every callback
  emitted by a forked command. `result` is the sum of all reported callback
  results; `ok=false` when any callback explicitly reports failure.
- `feedback`: translated command source messages.
- `feedback_truncated`: the 32-message/16,384-character feedback budget was hit.
- `cursor`: Brigadier parse error position.
- `duration_ms`: execution time on the server thread.

## Safety rules

Without `allow_dangerous=true`, the bridge rejects simple, explicit command
classes: server stop, ban/op/whitelist changes, mass `kill @a`/`kill @e`, and
`fill`/`clone`. The rule list is intentionally deterministic and lives in
`DangerPolicy`.

**This is an accidental-misuse guard, not a security sandbox.** Nested
`execute run`, datapack functions and other indirect commands can bypass the
string classifier. Bearer-token possession still grants a fully privileged
Minecraft command source. Do not expose either bridge port beyond loopback.

A server-thread timeout returns either `timeout_not_executed` with
`retry_safe=true`, or `timeout_unknown_outcome` with `retry_safe=false` and a
`request_id`. In the latter case the command had already started and may still
finish; never retry it automatically. A timed-out batch stops between commands
as soon as cancellation is observed, but its currently running command cannot
be safely interrupted.

Client input is also bounded: at most 200 steps per request, each duration is
limited to 30 seconds, and `total_timeout_ms` is limited to 1-120 seconds
(default 120 seconds). The whole request is validated before it reaches the
render thread. Timeout, focus loss and client shutdown cancel the sequence and
release every held key/button. Look-step `yaw_delta` and `pitch_delta` are
degrees; the client bridge converts them to vanilla `Entity.turn` units.
`completed_steps` counts only fully completed actions/durations; a cancelled
active hold is not counted.

Input step shapes:

| Type | Important fields | Meaning |
|---|---|---|
| `key` | `key`, `action`, `duration_ms` | Movement aliases or one-character vanilla keyboard event |
| `mouse` | `button`, `action`, `duration_ms`, `interval_ms` | Press, release, click, hold or double-click |
| `look` | `yaw_delta`, `pitch_delta` | Relative in-game camera movement in degrees |
| `cursor` | `action`, coordinates, `coordinate_space` | Absolute normalized/pixel or relative pixel GUI movement |
| `drag` | `from_*`, `to_*`, `button`, `duration_ms` | Smooth GUI drag between two points |
| `wait` | `duration_ms` | Delay without blocking the render thread |

Mouse actions use vanilla `MouseHandler`, and single-character keys use vanilla
`KeyboardHandler`; they therefore reach both gameplay bindings and open screens.
`cursor` and `drag` require an open GUI and a released mouse. Absolute
`coordinate_space` is `normalized` (0 through 1, default) or `pixel`.
`move_relative` always uses pixel deltas. A double-click consists of two bounded
press/release pairs separated by `interval_ms` (20 through 1,000 ms).

Screenshot responses contain an RGB JPEG. Minecraft 26.2 render-target pixels
are read as ARGB before JPEG encoding. Only the framebuffer capture runs on the
render thread; one bounded worker performs a single JPEG encode. Only one
screenshot may be in flight, and encoded JPEG bytes are limited to 16 MiB.

Paged chat/debug endpoints default to 64 items, accept `limit=1..256`, and also
stop before approximately 2 MiB of item JSON. Responses return `next_*`,
`latest_*`, `oldest_*`, `more`, `dropped`, `returned_count`, and
`response_bytes`. Pass `next_*` as the next `since`; continue while `more=true`.
`dropped=true` means retained history advanced past the requested cursor.
Individual chat text is limited to 32,000 characters and exposes `truncated`.

Debug event responses contain the rendered plain text, native component JSON,
function/line source mapping, synchronous function stack and truncation/error
metadata. Oversized native component JSON is omitted with
`component_omitted=true`; the bounded plain-text fallback remains available.
