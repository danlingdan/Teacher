// v3.4.4 CTB-2：数据页侧栏的「管理」按钮需要唤起顶栏的连接面板（同一个 popover），
// 用一个极小的模块级事件通道把两者解耦，避免为开个弹层引入全局状态库。
type Listener = () => void;

const listeners = new Set<Listener>();

/** 请求打开顶栏「数据库连接」popover（连接清单 + 新建/编辑入口）。 */
export function openConnectionPanel(): void {
  for (const listener of listeners) listener();
}

/** 供 TopbarConnection 订阅打开请求；返回取消订阅函数。 */
export function subscribeConnectionPanel(listener: Listener): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}
