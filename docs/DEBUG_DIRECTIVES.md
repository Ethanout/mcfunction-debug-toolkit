# `#!` Debug Directives

**English** | [简体中文](DEBUG_DIRECTIVES_zh-CN.md)

`#!` is a debug template embedded in a `.mcfunction` file. The mod parses these
comments when a datapack loads. When the function runs, it sends the result to
all players as a native Minecraft `Component` and stores a structured event for
MCP. It is designed to shorten debugging commands, not replace every feature of
`tellraw`.

## Quick example

```mcfunction
#! function: {fname}; position: {position:.2f}
#! num: {@e num: {"{display_name}: {score:04d}"}, ...}
#! storage: {storage demo:test values[]: {}, ...}
```

Basic form:

```text
#! plaintext {query[:format]} plaintext
```

A directive may contain multiple queries. Plain text and query results are
combined into one native text component.

Plain-text directives are also emitted, which is useful for labels and visible
separators:

```mcfunction
#! =====Debug=====
```

## Queries

Single-argument context queries:

| Syntax | Result |
|---|---|
| `name` | Display name using the same command-source semantics as `/say` |
| `dim` / `dimension` | Localized dimension name; hover shows the namespaced ID |
| `pos` / `position` | Execution position as `x y z` |
| `rot` / `rotation` | Execution rotation as `yaw pitch` |
| `anch` / `anchor` | `feet` or `eyes` |
| `fname` / `function_name` | Function currently being executed |
| `fstack` / `function_stack` | Current synchronous function call stack |

A scheduled function starts a new stack root. Synchronously nested `/function`
calls appear in `fstack`.

A selector by itself renders entity display names like vanilla's `tellraw`
selector component:

```mcfunction
#! Self: {@s}
#! Targets: {@e[tag=test]}
```

An empty selector renders nothing. Multiple entities use vanilla name joining.

Two arguments are parsed as a vanilla score holder and objective:

```mcfunction
#! {@s num}
#! {@e num: {"{display_name}: {score}"}, ...}
#! {* num: {"{holder}={score:04d}"}, ...}
```

Entities without a score in that objective are skipped. `*` follows vanilla's
tracked-holder behavior, including offline players and fake players. Score item
fields are:

- `{}` or `{score}`: score value;
- `{name}` or `{display_name}`: display name with the raw holder on hover;
- `{holder}`: exact scoreboard holder name.

NBT queries use vanilla selectors, block coordinates, and NBT path parsing:

```mcfunction
#! {storage demo:test values[]: {}, ...}
#! {entity @a Pos[]: {"{entity}[{index}]={value:.2f}"}, ...}
#! {block ~ ~ ~ id}
```

NBT item fields are:

- `{}` or `{value}`: current NBT value;
- `{entity}`: current source entity or data-source label;
- `{index}`: value index within the current source;
- `{entity_index}`: source entity index;
- `{global_index}`: global index in the flattened result.

NBT is displayed with vanilla's colored SNBT component, so strings keep their
quotes and numbers keep their NBT type suffixes.

## Lists and `...`

`...` repeats the current-level pattern immediately before it:

```mcfunction
#! {storage demo:test values[]: {}, ...}
#! {storage demo:test values[]: {}, {}, ...}
```

Each level may contain at most one `...`. It must be the final token at that
formatting level, followed only by whitespace and optional `/strip` or
`/no_strip`.

- Default `/strip`: silently removes the tail of the final pattern after the
  last value is consumed.
- `/no_strip`: preserves placeholders and trailing text that have no value.

For example, three values formatted with `{}, {}, ... /no_strip` produce
something like:

```text
1, 2, 3, {},
```

A compound item uses `{"..."}`. Multiple fields inside it belong to one value:

```mcfunction
#! {@e num: {"{display_name}: {score}"}, ...}
```

Nested formats have independent `...` operators. This keeps a two-dimensional
group per entity and then formats each entity's `Pos[]` values:

