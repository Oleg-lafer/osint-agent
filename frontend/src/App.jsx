import { useEffect, useState } from "react";
import Header from "./components/Header";
import ChatWindow from "./components/ChatWindow";
import Composer from "./components/Composer";
import { useChat } from "./hooks/useChat";
import { getRuns, getTopics } from "./api/client";
import styles from "./App.module.css";

export default function App() {
  const { messages, loading, send } = useChat();
  const [runs, setRuns] = useState([]);
  const [selectedRunId, setSelectedRunId] = useState(null);
  const [runsError, setRunsError] = useState(false);
  const [topics, setTopics] = useState([]);
  const [selectedTopics, setSelectedTopics] = useState([]);

  useEffect(() => {
    getRuns()
      .then((available) => {
        setRuns(available);
        const initial = available.find((run) => run.isDefault) ?? available[0];
        setSelectedRunId(initial?.pipelineRunId ?? null);
      })
      .catch(() => setRunsError(true));
  }, []);

  useEffect(() => {
    setSelectedTopics([]);
    if (selectedRunId == null) {
      setTopics([]);
      return;
    }
    let current = true;
    setTopics([]);
    getTopics(selectedRunId)
      .then((value) => current && setTopics(value))
      .catch(() => current && setTopics([]));
    return () => { current = false; };
  }, [selectedRunId]);

  return (
    <div className={styles.app}>
      <Header
        runs={runs}
        selectedRunId={selectedRunId}
        onSelectRun={setSelectedRunId}
        runSelectionLocked={messages.length > 0}
        runsError={runsError}
        topics={topics}
        selectedTopics={selectedTopics}
        onSelectTopics={setSelectedTopics}
      />
      <ChatWindow messages={messages} loading={loading} />
      <Composer
        onSend={(text) => send(text, selectedTopics, selectedRunId)}
        disabled={loading || selectedRunId == null}
      />
    </div>
  );
}
