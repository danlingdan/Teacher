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
});
