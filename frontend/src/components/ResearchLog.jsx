import { useState } from "react";
import styles from "./ResearchLog.module.css";

/**
 * The "how I investigated this" panel under an agent answer — a collapsible timeline of the
 * plain-language steps the agent took, so the answer isn't a black box.
 */
export default function ResearchLog({ steps }) {
  const [open, setOpen] = useState(false);
  if (!steps?.length) return null;

  return (
    <div className={styles.wrap}>
      <button
        type="button"
        className={styles.toggle}
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
      >
        <span className={styles.icon} aria-hidden="true">{open ? "▾" : "▸"}</span>
        How I investigated this
      </button>

      <div className={`${styles.collapse} ${open ? styles.open : ""}`}>
        <div className={styles.inner}>
          <ol className={styles.steps}>
            {steps.map((s, i) => (
              <li key={i} className={styles.step}>
                <span className={styles.dot} aria-hidden="true" />
                <div className={styles.title}>{s.title}</div>
                <div className={styles.detail}>{s.detail}</div>
              </li>
            ))}
          </ol>
        </div>
      </div>
    </div>
  );
}
