# REST API 与 Webhook 契约

## 1. 通用规则

- 基础路径：`/api/v1`。
- UTF-8 JSON，字段使用 `snake_case`。
- ID 使用应用生成的 UUIDv7。
- 日期为 `YYYY-MM-DD`，时刻为带时区 RFC 3339。
- 金额、数量、单价、税率和汇率使用十进制字符串。
- 命令型 POST 使用 `Idempotency-Key`。
- 可修改资源返回 `ETag`，更新使用 `If-Match`。
- 长任务返回 `202 Accepted` 和 `job_id`。
- 正式账单没有 DELETE 接口。

### 1.1 机器可读契约与前端生成客户端

- 仓库提交 `openapi/auto-invoice.json`，它是 API 启动器 `/v3/api-docs` 的 OpenAPI 3.1 导出物，也是前端 Orval 的默认输入。
- 动态 `/v3/api-docs` 和 Swagger UI 默认关闭，且生产 Web 不代理文档路径；仅在本地或受控构建环境显式设置 `OPENAPI_DOCS_ENABLED=true`（需要交互 UI 时再设置 `SWAGGER_UI_ENABLED=true`）后导出，避免正式环境公开完整接口枚举。
- Springdoc 自定义器统一把 Schema 属性和查询参数发布为 `snake_case`，把 `BigDecimal` 发布为带格式约束的十进制字符串，并把动态 `JsonNode` 发布为可索引对象。
- `frontend/src/api/generated/` 由 Orval 生成模型、Axios 请求函数和 TanStack Query Hooks，禁止手工编辑。读请求优先直接复用生成客户端；需要 `Idempotency-Key`、`If-Match` 或组合命令语义的请求使用薄封装补充请求头，不再定义重复 DTO。
- 生成函数保留契约中的 `/api/v1` 路径；共享 Axios 实例也以 `/api/v1` 为 `baseURL`，因此统一 mutator 在发送前只移除一次生成路径前缀，避免出现 `/api/v1/api/v1/...`。
- 公共接口变化必须同时更新控制器测试、静态 OpenAPI、Orval 生成结果和对应专题文档；前端构建必须能从提交的静态契约离线重新生成。

## 2. 分页

```json
{
  "data": [],
  "page": {
    "next_cursor": "opaque-token",
    "has_more": false
  }
}
```

参数：`limit`、`cursor`、`sort`、`status`、`q`。`limit` 默认 50，最大 200。

## 3. 错误格式

响应类型为 `application/problem+json`。

```json
{
  "type": "https://auto-invoice.example/problems/duplicate-billing-assignment",
  "title": "Duplicate billing assignment",
  "status": 409,
  "code": "DUPLICATE_BILLING_ASSIGNMENT",
  "detail": "The contract item is already charged by another active profile.",
  "instance": "/api/v1/invoice-profiles/019.../assignments",
  "request_id": "req_019...",
  "errors": [
    {
      "field": "contract_item_id",
      "message": "Already assigned to profile HK Bandwidth"
    }
  ]
}
```

## 3.1 认证与会话

```text
GET    /api/v1/auth/csrf
POST   /api/v1/auth/sign-in
POST   /api/v1/auth/mfa/verify
GET    /api/v1/auth/session
POST   /api/v1/auth/sign-out
POST   /api/v1/auth/mfa/enrollment
POST   /api/v1/auth/mfa/confirm
POST   /api/v1/auth/mfa/recovery-codes
POST   /api/v1/auth/mfa/disable
```

浏览器先获取 CSRF Token，认证成功后仅使用服务端会话 Cookie；不得把可读 Bearer Token 保存到 Zustand、Local Storage 或 JavaScript Cookie。

## 3.2 权限感知总览

```text
GET /api/v1/dashboard/summary
```

该接口允许已认证用户进入统一首页，但每个指标分别按其来源资源权限判断。服务端不会查询无权指标，并在响应中省略对应字段：客户和有效业务需要 `customer.read`；待审核预览需要任一预览读取/处理权限；正式化指标需要任一正式账单读取/处理权限；死信任务需要 `audit.read` 或 `system.admin`；未结应收需要 `payment.record`、`audit.read` 或 `system.admin`。客户端不得把缺失字段解释为零。

未结应收返回 `receivables[]`，每项包含 `currency_code`、`currency_symbol`、`minor_unit` 和十进制字符串 `outstanding_minor`。余额只统计 `CONFIRMED`、`SENT`、`REPLACED` 正式账单并扣除 `ACTIVE` 付款分配；不同币种禁止隐式相加。

## 4. 客户、公司和联系人

```text
GET    /api/v1/customers
POST   /api/v1/customers
GET    /api/v1/customers/{id}
PATCH  /api/v1/customers/{id}
POST   /api/v1/customers/{id}/archive

GET    /api/v1/companies
POST   /api/v1/companies
GET    /api/v1/companies/{id}
PATCH  /api/v1/companies/{id}

GET    /api/v1/customers/{id}/contacts
POST   /api/v1/customers/{id}/contacts
PATCH  /api/v1/contacts/{id}
```

