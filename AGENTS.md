# Auto Invoice Agent 指南

本文件是所有开发、评审、测试和文档 Agent 的必读入口。

## 1. 开始任何任务前

至少阅读：

1. [`auto-invoice.md`](auto-invoice.md)
2. [`docs/README.md`](docs/README.md)
3. [`docs/01-product-scope.md`](docs/01-product-scope.md)
4. [`docs/02-architecture.md`](docs/02-architecture.md)
5. [`docs/03-domain-model.md`](docs/03-domain-model.md)

然后根据任务阅读：

| 任务 | 必读文档 |
|---|---|
| 计费、金额、税费、取整 | `docs/04-billing-engine.md` |
| LibreNMS、流量、同步 | `docs/05-librenms-integration.md` |
| 预览、审批、正式账单 | `docs/06-invoice-lifecycle.md` |
| 模板、HTML、PDF | `docs/07-template-and-pdf.md` |
| 用户、权限、租户、安全 | `docs/08-security-and-audit.md` |
| REST API、Webhook | `docs/09-api-contracts.md` |
| 数据库、约束、索引 | `docs/10-database-design.md` 和 `docs/sql/001-initial-schema.sql` |
| 后台页面、部署、运维 | `docs/11-frontend-and-operations.md` |
| 测试、迁移、上线、路线图 | `docs/12-testing-and-roadmap.md` |

## 2. 不可破坏的业务不变量

1. 客户、公司、业务、合同、合同计费项、账单配置和账单是独立实体。
2. 合同计费项是自动出账最小单位；普通模式下只能归属一个有效自动账单配置。
3. 一个计费项进入多张账单时，必须使用显式分摊或 `DISPLAY_ONLY`，不能隐式重复收费。
4. LibreNMS Bill History 是 95 用量的权威来源；不要使用“入向 95 + 出向 95”代替 Aggregate。
5. 预览和正式账单使用不同持久化实体。
6. 正式账单进入 `FINALIZING` 后，金额、用量、价格、模板和账单抬头立即冻结。
7. 正式账单不能原地修改；更正采用“作废原账单 -> 新预览 -> 重新审核 -> 新正式账单”。
8. 金额只使用十进制定点数和最小货币单位；禁止使用浮点数计算金额。
9. 账期使用半开区间 `[period_start, period_end)`；数据库时刻统一保存 UTC。
10. 所有外部副作用必须幂等，并通过持久任务或事务 Outbox 执行。
11. 任何影响金额、用量、模板、账期、币种或付款条件的预览修改都会使审批失效。
12. Redis 不是账单、任务或支付的事实来源。

## 3. 实现约束

- 后端使用模块化单体，模块之间通过应用服务接口或领域事件交互；禁止跨模块直接写表。
- API 和 Worker 使用同一领域代码，不复制计费规则。
- Quartz 负责调度，PostgreSQL `background_jobs` 负责持久任务、租约、重试和死信。
- 所有命令型 POST 接口支持 `Idempotency-Key`；可修改资源支持版本号或 `If-Match`。
- 模板禁止 JavaScript、远程 URL、文件系统和任意 helper；Render Worker 默认无网络。
- 正式文件必须保存 SHA-256、模板版本、渲染器版本和对象存储引用。
- 所有租户数据表必须包含 `tenant_id`，唯一约束和查询必须考虑租户范围。
- 正式账单和审计记录不得物理删除。

## 4. 推荐开发顺序

```text
项目骨架和数据库迁移
  -> 身份、租户、客户、公司、业务和合同
  -> 版本化价格规则和纯计费引擎
  -> LibreNMS 适配器和用量快照
  -> 账单配置和重复计费检测
  -> 预览、审批和正式化
  -> 模板沙箱和 PDF Worker
  -> 通知、付款、报表和客户门户
```

## 5. 测试要求

- 计费引擎必须有参数化和边界单元测试。
- PostgreSQL 约束、幂等和正式化使用 Testcontainers 集成测试。
- LibreNMS 使用固定脱敏样本做 CI 契约测试，并在预发布环境做真实只读冒烟测试。
- 预览到正式账单、Worker 崩溃恢复、重复提交和权限隔离必须有端到端测试。
- 模板需要 XSS、SSRF、脚本、远程资源和超限资源安全测试。
- 任何修复账单金额的缺陷都必须添加回归测试。

## 6. 文档维护

- 代码、数据库或 API 改变本文档描述的行为时，同一变更必须更新对应 `docs/*.md`。
- 新增状态、错误码、计费类型或公共接口时，先更新专题文档和测试矩阵。
- 不要把所有设计重新堆回 `auto-invoice.md`；根文档只保留项目总览和导航。
- `docs/README.md` 是文档目录，新增文档必须在其中登记。

## 7. 当前仓库事实

- 默认分支：`main`。
- 远程仓库：`https://gitee.com/xxiax/auto-invoice.git`。
- 当前阶段：系统设计和初始化，尚无生产代码和历史兼容负担。
