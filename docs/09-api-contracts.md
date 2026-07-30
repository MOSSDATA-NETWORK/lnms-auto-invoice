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

## 12. 付款

```text
GET    /api/v1/payments
POST   /api/v1/payments
GET    /api/v1/payments/{id}
POST   /api/v1/payments/{id}/allocations
DELETE /api/v1/payments/{id}/allocations/{allocation_id}
POST   /api/v1/payments/{id}/refund
```

## 13. 文件、任务和导入

```text
POST   /api/v1/files/upload-intents
GET    /api/v1/files/{id}/download-url
GET    /api/v1/jobs/{id}
POST   /api/v1/jobs/{id}/retry
POST   /api/v1/imports/master-data
GET    /api/v1/imports/{id}
GET    /api/v1/imports/{id}/error-file
```

## 14. Webhook

Webhook 载荷包含事件 ID、类型、发生时间、租户、资源 ID 和数据版本。

头部：

```text
X-Auto-Invoice-Event-Id
X-Auto-Invoice-Timestamp
X-Auto-Invoice-Signature
```

签名：`HMAC-SHA256(secret, timestamp + "." + raw_body)`。

## 15. 主要错误码

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
| 503 | `DEPENDENCY_UNAVAILABLE` | LibreNMS、MinIO 或通知依赖不可用 |
