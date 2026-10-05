// v3.10.0 HAJ-1 修订：AI 引擎改为独立的顶栏弹层（TopbarAiEngine）后，数据页等
// 场景仍需要一键唤起它。用与 connectionPanel.ts 相同的极小模块级事件通道解耦，
// 避免为开个弹层引入全局状态库。
type Listener = () => void;

const listeners = new Set<Listener>();

/** 请求打开顶栏「AI 引擎」popover（引擎状态 + 本地模型 + 网络供应商管理）。 */
export function openAiEnginePanel(): void {
  for (const listener of listeners) listener();
}

/** 供 TopbarAiEngine 订阅打开请求；返回取消订阅函数。 */
export function subscribeAiEnginePanel(listener: Listener): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}
