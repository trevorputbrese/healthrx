import { useEffect, useRef, useState } from 'react';
import { useChatMessage } from '../api/hooks';
import type { ChatToolCall } from '../api/types';
import { Card, Chip } from '../components/ui';

interface ChatEntry {
  role: 'user' | 'assistant';
  text: string;
  toolCalls?: ChatToolCall[];
}

const STARTERS = [
  'What is the missed-dose guidance for Neurosphere?',
  'Outreach tips for rheumatology patients?',
  'Which medications do you have guidance for?',
];

/**
 * The HealthRx Assistant: a human-driven MCP client. Every answer is grounded by audited
 * knowledge-tool calls through the same MCP gateway the agents use.
 */
export default function AssistantPage() {
  const [entries, setEntries] = useState<ChatEntry[]>([]);
  const [conversationId, setConversationId] = useState<string | undefined>();
  const [input, setInput] = useState('');
  const send = useChatMessage();
  const threadRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    threadRef.current?.scrollTo({ top: threadRef.current.scrollHeight, behavior: 'smooth' });
  }, [entries, send.isPending]);

  const submit = (text: string) => {
    const message = text.trim();
    if (!message || send.isPending) {
      return;
    }
    setEntries((prev) => [...prev, { role: 'user', text: message }]);
    setInput('');
    send.mutate(
      { conversationId, message },
      {
        onSuccess: (res) => {
          setConversationId(res.conversationId);
          setEntries((prev) => [
            ...prev,
            { role: 'assistant', text: res.reply, toolCalls: res.toolCalls },
          ]);
        },
      },
    );
  };

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>Assistant</h1>
          <p className="page-sub">
            Ask about the formulary&apos;s medications and disease states. The assistant is an MCP
            client: every answer is grounded by knowledge-server tool calls made through the same
            governed MCP gateway the agents use — watch the tool chips under each reply.
          </p>
        </div>
      </div>

      <Card>
        <div className="chat-panel">
          <div className="chat-thread" ref={threadRef}>
            {entries.length === 0 && (
              <div className="chat-empty">
                <p className="chat-empty-title">Try one of these:</p>
                <div className="chat-starters">
                  {STARTERS.map((s) => (
                    <button key={s} type="button" className="chip-button" onClick={() => submit(s)}>
                      {s}
                    </button>
                  ))}
                </div>
              </div>
            )}
            {entries.map((e, i) => (
              <div key={i} className={`chat-msg ${e.role === 'user' ? 'is-user' : 'is-assistant'}`}>
                <div className="chat-bubble">{e.text}</div>
                {e.toolCalls && e.toolCalls.length > 0 && (
                  <div className="chat-tools">
                    {e.toolCalls.map((t, j) => (
                      <Chip key={j} tone="tone-info">
                        <span title={t.arguments}>gateway tool · {t.tool}</span>
                      </Chip>
                    ))}
                  </div>
                )}
              </div>
            ))}
            {send.isPending && (
              <div className="chat-msg is-assistant">
                <div className="chat-bubble chat-pending">Consulting the knowledge server…</div>
              </div>
            )}
            {send.isError && (
              <div className="chat-msg is-assistant">
                <div className="chat-bubble chat-error">
                  {(send.error as Error)?.message ?? 'The assistant is unavailable right now.'}
                </div>
              </div>
            )}
          </div>

          <form
            className="chat-input-row"
            onSubmit={(e) => {
              e.preventDefault();
              submit(input);
            }}
          >
            <input
              className="chat-input"
              type="text"
              placeholder="Ask about a medication or disease state…"
              value={input}
              maxLength={2000}
              onChange={(e) => setInput(e.target.value)}
            />
            <button type="submit" className="btn btn-primary" disabled={send.isPending || !input.trim()}>
              Send
            </button>
          </form>
        </div>
      </Card>
    </div>
  );
}
