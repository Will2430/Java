// Every call to the Spring API lives here, so components never build URLs or parse errors.
// Error bodies come from GlobalExceptionHandler and carry a `message` field.
import { getAccessToken, login } from './auth.js';

// A failed call. `status` is undefined when no response came back at all (timeout, dropped
// connection): the server may or may not have done the work, so the outcome is unknown.
export class ApiError extends Error {
  constructor(message, status, retryAfterMs) {
    super(message);
    this.status = status;
    this.retryAfterMs = retryAfterMs;
  }
}

async function request(url, options = {}) {
  // Every API call carries the user's access token; the server derives "who" from it, never from the body.
  const token = await getAccessToken();
  let res;
  try {
    res = await fetch(url, { ...options, headers: { ...options.headers, Authorization: `Bearer ${token}` } });
  } catch (err) {
    if (options.signal?.aborted) throw err;
    throw new ApiError('Could not reach the server.', undefined);
  }
  if (res.status === 401) {
    await login(); // token rejected (e.g. Keycloak restarted): log in again; this navigates away
  }
  // Not every failure has a JSON body (e.g. a proxy 502 while the API is down).
  const body = await res.json().catch(() => ({}));
  if (!res.ok) {
    const retryAfterSeconds = Number(res.headers.get('Retry-After'));
    throw new ApiError(body.message || `Request failed with status ${res.status}`, res.status,
      retryAfterSeconds > 0 ? retryAfterSeconds * 1000 : undefined);
  }
  return body;
}

// Resolves after `ms`, or rejects straight away if `signal` is aborted.
export function sleep(ms, signal) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(resolve, ms);
    signal?.addEventListener('abort', () => {
      clearTimeout(timer);
      reject(signal.reason);
    }, { once: true });
  });
}

const RETRY_BASE_MS = 500;
const RETRY_CAP_MS = 8000;
const MAX_RETRIES = 5;

// No response, 409 (still in progress), 429 (slow down) and 5xx may succeed next time.
// Any other 4xx means the request itself is wrong, and repeating it won't change the answer.
function isRetryable(err) {
  return err instanceof ApiError &&
    (err.status === undefined || err.status === 409 || err.status === 429 || err.status >= 500);
}

// Exponential backoff with "full jitter": wait a random time in [0, 0.5s), [0, 1s), [0, 2s)...
// capped at 8s. The randomness spreads out clients that failed at the same moment, so they
// don't all hit the server again in lockstep. A server-sent Retry-After is the minimum wait.
// Only use this for idempotent calls: a retry must not be able to do the work twice.
async function withRetry(call) {
  for (let attempt = 0; ; attempt++) {
    try {
      return await call();
    } catch (err) {
      if (attempt >= MAX_RETRIES || !isRetryable(err)) throw err;
      const backoff = Math.random() * Math.min(RETRY_CAP_MS, RETRY_BASE_MS * 2 ** attempt);
      await sleep(Math.max(backoff, err.retryAfterMs ?? 0));
    }
  }
}

// POST /api/captures returns 202 immediately (status: PENDING). OCR runs later in the
// worker module, so the client has to come back and ask GET /api/captures/{id}.
export function uploadCapture(file, signal) {
  const formData = new FormData();
  formData.append('image', file, file.name || 'pasted-image.png');
  return request('/api/captures', { method: 'POST', body: formData, signal });
}

const POLL_INTERVAL_MS = 1500;

export async function pollCaptureUntilDone(id, signal) {
  for (;;) {
    await sleep(POLL_INTERVAL_MS, signal);
    const capture = await request(`/api/captures/${id}`, { signal });
    if (capture.status !== 'PENDING') {
      return capture;
    }
  }
}

export function listCaptures() {
  return request('/api/captures?size=20&sort=createdAt,desc');
}

// Safe to retry: every attempt sends the same Idempotency-Key, so the server creates the payment once.
export function createPayment(idempotencyKey, body) {
  return withRetry(() => request('/api/payments', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(body),
  }));
}

export function getPayment(id, signal) {
  return request(`/api/payments/${id}`, { signal });
}
