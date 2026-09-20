// 账号中心面板（v3.8.0 ACC-D3）：「账号安全与数据治理」区从 CloudPage.tsx 原样搬移为独立面板，
// 并新增「教师身份」升级码兑换卡（决策点 1 方案 B）；除新增卡片外不改既有用户可见行为。
import { Button, Feedback, FormField } from "../../shared/ui";
import { formatAccountDate } from "./shared";
import { useAccountSecurity } from "./hooks/useAccountSecurity";

export function AccountCenterPanel() {
  const {
    currentPassword,
    setCurrentPassword,
    newPassword,
    setNewPassword,
    accountMessage,
    exportTaskId,
    sessions,
    openAccount,
    revokeSession,
    changePassword,
    requestExport,
    getExport,
    requestDeletion,
    cancelDeletion,
    deletionStatus,
    profileName,
    setProfileName,
    updateProfile,
    bindEmail,
    setBindEmail,
    emailCode,
    setEmailCode,
    emailCodeSent,
    setEmailCodeSent,
    sendEmailCode,
    verifyEmail,
    roleCode,
    setRoleCode,
    redeemRole,
  } = useAccountSecurity();
  const sessionItems = sessions.data?.items ?? [];
  return (
    <details
      className="content-card account-governance"
      onToggle={(event) => {
        if (event.currentTarget.open) openAccount();
      }}
    >
      <summary>
        <strong>账号安全与数据治理</strong>
      </summary>
      {/* v3.8.0 ACC-D3：自助修改显示名（决策点 6：显示名/邮箱/密码三项均可改）。 */}
      <section className="account-section">
        <h3>显示名称</h3>
        <p className="muted">班级花名册与学情画像中展示的名字。</p>
        <FormField label="新显示名称" hint="1-80 个字符">
          {(ids) => (
            <input
              {...ids}
              autoComplete="name"
              placeholder="输入新的显示名称"
              value={profileName}
              onChange={(event) => setProfileName(event.target.value)}
            />
          )}
        </FormField>
        <div className="button-row">
          <Button
            disabled={!profileName.trim()}
            busy={updateProfile.isPending}
            onClick={() => updateProfile.mutate()}
          >
            保存显示名称
          </Button>
        </div>
      </section>
      {/* v3.8.0 ACC-D2：邮箱绑定/换绑与验证码验证——找回密码闭环的前置。 */}
      <section className="account-section">
        <h3>邮箱验证</h3>
        <p className="muted">验证邮箱后可通过邮箱自助找回密码；换绑新邮箱也在这里完成。</p>
        <div className="password-form">
          <FormField label="邮箱地址">
            {(ids) => (
              <input
                {...ids}
                type="email"
                autoComplete="email"
                placeholder="name@example.com"
                value={bindEmail}
                onChange={(event) => setBindEmail(event.target.value)}
              />
            )}
          </FormField>
          {emailCodeSent && (
            <FormField label="验证码" hint="查看邮箱中的 6 位验证码，30 分钟内有效">
              {(ids) => (
                <input
                  {...ids}
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  placeholder="6 位验证码"
                  value={emailCode}
                  onChange={(event) => setEmailCode(event.target.value)}
                />
              )}
            </FormField>
          )}
        </div>
        <div className="button-row">
          {!emailCodeSent ? (
            <Button
              disabled={!bindEmail.trim()}
              busy={sendEmailCode.isPending}
              onClick={() => sendEmailCode.mutate()}
            >
              发送验证码
            </Button>
          ) : (
            <>
              <Button
                disabled={emailCode.trim().length < 6}
                busy={verifyEmail.isPending}
                onClick={() => verifyEmail.mutate()}
              >
                完成验证
              </Button>
              <Button variant="secondary" onClick={() => setEmailCodeSent(false)}>
                返回修改邮箱
              </Button>
            </>
          )}
        </div>
      </section>
      {/* v3.8.0 ACC-S4（决策点 1 方案 B）：一次性教师升级码兑换。 */}
      <section className="account-section">
        <h3>教师身份</h3>
        <p className="muted">已获管理员签发的教师升级码？输入并兑换即可激活教师功能（一次性，8 位字符）。</p>
        <FormField label="教师升级码" hint="管理员提供的一次性升级码，兑换后立即生效">
          {(ids) => (
            <input
              {...ids}
              autoCapitalize="characters"
              autoComplete="off"
              placeholder="例如 7KMQ4XAB"
              value={roleCode}
              onChange={(event) => setRoleCode(event.target.value)}
            />
          )}
        </FormField>
        <div className="button-row">
          <Button
            disabled={roleCode.trim().length < 8}
            busy={redeemRole.isPending}
            onClick={() => redeemRole.mutate()}
          >
            激活教师身份
          </Button>
        </div>
      </section>
      <section className="account-section">
        <h3>修改密码</h3>
        {/* v3.6.0 KUI：两个字段纵向等宽排列，修复左右高度/宽度不一致。 */}
        <div className="password-form">
          <FormField label="当前密码" hint="输入账号当前使用的密码">
            {(ids) => (
              <input
                {...ids}
                type="password"
                autoComplete="current-password"
                value={currentPassword}
                onChange={(event) => setCurrentPassword(event.target.value)}
              />
            )}
          </FormField>
          <FormField label="新密码" hint="12-128 个字符">
            {(ids) => (
              <input
                {...ids}
                type="password"
                autoComplete="new-password"
                value={newPassword}
                onChange={(event) => setNewPassword(event.target.value)}
              />
            )}
          </FormField>
        </div>
        <div className="button-row">
          <Button
            disabled={!currentPassword || newPassword.length < 12}
            busy={changePassword.isPending}
            onClick={() => changePassword.mutate()}
          >
            修改密码
          </Button>
        </div>
      </section>
      <section className="account-section">
        <h3>登录会话</h3>
        {sessions.isPending && <p className="muted">正在读取会话…</p>}
        <ul className="plain-list">
          {sessionItems.map((item) => (
            <li key={item.id}>
              <strong>{item.current ? "当前会话" : item.userAgent || "其他会话"}</strong>
              <span>{formatAccountDate(item.lastSeenAt)}</span>
              {!item.current && (
                <Button variant="secondary" onClick={() => revokeSession.mutate(item.id)}>
                  撤销
                </Button>
              )}
            </li>
          ))}
        </ul>
        {!sessions.isPending && sessionItems.length === 0 && (
          <p className="muted">没有可显示的其他会话。</p>
        )}
      </section>
      <section className="account-section danger-zone">
        <h3>数据导出与账号删除</h3>
        <p className="muted">导出不会修改账号；删除申请进入可撤销期，请谨慎操作。</p>
        <div className="button-row">
          <Button
            variant="secondary"
            busy={requestExport.isPending}
            onClick={() => requestExport.mutate()}
          >
            申请导出我的数据
          </Button>
          {exportTaskId && (
            <Button
              variant="secondary"
              busy={getExport.isPending}
              onClick={() => getExport.mutate()}
            >
              获取导出结果
            </Button>
          )}
          <Button
            variant="danger"
            busy={requestDeletion.isPending}
            onClick={() => requestDeletion.mutate()}
          >
            申请删除账号
          </Button>
          <Button
            variant="secondary"
            busy={deletionStatus.isPending}
            onClick={() => deletionStatus.mutate()}
          >
            查询删除状态
          </Button>
          <Button
            variant="secondary"
            busy={cancelDeletion.isPending}
            onClick={() => cancelDeletion.mutate()}
          >
            取消账号删除
          </Button>
        </div>
      </section>
      {accountMessage && (
        <Feedback tone="info" title="账号状态">
          {accountMessage}
        </Feedback>
      )}
    </details>
  );
}
