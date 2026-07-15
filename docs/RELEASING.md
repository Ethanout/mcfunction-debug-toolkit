# Release workflow

**English** | [简体中文](RELEASING_zh-CN.md)

The root `package.json` is the only manually maintained toolkit version.

Set a release version with one command from the repository root:

```powershell
npm run version:set -- 0.1.1
```

This updates both `package.json` and the generated `package-lock.json`. Do not
edit workspace package versions, Gradle properties, source constants, README
examples, or artifact names for a release.

The Fabric build reads the root version during Gradle configuration and expands
it into `fabric.mod.json`. The MCP server reads the same root version at startup.
The MCP smoke test asserts that the reported server version still matches it.