## 5. 产品、业务和合同

```text
GET    /api/v1/products
POST   /api/v1/products

GET    /api/v1/services
POST   /api/v1/services
GET    /api/v1/services/{id}
PATCH  /api/v1/services/{id}
GET    /api/v1/services/{id}/resources
POST   /api/v1/services/{id}/resources

GET    /api/v1/contracts
POST   /api/v1/contracts
GET    /api/v1/contracts/{id}
PATCH  /api/v1/contracts/{id}
POST   /api/v1/contracts/{id}/activate
POST   /api/v1/contracts/{id}/items
PATCH  /api/v1/contract-items/{id}
```

## 6. 价格规则

```text
GET    /api/v1/pricing-rules
POST   /api/v1/pricing-rules
GET    /api/v1/pricing-rules/{id}
POST   /api/v1/pricing-rules/{id}/versions
POST   /api/v1/pricing-rule-versions/{id}/validate
POST   /api/v1/pricing-rule-versions/{id}/publish
POST   /api/v1/pricing-rule-versions/{id}/retire
```

发布前返回有效期重叠、阶梯空档、封顶低于最低消费、缺少必需配置等错误。

## 7. 账单配置

```text
GET    /api/v1/invoice-profiles
POST   /api/v1/invoice-profiles
GET    /api/v1/invoice-profiles/{id}
PATCH  /api/v1/invoice-profiles/{id}
POST   /api/v1/invoice-profiles/{id}/assignments
DELETE /api/v1/invoice-profiles/{id}/assignments/{assignment_id}
POST   /api/v1/invoice-profiles/{id}/validate
POST   /api/v1/invoice-profiles/{id}/preview
```

## 8. LibreNMS

```text
GET    /api/v1/librenms/instances
POST   /api/v1/librenms/instances
PATCH  /api/v1/librenms/instances/{id}
POST   /api/v1/librenms/instances/{id}/test
POST   /api/v1/librenms/instances/{id}/discover-bills
GET    /api/v1/librenms/bills
POST   /api/v1/librenms/mappings
PATCH  /api/v1/librenms/mappings/{id}
POST   /api/v1/librenms/mappings/{id}/sync
GET    /api/v1/librenms/mappings/{id}/history
GET    /api/v1/usage-snapshots/{id}
GET    /api/v1/sync-runs
```

## 9. 模板

```text
GET    /api/v1/invoice-templates
POST   /api/v1/invoice-templates
POST   /api/v1/invoice-templates/import
POST   /api/v1/invoice-templates/{id}/copy
POST   /api/v1/invoice-templates/{id}/versions
POST   /api/v1/template-versions/{id}/validate
POST   /api/v1/template-versions/{id}/preview
POST   /api/v1/template-versions/{id}/publish
POST   /api/v1/invoice-templates/{id}/rollback
```

## 10. 预览

```text
POST   /api/v1/invoice-previews/generate
GET    /api/v1/invoice-previews
GET    /api/v1/invoice-previews/{id}
POST   /api/v1/invoice-previews/{id}/sync-usage
POST   /api/v1/invoice-previews/{id}/recalculate
POST   /api/v1/invoice-previews/{id}/adjustments
DELETE /api/v1/invoice-previews/{id}/adjustments/{adjustment_id}
POST   /api/v1/invoice-previews/{id}/exclusions
POST   /api/v1/invoice-previews/{id}/submit-business-review
POST   /api/v1/invoice-previews/{id}/approve-business
POST   /api/v1/invoice-previews/{id}/approve-finance
POST   /api/v1/invoice-previews/{id}/reject
POST   /api/v1/invoice-previews/{id}/finalize
```

### 生成预览

```http
POST /api/v1/invoice-previews/generate
Idempotency-Key: profile-019-period-202607-v1
```

```json
{
  "invoice_profile_id": "01900000-0000-7000-8000-000000000100",
  "period_start": "2026-07-01",
  "period_end": "2026-08-01",
  "force_usage_sync": true
}
```

```json
{
  "job_id": "01900000-0000-7000-8000-000000000200",
  "status": "PENDING",
  "resource_type": "invoice_preview",
  "resource_id": null
}
```

### 重新计算

```json
{
  "expected_version": 4,
  "preserve_adjustments": true,
  "preserve_exclusions": true,
  "usage_strategy": "LATEST_VALIDATED_SNAPSHOT"
}
```

成功后预览版本变为 5，旧审批失效。

### 正式化

```http
POST /api/v1/invoice-previews/{id}/finalize
Idempotency-Key: finalize-preview-019-version-5
If-Match: "5"
```

```json
{
  "expected_version": 5,
  "confirmation_note": "Finance review completed"
}
```

