import { cp, mkdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const source = path.join(root, "test-data", "datapacks", "mc-command-syntax-test");
const saveName = process.env.MC_COMMAND_TEST_SAVE ?? "MC Command Syntax Test";
const destination = path.join(
  root, "mods", "fabric", "run", "saves", saveName, "datapacks", "mc-command-syntax-test",
);

await mkdir(path.dirname(destination), { recursive: true });
await cp(source, destination, { recursive: true, force: true });
console.log(`Installed syntax-test datapack into ${destination}`);
