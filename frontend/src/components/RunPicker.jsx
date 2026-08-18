import styles from "./RunPicker.module.css";

function label(run) {
  const date = run.completedAt ? new Date(run.completedAt).toLocaleString() : "Local data";
  return `Run ${run.pipelineRunId} · ${date} · ${run.postCount} posts`;
}

/** Selects the immutable preprocessing snapshot for a new conversation. */
export default function RunPicker({ runs, selectedRunId, onChange, disabled }) {
  return (
    <label className={styles.field}>
      <span className={styles.label}>Knowledge run</span>
      <select
        className={styles.select}
        value={selectedRunId ?? ""}
        onChange={(event) => onChange(Number(event.target.value))}
        disabled={disabled || runs.length === 0}
        aria-label="Preprocessing run"
        title={disabled ? "The run is fixed after the conversation starts" : undefined}
      >
        {runs.length === 0 && <option value="">No runs available</option>}
        {runs.map((run) => (
          <option key={run.pipelineRunId} value={run.pipelineRunId}>
            {label(run)}
          </option>
        ))}
      </select>
    </label>
  );
}