```mcfunction
#! positions: {
#! entity @a Pos[]: {{}, ...}\n ...
#! }
```

An explicit format without `...` accepts exactly one runtime value. Multiple
values discard the normal output but do not stop the function.

## Numeric formatting

Values follow vanilla SNBT color semantics: numbers are gold, strings and
otherwise unstyled entity names are green, and resource identifiers such as
dimensions and function names are aqua. Existing entity team or custom colors
are preserved. Plain text is not colored automatically.

The formatter supports `d`, `f`, `e`, `g`, and custom `p`:

- `d`: integer;
- `f`: fixed-point decimal;
- `e`: scientific notation;
- `g`: significant digits, with scientific notation when appropriate;
- `p`: significant digits, never using scientific notation.

Precision, width, zero padding, and basic alignment are supported:

```mcfunction
#! {position:.2f}
#! {@s num: {"{score:04d}"}}
#! {storage demo:test value: {value:.6p}}
```

Applying a numeric format to a non-number is not an error; the original value
is displayed. Unformatted positions and rotations use Java's shortest
round-trip number text. Width and precision are each limited to 32,000. Zero
padding for negative numbers follows the sign, so `-3` with `04d` becomes
`-003`. For `NaN` and infinity, `p` preserves Java's non-finite text instead of
performing decimal rounding. `d` applies only to values exactly representable
as integers; decimals, `NaN`, and infinity remain unchanged instead of being
silently truncated. Like Python's `g`, `.0g` and the corresponding custom `.0p`
use one significant digit.

## Multiline directives and escaping

Consecutive `#!` lines are joined while braces remain unmatched. The first
non-`#!` line is never consumed.

Supported escapes are `\{`, `\}`, `\\`, `\"`, `\n`, and `\t`. To surround a
list with literal braces, write:

```mcfunction
#! literal=\{{storage demo:test values[]: {}, ...}\}
```

## Errors, limits, and AI output

Parse errors are handled during datapack reload: the entire `#!` directive is
skipped with a warning, while other function lines continue loading. Warnings
map to the real `.mcfunction` line and column. Selectors, scoreboard holders,
storage IDs, block coordinates, NBT paths, format fields, and nested-list
validity are also checked at reload time with vanilla parsers. A reload swaps
to the new directive registry atomically only after success; an unexpected
listener failure preserves the previous generation.

Dynamic NBT content follows vanilla `tellraw` empty-result behavior. A missing
path, empty entity selection, unloaded block, or non-block-entity renders an
empty query instead of a red runtime error.

Runtime errors:

1. discard that directive's normal output;
2. show all players a short error such as
   `[#! demo:main:18] expected one value, got 4`;
3. write full details to the log and AI diagnostics buffer;
4. continue executing later commands in the function.

Each directive shares limits of 256 collection leaves, 512 score/entity/storage/
block source visits, 1 MiB of selected NBT data, 32,000 rendered characters,
and 64 template levels. After vanilla parsing, an unbounded entity selector is
internally capped at probing 513 results so truncation can be marked precisely
at the 513th source. Exceeding a budget does not stop the function: fitting
results are retained, `…[truncated]` is appended, and the structured event
returns `truncated=true`. Character truncation preserves complete native
components, styling, and hover information when possible, shortening only the
final fragment that does not fit. Repeated runtime diagnostics are rate-limited.

MCP tools:

- `mc_debug_events`: incrementally reads successful output and runtime errors;
- `mc_debug_diagnostics`: incrementally reads reload warnings and detailed
  runtime diagnostics.

Both use paged cursors and return `next_id`, `latest_id`, `oldest_id`, `more`,
and `dropped`. Pass `next_id` as `since` on the next poll and continue while
`more=true`. `dropped=true` means the requested cursor predates the oldest
event retained in the bounded buffer, so the caller should resynchronize from
the current response. Oversized native-component JSON is omitted with
`component_omitted=true`, while bounded plain text remains available.
