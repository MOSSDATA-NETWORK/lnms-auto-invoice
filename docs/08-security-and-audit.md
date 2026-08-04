# 安全、权限与审计

## 1. 租户模型

首版只有一个运营租户，但业务表从第一天包含 `tenant_id`。

- 租户上下文来自认证会话，不接受客户端任意传入。
- 唯一约束、缓存键、任务键、文件路径和编号规则包含租户。
- 所有租户内实体关系同时使用 `(tenant_id, id)` 复合外键；保留的单列外键只用于兼容，不能单独作为租户隔离边界。
- 第三阶段启用 PostgreSQL RLS 和平台级跨租户管理。

## 2. 认证

- 用户名或邮箱加密码。
- Argon2id 密码哈希。
- TOTP MFA 和一次性恢复码。
- HttpOnly、Secure、SameSite Cookie。
- 会话超时、设备记录和强制注销。
- 登录失败限速、锁定和审计。
- 空库 bootstrap 管理员及管理员创建、重置的临时密码必须在 24 小时内完成首次修改；修改前只能访问完成认证所需的端点。

认证限流桶必须保持作用域一致：IP 桶不保存租户或用户，MFA 用户桶同时保存租户和用户；登录身份桶允许未知用户，但只要保存 `user_id` 就必须同时保存对应 `tenant_id`。

OIDC 身份保存在独立映射表，不使用外部 Subject 作为业务用户主键。

首版会话接口：

```text
GET  /api/v1/auth/csrf
POST /api/v1/auth/sign-in
POST /api/v1/auth/mfa/verify
GET  /api/v1/auth/session
POST /api/v1/auth/sign-out
POST /api/v1/auth/mfa/enrollment
POST /api/v1/auth/mfa/confirm
POST /api/v1/auth/mfa/recovery-codes
POST /api/v1/auth/mfa/disable
```

会话响应返回当前租户、用户、角色和权限代码。前端菜单与路由守卫只改善体验，不能替代 `@PreAuthorize` 和租户归属校验。聚合接口也必须逐项执行最小权限控制；例如总览只查询并返回当前账号有权读取的指标，不能因为接口本身允许登录用户访问就泄露其他业务域的数量或金额。

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
- 主密钥通过 Docker Secret、KMS 或外部密钥管理提供；缺失、Base64 非法或不是 32 字节时应用在构造秘密服务阶段立即拒绝启动。
- 日志遮罩 Token、密码、Cookie、邮箱和电话。
- 正式 PDF 使用不可猜测对象键和短时签名 URL。
- 客户数据和附件导出需要额外权限并记录审计。
- 普通用户首次把上传文件绑定到导入或预览调整时，只能引用同租户且由自己上传、未软删除的文件；`system.admin` 才能代绑定。越权与不存在统一返回 404，上传对象键包含 `user_id`，防止同租户按哈希去重复用他人的文件记录。

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

实现采用双层防护：模板源和 Handlebars 展开后的完整文档都执行规范化校验，Chromium 同时关闭 JavaScript、网络、下载和 Service Worker，并保持沙箱启用。HTML/CSS、`data:` URI、布局高度、渲染总时长、PDF 页数和字节数均有硬上限；超时、中断或异常必须清理本次渲染创建的浏览器子进程，不能让恶意模板持续占用 Worker 资源。

## 9. API 安全

- 所有写接口校验租户和对象归属。
- 使用 CSRF 防护或明确的同源 Token 策略。
- 敏感命令要求 MFA 已验证会话。
- Webhook 使用时间戳和 HMAC-SHA256 签名。
- Webhook 目标默认只允许 HTTPS，拒绝端口 `0`、凭据、fragment、重定向及解析到私网、loopback、链路本地、保留地址、6to4、Teredo、NAT64 和 IPv4-compatible IPv6 的主机；HTTP 仅允许显式本地开发豁免。
- 执行通知任务的 Worker 必须启用且强制 STARTTLS，并校验 SMTP 证书主机名；不安全 SMTP 只允许显式本地开发豁免。
- 幂等键必须绑定用户、租户、接口和请求哈希。
- LibreNMS 数据源只能使用 `LIBRENMS_ALLOWED_ORIGINS` 中的 exact origin；API 写入时与 Sync Worker 建立连接时使用同一 core 策略重复校验，空配置 fail closed。

LibreNMS allowlist 约束应用可连接的 origin，但不会固定 DNS 解析结果。生产环境仍应由出口防火墙或代理限制 Sync Worker
只能访问获准 LibreNMS 地址，并防止 allowlist 域名被 DNS 劫持或重绑定到云元数据、loopback 或其他内网目标。

## 10. 数据保留默认值

| 数据 | 默认保留 |
|---|---|
| 正式账单、明细、PDF、审批 | 不自动删除 |
| 审计日志 | 不自动删除，按年度归档 |
| 正式用量证据 | 不短于正式账单 |
| 付款、分配和退款历史 | 不自动删除；金额、归属和原始凭据不可原地修改，更正使用分配冲销或受控状态迁移 |
| 预览 PDF | 正式化或作废后 90 天 |
| 同步运行摘要 | 180 天 |
| 通知日志 | 2 年 |
| 在线备份 | 35 天，另有离线归档策略 |

## 11. 运维安全开关

`tenant_operational_settings` 保存自动生成、自动发送、系统执行用户和紧急停用状态。更新接口要求版本匹配；紧急停用必须记录原因。该开关只阻止新的自动副作用，不修改已冻结正式账单，也不把 Redis 作为开关事实来源。
