# Auto Invoice 自动账单系统

Auto Invoice 是面向 IDC、带宽、托管、IP、机柜、专线和其他周期性业务的商业账单自动化平台。

系统从客户、公司、业务、合同和计费规则出发，对接 LibreNMS Bill History 和流量图，生成可审核的预览账单；财务确认后冻结数据、模板和 PDF，形成可追溯的正式账单。

## 核心能力

- 同一客户、公司和账期可以生成多张独立账单。
- 合同计费项是自动出账的最小归属单位，可显式分摊或仅复制展示。
- 支持 95 带宽、固定带宽、总流量、固定月费、数量单价、阶梯价格、按天折算和一次性费用。
- LibreNMS 提供流量与 Bill History，Auto Invoice 负责金额、审核、模板、PDF、通知和付款。
- 预览账单可以重算和调整，正式账单进入冻结状态后不能原地修改。
- 支持 HTML/CSS 模板、字段别名、多列表、模板版本和安全 PDF 渲染。
- 保存用量、价格、汇率、税率、调整、审批、模板和文件哈希，保证历史可重现。
- 为 CRM、工单、支付、财务、电子发票、网络控制和多租户 SaaS 预留接口。

## 已确定的技术方向

- Java 21、Spring Boot、Spring Security。
- Vue 3、TypeScript、Element Plus、ECharts。
- PostgreSQL 16+、Redis、Quartz、MinIO/S3。
- 模块化单体 API，加独立同步、计费、渲染和通知 Worker。
- Handlebars 无脚本模板沙箱，Playwright/Chromium 生成 PDF。
- Docker Compose 作为首版私有化部署基线。

## 文档入口

完整设计拆分在 [`docs/README.md`](docs/README.md)。

推荐阅读顺序：

1. [`docs/01-product-scope.md`](docs/01-product-scope.md)：系统目标、范围、角色和成功标准。
2. [`docs/02-architecture.md`](docs/02-architecture.md)：总体架构、部署和模块边界。
3. [`docs/03-domain-model.md`](docs/03-domain-model.md)：客户、业务、合同、账单等领域模型。
4. [`docs/04-billing-engine.md`](docs/04-billing-engine.md)：计费规则、精度、取整、税费和快照。
5. 按具体任务继续阅读 `docs/` 中对应专题。

后续 Agent 必须先阅读 [`AGENTS.md`](AGENTS.md)。

## 当前状态

仓库处于设计和初始化阶段，尚无业务代码。第一阶段应先完成领域模型、数据库迁移、计费引擎和 LibreNMS 契约，再实现后台页面与自动发送。
