import { useCallback, useState } from "react";
import { cancelLocalAppRequest, localAppRequestWithId } from "../../shared/ipc";
import type { AiKnowledgeAnswer } from "../../shared/types";

// v3.4.4 KUI-3：知识助教会话轮次。每轮仍是一次独立的单轮检索问答（后端契约不变），
// 会话历史只存在于当前窗口。
export type AssistantTurn = {
  id: string;
  question: string;
  requestId: string;
  pending: boolean;
  answer?: AiKnowledgeAnswer;
  error?: string;
};

/** v3.6.0 KBF-1：阅读上下文走结构化字段进检索过滤，不再拼接问题文本前缀。 */
export type AssistantContext = {
  courseTitle?: string;
  sectionTitle?: string;
};

/**
 * 知识助教的提问-回答状态机：主窗口与独立子窗口共用。
 * `context` 是可选的文档上下文（结构化传给 Java 端做检索过滤，问题文本保持纯净）。
 */
export function useAssistantTurns(context: AssistantContext) {
  const [turns, setTurns] = useState<AssistantTurn[]>([]);
  const askPending = turns.some((turn) => turn.pending);

  const submitQuestion = useCallback(
    (text: string): boolean => {
      const trimmed = text.trim();
      if (trimmed.length < 2 || askPending) return false;
      const turnId = crypto.randomUUID();
      const requestId = crypto.randomUUID();
      setTurns((value) => [...value, { id: turnId, question: trimmed, requestId, pending: true }]);
      localAppRequestWithId<AiKnowledgeAnswer>(
        "ai.knowledge.ask",
        {
          question: trimmed,
          context: {
            courseTitle: context.courseTitle ?? "",
            sectionTitle: context.sectionTitle ?? "",
          },
        },
        requestId,
      )
        .then((answer) =>
          setTurns((current) =>
            current.map((turn) =>
              turn.id === turnId ? { ...turn, answer, pending: false } : turn,
            ),
          ),
        )
        .catch((error: Error) =>
          setTurns((current) =>
            current.map((turn) =>
              turn.id === turnId ? { ...turn, error: error.message, pending: false } : turn,
            ),
          ),
        );
      return true;
    },
    [askPending, context.courseTitle, context.sectionTitle],
  );

  const cancelTurn = useCallback((turn: AssistantTurn) => {
    // system.cancel：桥端若不支持中断该方法，请求会自然跑完，这里不阻塞等待。
    void cancelLocalAppRequest(turn.requestId).catch(() => undefined);
  }, []);

  const clearTurns = useCallback(() => setTurns([]), []);

  return { turns, askPending, submitQuestion, cancelTurn, clearTurns };
}
