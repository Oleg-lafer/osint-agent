import { useEffect, useState } from "react";
import { getStatus } from "../api/client";
import styles from "./StatusIndicator.module.css";

/** The data-source indicator: confirms the CSV has been processed and summarized. */
export default function StatusIndicator({ pipelineRunId }) {
  const [status, setStatus] = useState(null);
  const [offline, setOffline] = useState(false);

  useEffect(() => {
    if (pipelineRunId == null) return;
    setStatus(null);
    setOffline(false);
    getStatus(pipelineRunId).then(setStatus).catch(() => setOffline(true));
  }, [pipelineRunId]);

  if (offline) {
    return (
      <span className={`${styles.pill} ${styles.offline}`}>
        <span className={styles.dot} /> Backend offline
      </span>
    );
  }
  if (!status) {
    return (
      <span className={`${styles.pill} ${styles.connecting}`}>
        <span className={styles.dot} /> Connecting…
      </span>
    );
  }
  return (
    <span className={styles.pill}>
      <span className={styles.dot} />
      Dataset processed
      <span className={styles.meta}>
        · {status.totalPosts.toLocaleString()} posts · {status.topicCount} topics
      </span>
    </span>
  );
}
