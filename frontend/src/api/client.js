// The one place that knows how to talk to the Java backend.
// Override with a VITE_API_URL env var; defaults to the local server's port.
const API_BASE = import.meta.env.VITE_API_URL ?? "http://localhost:7070";

/** GET /status — is the dataset processed, and how big is it? */
export async function getStatus() {
  const res = await fetch(`${API_BASE}/status`);
  if (!res.ok) throw new Error(`status request failed (${res.status})`);
  return res.json();
}

/** GET /topics — the list of topics, for the picker. */
export async function getTopics() {
  const res = await fetch(`${API_BASE}/topics`);
  if (!res.ok) throw new Error(`topics request failed (${res.status})`);
  return res.json();
}

/**
 * POST /chat — send a question, get back { answer, sources, elapsedMs }.
 * topicIds (optional) scopes the answer to the topics the user picked.
 */
export async function sendChat(query, topicIds = [], sessionId = null) {
  const res = await fetch(`${API_BASE}/chat`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ query, topicIds, sessionId }),
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.error ?? `request failed (${res.status})`);
  }
  return res.json();
}
