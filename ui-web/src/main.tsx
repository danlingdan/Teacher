import React from "react";
import ReactDOM from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { HashRouter } from "react-router-dom";
import App from "./App";
import "./App.css";
import AssistantWindow from "./features/knowledge/AssistantWindow";
import { Toaster } from "./shared/ui";

export const queryClient = new QueryClient({ defaultOptions: { queries: { retry: 1, refetchOnWindowFocus: false } } });

async function bootstrap() {
  if (import.meta.env.VITE_WDIO === "true") {
    await import("@wdio/tauri-plugin");
    (globalThis as typeof globalThis & { __SQLTEACHER_E2E_QUERY_CLIENT__?: QueryClient }).__SQLTEACHER_E2E_QUERY_CLIENT__ = queryClient;
  }
  // v3.4.4：知识助教子窗口复用同一前端产物，按 hash 路由切到独立轻量页面。
  const isAssistantWindow = window.location.hash.startsWith("#/assistant-window");
  ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(
    <React.StrictMode><QueryClientProvider client={queryClient}><Toaster><HashRouter>{isAssistantWindow ? <AssistantWindow /> : <App />}</HashRouter></Toaster></QueryClientProvider></React.StrictMode>,
  );
}

void bootstrap();
