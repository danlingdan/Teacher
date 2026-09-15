import type { ToastTone } from "./ui";

type Toast = (tone: ToastTone, message: string) => void;

/**
 * 统一的「写剪贴板 + toast 反馈」流程；此前在三处页面各自内联实现。
 * 剪贴板不可用（非安全上下文/无权限）时静默失败并提示，不抛错。
 */
export function copyToClipboard(
  text: string,
  successMessage: string,
  failureMessage: string,
  toast: Toast,
): void {
  void navigator.clipboard
    ?.writeText(text)
    .then(() => toast("success", successMessage))
    .catch(() => toast("error", failureMessage));
}
