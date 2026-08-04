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
spring_session
spring_session_attributes
files
idempotency_keys
number_sequences
background_jobs
outbox_events
audit_logs
tenant_operational_settings
import_jobs
import_row_errors
import_staging_rows
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
librenms_discovered_bills
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
payment_refunds
notification_templates
notification_logs
webhook_endpoints
```

## 3. 关键约束

- `tenant_id + customer_no/company_code/service_no/contract_no` 唯一。
- 租户表之间的结构化引用同时保留 `(tenant_id, parent_id) -> (tenant_id, id)` 复合外键，数据库必须直接拒绝跨租户关系。
- 同一价格规则版本号唯一，已发布版本有效期不得重叠。
- `pricing_rules.current_version_id` 必须引用同租户的价格版本；发布版本时原子更新当前版本指针。
- 同一有效计费项只能有一个开放式 `CHARGE` 关联。
- 正式账单 `source_preview_id` 唯一。
- 正式编号在租户内唯一。
- 付款外部流水号在来源系统内唯一。
- 计费价格、数量、阶梯边界和阶梯单价不得为负；取整模式和小数位必须属于受控范围。
- Outbox 事件 ID、任务唯一键和 API 幂等键唯一。
- 同一原正式账单只能存在一个 `REPLACES` 关系。
- 正式明细和调整的预览来源 ID 必须非空，并在同一正式账单内唯一；正式记录必须逐列等于来源预览，且确认前完整覆盖全部未排除明细和全部 `ACTIVE` 调整。
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
- 正式账单主表只允许以 `FINALIZING`、`NOT_QUEUED`、`UNPAID`、`version = 0` 且生命周期时间全空的初始状态插入；插入必须绑定同租户、同预览、状态为 `APPROVED` 的审批实例，审批修订和审批前内容版本必须与刚进入 `FINALIZING` 的预览一致，正式抬头、账期、模板、币种、金额和 JSON 冻结快照必须逐项等于该预览。后续仅允许更新文档状态、发送状态、付款状态、生命周期时间戳和版本，禁止更新冻结财务列。
- 正式明细和调整只能在父账单处于 `FINALIZING` 时插入，并必须精确复制该账单来源预览中的未排除明细和 `ACTIVE` 调整；预览处于 `FINALIZING` 或 `FINALIZED` 时，明细、调整和排除项禁止增删改。更正关系只能连接仍为 `VOIDED` 的原账单与已确认的更正账单，且更正账单确认、关系写入和原账单 `VOIDED -> REPLACED` 必须在同一事务提交。
- `audit_logs` 只允许 INSERT。
- 已发布价格阶梯、模板资源和审批步骤不可增删改；完成的审批实例和审批动作不可篡改。
- 正式用量证据关联及其底层文件元数据不可修改，已经软删除的文件不得新建任何冻结证据引用；正式账单从 `FINALIZING` 确认时只接受未软删除、非空、MIME、哈希、模板和渲染器信息完整的 PDF。付款、分配和退款历史禁止物理删除。
- 付款主记录状态由有效分配和已确认退款在数据库触发器中派生，应用或直接 SQL 不能手工覆盖；每次真实分配或退款状态变化只递增一次付款版本，同状态退款 `UPDATE` 不得制造虚假版本。分配只允许 `ACTIVE -> REVERSED`，并且冲销操作者、时间和 `btrim` 后非空原因必须同时存在；退款只允许受控状态迁移，金额、归属和原始凭据不可原地修改。付款子表外键已经在父付款上持有 `KEY SHARE`，余额守恒和状态刷新统一使用兼容该外键锁的 `FOR NO KEY UPDATE` 串行化，禁止用 `FOR UPDATE` 制造并发锁升级死锁；等待父锁后使用 `clock_timestamp()` 写入更新时间，避免事务开始时间早于前一笔已提交更新时间而误触单调性约束。
- 账单付款状态同样由有效分配在数据库内派生：分配插入或 `ACTIVE -> REVERSED` 后，触发器同步计算 `UNPAID`、`PARTIALLY_PAID`、`PAID`、`OVERDUE`，按账单时区判断逾期并维护 `paid_at`、`updated_at` 和版本。应用服务只读取派生结果，不执行第二次状态写入。
- 发布版本与子表修改、正式账单与审批动作、正式明细与用量证据关联使用同一父行 `FOR UPDATE` 锁串行化，避免检查与冻结之间的并发穿透。用量证据链接移动必须先按 UUID 顺序锁定全部 OLD/NEW 快照，再按 UUID 顺序锁定全部 OLD/NEW 文件；模板发布、快照转正式和正式明细引用沿用相同的“父对象后文件”全局锁序。
- 正式账单的确认、首次发送和作废时间只能在对应状态迁移时首次写入，之后不可改写；发送成功必须同步推进文档状态，付款状态变化必须与有效付款分配余额相符，生命周期时刻不得倒序或写入未来时间。
- 所有应用与触发器函数显式使用 `SECURITY INVOKER`，并固定 `search_path = pg_catalog, public, pg_temp`；`public` 必须位于显式 `pg_temp` 之前，防止应用会话用临时同名表遮蔽付款、冻结证据或审计所依赖的真实表。
- 数据库使用不同角色区分迁移、应用和只读报表权限。

生产部署至少分离以下角色：

| 角色 | 权限边界 |
|---|---|
| `auto_invoice_admin` | 仅初始化数据库角色、预创建 `btree_gist` 扩展、备份与恢复；API 和 Worker 不得使用 |
| `auto_invoice_migrator` | Flyway 专用，拥有业务 Schema 和 DDL 权限，不承载运行流量 |
| `auto_invoice_app` | API/Worker 共用运行角色，仅拥有既有业务表 DML、序列和必要函数权限，无 DDL 和迁移历史表权限 |

`R__application_grants.sql` 为运行角色授予既有对象和默认对象权限，并再次撤销审计、审批动作、正式账单明细等不可变表的 UPDATE/DELETE，以及账单、付款、分配和退款历史的 DELETE 与 `flyway_schema_history` 的全部权限。生产 API/Worker 默认关闭内置 Flyway，只允许一次性 Flyway 服务使用迁移角色执行版本迁移。

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
- 当前实施迁移为 `V1` 至 `V29`，另有可重复权限迁移 `R__application_grants.sql`。`V14` 加固付款余额约束，`V15` 加固会话/MFA/幂等，`V16` 加固审计链和通知恢复，`V17` 加固临时凭据和 MFA enrollment proof，`V18` 加固计费输入、发布版本子表、审批、正式证据与付款历史，`V19` 为租户内结构化关系补充复合唯一键和复合外键并校验认证限流作用域，`V20` 关闭冻结文件引用的并发锁窗口并统一用量链接锁序，`V21` 强制付款状态由有效分配和已确认退款派生、拒绝空白冲销原因并跳过无变化退款刷新，`V22` 固定全部应用函数的调用者权限和对象解析路径，阻断 `pg_temp` 同名表遮蔽，`V23` 消除付款子表外键锁与父付款串行锁之间的升级死锁，`V24` 禁止直接插入伪造的已冲销付款分配历史，`V25` 强制正式账单从唯一合法初始生命周期状态进入状态机，`V26` 补齐正式账单确认/作废和付款分配并发锁序，`V27` 强制审批与预览精确绑定、拒绝已删除证据并冻结生命周期时间和派生付款状态，`V28` 强制正式明细/调整完整且逐列等于批准预览，并把更正确认与 `REPLACES` 生命周期收敛为单事务，`V29` 让付款分配同步派生账单付款状态。`docs/sql/001-initial-schema.sql` 是可独立执行的参考快照并由 PostgreSQL 集成测试校验；Flyway 文件仍是运行时权威顺序。
