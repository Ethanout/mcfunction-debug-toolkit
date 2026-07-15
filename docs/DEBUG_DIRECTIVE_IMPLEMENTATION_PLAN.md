# `#!` debug directive implementation plan

**English** | [简体中文](DEBUG_DIRECTIVE_IMPLEMENTATION_PLAN_zh-CN.md)

This document is the implementation checklist and decision log for the human-first
Minecraft function debugging syntax. Read it before changing the parser or runtime.

## Implementation status (2026-07-14)

- Stages B-E are implemented: parser/AST, reload preprocessing, atomic registry
  generations, native component rendering, context/score/NBT queries, nested
  list and numeric formatting, runtime events and diagnostics, HTTP endpoints
  and MCP tools.
- Parser/registry tests pass. Standard JUnit is used on ASCII paths; on Chinese
  Windows paths the build automatically runs the same assertions through an
  isolated in-process launcher because Gradle's test-worker argument file
  corrupts the project path.
- Stage F was exercised against a real Minecraft 26.2 dedicated dev server and
  dev client. Verified output includes fake-player scores, entity/storage/block
  NBT, strip/no-strip, literal braces, nested lists, localized dimension
  components, numeric precision, current/nested function stacks, reload warning
  recovery, structured polling, JPEG capture and short in-game runtime errors.

The second correctness audit and follow-up bridge-boundary audit are closed
except for the deliberately retained reload-failure observation noted below.
Do not mark a future change complete merely because the happy-path demo or a
clean build passes.

## Active correctness checklist

Work through this list in order. Check an item only after an automated test or a
real-game observation proves the stated behavior.

- [x] Compile the parser and reload validator changes; repair all
  parent/child source-offset propagation.
- [x] Map a parse error in a continued `#!` block back to the exact physical
  `.mcfunction` line and column. A normal line after the block must never be
  consumed.
- [x] Ignore braces inside quoted composite formats while balancing outer
  directives; reject widths or precisions above the 32,000-character limit.
- [x] Validate selectors, score holders, resource locations, block positions,
  NBT paths, format fields and nested-list applicability during reload. Invalid
  directives warn and are omitted instead of failing on first execution.
- [x] Match vanilla dynamic NBT-component empty-result behavior: a missing path,
  an unloaded/non-block-entity block, or an empty entity selection renders no
  dynamic content and is not a runtime error.
- [x] Use one 256-leaf render budget for the whole directive. Exhaustion must
  truncate, append a visible `…[truncated]` marker and set the structured
  event's `truncated=true`; it must not abort the directive.
- [x] Enforce the 32,000-character limit without flattening all native component
  style/hover information. Keep complete styled component fragments and only
  shorten the final fragment when necessary.
- [x] Put zero padding after a numeric sign (`-003`, not `00-3`) and make custom
  `p` formatting safe for NaN and infinity.
- [x] Prove in Minecraft that a scheduled function starts a new `fstack` root
  and multi-entity NBT list formatting preserves source groups.
- [ ] Observe a genuinely exceptional `ServerFunctionLibrary.reload` and prove
  that the previous directive generation remains callable. The `finishReload`
  failure branch has a unit test. Two real attempts with malformed function-tag
  JSON were intentionally stopped because vanilla logs and skips each malformed
  tag while completing the listener successfully; those cases are not failed
  reloads and therefore cannot prove this branch.
- [x] Prove in Minecraft that more than 256 NBT leaves and more than 32,000
  characters produce bounded, visibly marked events; also prove vanilla-empty
  NBT cases do not render a red runtime error.
- [x] Finish the current audit with `gradlew clean build`, TypeScript
  build/typecheck/tests, MCP
  handshake smoke tests, and update both directive documents to match observed
  behavior.

## Follow-up bridge correctness checklist (2026-07-14)

- [x] Replace direct reads of the newest-first HUD history with an insertion-time,
  monotonic 2,048-message buffer. `mc_chat` returns `next_index`, `oldest_index`
  and `dropped`, so a stale caller can detect lost history.
- [x] Validate the complete input request before scheduling it on the render
  thread. Enforce `total_timeout_ms`; release held input on timeout, focus loss
  and client shutdown; reject malformed steps as HTTP 400 without crashing the
  client.
- [x] Treat look deltas as degrees at the bridge boundary. Convert them before
  calling vanilla `Entity.turn`, whose arguments are scaled internally by
  `0.15`.
- [x] Convert Minecraft 26.2 screenshot pixels from ARGB to JPEG RGB without
  swapping the red and blue channels.
- [x] Add `oldest_id` and `dropped` to both debug-event and diagnostic cursors.
- [x] Share token loading/generation between the server and client bridges, and
  harden lifecycle cleanup, port parsing, batch request types and deterministic
  dangerous-command checks.
