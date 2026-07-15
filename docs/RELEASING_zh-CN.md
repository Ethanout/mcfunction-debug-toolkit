# 发布流程

[English](RELEASING.md) | **简体中文**

根目录的 `package.json` 是唯一需要手动维护的工具包版本来源。

在仓库根目录用一条命令设置发布版本：

```powershell
npm run version:set -- 0.1.1
```

该命令会同时更新 `package.json` 和生成的 `package-lock.json`。发布时不要
手动修改工作区包版本、Gradle 属性、源码常量、README 示例或产物名称。

Fabric 构建会在 Gradle 配置阶段读取根版本并写入 `fabric.mod.json`。MCP
服务器启动时读取同一个根版本；MCP 冒烟测试会检查服务器报告的版本是否仍与其一致。
