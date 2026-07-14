# Hostile audit remediation plan

This file records the independent hostile review and the decisions made after
checking each claim against the current Minecraft 26.2 implementation. Keep it
updated while the fixes are in progress.

## Accepted P1 findings

- [x] Bound query work separately from the 256 rendered-leaf budget. Limit
  entity/score source visits and total selected NBT bytes before pretty-component
  conversion. Missing paths must not permit unbounded per-entity NBT work.
- [x] Replace last-callback-wins command results with explicit callback,
  success/failure and result-sum aggregation. Preserve the unreported-result
  state.
- [x] Add a deadline/cancellation gate before queued server work starts. Once
  work has started, report `unknown_outcome` instead of implying that a 504 means
  the command did not execute; stop a timed-out batch between commands.
- [x] Page debug events, diagnostics and client chat by count and response-byte
  budget. Bound captured command feedback and expose truncation/more/latest
  metadata.
- [x] Keep render-thread screenshot work to the framebuffer capture. Move pixel
  conversion/JPEG encoding to one bounded worker, encode once, enforce one
  in-flight request and a JPEG byte limit, and use collision-free filenames.
- [x] Replace the hand-maintained Chinese-path self-test list with JUnit Platform
  discovery so every `@Test` is executed by `gradlew check`.

## Accepted P2/P3 findings

- [x] Stage reload directives outside the live registry and atomically swap an
  immutable generation only on successful completion. Overlapping vanilla
  reload entry points still require separate real-game confirmation.
- [x] Do not apply integer `d` formatting to fractional/non-finite values; retain
  the original native value instead of silently truncating it.
- [x] Count input steps only after their duration/action completes, not when they
  start. Keep cancellation from claiming an active hold as completed.
- [x] Move the real-world datapack into versioned test data and provide a live
  assertion script; an ignored local save and prose transcript are not a
  reproducible regression test.
- [x] Bound the runtime diagnostic rate-limit key cache and clear it at an
  appropriate lifecycle boundary.

## Rejected or deliberately deferred claims

- `.0g` is not rejected: Python itself treats precision zero as one significant
  digit. Custom `.0p` follows that same rule. The behavior must be documented and
  tested rather than silently changed.
- `DangerPolicy` is only a deterministic accidental-misuse guard. It cannot stop
  nested `execute run`, datapack functions or other indirect side effects and
  must never be described as an authorization/security boundary. A real sandbox
  would require a restricted command source/dispatcher and is out of scope for
  this regex list.
- No function-stack rewrite is justified without a reproducible stale-depth
  failure.
- A truly overlapping or exceptionally failed Minecraft reload remains a
  real-game verification item; do not claim it from a synthetic unit test alone.

## Verification gates

- [ ] Pure/unit tests for every accepted behavior.
- [ ] Fabric `clean build` through automatic JUnit discovery on this Chinese
  path.
- [ ] TypeScript build/typecheck and MCP manifest smoke test.
- [ ] Real server/client probes for command aggregation, pagination, timeout
  metadata, screenshot single-flight, input cancellation and the versioned
  syntax datapack.
- [ ] Normal client shutdown and closed ports 8766/8767/25565.
