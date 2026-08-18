import { useCallback, useState } from "react";
import { sendChat } from "../api/client";

let nextId = 0;

/**
 * Holds the conversation and the "waiting for the agent" flag, and exposes send().
 * Keeping this here means the components stay purely about rendering.
 */
export function useChat() {
  const [messages, setMessages] = useState([]);
  const [loading, setLoading] = useState(false);
  const [sessionId, setSessionId] = useState(null);

  const send = useCallback(
    async (raw, topicIds = []) => {
      const question = raw.trim();
      if (!question || loading) return;

      setMessages((prev) => [...prev, { id: nextId++, role: "user", text: question }]);
      setLoading(true);
      try {
        const result = await sendChat(question, topicIds, sessionId);
        const { answer, sources, researchLog } = result;
        setSessionId(result.sessionId ?? null);
        setMessages((prev) => [
          ...prev,
          { id: nextId++, role: "agent", text: answer, sources, researchLog },
        ]);
      } catch (err) {
        setMessages((prev) => [
          ...prev,
          { id: nextId++, role: "agent", text: `Something went wrong: ${err.message}`, error: true },
        ]);
      } finally {
        setLoading(false);
      }
    },
    [loading, sessionId],
  );

  return { messages, loading, send };
}
