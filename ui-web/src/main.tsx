import React from "react";
import ReactDOM from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { HashRouter } from "react-router-dom";
import App from "./App";

export const queryClient = new QueryClient({ defaultOptions: { queries: { retry: 1, refetchOnWindowFocus: false } } });

async function bootstrap() {
  if (import.meta.env.VITE_WDIO === "true") {
    await import("@wdio/tauri-plugin");
    (globalThis as typeof globalThis & { __SQLTEACHER_E2E_QUERY_CLIENT__?: QueryClient }).__SQLTEACHER_E2E_QUERY_CLIENT__ = queryClient;
  }
  ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(
    <React.StrictMode><QueryClientProvider client={queryClient}><HashRouter><App /></HashRouter></QueryClientProvider></React.StrictMode>,
  );
}

void bootstrap();
