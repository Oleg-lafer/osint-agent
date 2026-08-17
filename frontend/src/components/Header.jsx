import StatusIndicator from "./StatusIndicator";
import TopicPicker from "./TopicPicker";
import styles from "./Header.module.css";

export default function Header({ topics, selectedTopics, onSelectTopics }) {
  return (
    <header className={styles.header}>
      <div className={styles.brand}>
        <span className={styles.logo} aria-hidden="true">◈</span>
        <div className={styles.titles}>
          <span className={styles.name}>Multi-Post Agent</span>
          <span className={styles.sub}>Ask the knowledge base</span>
        </div>
      </div>
      <div className={styles.right}>
        <TopicPicker topics={topics} selected={selectedTopics} onChange={onSelectTopics} />
        <StatusIndicator />
      </div>
    </header>
  );
}
