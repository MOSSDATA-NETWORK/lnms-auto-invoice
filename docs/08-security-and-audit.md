# 安全、权限与审计

## 1. 租户模型

首版只有一个运营租户，但业务表从第一天包含 `tenant_id`。

- 租户上下文来自认证会话，不接受客户端任意传入。
- 唯一约束、缓存键、任务键、文件路径和编号规则包含租户。
- 第三阶段启用 PostgreSQL RLS 和平台级跨租户管理。

## 2. 认证

- 用户名或邮箱加密码。
- Argon2id 密码哈希。
- TOTP MFA 和一次性恢复码。
- HttpOnly、Secure、SameSite Cookie。
- 会话超时、设备记录和强制注销。
- 登录失败限速、锁定和审计。

OIDC 身份保存在独立映射表，不使用外部 Subject 作为业务用户主键。

## 3. 权限代码

```text
customer.read
customer.write
contract.write
pricing.publish
usage.sync
preview.generate
preview.adjust
preview.approve.business
preview.approve.finance
invoice.finalize
invoice.send
invoice.void
payment.record
template.publish
audit.read
system.admin
```

代码根据权限判断，不仅根据角色名称判断。

## 4. 角色矩阵

| 操作 | 管理员 | 业务 | 财务 | 销售 | 模板管理员 | 客户 |
|---|---:|---:|---:|---:|---:|---:|
| 客户和业务维护 | 是 | 是 | 只读 | 限名下 | 否 | 否 |
| 合同维护 | 是 | 是 | 只读 | 限名下 | 否 | 否 |
| 发布价格 | 是 | 受限 | 是 | 否 | 否 | 否 |
| 用量同步 | 是 | 是 | 只读 | 限名下 | 否 | 自有只读 |
| 业务审核 | 是 | 是 | 否 | 评论 | 否 | 否 |
| 财务审核和正式化 | 是 | 否 | 是 | 否 | 否 | 否 |
| 模板发布 | 是 | 否 | 否 | 否 | 是 | 否 |
| 付款记录 | 是 | 否 | 是 | 只读 | 否 | 自有只读 |

## 5. 秘密和个人数据

- LibreNMS Token、SMTP 密码和 Webhook Secret 使用 AES-GCM 信封加密。
- 主密钥通过 Docker Secret、KMS 或外部密钥管理提供。
- 日志遮罩 Token、密码、Cookie、邮箱和电话。
- 正式 PDF 使用不可猜测对象键和短时签名 URL。
- 客户数据和附件导出需要额外权限并记录审计。

## 6. 审计范围

- 客户、公司、业务、合同和价格修改。
- LibreNMS 配置、映射、同步和数据修正。
- 模板创建、发布、回滚和停用。
- 预览生成、重算、调整、排除和模板变更。
- 审批、驳回、正式化、发送、作废和更正。
- 付款、核销、退款和冲销。
- 权限、登录、导出和敏感数据访问。

## 7. 审计字段

```text
tenant_id
actor_type / actor_id
request_id / correlation_id
action
object_type / object_id
before_json / after_json
ip_address / user_agent
reason
created_at
```

审计表只允许 INSERT；应用角色不能 UPDATE 或 DELETE。

## 8. 模板安全

模板属于不可信输入。上传、验证和渲染必须防止：

- XSS 和 JavaScript 执行。
- SSRF、内网探测和云元数据访问。
- 本地文件读取。
- 超大图片、无限分页和资源耗尽。
- 模板 helper 越权访问对象属性。

## 9. API 安全

- 所有写接口校验租户和对象归属。
- 使用 CSRF 防护或明确的同源 Token 策略。
- 敏感命令要求 MFA 已验证会话。
- Webhook 使用时间戳和 HMAC-SHA256 签名。
- 幂等键必须绑定用户、租户、接口和请求哈希。

## 10. 数据保留默认值

| 数据 | 默认保留 |
|---|---|
| 正式账单、明细、PDF、审批 | 不自动删除 |
| 审计日志 | 不自动删除，按年度归档 |
| 正式用量证据 | 不短于正式账单 |
| 预览 PDF | 正式化或作废后 90 天 |
| 同步运行摘要 | 180 天 |
| 通知日志 | 2 年 |
| 在线备份 | 35 天，另有离线归档策略 |