- [x] Establish the exact success/result semantics of `function <id>` through
  Minecraft 26.2 bytecode and a real-server timeline. Determine whether
  `performPrefixedCommand` returns only after the queued function and root
  callback complete. Verified that the queue is synchronous, an ordinary
  function may complete without invoking the root result callback, and an
  explicit `return 7` reports success/result 7. The bridge now exposes
  `result_reported` rather than inventing result 0 or failure. It prevalidates
  Brigadier input and invokes the public execution-context queue directly so an
  unexpected command-handler exception remains observable by the HTTP layer.
- [x] Rerun all final builds and smoke tests after the above item is resolved or
  documented as confirmed vanilla behavior.

## Hardening verification record (2026-07-14)

- Automated parser/self-tests cover quoted braces, exact continued-line source
  mapping, native syntax validation, query-specific fields, nesting rules,
  oversized numeric formats, vanilla-empty NBT paths, sign-aware zero padding,
  non-finite `p`, component style/hover truncation and render-budget overflow.
- A real 26.2 dedicated server produced exactly 256 values followed by
  `…[truncated]`, and a 32,000-character result whose event also had
  `truncated=true`.
- The same server rendered missing storage paths and ordinary blocks as empty
  dynamic content without runtime diagnostics, preserved two entity `Pos[]`
  groups, and gave a scheduled function a one-element root stack.
- A real client displayed the short red error, exposed the same line through
  `/v1/client/chat`, and saved an 854x480 JPEG. The test also found and fixed a
  client HTTP lifecycle leak; a second launch and Alt+F4 shutdown completed with
  `BUILD SUCCESSFUL` instead of the client shutdown watchdog.
- Final regression: Fabric clean build, TypeScript build/typecheck, and the MCP
  10-tool handshake all passed.
- Follow-up regression after command-result hardening: Fabric `clean build`,
  TypeScript build/typecheck, MCP 10-tool handshake, real server command/batch
  probes, and another client Alt+F4 shutdown all passed. Ports 8766, 8767 and
  25565 were closed afterward.
- The client chat cursor audit found and fixed a real newest-first HUD-list bug:
  chat is now captured at insertion time in a monotonic bounded buffer, with
  explicit dropped-history reporting. Input requests are validated before the
  render thread and cancel held keys on focus loss, timeout and shutdown.

If an unclear Minecraft behavior affects one of these items, inspect the current
26.2 implementation (or current Fabric/community sources) before choosing a
behavior. After several incompatible attempts, stop and record the exact blocker
instead of substituting an approximation.

## Goal

Turn consecutive `#!` comment lines in `.mcfunction` files into short, runtime
debug statements. Output must use native Minecraft `Component` behavior and be
sent to all players, while a structured copy is retained for the local AI bridge.
Invalid directives warn once during reload and are omitted without preventing the
rest of the function from loading or running.

## Fixed syntax decisions

- General form: `#! plaintext {content[:format]} plaintext`.
- Consecutive `#!` lines may form one directive while braces remain unbalanced.
- A normal non-`#!` line ends collection. An incomplete directive is warned and
  discarded; the normal line is never consumed.
- Literal format/plaintext braces use `\{` and `\}`. `\\`, `\"`, `\n`, and
  `\t` are supported escapes where applicable.
- Query dispatch:
  - one token: `name`, `dim|dimension`, `pos|position`, `rot|rotation`,
    `anch|anchor`, `fname|function_name`, `fstack|function_stack`;
  - two tokens: scoreboard holder/selector plus objective;
  - explicit: `storage <id> <nbt-path>`, `entity <selector> <nbt-path>`,
    `block <x> <y> <z> <nbt-path>`.
- `feedback|fdb`, `self`, `sname`, and `self_name` do not exist.
- `name` follows the same command-source display-name behavior as `/say`.
- `dimension` renders a client-localized component and has the namespace ID in
  hover text; structured events always retain the namespace ID.
- `position` and `rotation` default to Java's shortest round-trip number text.
- `anchor` is `feet` or `eyes`.
- `fname` is the current function ID. `fstack` is the current synchronous
  function stack; a scheduled function starts a new root stack.

## Collection and format decisions

- `...` may occur at most once per list-format scope and only at the end of that
  scope, followed by optional whitespace and then optional `/strip` or
  `/no_strip`. Nothing else may occur after it in that scope.
- The repeated pattern is the exact content from the start of the current format
  scope to the `...` token. Nested format scopes repeat independently.
- `/strip` is the default. It truncates only the unused tail of the final repeated
  pattern. Fixed text outside that repeated scope is never removed.
- Without `...`, a collection query accepts at most one runtime value. More than
  one value drops the normal directive output, renders a short red in-game error
  for `@a`, and emits a rate-limited structured diagnostic with full details.
- Empty native dynamic content follows vanilla text-component behavior.
- Score behavior:
  - `{}` is the score value;
  - item fields: `{score}`, `{name}`, `{display_name}`, `{holder}`;
  - `name` and `display_name` are the entity/player display component;
  - `holder` is the exact scoreboard holder name;
  - entities without the objective score are skipped;
  - `*` follows vanilla scoreboard-holder behavior, including offline/fake names;
  - default single result is the score; default multi-result is display name plus
    score, with the holder name in hover text.
