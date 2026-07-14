import { z } from "zod";

export const commandRequestSchema = z.object({
  command: z.string().min(1).max(32_000),
  allow_dangerous: z.boolean().optional(),
});

export const batchRequestSchema = z.object({
  commands: z.array(z.string().min(1).max(32_000)).min(1).max(100),
  stop_on_error: z.boolean().optional(),
  allow_dangerous: z.boolean().optional(),
});

export const inputStepSchema = z.union([
  z.object({ type: z.literal("key"), key: z.string().min(1).max(32), action: z.enum(["press", "release", "hold"]).default("press"), duration_ms: z.number().int().min(0).max(30_000).optional() }),
  z.object({ type: z.literal("mouse"), button: z.enum(["left", "right", "middle"]), action: z.enum(["press", "release", "click", "double_click", "hold"]).default("click"), duration_ms: z.number().int().min(0).max(30_000).optional(), interval_ms: z.number().int().min(20).max(1_000).optional() }),
  z.object({ type: z.literal("cursor"), action: z.literal("move").default("move"), x: z.number().min(0).max(100_000), y: z.number().min(0).max(100_000), coordinate_space: z.enum(["normalized", "pixel"]).default("normalized") }).superRefine((value, context) => {
    if (value.coordinate_space === "normalized" && (value.x > 1 || value.y > 1)) context.addIssue({ code: "custom", message: "normalized cursor coordinates must be between 0 and 1" });
  }),
  z.object({ type: z.literal("cursor"), action: z.literal("move_relative"), dx: z.number().min(-100_000).max(100_000), dy: z.number().min(-100_000).max(100_000), coordinate_space: z.literal("pixel").default("pixel") }),
  z.object({ type: z.literal("drag"), button: z.enum(["left", "right", "middle"]).default("left"), from_x: z.number().min(0).max(100_000), from_y: z.number().min(0).max(100_000), to_x: z.number().min(0).max(100_000), to_y: z.number().min(0).max(100_000), duration_ms: z.number().int().min(0).max(30_000).default(500), coordinate_space: z.enum(["normalized", "pixel"]).default("normalized") }).superRefine((value, context) => {
    if (value.coordinate_space === "normalized" && Math.max(value.from_x, value.from_y, value.to_x, value.to_y) > 1) context.addIssue({ code: "custom", message: "normalized drag coordinates must be between 0 and 1" });
  }),
  z.object({ type: z.literal("look"), yaw_delta: z.number().min(-1800).max(1800).optional(), pitch_delta: z.number().min(-1800).max(1800).optional() }),
  z.object({ type: z.literal("wait"), duration_ms: z.number().int().min(0).max(30_000) }),
]);

export const inputSequenceRequestSchema = z.object({
  steps: z.array(inputStepSchema).min(1).max(200),
  total_timeout_ms: z.number().int().min(1_000).max(120_000).optional(),
});

export type InputStep = z.infer<typeof inputStepSchema>;
export type InputSequenceRequest = z.infer<typeof inputSequenceRequestSchema>;

export type CommandRequest = z.infer<typeof commandRequestSchema>;
export type BatchRequest = z.infer<typeof batchRequestSchema>;

export interface BridgeStatus {
  ok: boolean;
  protocol: string;
  mod: string;
  port: number;
  connected: boolean;
  game_version?: string;
  capabilities?: string[];
}

export interface CommandResult {
  ok: boolean;
  command: string;
  game_version?: string;
  valid?: boolean;
  result?: number;
  result_reported?: boolean;
  callback_count?: number;
  success_count?: number;
  failure_count?: number;
  feedback?: string[];
  feedback_truncated?: boolean;
  error?: string;
  error_code?: string;
  cursor?: number;
  duration_ms?: number;
}

export interface BatchResult {
  ok: boolean;
  game_version?: string;
  completed: number;
  requested: number;
  results: CommandResult[];
  error?: string;
}

export interface ClientStatus {
  ok: boolean;
  protocol: string;
  connected: boolean;
  focused: boolean;
  mouse_grabbed: boolean;
  game_version?: string;
  capabilities?: string[];
}

export interface ChatMessage {
  index: number;
  added_time: number;
  text: string;
  source?: string;
  truncated: boolean;
}

export interface ChatResult {
  ok: boolean;
  messages: ChatMessage[];
  next_index: number;
  latest_index: number;
  oldest_index: number;
  dropped: boolean;
  more: boolean;
  returned_count: number;
  response_bytes: number;
}

export interface InputSequenceResult {
  ok: boolean;
  completed_steps: number;
  requested_steps: number;
  duration_ms: number;
  error?: string;
}

export interface ScreenshotResult {
  ok: boolean;
  path?: string;
  width?: number;
  height?: number;
  mime_type?: string;
  image_base64?: string;
  error?: string;
}

export interface DebugEvent {
  id: number;
  timestamp: number;
  function_id: string;
  line: number;
  text: string;
  component?: unknown;
  component_omitted: boolean;
  function_stack: string[];
  truncated: boolean;
  error_code?: string;
  error?: string;
}

export interface DebugEventsResult {
  ok: boolean;
  events: DebugEvent[];
  next_id: number;
  latest_id: number;
  oldest_id: number;
  dropped: boolean;
  more: boolean;
  returned_count: number;
  response_bytes: number;
}

export interface DebugDiagnostic {
  id: number;
  timestamp: number;
  phase: "reload" | "runtime" | string;
  code: string;
  function_id: string;
  line: number;
  column: number;
  message: string;
  source: string;
}

export interface DebugDiagnosticsResult {
  ok: boolean;
  diagnostics: DebugDiagnostic[];
  next_id: number;
  latest_id: number;
  oldest_id: number;
  dropped: boolean;
  more: boolean;
  returned_count: number;
  response_bytes: number;
}
