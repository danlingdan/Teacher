import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import AuthPage from "./AuthPage";

const requestMock = vi.fn();
vi.mock("../../shared/ipc", () => ({ localAppRequest: (...args: unknown[]) => requestMock(...args) }));

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}><MemoryRouter initialEntries={["/login?returnTo=/cloud"]}><Routes><Route path="login" element={<AuthPage />} /><Route path="cloud" element={<div>云端工作区</div>} /><Route path="today" element={<div>离线首页</div>} /></Routes></MemoryRouter></QueryClientProvider>);
}

describe("AuthPage", () => {
  beforeEach(() => {
    requestMock.mockReset();
    requestMock.mockImplementation((method: string) => {
      if (method === "session.current") return Promise.resolve({ subjectId: "guest", displayName: "本地学习者", role: "STUDENT", authenticated: false, permissions: [] });
      if (method === "account.login") return Promise.resolve({ subjectId: "user-1", displayName: "测试用户", role: "STUDENT", authenticated: true, permissions: [] });
      if (method === "account.password.reset.request") return Promise.resolve({ accepted: true });
      if (method === "account.password.reset") return Promise.resolve({ reset: true });
      throw new Error(`Unexpected request: ${method}`);
    });
  });

  it("keeps offline learning available and signs in through Java", async () => {
    renderPage();
    expect(await screen.findByRole("heading", { name: "欢迎回来" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /继续离线学习/ })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("邮箱地址"), { target: { value: "student@example.com" } });
    fireEvent.change(screen.getByLabelText("密码"), { target: { value: "correct horse battery staple" } });
    fireEvent.click(screen.getByRole("button", { name: "登录" }));
    await waitFor(() => expect(requestMock).toHaveBeenCalledWith("account.login", { email: "student@example.com", password: "correct horse battery staple" }));
    expect(await screen.findByText("云端工作区")).toBeInTheDocument();
  });

  // v3.8.0 ACC-D2：找回密码两步流——请求邮箱验证码 → 输码设置新密码 → 回到登录。
  it("completes the two-step password reset with the mailed code", async () => {
    renderPage();
    fireEvent.click(screen.getByRole("tab", { name: "找回密码" }));
    expect(screen.getByRole("heading", { name: "找回密码" })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("邮箱地址"), { target: { value: "student@example.com" } });
    fireEvent.click(screen.getByRole("button", { name: "发送验证码" }));
    await waitFor(() => expect(requestMock).toHaveBeenCalledWith("account.password.reset.request", { email: "student@example.com" }));
    expect(await screen.findByRole("heading", { name: "设置新密码" })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("验证码"), { target: { value: "123456" } });
    fireEvent.change(screen.getByLabelText("新密码"), { target: { value: "another passphrase 123" } });
    fireEvent.click(screen.getByRole("button", { name: "重置密码" }));
    await waitFor(() => expect(requestMock).toHaveBeenCalledWith("account.password.reset", { email: "student@example.com", code: "123456", newPassword: "another passphrase 123" }));
    expect(await screen.findByText("密码已重置，请使用新密码登录。")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "欢迎回来" })).toBeInTheDocument();
  });
});
