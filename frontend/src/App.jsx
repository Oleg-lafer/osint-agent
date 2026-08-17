import { useEffect, useState } from "react";
import Header from "./components/Header";
import ChatWindow from "./components/ChatWindow";
import Composer from "./components/Composer";
import { useChat } from "./hooks/useChat";
import { getTopics } from "./api/client";
import styles from "./App.module.css";

export default function App() {
  const { messages, loading, send } = useChat();
  const [topics, setTopics] = useState([]);
  const [selectedTopics, setSelectedTopics] = useState([]);

  useEffect(() => {
    getTopics().then(setTopics).catch(() => {});
  }, []);

  return (
    <div className={styles.app}>
      <Header topics={topics} selectedTopics={selectedTopics} onSelectTopics={setSelectedTopics} />
      <ChatWindow messages={messages} loading={loading} />
      <Composer onSend={(text) => send(text, selectedTopics)} disabled={loading} />
    </div>
  );
}
