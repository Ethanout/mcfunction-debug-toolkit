# Real-world syntax test

**English** | [简体中文](REAL_WORLD_SYNTAX_TEST_zh-CN.md)

The versioned source of the datapack is:

```text
test-data/datapacks/mc-command-syntax-test
```

`npm run test:install-datapack` copies it into the separate development save
named `MC Command Syntax Test`. The destination under `run/` is intentionally
ignored by Git because it contains local Minecraft state. Do not use the old
mod/MCP project or the dedicated-server `world` directory for this test.

## Run procedure

1. Run `npm run test:install-datapack` while the save is closed.
2. Launch the Fabric 26.2 development client and open `MC Command Syntax Test`.
3. Confirm `/datapack list enabled` contains `file/mc-command-syntax-test`.
4. Run `npm run test:real`. The script snapshots all three cursors, executes the
   setup/main/malformed functions, follows every page while `more=true`, and
   asserts the required outputs below.

Equivalent manual steps begin with `/reload`. Inspect
`mc_debug_diagnostics`; the deliberately malformed
   directive in `syntax_test:malformed` must produce one reload warning while
   the function remains available.
5. Run `function syntax_test:setup`.
6. Run `execute as @p at @s run function syntax_test:run`.
7. Run `function syntax_test:malformed`.
8. Poll `mc_debug_events`, `mc_debug_diagnostics`, and `mc_chat` from their
   previous cursors.

## Required observations

- Context output resolves the player name, current function, one-element root
  stack, localized dimension with namespace hover, position, rotation and feet
  anchor.
- Score output includes the player plus `#alpha=7` and `#beta=-3`; `-3` formatted
  as `04d` is `-003`.
- Storage numbers render both raw NBT doubles and `.2f` fixed-point values.
- Entity `Pos[]` retains entity/index fields; block NBT resolves the chest ID.
- The two invisible `syntax_probe` armor stands render as two separate `Pos[]`
  source groups in the multiline `entity-groups` expression.
- Default strip removes unused repeated placeholders. `/no_strip` retains them.
- Escaped outer braces render literally, and the multiline nested expression
  retains its source grouping.
- The deliberate many-values-without-ellipsis expression emits a short red
  runtime error, a structured runtime diagnostic, and does not prevent the final
  `say syntax_test_completed_after_runtime_error` or completion storage flag.
- `child` and `grandchild` show a growing synchronous function stack. The
  one-tick scheduled function starts a new root stack.
- The malformed reload directive is omitted, but both surrounding valid
  directives and the final `say` still execute.

The test passes only when the visible chat output and structured bridge events
agree and no unexpected runtime diagnostic is present.

## Verified result (2026-07-14, Minecraft 26.2)

The test was executed in the newly created single-player save with the Fabric
development client:

- `/datapack list enabled` reported `file/mc-command-syntax-test (world)`.
- Reload produced exactly the intended `unknown_query` warning at
  `syntax_test:malformed:2:46`.
- The strengthened main run produced events 20-37: 17 successful directive
  outputs, one intentional `expected_one_value` runtime-error event, and the
  scheduled-root event.
- The two armor-stand groups rendered independently as
  `1.0d, 68.0d, 0.0d\n 2.0d, 69.0d, 0.0d`.
- Score padding rendered `0042`, `0007`, and `-003`; `/no_strip` retained the
  final `{}`, while default strip removed it.
- The final command after the deliberate runtime error appeared in client chat,
  and `storage syntax_test:state completed` was `1b`.
- The client chat cursor returned every visible message with `dropped=false`.
- The client saved an 854x480 JPEG evidence image at
  `mods/fabric/run/screenshots/mc-command-mcp/mc-command-1784037315392.jpg`.
- Saving the world and closing the client completed with `BUILD SUCCESSFUL`.

No unexpected reload or runtime diagnostic occurred.
