import type { ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { sessionQuery } from "./queries";
import type { AppRole } from "../shared/types";
import { Feedback } from "../shared/ui";

export function RoleGuard({ allow, children }: { allow: AppRole[]; children: ReactNode }) {
  const session = useQuery(sessionQuery);
  if (session.isPending) return <section className="page-skeleton" aria-live="polite">正在验证角色…</section>;
  if (session.isError || !session.data || !allow.includes(session.data.role)) {
    return <Feedback tone="warning" title="当前角色无法访问">此区域需要教师或管理员角色，权限由 Java 会话接口判定。</Feedback>;
  }
  return children;
}