- Data behavior:
  - `{}` and `{value}` are the current NBT value;
  - fields include `{entity}`, `{index}`, `{entity_index}`, `{global_index}`;
  - preserve source/value hierarchy internally; the first renderer may expose a
    flattened leaf view without discarding the hierarchy.
- Composite item format is written as `{"..."}`, for example
  `{"{display_name}: {score}"}`.
- Numeric format subset: `d`, `f`, `e`, `g`, and custom `p` (significant digits,
  never exponent), with precision, width, alignment where practical, and zero
  padding. A numeric format applied to a non-number is ignored and the original
  value is rendered; the AI diagnostic may note this.

## Limits

- At most 256 rendered collection leaves per directive.
- At most 32,000 rendered characters per directive.
- At most 64 nested data/template levels.
- Runtime repeated warnings are keyed by function ID, line and error code and are
  rate limited.
- Runtime errors use a short human message such as
  `[#! test:main:18] expected one value, got 4`; detailed selector/path/error data
  belongs in logs and the AI diagnostic rather than the chat line.
- Truncation appends a visible marker and sets `truncated=true` in the event.

## Architecture

1. Intercept Minecraft function loading before vanilla discards comment lines.
2. Collect consecutive `#!` lines and parse them during datapack reload.
3. Store valid immutable directive ASTs in a reload-scoped registry keyed by a
   stable directive ID; replace each valid block with one synthetic registered
   command. Omit invalid blocks and log a source-mapped warning.
4. Register the synthetic command through Fabric command registration. It must
   execute using the incoming `CommandSourceStack`, never a newly created source.
5. Track the synchronous function call stack on the server thread by execution
   frame depth. When a function is queued, write its ID at `frame.depth + 1` and
   clear entries at that depth or deeper. When rendering, read only entries up to
   the current frame depth. Vanilla function execution is queue based, so the
   mixin must not pop merely when `CallFunction.execute` returns.
6. Render to native `MutableComponent` nodes. Reuse vanilla selector, NBT path,
   coordinate, resource-location, text-component resolution and `/tellraw`
   delivery behavior wherever available. Do not flatten to plain strings early.
7. Send the resolved component to every player. Also append a bounded structured
   debug event containing function, source line, component/plain fallback,
   call stack, truncation and diagnostics.
8. Add authenticated HTTP endpoints for polling debug events and reload
   diagnostics. Add corresponding TypeScript protocol types and MCP tools.

## Implementation stages

### Stage A: discovery and spike

- Inspect Minecraft 26.2 `ServerFunctionLibrary`, function parser and execution
  classes using the local mapped jars and `javap`/sources.
- Identify the narrowest stable mixin points for raw-line transformation and
  function-frame tracking.
- Inspect vanilla `TellRawCommand`, `ComponentUtils`, selector/NBT component
  classes, scoreboard APIs and command-source name/anchor/dimension accessors.
- If mapped APIs are unclear, check official Fabric docs/source and one current
  open-source Fabric mod. Stop after repeated incompatible approaches rather than
  inventing names.

### Stage B: parser and unit tests

- Implement a standalone parser with source spans, quote/escape state and nested
  brace scopes.
- Implement multiline collection and recovery.
- Implement query AST, format AST, terminal ellipsis/modifier validation and
  numeric-format parsing.
- Add pure Java tests for valid examples, malformed braces, namespaces containing
  `:`, selectors/SNBT braces, nested repeats, strip/no-strip and limits.

### Stage C: reload integration

- Transform valid directive blocks into synthetic commands.
- Populate/replace the directive registry atomically on successful resource
  preparation.
- Log warnings with function ID and line/column while preserving normal commands.

### Stage D: runtime queries and rendering

- Context/name/dimension/position/rotation/anchor.
- Score single/multi/wildcard values and fields.
- Entity/storage/block NBT queries using vanilla parsers/resolvers.
- Current function and synchronous function stack.
- Composite formats, repeat expansion, default strip and numeric formats.
- Native component delivery to all players.

### Stage E: AI bridge

- Bounded event and diagnostic buffers with monotonically increasing IDs.
- `GET /v1/debug/events?since=<id>` and diagnostics endpoint (or one combined
  endpoint if the implementation is simpler and unambiguous).
- MCP polling tool(s) returning structured events.

### Stage F: verification

- Clean Fabric build, TypeScript build/typecheck and MCP manifest smoke test.
- Launch Minecraft 26.2 dev client, create/reuse a datapack with representative
  directives, reload it, and verify chat output, hover components where possible,
  nested function names/stack, score, NBT, strip/no-strip and malformed-block
  recovery.
- Verify the AI event matches the human-visible output and both bridge ports close
  after shutdown.

## Stop conditions

- Stop and report if no safe function-load interception point can preserve vanilla
  parsing after several verified attempts.
- Stop and report rather than approximating a vanilla tellraw behavior that cannot
  be confirmed from current 26.2 code/docs.
- Never modify the old `minecraft-mod-mcp` project.
