import { useRef, useState } from "react";
import styles from "./Composer.module.css";

/**
 * The input bar. Enter sends; Shift+Enter inserts a new line. The field auto-grows
 * with its content up to a max height.
 */
export default function Composer({ onSend, disabled }) {
  const [text, setText] = useState("");
  const areaRef = useRef(null);

  const submit = () => {
    if (!text.trim() || disabled) return;
    onSend(text);
    setText("");
    if (areaRef.current) areaRef.current.style.height = "auto";
  };

  const onKeyDown = (e) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault(); // Enter sends; Shift+Enter falls through to a new line
      submit();
    }
  };

  const onChange = (e) => {
    setText(e.target.value);
    const el = e.target;
    el.style.height = "auto";
    el.style.height = `${Math.min(el.scrollHeight, 160)}px`;
  };

  return (
    <form
      className={styles.composer}
      onSubmit={(e) => {
        e.preventDefault();
        submit();
      }}
    >
      <div className={styles.inner}>
        <textarea
          ref={areaRef}
          className={styles.input}
          value={text}
          onChange={onChange}
          onKeyDown={onKeyDown}
          placeholder="Ask a question about the posts…  (Shift+Enter for a new line)"
          rows={1}
          disabled={disabled}
        />
        <button className={styles.send} type="submit" disabled={disabled || !text.trim()} aria-label="Send">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path d="M4 12l15-7-6 7 6 7-15-7z" fill="currentColor" />
          </svg>
        </button>
      </div>
      <p className={styles.hint}>
        High-level questions use the overview · specific ones search the clusters and posts
      </p>
    </form>
  );
}
