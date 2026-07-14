import assert from "node:assert/strict";
import { spawn } from "node:child_process";

const child = spawn(process.execPath, ["packages/mcp-server/dist/index.js"], {
  cwd: process.cwd(),
  stdio: ["pipe", "pipe", "pipe"],
});

let buffer = "";
const messages = [];
const waiters = [];

child.stdout.setEncoding("utf8");
child.stdout.on("data", (chunk) => {
  buffer += chunk;
  let newline;
  while ((newline = buffer.indexOf("\n")) >= 0) {
    const line = buffer.slice(0, newline).trim();
    buffer = buffer.slice(newline + 1);
    if (!line) continue;
    const message = JSON.parse(line);
    messages.push(message);
    for (const waiter of [...waiters]) waiter();
  }
});

function send(message) {
  child.stdin.write(`${JSON.stringify(message)}\n`);
}

function response(id) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error(`timed out waiting for response ${id}`)), 5_000);
    const check = () => {
      const found = messages.find((message) => message.id === id);
      if (!found) return;
      clearTimeout(timeout);
      waiters.splice(waiters.indexOf(check), 1);
      resolve(found);
    };
    waiters.push(check);
    check();
  });
}

try {
  send({
    jsonrpc: "2.0",
    id: 1,
    method: "initialize",
    params: {
      protocolVersion: "2025-06-18",
      capabilities: {},
      clientInfo: { name: "smoke-test", version: "0.1.0" },
    },
  });
  const initialized = await response(1);
  assert.equal(initialized.result.serverInfo.name, "mc-command-mcp");

  send({ jsonrpc: "2.0", method: "notifications/initialized" });
  send({ jsonrpc: "2.0", id: 2, method: "tools/list", params: {} });
  const listed = await response(2);
  assert.deepEqual(
    listed.result.tools.map((tool) => tool.name),
    ["mc_status", "mc_client_status", "mc_chat", "mc_input_sequence", "mc_screenshot", "mc_debug_events", "mc_debug_diagnostics", "mc_command_validate", "mc_command_run", "mc_command_batch"],
  );
  console.log("MCP stdio handshake and 10-tool manifest passed");
} finally {
  child.kill();
}
