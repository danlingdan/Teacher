import type { ButtonHTMLAttributes, ReactNode } from "react";
import { createContext, useCallback, useContext, useEffect, useId, useRef, useState } from "react";
import "./ui.css";

export function Button({ variant = "primary", busy = false, children, disabled, ...props }: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: "primary" | "secondary" | "danger"; busy?: boolean }) {
  return <button className={`ui-button ${variant}`} disabled={disabled || busy} aria-busy={busy} {...props}>{busy ? "处理中…" : children}</button>;
}

type ToastTone = "success" | "error";
type ToastItem = { id: number; tone: ToastTone; message: string };
const ToastContext = createContext<(tone: ToastTone, message: string) => void>(() => {});

/** Pushes a transient toast: `const toast = useToast(); toast("success", "已发布");` */
export function useToast() {
  return useContext(ToastContext);
}

export function Toaster({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([]);
  const push = useCallback((tone: ToastTone, message: string) => {
    const id = Date.now() + Math.random();
    setItems(current => [...current.slice(-3), { id, tone, message }]);
    window.setTimeout(() => setItems(current => current.filter(item => item.id !== id)), 4200);
  }, []);
  return <ToastContext.Provider value={push}>
    {children}
    <div className="ui-toaster" role="status" aria-live="polite">
      {items.map(item => <div key={item.id} className={`ui-toast ${item.tone}`}>{item.message}</div>)}
    </div>
  </ToastContext.Provider>;
}

export function FormField({ label, hint, error, children }: { label: string; hint?: string; error?: string; children: (ids: { id: string; "aria-describedby"?: string }) => ReactNode }) {
  const id = useId();
  const hintId = hint ? `${id}-hint` : undefined;
  const errorId = error ? `${id}-error` : undefined;
  return <div className="ui-field"><label htmlFor={id}>{label}</label>{children({ id, "aria-describedby": [hintId, errorId].filter(Boolean).join(" ") || undefined })}{hint && <small id={hintId}>{hint}</small>}{error && <small className="field-error" id={errorId}>{error}</small>}</div>;
}

export function Stepper({ steps, current }: { steps: string[]; current: number }) {
  return <ol className="ui-stepper" aria-label="进度">{steps.map((step, index) => <li key={step} aria-current={index === current ? "step" : undefined} className={index <= current ? "complete" : ""}><span>{index + 1}</span>{step}</li>)}</ol>;
}

export function Feedback({ tone = "info", title, children }: { tone?: "info" | "success" | "warning" | "error"; title: string; children: ReactNode }) {
  return <section className={`ui-feedback ${tone}`} role={tone === "error" ? "alert" : "status"}><strong>{title}</strong><div>{children}</div></section>;
}

export function EmptyState({ title, children, action }: { title: string; children: ReactNode; action?: ReactNode }) {
  return <section className="ui-empty"><h2>{title}</h2><div>{children}</div>{action}</section>;
}

export function Dialog({ open, title, onClose, children }: { open: boolean; title: string; onClose: () => void; children: ReactNode }) {
  const titleId = useId();
  const closeRef = useRef<HTMLButtonElement>(null);
  useEffect(() => { if (open) closeRef.current?.focus(); }, [open]);
  useEffect(() => {
    if (!open) return;
    const close = (event: KeyboardEvent) => { if (event.key === "Escape") onClose(); };
    window.addEventListener("keydown", close);
    return () => window.removeEventListener("keydown", close);
  }, [open, onClose]);
  if (!open) return null;
  return <div className="ui-dialog-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}><section className="ui-dialog" role="dialog" aria-modal="true" aria-labelledby={titleId}><header><h2 id={titleId}>{title}</h2><button ref={closeRef} aria-label="关闭对话框" onClick={onClose}>×</button></header>{children}</section></div>;
}

export function DataTable<T>({ caption, rows, columns }: { caption: string; rows: T[]; columns: Array<{ key: string; title: string; render: (row: T) => ReactNode }> }) {
  return <div className="ui-table-wrap"><table className="ui-table"><caption>{caption}</caption><thead><tr>{columns.map(column => <th scope="col" key={column.key}>{column.title}</th>)}</tr></thead><tbody>{rows.length === 0 ? <tr><td colSpan={columns.length}>暂无数据</td></tr> : rows.map((row, index) => <tr key={index}>{columns.map(column => <td key={column.key}>{column.render(row)}</td>)}</tr>)}</tbody></table></div>;
}

export type TreeNode = { id: string; label: string; children?: TreeNode[] };
function TreeItem({ node }: { node: TreeNode }) {
  const [expanded, setExpanded] = useState(true);
  const branch = Boolean(node.children?.length);
  return <li role="treeitem" aria-expanded={branch ? expanded : undefined}><div>{branch && <button aria-label={`${expanded ? "折叠" : "展开"}${node.label}`} onClick={() => setExpanded(value => !value)}>{expanded ? "−" : "+"}</button>}<span>{node.label}</span></div>{branch && expanded && <ul role="group">{node.children!.map(child => <TreeItem key={child.id} node={child} />)}</ul>}</li>;
}
export function TreeView({ label, nodes }: { label: string; nodes: TreeNode[] }) { return <ul className="ui-tree" role="tree" aria-label={label}>{nodes.map(node => <TreeItem key={node.id} node={node} />)}</ul>; }

export function LiveRegion({ children }: { children: ReactNode }) { return <div className="sr-only" aria-live="polite">{children}</div>; }
