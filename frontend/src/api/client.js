// The one place that knows how to talk to the Java backend.
// Override with a VITE_API_URL env var; defaults to the local server's port.
const API_BASE = import.meta.env.VITE_API_URL ?? "http://localhost:7070";

/** GET /status — is the dataset processed, and how big is it? */
export async function getStatus(pipelineRunId = null) {
  const query = pipelineRunId == null ? "" : `?pipelineRunId=${encodeURIComponent(pipelineRunId)}`;
  const res = await fetch(`${API_BASE}/status${query}`);
  if (!res.ok) throw new Error(`status request failed (${res.status})`);
  return res.json();
}

/** GET /runs — completed preprocessing runs that can back a chat session. */
export async function getRuns() {
  const res = await fetch(`${API_BASE}/runs`);
  if (!res.ok) throw new Error(`runs request failed (${res.status})`);
  return res.json();
}

/**
 * POST /chat — send a question, get back { answer, sources, elapsedMs }.
 * pipelineRunId selects the immutable knowledge snapshot.
 */
export async function sendChat(query, sessionId = null, pipelineRunId = null) {
  const res = await fetch(`${API_BASE}/chat`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ query, sessionId, pipelineRunId }),
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.error ?? `request failed (${res.status})`);
  }
  return res.json();
}
