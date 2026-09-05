import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Navigate, useNavigate, useSearchParams } from "react-router-dom";
import { sessionQuery } from "../../app/queries";
import { localAppRequest } from "../../shared/ipc";
import type { SessionResult } from "../../shared/types";
import { Button, Feedback, FormField } from "../../shared/ui";

type AuthMode = "login" | "register" | "reset";
const safeDestinations = new Set(["/today", "/knowledge", "/practice", "/data", "/teaching", "/cloud", "/settings"]);

export default function AuthPage() {
  const client = useQueryClient();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const session = useQuery(sessionQuery);
  const [mode, setMode] = useState<AuthMode>("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [passwordVisible, setPasswordVisible] = useState(false);
  const [message, setMessage] = useState("");
  const requestedReturn = searchParams.get("returnTo") ?? "/cloud";
  const returnTo = safeDestinations.has(requestedReturn) ? requestedReturn : "/cloud";

  const finishSignIn = async (value: SessionResult) => {
    client.setQueryData(sessionQuery.queryKey, value);
    await client.invalidateQueries({ queryKey: ["cloud", "workspace"] });
    navigate(returnTo, { replace: true });
  };
  const login = useMutation({
    mutationFn: () => localAppRequest<SessionResult>("account.login", { email: email.trim(), password }),
    onSuccess: finishSignIn,
    onSettled: () => setPassword(""),
  });
  const register = useMutation({
    mutationFn: () => localAppRequest<SessionResult>("account.register", { email: email.trim(), displayName: displayName.trim(), password }),
    onSuccess: finishSignIn,
    onSettled: () => setPassword(""),
  });
  const reset = useMutation({
    mutationFn: () => localAppRequest("account.password.reset.request", { email: email.trim() }),
    onSuccess: () => setMessage("如果账号存在，密码重置邮件已经发送。"),
  });
  useEffect(() => { setMessage(""); }, [mode]);

  if (session.data?.authenticated) return <Navigate to={returnTo} replace />;
  const error = login.error ?? register.error ?? reset.error;
  const submit = () => mode === "login" ? login.mutate() : mode === "register" ? register.mutate() : reset.mutate();
  const disabled = !email.trim() || (mode === "login" && !password) || (mode === "register" && (!displayName.trim() || password.length < 12));
  const busy = login.isPending || register.isPending || reset.isPending;

  return <main className="auth-shell">
    <section className="auth-story" aria-label="SQLTeacher 介绍">
      <div className="auth-brand"><span className="brand-mark">S</span><div><strong>SQLTeacher</strong><small>Learning Studio</small></div></div>
      <div className="auth-story-copy"><p className="eyebrow">Learn with clarity</p><h1>把每一次练习<br />变成可见的进步</h1><p>本地优先的计算机科学学习工作台。</p><ul><li><span>01</span>离线可用，无需账号</li><li><span>02</span>SQL 执行受安全边界保护</li><li><span>03</span>登录后同步班级与进度</li></ul></div>
      <small className="auth-version">SQLTeacher 3.0 · Windows</small>
    </section>
    <section className="auth-panel">
      <div className="auth-card">
        <button type="button" className="auth-back" onClick={() => navigate("/today", { replace: true })}>← 继续离线学习</button>
        <header><p className="eyebrow">SQLTeacher Cloud</p><h2>{mode === "login" ? "欢迎回来" : mode === "register" ? "创建你的账号" : "找回密码"}</h2><p>{mode === "login" ? "登录后继续同步课程与班级进度。" : mode === "register" ? "一个账号连接你的班级、任务和学习记录。" : "输入注册邮箱，我们会发送安全的重置指引。"}</p></header>
        <div className="auth-tabs" role="tablist" aria-label="账号操作"><button role="tab" aria-selected={mode === "login"} className={mode === "login" ? "selected" : ""} onClick={() => setMode("login")}>登录</button><button role="tab" aria-selected={mode === "register"} className={mode === "register" ? "selected" : ""} onClick={() => setMode("register")}>注册</button><button role="tab" aria-selected={mode === "reset"} className={mode === "reset" ? "selected" : ""} onClick={() => setMode("reset")}>找回密码</button></div>
        <form className="auth-form" onSubmit={event => { event.preventDefault(); if (!disabled && !busy) submit(); }}>
          <FormField label="邮箱地址">{ids => <input {...ids} type="email" autoComplete="username" placeholder="name@example.com" value={email} onChange={event => setEmail(event.target.value)} />}</FormField>
          {mode === "register" && <FormField label="显示名称">{ids => <input {...ids} autoComplete="name" placeholder="你希望显示的名字" value={displayName} onChange={event => setDisplayName(event.target.value)} />}</FormField>}
          {mode !== "reset" && <FormField label="密码" hint={mode === "register" ? "至少 12 个字符" : undefined}>{ids => <div className="password-field"><input {...ids} type={passwordVisible ? "text" : "password"} autoComplete={mode === "login" ? "current-password" : "new-password"} placeholder={mode === "login" ? "输入密码" : "设置安全密码"} value={password} onChange={event => setPassword(event.target.value)} /><button type="button" onClick={() => setPasswordVisible(value => !value)} aria-label={passwordVisible ? "隐藏密码" : "显示密码"}>{passwordVisible ? "隐藏" : "显示"}</button></div>}</FormField>}
          {error && <Feedback tone="error" title="账号操作失败"><p>{error.message}</p></Feedback>}
          {message && <Feedback tone="info" title="请检查邮箱"><p>{message}</p></Feedback>}
          <Button type="submit" busy={busy} disabled={disabled}>{mode === "login" ? "登录" : mode === "register" ? "创建账号并登录" : "发送重置邮件"}</Button>
        </form>
        <p className="auth-privacy">离线学习无需登录。</p>
      </div>
    </section>
  </main>;
}
