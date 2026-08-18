import { useEffect, useState } from "react";
import Header from "./components/Header";
import ChatWindow from "./components/ChatWindow";
import Composer from "./components/Composer";
import { useChat } from "./hooks/useChat";
import { getRuns } from "./api/client";
import styles from "./App.module.css";

export default function App() {
  const { messages, loading, send } = useChat();
  const [runs, setRuns] = useState([]);
  const [selectedRunId, setSelectedRunId] = useState(null);
  const [runsError, setRunsError] = useState(false);

  useEffect(() => {
    getRuns()
      .then((available) => {
        setRuns(available);
        const initial = available.find((run) => run.isDefault) ?? available[0];
        setSelectedRunId(initial?.pipelineRunId ?? null);
      })
      .catch(() => setRunsError(true));
  }, []);

  return (
    <div className={styles.app}>
      <Header
        runs={runs}
        selectedRunId={selectedRunId}
        onSelectRun={setSelectedRunId}
        runSelectionLocked={messages.length > 0}
        runsError={runsError}
      />
      <ChatWindow messages={messages} loading={loading} />
      <Composer
        onSend={(text) => send(text, selectedRunId)}
        disabled={loading || selectedRunId == null}
      />
    </div>
  );
}
