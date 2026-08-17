import { useEffect, useRef } from "react";
import Message from "./Message";
import TypingIndicator from "./TypingIndicator";
import styles from "./ChatWindow.module.css";

/** The scrollable conversation: the message list, the loading state, and auto-scroll. */
export default function ChatWindow({ messages, loading }) {
  const endRef = useRef(null);

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, loading]);

  const empty = messages.length === 0 && !loading;

  return (
    <div className={styles.window}>
      <div className={styles.thread}>
        {empty && (
          <div className={styles.empty}>
            <p className={styles.emptyTitle}>Ask about the posts</p>
            <p className={styles.emptyHint}>
              Try “what are people talking about?” or “tell me about the football posts”.
            </p>
          </div>
        )}

        {messages.map((m) => (
          <Message
            key={m.id}
            role={m.role}
            text={m.text}
            sources={m.sources}
            researchLog={m.researchLog}
            error={m.error}
          />
        ))}

        {loading && <TypingIndicator />}
        <div ref={endRef} />
      </div>
    </div>
  );
}
