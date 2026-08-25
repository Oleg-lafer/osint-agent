import { Fragment } from "react";
import Sources from "./Sources";
import ResearchLog from "./ResearchLog";
import styles from "./Message.module.css";

function renderBoldMarkdown(text) {
  const parts = [];
  const pattern = /\*\*(.+?)\*\*/gs;
  let cursor = 0;
  let match;

  while ((match = pattern.exec(text)) !== null) {
    parts.push(text.slice(cursor, match.index));
    parts.push(<strong key={match.index}>{match[1]}</strong>);
    cursor = pattern.lastIndex;
  }

  parts.push(text.slice(cursor));
  return parts;
}

function renderMarkdown(text) {
  return text.split("\n").map((line, index, lines) => {
    const heading = line.match(/^###\s+(.+)$/);
    if (heading) {
      return (
        <h3 className={styles.heading} key={index}>
          {renderBoldMarkdown(heading[1])}
        </h3>
      );
    }

    return (
      <Fragment key={index}>
        {renderBoldMarkdown(line)}
        {index < lines.length - 1 && <br />}
      </Fragment>
    );
  });
}

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
        <div className={styles.text}>{renderMarkdown(text)}</div>
        {!isUser && <Sources sources={sources} />}
        {!isUser && !error && <ResearchLog steps={researchLog} />}
      </div>
    </div>
  );
}
