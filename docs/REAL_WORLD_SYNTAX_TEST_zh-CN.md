# 真实语法测试

[English](REAL_WORLD_SYNTAX_TEST.md) | **简体中文**

受版本控制的数据包源码位于：

```text
test-data/datapacks/mc-command-syntax-test
```

`npm run test:install-datapack` 会将其复制到名为 `MC Command Syntax Test` 的独立开发存档。`run/` 下的目标目录包含本地 Minecraft 状态，因此会被 Git 有意忽略。不要使用旧的 mod/MCP 项目或专用服务器的 `world` 目录完成此测试。

## 运行步骤

1. 关闭存档后运行 `npm run test:install-datapack`。
2. 启动 Fabric 26.2 开发客户端并打开 `MC Command Syntax Test`。
3. 确认 `/datapack list enabled` 包含 `file/mc-command-syntax-test`。
4. 运行 `npm run test:real`。脚本会记录三个游标，执行 setup/main/malformed 函数，在 `more=true` 时遍历全部页面，并断言下列结果。

等效的手动步骤从 `/reload` 开始。检查 `mc_debug_diagnostics`；`syntax_test:malformed` 中故意写错的指令必须生成一条 reload 警告，同时函数仍可使用。

5. 运行 `function syntax_test:setup`。
6. 运行 `execute as @p at @s run function syntax_test:run`。
7. 运行 `function syntax_test:malformed`。
8. 从之前的游标开始轮询 `mc_debug_events`、`mc_debug_diagnostics` 和 `mc_chat`。

## 必须观察到的结果

- 上下文输出解析玩家名、当前函数、单元素根调用栈、本地化维度及命名空间 hover、位置、朝向和 feet 锚点。
- 分数输出包含玩家以及 `#alpha=7`、`#beta=-3`；`-3` 使用 `04d` 后为 `-003`。
- Storage 数字同时显示原始 NBT double 和 `.2f` 定点值。
- 实体 `Pos[]` 保留 entity/index 字段；方块 NBT 能解析箱子 ID。
- 两个不可见 `syntax_probe` 盔甲架在多行 `entity-groups` 表达式中形成两个独立的 `Pos[]` 来源组。
- 默认 strip 删除未使用的重复占位符；`/no_strip` 保留它们。
- 转义的外层花括号按字面显示，多行嵌套表达式保留来源分组。
- 故意制造的“多值但无省略号”表达式产生简短红色运行时错误和结构化诊断，但不阻止最终的 `say syntax_test_completed_after_runtime_error` 或完成 storage 标志。
- `child` 与 `grandchild` 显示逐渐增长的同步函数栈；延迟一 tick 的函数从新的根栈开始。
- reload 时错误的指令被省略，但其前后的有效指令和最后的 `say` 仍会执行。

只有可见聊天输出与结构化桥接事件一致，且不存在意外运行时诊断时，测试才算通过。

## 已验证结果（2026-07-14，Minecraft 26.2）

该测试已在新建的单人存档和 Fabric 开发客户端中执行：

- `/datapack list enabled` 报告 `file/mc-command-syntax-test (world)`。
- Reload 只在 `syntax_test:malformed:2:46` 产生预期的 `unknown_query` 警告。
- 加强后的主测试产生事件 20–37：17 条成功指令输出、1 条预期的 `expected_one_value` 运行时错误事件，以及 scheduled-root 事件。
- 两组盔甲架分别渲染为 `1.0d, 68.0d, 0.0d\n 2.0d, 69.0d, 0.0d`。
- 分数补零得到 `0042`、`0007` 和 `-003`；`/no_strip` 保留最终 `{}`，默认 strip 删除它。
- 故意制造的运行时错误之后，最后一条命令仍出现在客户端聊天中，且 `storage syntax_test:state completed` 为 `1b`。
- 客户端聊天游标返回所有可见消息，`dropped=false`。
- 客户端把 854×480 JPEG 证据图保存到 `mods/fabric/run/screenshots/mc-command-mcp/mc-command-1784037315392.jpg`。
- 保存世界并关闭客户端后，以 `BUILD SUCCESSFUL` 结束。

没有出现意外的 reload 或运行时诊断。
