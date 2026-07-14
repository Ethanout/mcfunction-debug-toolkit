# Changelog

All notable changes to MCFunction Debug Toolkit are documented in this file.
The project follows [Semantic Versioning](https://semver.org/).

## [0.1.1] - 2026-07-15

### Added

- Vanilla selector shorthand such as `{@s}` and `{@e[tag=target]}` for concise
  entity-name output without a separate query keyword.
- Pure-text `#!` lines for labels, separators, literal braces, and structured
  multiline debug output.
- Vanilla-style value colors: gold numbers, green strings and unstyled entity
  names, aqua resource identifiers, gray stack separators, and native SNBT
  highlighting.
- A dedicated AI agent setup guide, release workflow, changelog, and real
  in-game screenshot in both README variants.

### Changed

- Reworked the English and Simplified Chinese READMEs around compact, practical
  datapack debugging examples, with MCP presented as an optional capability.
- Consolidated the project version into the root `package.json`; Fabric artifact
  names, Fabric metadata, and the MCP handshake now derive from that value.
- Expanded ignore rules for credentials, local environments, generated output,
  coverage data, and operating-system metadata.

### Fixed

- Numeric formats such as `.1f` now retain gold number styling instead of
  dropping the nested SNBT color.
- Selector and score output preserve native entity team colors, hover events,
  click events, and insertion metadata.
- Selector evaluation uses the same bounded source and leaf budgets as other
  collection queries.

## [0.1.0] - 2026-07-15

First public development preview under the MCFunction Debug Toolkit name.

### Added

- Human-first `#!` debug directives for `.mcfunction` files, with execution
  context, scoreboard, entity NBT, block NBT, and command storage queries.
- Multiline templates, escaped literal braces, repeating `...` patterns,
  nested list grouping, `/strip` and `/no_strip`, and compact numeric formats.
- Structured debug events, reload diagnostics, short in-game runtime errors,
  function names, and nested function call stacks.
- English and Simplified Chinese project documentation, protocol details,
  architecture notes, security guidance, and real-world datapack tests.

### Changed

- Repositioned MCP as an optional automation layer around a standalone datapack
  debugging mod.
- Bounded rendered leaves, NBT bytes, component size, event history, chat
  history, screenshots, command feedback, and input duration.

### Fixed

- Invalid directives are reported and skipped during reload without stopping
  the remaining function commands.
- Client shutdown, focus loss, and request cancellation release held keys and
  mouse buttons cleanly.

## [0.0.0] - 2026-07-14

Internal proof-of-concept used to validate the project architecture.

### Added

- Initial Fabric mod and Node.js MCP server workspace for Minecraft Java 26.2.
- Authenticated loopback HTTP bridges for server commands and client actions.
- Live Brigadier command validation, single-command execution, and bounded
  sequential command batches.
- Incremental HUD chat capture, JPEG screenshots, keyboard and mouse input,
  camera movement, clicks, double-clicks, holds, and drags.
- Shared bearer-token discovery, deterministic dangerous-command guards,
  timeout reporting, bounded response pages, and initial automated tests.

[0.1.1]: https://github.com/Ethanout/mcfunction-debug-toolkit/compare/88dd643...v0.1.1
[0.1.0]: https://github.com/Ethanout/mcfunction-debug-toolkit/compare/769f99c...88dd643
[0.0.0]: https://github.com/Ethanout/mcfunction-debug-toolkit/commit/769f99c
