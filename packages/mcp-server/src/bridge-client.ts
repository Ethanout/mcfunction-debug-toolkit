import type { BatchRequest, BatchResult, BridgeStatus, ChatResult, ClientStatus, CommandRequest, CommandResult, DebugDiagnosticsResult, DebugEventsResult, InputSequenceRequest, InputSequenceResult, ScreenshotResult } from "@mc-command/protocol";

export class BridgeError extends Error {
  constructor(public readonly status: number, message: string, public readonly details?: unknown) {
    super(message);
    this.name = "BridgeError";
  }
}

export class BridgeClient {
  private readonly baseUrl = (process.env.MC_COMMAND_BASE_URL ?? "http://127.0.0.1:8766").replace(/\/$/, "");
  private readonly clientBaseUrl = (process.env.MC_COMMAND_CLIENT_BASE_URL ?? "http://127.0.0.1:8767").replace(/\/$/, "");
  private readonly token = process.env.MC_COMMAND_TOKEN ?? "";

  async status(): Promise<BridgeStatus> {
    return this.request<BridgeStatus>("/v1/status", false);
  }

  async validate(request: CommandRequest): Promise<CommandResult> {
    return this.request<CommandResult>("/v1/command/validate", true, request);
  }

  async run(request: CommandRequest): Promise<CommandResult> {
    return this.request<CommandResult>("/v1/command/run", true, request);
  }

  async batch(request: BatchRequest): Promise<BatchResult> {
    return this.request<BatchResult>("/v1/command/batch", true, request);
  }

  async clientStatus(): Promise<ClientStatus> {
    return this.request< ClientStatus>("/v1/client/status", false, undefined, this.clientBaseUrl);
  }

  async chat(since = 0, limit = 64): Promise<ChatResult> {
    return this.request<ChatResult>(`/v1/client/chat?since=${Math.max(0, Math.floor(since))}&limit=${Math.max(1, Math.floor(limit))}`, true, undefined, this.clientBaseUrl);
  }

  async inputSequence(request: InputSequenceRequest): Promise<InputSequenceResult> {
    const timeoutMs = (request.total_timeout_ms ?? 120_000) + 2_000;
    return this.request<InputSequenceResult>("/v1/client/input", true, request, this.clientBaseUrl, timeoutMs);
  }

  async screenshot(): Promise<ScreenshotResult> {
    return this.request<ScreenshotResult>("/v1/client/screenshot", true, {}, this.clientBaseUrl, 32_000);
  }

  async debugEvents(since = 0, limit = 64): Promise<DebugEventsResult> {
    return this.request<DebugEventsResult>(`/v1/debug/events?since=${Math.max(0, Math.floor(since))}&limit=${Math.max(1, Math.floor(limit))}`, true);
  }

  async debugDiagnostics(since = 0, limit = 64): Promise<DebugDiagnosticsResult> {
    return this.request<DebugDiagnosticsResult>(`/v1/debug/diagnostics?since=${Math.max(0, Math.floor(since))}&limit=${Math.max(1, Math.floor(limit))}`, true);
  }

  private async request<T>(
    path: string,
    authenticated: boolean,
    body?: unknown,
    baseUrl = this.baseUrl,
    timeoutMs = 15_000,
  ): Promise<T> {
    const headers: Record<string, string> = { Accept: "application/json" };
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (authenticated && this.token) headers.Authorization = `Bearer ${this.token.trim()}`;
    const response = await fetch(`${baseUrl}${path}`, {
      method: body === undefined ? "GET" : "POST",
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(timeoutMs),
    });
    const text = await response.text();
    let payload: T | { error?: string };
    try {
      payload = JSON.parse(text) as T | { error?: string };
    } catch {
      throw new BridgeError(response.status, `bridge returned non-JSON response (${response.status})`);
    }
    if (!response.ok) {
      const detail = payload as {
        error?: string;
        error_code?: string;
        request_id?: string;
        outcome?: string;
        retry_safe?: boolean;
      };
      const context = [
        detail.error_code ? `code=${detail.error_code}` : undefined,
        detail.request_id ? `request_id=${detail.request_id}` : undefined,
        detail.outcome ? `outcome=${detail.outcome}` : undefined,
        detail.retry_safe !== undefined ? `retry_safe=${detail.retry_safe}` : undefined,
      ].filter(Boolean).join(", ");
      const message = detail.error ?? `bridge error ${response.status}`;
      throw new BridgeError(response.status, context ? `${message} (${context})` : message, payload);
    }
    return payload as T;
  }
}
