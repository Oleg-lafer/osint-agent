import styles from "./Sources.module.css";

/** The "Sources" chips under an agent answer — the generated clusters it drew from. */
export default function Sources({ sources }) {
  if (!sources?.length) return null;
  return (
    <div className={styles.sources}>
      <span className={styles.label}>Sources</span>
      {sources.map((s) => (
        <span
          key={s.clusterId}
          className={styles.chip}
          title={`${s.postCount} posts · similarity ${s.score}`}
        >
          {s.what}
        </span>
      ))}
    </div>
  );
}
