# Auto Invoice 文档目录

文档按“先理解目标和边界，再阅读具体技术参考”的顺序组织。

| 文档 | 内容 | 主要读者 |
|---|---|---|
| [`01-product-scope.md`](01-product-scope.md) | 产品目标、范围、角色、术语、阶段和成功标准 | 产品、业务、项目经理 |
| [`02-architecture.md`](02-architecture.md) | 总体架构、模块边界、部署、数据流和故障边界 | 架构、后端、运维 |
| [`03-domain-model.md`](03-domain-model.md) | 客户、公司、业务、合同、计费项、账单配置和账单模型 | 后端、数据库、测试 |
| [`04-billing-engine.md`](04-billing-engine.md) | 95、固定费、阶梯、折算、金额精度、税费和计算快照 | 业务、后端、测试 |
| [`05-librenms-integration.md`](05-librenms-integration.md) | LibreNMS API、映射、同步、用量留存和异常处理 | 网络、后端、运维 |
| [`06-invoice-lifecycle.md`](06-invoice-lifecycle.md) | 多账单、预览、审批、正式化、发送、作废和更正 | 业务、财务、后端 |
| [`07-template-and-pdf.md`](07-template-and-pdf.md) | 模板版本、字段、多列表、安全沙箱和 PDF | 模板管理员、前后端 |
| [`08-security-and-audit.md`](08-security-and-audit.md) | 租户、认证、RBAC、秘密、审计和数据保护 | 安全、后端、运维 |
| [`09-api-contracts.md`](09-api-contracts.md) | REST 约定、端点、请求响应、错误码和 Webhook | 前后端、集成团队 |
| [`10-database-design.md`](10-database-design.md) | 表分组、约束、索引、分区和不可变策略 | 后端、DBA |
| [`sql/001-initial-schema.sql`](sql/001-initial-schema.sql) | PostgreSQL 16+ 初始参考 DDL | 后端、DBA |
| [`11-frontend-and-operations.md`](11-frontend-and-operations.md) | 菜单、页面行为、任务、部署、监控和备份 | 前端、运维、实施 |
| [`12-testing-and-roadmap.md`](12-testing-and-roadmap.md) | 测试矩阵、数据导入、并行上线和三阶段路线图 | 测试、项目经理、全体研发 |

## 阅读路径

### 新加入项目

```text
auto-invoice.md
  -> AGENTS.md
  -> 01-product-scope.md
  -> 02-architecture.md
  -> 03-domain-model.md
```

### 实现一个功能

先读领域和专题文档，再读 API 与数据库文档，最后检查测试矩阵。

### 修改设计

更新专题文档后，检查：

- `AGENTS.md` 中的不变量是否需要调整。
- `09-api-contracts.md` 是否需要更新接口。
- `10-database-design.md` 和 SQL 是否保持一致。
- `12-testing-and-roadmap.md` 是否需要新增回归或验收场景。
