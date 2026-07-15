# `#!` 调试指令

[English](DEBUG_DIRECTIVES.md) | **简体中文**

`#!` 是写在 `.mcfunction` 里的调试模板。数据包加载时，模组会解析这些
注释；函数运行时，它把结果作为原生 Minecraft `Component` 发给所有玩家，
同时把结构化事件保存给 MCP。它的目标是减少调试命令长度，而不是替代
`tellraw` 的全部功能。

## 快速示例

```mcfunction
#! 当前函数：{fname}；位置：{position:.2f}
#! num：{@e num: {"{display_name}: {score:04d}"}, ...}
#! storage：{storage demo:test values[]: {}, ...}
```

基本形式：

```text
#! plaintext {query[:format]} plaintext
```

同一条调试指令可以有多个查询。普通文本和查询结果最终拼成同一个原生
文本组件。

纯文本调试行也会正常输出，可用于插入醒目的分隔标题：

```mcfunction
#! =====Debug=====
```

## 查询

单参数上下文查询：

| 写法 | 结果 |
|---|---|
| `name` | 与命令源、`/say` 相同语义的显示名称 |
| `dim` / `dimension` | 客户端本地化维度名；悬停显示命名空间 ID |
| `pos` / `position` | 执行位置 `x y z` |
| `rot` / `rotation` | 执行朝向 `yaw pitch` |
| `anch` / `anchor` | `feet` 或 `eyes` |
| `fname` / `function_name` | 当前正在运行的函数 |
| `fstack` / `function_stack` | 当前同步函数调用栈 |

计划函数开始新的调用栈根；同步嵌套的 `/function` 会出现在 `fstack` 中。

单个选择器会按原版 `tellraw` selector 文本组件渲染实体显示名称：

```mcfunction
#! Self: {@s}
#! Targets: {@e[tag=test]}
```

选择器为空时渲染为空；多实体结果使用原版名称连接方式。

两个参数按原版 score holder + objective 解析：

```mcfunction
#! {@s num}
#! {@e num: {"{display_name}: {score}"}, ...}
#! {* num: {"{holder}={score:04d}"}, ...}
```

实体没有该 objective 的分数时会跳过。`*` 使用原版 tracked-holder 行为，
包括离线玩家和假玩家。score 项字段为：

- `{}`、`{score}`：分数值；
- `{name}`、`{display_name}`：显示名称，悬停显示 holder 原名；
- `{holder}`：记分板中的精确名称。

NBT 查询使用原版选择器、方块坐标和 NBT path 解析：

```mcfunction
#! {storage demo:test values[]: {}, ...}
#! {entity @a Pos[]: {"{entity}[{index}]={value:.2f}"}, ...}
#! {block ~ ~ ~ id}
```

NBT 项字段为：

- `{}`、`{value}`：当前 NBT 值；
- `{entity}`：当前来源实体或数据来源标签；
- `{index}`：当前来源内的值下标；
- `{entity_index}`：来源实体下标；
- `{global_index}`：扁平结果的全局下标。

NBT 用原版彩色 SNBT Component 显示，因此字符串保留引号，数字保留 NBT
类型后缀。

## 列表与 `...`

`...` 表示重复它之前的当前层 pattern：

```mcfunction
#! {storage demo:test values[]: {}, ...}
#! {storage demo:test values[]: {}, {}, ...}
```

每层最多一个 `...`。它必须是该格式层的最后一个 token，后面只能有空白
和可选的 `/strip` 或 `/no_strip`。

- 默认 `/strip`：最后一份 pattern 用完最后一个值后，静默删除其尾部；
- `/no_strip`：保留没有值可填的占位符和尾部文本。

例如三个值使用 `{}, {}, ... /no_strip` 时，会得到类似：

```text
1, 2, 3, {},
```

复合项使用 `{"..."}`，其内部多个字段属于同一个值：

```mcfunction
#! {@e num: {"{display_name}: {score}"}, ...}
```

嵌套格式有独立的 `...`。下面按实体保留二维分组，再格式化每个实体的
`Pos[]`：