```json
{
  "invoice_id": "01900000-0000-7000-8000-000000000300",
  "invoice_number": "INV-202607-CUST-2026-0001-001",
  "document_status": "FINALIZING",
  "job_id": "01900000-0000-7000-8000-000000000301"
}
```

## 11. 正式账单

```text
GET    /api/v1/invoices
GET    /api/v1/invoices/{id}
GET    /api/v1/invoices/{id}/pdf
POST   /api/v1/invoices/{id}/send
POST   /api/v1/invoices/{id}/void
POST   /api/v1/invoices/{id}/create-replacement-preview
```

作废和创建更正预览均要求 `If-Match`、`Idempotency-Key`、`expected_version` 与非空原因。存在有效付款分配时不能作废；更正预览返回新的 `preview_id`，不会修改原正式账单冻结内容。

## 12. 付款

```text
GET    /api/v1/payments
POST   /api/v1/payments
GET    /api/v1/payments/{id}
POST   /api/v1/payments/{id}/allocations
POST   /api/v1/payments/{id}/allocations/{allocation_id}/reverse
POST   /api/v1/payments/{id}/refunds
```

分配冲销和退款均要求 `If-Match`、`Idempotency-Key`、期望付款版本与非空原因；分配历史通过 `ACTIVE -> REVERSED` 留痕，不执行物理删除。

## 13. 文件、任务和导入

```text
POST   /api/v1/files
GET    /api/v1/files/{id}
GET    /api/v1/files/{id}/content
GET    /api/v1/jobs
GET    /api/v1/jobs/{id}
POST   /api/v1/jobs/{id}/retry
GET    /api/v1/imports
POST   /api/v1/imports/master-data
GET    /api/v1/imports/{id}
POST   /api/v1/imports/{id}/confirm
GET    /api/v1/imports/{id}/error-file
```

上传文件记录必须保存 `created_by`，对象键按 `{tenant_id}/uploads/{user_id}/{sha256}/{filename}` 隔离，不能仅按租户和哈希复用其他用户的文件记录。文件首次绑定到导入任务或预览调整附件时，普通用户只能引用自己上传且未软删除的文件；`system.admin` 可以在同租户内代绑定。不存在、已删除或其他用户的文件统一返回 `404 RESOURCE_NOT_FOUND`，避免泄露文件 ID 是否有效。该规则不改变已经冻结到正式账单中的历史证据。

## 14. 系统管理、运维和报表

```text
GET    /api/v1/system/users
POST   /api/v1/system/users
POST   /api/v1/system/users/{id}/status
POST   /api/v1/system/users/{id}/roles
GET    /api/v1/system/roles
POST   /api/v1/system/roles
POST   /api/v1/system/roles/{id}
GET    /api/v1/system/permissions

GET    /api/v1/operations/settings
PATCH  /api/v1/operations/settings
GET    /api/v1/operations/status
GET    /api/v1/reports/receivables
```

## 15. Webhook

Webhook 载荷包含事件 ID、类型、发生时间、租户、资源 ID 和数据版本。

头部：

```text
X-Auto-Invoice-Event-Id
X-Auto-Invoice-Timestamp
X-Auto-Invoice-Signature
```

签名：`HMAC-SHA256(secret, timestamp + "." + raw_body)`。

## 16. 主要错误码

| HTTP | code | 含义 |
|---:|---|---|
| 400 | `VALIDATION_FAILED` | 字段或业务规则校验失败 |
| 401 | `AUTHENTICATION_REQUIRED` | 未认证或会话过期 |
| 403 | `PERMISSION_DENIED` | 没有权限 |
| 404 | `RESOURCE_NOT_FOUND` | 对象不存在或不属于当前租户 |
| 409 | `VERSION_CONFLICT` | ETag/版本不匹配 |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 同一键用于不同请求 |
| 409 | `DUPLICATE_BILLING_ASSIGNMENT` | 重复自动计费归属 |
| 409 | `PREVIEW_ALREADY_FINALIZED` | 预览已正式化 |
| 422 | `BILLING_DATA_INCOMPLETE` | 用量或价格不足以计费 |
| 422 | `APPROVAL_INVALIDATED` | 审批版本已失效 |
| 422 | `TEMPLATE_VALIDATION_FAILED` | 模板语法或安全失败 |
| 422 | `LIBRENMS_ORIGIN_INVALID` | LibreNMS 地址不是无凭据、无 query/fragment/非根路径的 HTTP(S) origin |
| 422 | `LIBRENMS_ORIGIN_NOT_ALLOWED` | LibreNMS origin 不在部署 allowlist 中 |
| 503 | `LIBRENMS_ORIGINS_NOT_CONFIGURED` | 未配置 LibreNMS origin allowlist，外连默认关闭 |
| 503 | `DEPENDENCY_UNAVAILABLE` | LibreNMS、MinIO 或通知依赖不可用 |
