import styles from "./TypingIndicator.module.css";

/** The "agent is synthesizing" loading state. */
export default function TypingIndicator() {
  return (
    <div className={styles.row}>
      <div className={styles.bubble} aria-label="Agent is thinking">
        <span className={styles.dot} />
        <span className={styles.dot} />
        <span className={styles.dot} />
      </div>
    </div>
  );
}