```mcfunction
#! 坐标：{
#! entity @a Pos[]: {{}, ...}\n ...
#! }
```

显式格式没有 `...` 时只接受一个运行时值；得到多个值会丢弃正常输出，
但不会中止函数。

## 数字格式

参数值沿用原版 SNBT 的视觉语义：数字为金色，字符串与未着色的实体名称为绿色，维度、函数名等资源标识符为青色。实体已有的队伍色或自定义颜色会被保留；普通文本不自动着色。

支持 `d`、`f`、`e`、`g` 和自定义 `p`：

- `d`：整数；
- `f`：定点小数；
- `e`：科学计数法；
- `g`：有效数字，可使用科学计数法；
- `p`：有效数字，强制不用科学计数法。

支持精度、宽度、补零和基础对齐：

```mcfunction
#! {position:.2f}
#! {@s num: {"{score:04d}"}}
#! {storage demo:test value: {value:.6p}}
```

数字格式遇到非数字时不报错，原样显示该值。位置和朝向在没有格式时使用
Java 的 shortest round-trip 数字文本。宽度和精度都不能超过 32,000；
负数补零放在符号后面，例如 `-3` 使用 `04d` 得到 `-003`。`p` 遇到
`NaN` 或无穷大时保留 Java 的非有限数字文本，不进入十进制舍入。
`d` 只应用于可精确表示为整数的数字；小数、`NaN` 和无穷大保持原生值，
不会被静默截成整数。与 Python `g` 一致，`.0g`（以及对应的自定义 `.0p`）
按 1 位有效数字处理。

## 换行与转义

括号尚未配对时，连续的 `#!` 行会拼成同一条指令。第一条非 `#!` 行不会
被吞掉。

支持：`\{`、`\}`、`\\`、`\"`、`\n`、`\t`。给列表加字面量花括号可写：

```mcfunction
#! literal=\{{storage demo:test values[]: {}, ...}\}
```

## 错误、限制与 AI 输出

解析错误发生在数据包 reload：整条 `#!` 被跳过、记录警告，其他函数行
继续加载。警告会映射到真实 `.mcfunction` 行列。选择器、记分板 holder、
storage ID、方块坐标、NBT path、格式字段和嵌套列表的适用性也在 reload
阶段用原版解析器验证。reload 只有成功后才会原子切换到新一代指令注册表；
监听器异常失败时保留上一代。

NBT 动态内容遵循原版 `tellraw` 的空结果：路径不存在、实体选择为空、方块
未加载或不是方块实体时，该查询渲染为空，不显示红色运行时错误。

运行时错误会：

1. 丢弃该条正常输出；
2. 给所有玩家显示短错误，例如
   `[#! demo:main:18] expected one value, got 4`；
3. 向日志和 AI 诊断缓冲写入完整信息；
4. 继续执行函数后续命令。

限制：每条指令共享最多 256 个集合叶子、512 个 score/entity/storage/block
来源访问、1 MiB 被选择的 NBT 数据、32,000 个渲染字符和 64 层模板深度。
无界实体选择器会在原版解析后被内部改写为最多探测 513 个结果，以便在
第 513 个来源处准确标记截断。超过预算时不终止函数，而是保留能容纳的结果、追加
`…[truncated]`，并让结构化事件返回 `truncated=true`。字符截断会尽量保留
完整原生组件及其样式、悬停信息，只缩短最后一个装不下的片段。重复运行时
诊断会限流。

MCP 使用：

- `mc_debug_events`：增量读取成功输出和运行时错误；
- `mc_debug_diagnostics`：增量读取 reload 警告和详细运行时诊断。

两者使用分页游标，返回 `next_id`、`latest_id`、`oldest_id`、`more` 和
`dropped`。下一次轮询把 `next_id` 作为 `since`，并在 `more=true` 时继续；
`dropped=true` 表示请求的游标早于有界缓冲中仍保留的最旧事件，调用方应从
当前响应重新同步。过大的原生组件 JSON 会以 `component_omitted=true` 省略，
但保留有界纯文本。
