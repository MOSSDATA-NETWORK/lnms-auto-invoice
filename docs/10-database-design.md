# 数据库设计

## 1. 基线

- PostgreSQL 16+。
- 应用生成 UUIDv7，排序使用 `created_at` 和业务序列，不依赖 UUID 自然顺序。
- 所有租户业务表包含 `tenant_id`。
- 金额计算值使用 `numeric(30,12)`，最终金额使用 `bigint` 最小货币单位。
- 时刻使用 `timestamptz`，业务账期保存开始和结束边界。
- 状态使用 `varchar + CHECK`，通过 Flyway 演进。

完整参考 SQL：[`sql/001-initial-schema.sql`](sql/001-initial-schema.sql)。

## 2. 表分组

### 平台与身份

```text
tenants
currencies
users
external_identities
roles
permissions
role_permissions
user_roles
user_sessions
files
idempotency_keys
number_sequences
background_jobs
outbox_events
audit_logs
import_jobs
import_row_errors
```

### 客户、业务和合同

```text
customers
companies
customer_contacts
products
service_groups
services
service_resources
contracts
contract_files
contract_items
pricing_rules
pricing_rule_versions
pricing_tiers
```

### LibreNMS 和用量

```text
librenms_instances
librenms_bill_mappings
usage_sync_runs
usage_current
usage_snapshots
usage_snapshot_files
```

### 模板和审批

```text
invoice_templates
invoice_template_versions
invoice_template_assets
template_bindings
approval_workflows
approval_workflow_versions
approval_steps
approval_instances
approval_actions
```

### 账单和付款

```text
invoice_profiles
invoice_profile_assignments
invoice_batches
invoice_previews
invoice_preview_items
invoice_preview_adjustments
invoice_preview_exclusions
invoices
invoice_items
invoice_adjustments
invoice_files
invoice_relations
payments
payment_allocations
notification_templates
notification_logs
```

## 3. 关键约束

- `tenant_id + customer_no/company_code/service_no/contract_no` 唯一。
- 同一价格规则版本号唯一，已发布版本有效期不得重叠。
- 同一有效计费项只能有一个开放式 `CHARGE` 关联。
- 正式账单 `source_preview_id` 唯一。
- 正式编号在租户内唯一。
- 付款外部流水号在来源系统内唯一。
- Outbox 事件 ID、任务唯一键和 API 幂等键唯一。
- 正式账单明细和审计记录禁止 UPDATE/DELETE。

价格有效期不重叠和百分比分摊合计 100% 需要事务级校验；数据库索引负责基础冲突防护，应用服务负责跨行汇总规则。

## 4. 索引

必须覆盖：

- 客户、公司、业务和合同编号查询。
- 计费项按合同、业务、状态和有效期查询。
- 账单配置按客户、公司和状态查询。
- 预览和正式账单按客户、公司、账期、状态和创建时间查询。
- 用量快照按计费项和账期查询。
- 同步、任务、Outbox 和通知按状态与下一执行时间领取。
- 审计按对象、操作者和时间查询。

## 5. 分区

达到以下规模后启用月度或季度分区：

- `audit_logs`
- `usage_sync_runs`
- `notification_logs`
- `outbox_events` 已发布历史
- `background_jobs` 已完成历史

正式账单和用量快照先保持普通表，除非实际数据证明分区有收益。

## 6. 不可变策略

- 正式化将所有冻结数据复制到正式账单和明细表。
- `invoice_items`、`invoice_adjustments` 和正式文件关联只允许 INSERT。
- 正式账单主表允许更新文档状态、发送状态、付款状态和时间戳，禁止更新冻结财务列。
- `audit_logs` 只允许 INSERT。
- 数据库使用不同角色区分迁移、应用和只读报表权限。

## 7. JSONB 使用范围

适合 JSONB：

- 类型专属价格参数。
- 计算快照。
- 模板字段和列表配置。
- 冻结抬头、付款账户和渲染模型。
- 外部响应摘要和扩展属性。

不适合 JSONB：

- 需要唯一、外键、范围、金额或高频过滤的核心字段。
- 正式账单总额和状态。
- 计费项归属和付款分配。

## 8. 迁移规则

- 使用 Flyway，版本迁移只前进。
- 删除列分两次发布：先停止读写，再在后续版本删除。
- 大表索引使用 `CREATE INDEX CONCURRENTLY` 的独立运维迁移。
- 迁移前执行备份和恢复演练。
- 应用启动时只检查迁移状态，生产环境由部署流程执行迁移。
