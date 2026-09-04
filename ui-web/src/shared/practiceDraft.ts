// 练习作答草稿的本地持久化。按题目 ID 隔离，避免不同题目的代码互相覆盖；
// 存储失败（如隐私模式）时静默降级为不持久化，不影响作答流程。
const draftKey = (exerciseId: string) => `sqlteacher.practice.draft.${exerciseId}`;

export function loadDraft(exerciseId: string): string | undefined {
  try {
    return localStorage.getItem(draftKey(exerciseId)) ?? undefined;
  } catch {
    return undefined;
  }
}

export function saveDraft(exerciseId: string, answer: string) {
  try {
    localStorage.setItem(draftKey(exerciseId), answer);
  } catch {
    // ignore
  }
}

export function clearDraft(exerciseId: string) {
  try {
    localStorage.removeItem(draftKey(exerciseId));
  } catch {
    // ignore
  }
}
