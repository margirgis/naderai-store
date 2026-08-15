/**
 * ProviderApiClient — secure server-side client for Reseller API.
 * API Key is read from Deno.env only; never logged, never returned to callers.
 */

export interface ProviderResponse<T = unknown> {
  success: boolean;
  data?: T;
  error?: {
    code?: string;
    message?: string;
  };
  meta?: {
    request_id?: string;
    [key: string]: unknown;
  };
}

export interface ProviderCallResult<T = unknown> {
  ok: boolean;
  data?: T;
  httpStatus: number;
  requestId?: string;
  responseTimeMs: number;
  errorCode?: string;
  errorMessage?: string;
}

const TIMEOUT_MS = 15_000;
const MAX_RETRIES_ON_NETWORK_ERROR = 1;

function getConfig() {
  const apiKey = Deno.env.get('PROVIDER_API_KEY');
  const baseUrl =
    Deno.env.get('PROVIDER_API_BASE_URL') ?? 'https://api.geminioffer.web.id/api/v1';
  const environment = Deno.env.get('PROVIDER_ENVIRONMENT') ?? 'sandbox';
  return { apiKey, baseUrl, environment };
}

function sanitizeErrorMessage(msg: string): string {
  // Remove any potential key fragments from error messages
  return msg.replace(/Bearer\s+\S+/gi, 'Bearer [REDACTED]');
}

/** Core HTTP call — accepts explicit apiKey override for validation flows */
export async function providerCallCore<T = unknown>(
  endpoint: string,
  method: 'GET' | 'POST' = 'GET',
  body?: unknown,
  overrideKey?: string,
): Promise<ProviderCallResult<T>> {
  const { apiKey: envKey, baseUrl } = getConfig();
  const apiKey = overrideKey ?? envKey;
  const start = Date.now();

  if (!apiKey) {
    return {
      ok: false,
      httpStatus: 0,
      responseTimeMs: 0,
      errorCode: 'NO_API_KEY',
      errorMessage: 'Provider API key not configured in environment variables.',
    };
  }

  const url = `${baseUrl.replace(/\/$/, '')}/${endpoint.replace(/^\//, '')}`;

  let attempts = 0;
  while (attempts <= MAX_RETRIES_ON_NETWORK_ERROR) {
    attempts++;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);

    try {
      const res = await fetch(url, {
        method,
        headers: {
          Authorization: `Bearer ${apiKey}`,
          'Content-Type': 'application/json',
          Accept: 'application/json',
        },
        body: body ? JSON.stringify(body) : undefined,
        signal: controller.signal,
      });
      clearTimeout(timer);

      const responseTimeMs = Date.now() - start;
      const httpStatus = res.status;

      // Handle rate limit
      if (httpStatus === 429) {
        const retryAfter = res.headers.get('Retry-After');
        return {
          ok: false,
          httpStatus,
          responseTimeMs,
          errorCode: 'RATE_LIMITED',
          errorMessage: retryAfter
            ? `Rate limited. Retry after ${retryAfter} seconds.`
            : 'Rate limited by provider. Please wait before retrying.',
        };
      }

      // Handle auth errors — no retry
      if (httpStatus === 401 || httpStatus === 403) {
        return {
          ok: false,
          httpStatus,
          responseTimeMs,
          errorCode: httpStatus === 401 ? 'AUTH_INVALID' : 'AUTH_FORBIDDEN',
          errorMessage:
            httpStatus === 401
              ? 'Provider authentication failed. Check API key configuration.'
              : 'Access forbidden by provider.',
        };
      }

      // Parse response body
      let envelope: ProviderResponse<T>;
      try {
        envelope = await res.json();
      } catch {
        return {
          ok: false,
          httpStatus,
          responseTimeMs,
          errorCode: 'PARSE_ERROR',
          errorMessage: 'Failed to parse provider response.',
        };
      }

      const requestId = envelope.meta?.request_id as string | undefined;

      // HTTP 200 does not guarantee business success — check envelope
      if (httpStatus === 200 && envelope.success === true) {
        return {
          ok: true,
          data: envelope.data,
          httpStatus,
          requestId,
          responseTimeMs,
        };
      }

      // Business-level failure even with 200
      const errCode = envelope.error?.code ?? String(httpStatus);
      const errMsg = sanitizeErrorMessage(
        envelope.error?.message ?? `Provider returned error: HTTP ${httpStatus}`
      );

      return {
        ok: false,
        httpStatus,
        requestId,
        responseTimeMs,
        errorCode: errCode,
        errorMessage: errMsg,
      };
    } catch (err) {
      clearTimeout(timer);
      const responseTimeMs = Date.now() - start;

      if ((err as Error).name === 'AbortError') {
        return {
          ok: false,
          httpStatus: 0,
          responseTimeMs,
          errorCode: 'TIMEOUT',
          errorMessage: `Request timed out after ${TIMEOUT_MS / 1000}s.`,
        };
      }

      // Network error — retry once
      if (attempts <= MAX_RETRIES_ON_NETWORK_ERROR) continue;

      return {
        ok: false,
        httpStatus: 0,
        responseTimeMs,
        errorCode: 'NETWORK_ERROR',
        errorMessage: 'Network error connecting to provider.',
      };
    }
  }

  return {
    ok: false,
    httpStatus: 0,
    responseTimeMs: Date.now() - start,
    errorCode: 'UNKNOWN',
    errorMessage: 'Unknown error.',
  };
}

/** Standard call using env key */
export function providerCall<T = unknown>(
  endpoint: string,
  method: 'GET' | 'POST' = 'GET',
  body?: unknown,
): Promise<ProviderCallResult<T>> {
  return providerCallCore<T>(endpoint, method, body);
}

/** Call using an explicit key (for validation only — never store result) */
export function providerCallWithKey<T = unknown>(
  endpoint: string,
  method: 'GET' | 'POST' = 'GET',
  candidateKey: string,
): Promise<ProviderCallResult<T>> {
  return providerCallCore<T>(endpoint, method, undefined, candidateKey);
}

export function getEnvironment(): string {
  return Deno.env.get('PROVIDER_ENVIRONMENT') ?? 'sandbox';
}

export function getBaseUrl(): string {
  return Deno.env.get('PROVIDER_API_BASE_URL') ?? 'https://api.geminioffer.web.id/api/v1';
}

export function hasApiKey(): boolean {
  return !!Deno.env.get('PROVIDER_API_KEY');
}

/** Return masked key prefix (first 12 chars + redaction) — NEVER full key */
export function getMaskedKeyPrefix(): string {
  const k = Deno.env.get('PROVIDER_API_KEY') ?? '';
  if (!k) return '';
  const visible = k.slice(0, 12);
  return `${visible}••••••••••••••••••`;
}
