import Sources from "./Sources";
import ResearchLog from "./ResearchLog";
import styles from "./Message.module.css";

/** One chat bubble — a user question (right) or an agent answer (left). */
export default function Message({ role, text, sources, researchLog, error }) {
  const isUser = role === "user";
  return (
    <div className={`${styles.row} ${isUser ? styles.user : styles.agent}`}>
      <div
        className={[
          styles.bubble,
          isUser ? styles.userBubble : styles.agentBubble,
          error ? styles.error : "",
        ].join(" ")}
      >
        <p className={styles.text}>{text}</p>
        {!isUser && <Sources sources={sources} />}
        {!isUser && !error && <ResearchLog steps={researchLog} />}
      </div>
    </div>
  );
}
