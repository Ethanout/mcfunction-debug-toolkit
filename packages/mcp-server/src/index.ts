import { readFileSync } from "node:fs";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { batchRequestSchema, commandRequestSchema, inputSequenceRequestSchema } from "@mc-command/protocol";
import { z } from "zod";
import { BridgeClient } from "./bridge-client.js";

const toolkitMetadata = JSON.parse(
  readFileSync(new URL("../../../package.json", import.meta.url), "utf8"),
) as { version?: unknown };
if (typeof toolkitMetadata.version !== "string") {
  throw new Error("Root package.json does not contain a toolkit version");
}

const bridge = new BridgeClient();
const server = new McpServer({
  name: "mc-command-mcp",
  version: toolkitMetadata.version,
});

function textResult(value: unknown) {
  return { content: [{ type: "text" as const, text: JSON.stringify(value, null, 2) }] };
}

function errorResult(error: unknown) {
  const message = error instanceof Error ? error.message : String(error);
  return { isError: true, content: [{ type: "text" as const, text: message }] };
}

function imageResult(value: { path?: string; width?: number; height?: number; mime_type?: string; image_base64?: string }) {
  const summary = { ok: true, path: value.path, width: value.width, height: value.height, mime_type: value.mime_type };
  return {
    content: [
      { type: "text" as const, text: JSON.stringify(summary, null, 2) },
      ...(value.image_base64 ? [{ type: "image" as const, data: value.image_base64, mimeType: value.mime_type ?? "image/jpeg" }] : []),
    ],
  };
}

server.registerTool(
  "mc_status",
  {
    description: "Check the local MC Command Bridge connection and Minecraft version.",
    inputSchema: {},
  },
  async () => {
    try {
      return textResult(await bridge.status());
    } catch (error) {
      return errorResult(error);
    }
  },
);

server.registerTool(
  "mc_client_status",
  {
    description: "Check whether the Minecraft client is connected, focused, and holding the mouse.",
    inputSchema: {},
  },
  async () => {
    try { return textResult(await bridge.clientStatus()); } catch (error) { return errorResult(error); }
  },
);

server.registerTool(
  "mc_chat",
  {
    description: "Read new Minecraft HUD chat messages, including tellraw output. Pass the previous next_index to poll incrementally.",
    inputSchema: { since: z.number().int().min(0).optional(), limit: z.number().int().min(1).max(256).optional() },
  },
  async (input) => {
    try { return textResult(await bridge.chat(input.since ?? 0, input.limit ?? 64)); } catch (error) { return errorResult(error); }
  },
);

server.registerTool(
  "mc_input_sequence",
  {
    description: "Execute a bounded client-side sequence of keys, camera look, GUI cursor movement, clicks, double-clicks, drags, and waits.",
    inputSchema: inputSequenceRequestSchema.shape,
  },
  async (input) => {
    try { return textResult(await bridge.inputSequence(inputSequenceRequestSchema.parse(input))); } catch (error) { return errorResult(error); }
  },
);

server.registerTool(
  "mc_screenshot",
  {
    description: "Capture the current Minecraft client viewport as a JPEG and return it for visual inspection.",
    inputSchema: {},
  },
  async () => {
    try { return imageResult(await bridge.screenshot()); } catch (error) { return errorResult(error); }
  },
);

server.registerTool(
  "mc_debug_events",
  {
    description: "Read new structured #! debug outputs. Pass the previous next_id to poll incrementally.",
    inputSchema: { since: z.number().int().min(0).optional(), limit: z.number().int().min(1).max(256).optional() },
  },
  async (input) => {
    try { return textResult(await bridge.debugEvents(input.since ?? 0, input.limit ?? 64)); } catch (error) { return errorResult(error); }
  },
);

server.registerTool(
  "mc_debug_diagnostics",
  {
    description: "Read new #! reload warnings and rate-limited runtime diagnostics with source locations.",
    inputSchema: { since: z.number().int().min(0).optional(), limit: z.number().int().min(1).max(256).optional() },
  },
  async (input) => {
    try { return textResult(await bridge.debugDiagnostics(input.since ?? 0, input.limit ?? 64)); } catch (error) { return errorResult(error); }
  },
);

server.registerTool(
  "mc_command_validate",
  {
    description: "Validate a Minecraft command against the live Brigadier command tree.",
    inputSchema: commandRequestSchema.shape,
  },
  async (input) => {
    try {
      return textResult(await bridge.validate(commandRequestSchema.parse(input)));
    } catch (error) {
      return errorResult(error);
    }
  },
);

server.registerTool(
  "mc_command_run",
  {
    description: "Execute one Minecraft command on the server thread and return structured feedback.",
    inputSchema: commandRequestSchema.shape,
  },
  async (input) => {
    try {
      return textResult(await bridge.run(commandRequestSchema.parse(input)));
    } catch (error) {
      return errorResult(error);
    }
  },
);

server.registerTool(
  "mc_command_batch",
  {
    description: "Execute up to 100 Minecraft commands sequentially with per-command results.",
    inputSchema: batchRequestSchema.shape,
  },
  async (input) => {
    try {
      return textResult(await bridge.batch(batchRequestSchema.parse(input)));
    } catch (error) {
      return errorResult(error);
    }
  },
);

const transport = new StdioServerTransport();
await server.connect(transport);
