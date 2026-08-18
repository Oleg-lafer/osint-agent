import StatusIndicator from "./StatusIndicator";
import RunPicker from "./RunPicker";
import styles from "./Header.module.css";

export default function Header({
  runs,
  selectedRunId,
  onSelectRun,
  runSelectionLocked,
  runsError,
}) {
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
        <RunPicker
          runs={runs}
          selectedRunId={selectedRunId}
          onChange={onSelectRun}
          disabled={runSelectionLocked || runsError}
        />
        <StatusIndicator pipelineRunId={selectedRunId} />
      </div>
    </header>
  );
}
