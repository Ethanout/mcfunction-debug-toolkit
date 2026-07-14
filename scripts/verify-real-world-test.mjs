import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const token = (process.env.MC_COMMAND_TOKEN
  ?? await readFile(path.join(root, "mods", "fabric", "run", "config", "mc-command-mcp.token"), "utf8")
).trim();
const server = (process.env.MC_COMMAND_BASE_URL ?? "http://127.0.0.1:8766").replace(/\/$/, "");
const client = (process.env.MC_COMMAND_CLIENT_BASE_URL ?? "http://127.0.0.1:8767").replace(/\/$/, "");
const headers = { Authorization: `Bearer ${token}`, "Content-Type": "application/json" };

async function json(url, init = {}) {
  const response = await fetch(url, { ...init, headers: { ...headers, ...(init.headers ?? {}) } });
  const body = await response.json();
  if (!response.ok) throw new Error(`${response.status} ${JSON.stringify(body)}`);
  return body;
}

async function command(value) {
  return json(`${server}/v1/command/run`, { method: "POST", body: JSON.stringify({ command: value }) });
}

async function collect(pathname, key, cursor, latestKey) {
  const result = [];
  let pages = 0;
  for (;;) {
    const page = await json(`${pathname}?since=${cursor}&limit=64`, { method: "GET", headers: {} });
    pages++;
    result.push(...page[key]);
    cursor = page[key === "messages" ? "next_index" : "next_id"];
    if (!page.more) return { values: result, cursor, latest: page[latestKey], pages };
  }
}

function require(condition, message) {
  if (!condition) throw new Error(`real-world assertion failed: ${message}`);
}

const eventStart = await json(`${server}/v1/debug/events?since=0&limit=1`, { method: "GET", headers: {} });
const diagnosticStart = await json(`${server}/v1/debug/diagnostics?since=0&limit=1`, { method: "GET", headers: {} });
const chatStart = await json(`${client}/v1/client/chat?since=0&limit=1`, { method: "GET", headers: {} });

await command("reload");
await command("function syntax_test:setup");
for (let run = 0; run < 4; run++) {
  await command("execute as @p at @s run function syntax_test:run");
}
await command("function syntax_test:malformed");
await new Promise((resolve) => setTimeout(resolve, 250));

const eventResult = await collect(
  `${server}/v1/debug/events`, "events", eventStart.latest_id, "latest_id",
);
const diagnosticResult = await collect(
  `${server}/v1/debug/diagnostics`, "diagnostics", diagnosticStart.latest_id, "latest_id",
);
const chatResult = await collect(
  `${client}/v1/client/chat`, "messages", chatStart.latest_index, "latest_index",
);
const events = eventResult.values;
const diagnostics = diagnosticResult.values;
const chat = chatResult.values;
const completion = await command("data get storage syntax_test:state completed");

const texts = events.map((event) => event.text);
require(texts.some((text) => text.includes("#beta [#beta]=-003")), "negative score padding");
require(texts.some((text) => text.includes("no-strip=") && text.includes("{}")), "no_strip placeholder");
require(texts.some((text) => text.includes("entity-groups=") && text.includes("\n")), "two-dimensional entity groups");
require(events.some((event) => event.error_code === "expected_one_value"), "intentional runtime error event");
require(events.some((event) => event.function_stack?.length === 3), "nested synchronous function stack");
require(events.some((event) => event.function_id === "syntax_test:scheduled"
  && event.function_stack?.length === 1), "scheduled root stack");
require(diagnostics.some((item) => item.phase === "reload" && item.code === "unknown_query"
  && item.line === 2), "source-mapped malformed reload warning");
require(diagnostics.some((item) => item.phase === "runtime" && item.code === "expected_one_value"),
  "runtime diagnostic");
require(chat.some((item) => item.text.includes("syntax_test_completed_after_runtime_error")),
  "execution continued after runtime error");
require(chat.some((item) => item.text.includes("malformed_directive_did_not_break_function")),
  "malformed directive did not remove the function");
require(completion.result_reported && completion.result === 1, "completion storage flag is 1b");
require(eventResult.pages >= 2, "debug event pagination");
require(chatResult.pages >= 2, "chat pagination");

console.log(JSON.stringify({
  ok: true,
  events: events.length,
  diagnostics: diagnostics.length,
  chat_messages: chat.length,
  event_pages: eventResult.pages,
  chat_pages: chatResult.pages,
  event_latest: eventStart.latest_id + events.length,
}, null, 2));
