import { readFile } from "node:fs/promises";
import { resolve } from "node:path";

const tokenPath = resolve("mods/fabric/run/config/mc-command-mcp.token");
const token = (process.env.MC_COMMAND_TOKEN ?? await readFile(tokenPath, "utf8")).trim();
const url = process.env.MC_COMMAND_CLIENT_BASE_URL ?? "http://127.0.0.1:8767";

const results = await Promise.all(Array.from({ length: 3 }, async (_, index) => {
  const response = await fetch(`${url}/v1/client/screenshot`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
    },
    body: "{}",
  });
  const payload = await response.json();
  return {
    index,
    status: response.status,
    ok: payload.ok === true,
    error_code: payload.error_code,
    jpeg_bytes: payload.image_base64
      ? Buffer.from(payload.image_base64, "base64").byteLength
      : 0,
    path: payload.path,
  };
}));

const successful = results.filter((result) => result.status === 200 && result.ok);
const busy = results.filter((result) =>
  result.status === 429 && result.error_code === "screenshot_busy");
if (successful.length !== 1 || busy.length !== 2 || successful[0].jpeg_bytes === 0) {
  throw new Error(`unexpected screenshot concurrency result: ${JSON.stringify(results)}`);
}

console.log(JSON.stringify({ ok: true, results }, null, 2));
