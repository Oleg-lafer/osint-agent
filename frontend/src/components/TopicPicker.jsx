import { useEffect, useRef, useState } from "react";
import styles from "./TopicPicker.module.css";

/**
 * Lets the user scope the conversation to chosen topics.
 * Controlled: `selected` is an array of clusterIds; `onChange` replaces it.
 */
export default function TopicPicker({ topics, selected, onChange }) {
  const [open, setOpen] = useState(false);
  const wrapRef = useRef(null);

  // Close the dropdown when clicking anywhere outside it.
  useEffect(() => {
    if (!open) return;
    const onClick = (e) => {
      if (wrapRef.current && !wrapRef.current.contains(e.target)) setOpen(false);
    };
    document.addEventListener("mousedown", onClick);
    return () => document.removeEventListener("mousedown", onClick);
  }, [open]);

  const toggle = (id) =>
    onChange(selected.includes(id) ? selected.filter((x) => x !== id) : [...selected, id]);

  const label = selected.length === 0 ? "All topics" : `${selected.length} selected`;

  return (
    <div className={styles.wrap} ref={wrapRef}>
      <button
        type="button"
        className={styles.trigger}
        onClick={() => setOpen((o) => !o)}
        disabled={topics.length === 0}
      >
        {label}
        <span className={styles.caret} aria-hidden="true">▾</span>
      </button>

      {open && (
        <div className={styles.panel}>
          <div className={styles.head}>
            <span>Discuss which topics?</span>
            {selected.length > 0 && (
              <button type="button" className={styles.clear} onClick={() => onChange([])}>
                Clear
              </button>
            )}
          </div>
          <div className={styles.list}>
            {topics.map((t) => (
              <label key={t.clusterId} className={styles.item}>
                <input
                  type="checkbox"
                  checked={selected.includes(t.clusterId)}
                  onChange={() => toggle(t.clusterId)}
                />
                <span className={styles.what}>{t.what}</span>
                <span className={styles.count}>{t.postCount}</span>
              </label>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
