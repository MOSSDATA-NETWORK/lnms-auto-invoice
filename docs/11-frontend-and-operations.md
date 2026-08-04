# 后台界面、部署与运维

本文件定义首版后台信息架构、关键页面行为和私有化部署运维基线。页面不能绕过领域规则，所有生成、同步、正式化和发送操作都通过后端命令与持久任务执行。

## 1. 后台菜单

```text
控制台

客户管理
  ├─ 客户列表
  ├─ 公司主体
  ├─ 联系人
  └─ 客户标签

业务管理
  ├─ 业务列表
  ├─ 产品类型
  ├─ 服务资源
  └─ 业务分组

合同管理
  ├─ 合同列表
  ├─ 合同计费项
  ├─ 价格规则
  ├─ 合同附件
  └─ 到期提醒

LibreNMS
  ├─ 数据源
  ├─ Bill 映射
  ├─ 当前用量
  ├─ 历史用量
  ├─ 同步任务
  └─ 同步日志

账单模板
  ├─ 模板列表
  ├─ 新建与上传
  ├─ 模板编辑器
  ├─ 字段与列表配置
  ├─ 模板预览
  └─ 模板版本

账单中心
  ├─ 账单配置
  ├─ 待生成账单
  ├─ 预览账单
  ├─ 待审核账单
  ├─ 正式账单
  ├─ 异常与作废账单
  └─ 账单批次

付款管理
通知中心
报表中心
系统管理
```

菜单、路由、按钮和批量操作都按权限代码控制。无权限页面不出现在导航中，后端仍必须独立鉴权。总览卡片、图表、金额和深链也按权限隐藏；当账号没有可展示指标时显示安全空态，不以零值或假数据代替被省略的字段。未结应收按币种逐项显示，使用服务端返回的 `minor_unit` 格式化，不能在浏览器把不同币种合计为一个金额。

## 2. 前端技术基线

- React 19、TypeScript、Vite、Tailwind CSS 4 和 shadcn/ui。
- 以 `satnaing/shadcn-admin` 提交 `e16c87f213a5ba5e45964e9b67c792105ec74d26` 为一次性代码基线；保留布局、Sidebar、主题、Command Menu、Data Table、Dialog、错误页和测试配置，不自动合并上游。
- TanStack Router 使用文件路由、权限守卫和按路由代码分割。
- TanStack Query 管理服务端状态、缓存失效和异步任务轮询；TanStack Table 管理服务端分页、排序、筛选和 URL 状态。
- React Hook Form + Zod 用于表单结构和客户端提示，后端始终是业务校验权威。
- Zustand 仅保存主题、侧栏和短期 UI 状态，不保存账单、付款或任务事实。
- Recharts/shadcn Charts 展示应收、账龄和趋势；LibreNMS 原始流量图继续作为受控证据文件或受控数据展示。
- React i18next 提供简体中文语言包，所有新增用户文本必须进入语言资源。
- OpenAPI + Orval + Axios 生成 API 类型、客户端和 Query Hooks；禁止长期保留手工重复 DTO。
- Decimal.js 仅用于安全展示、输入归一化和格式化，不在浏览器计算权威金额。
- 金额、数量、税率和汇率在前端保持十进制字符串，不转换为 JavaScript 浮点数参与计算。
- 日期时间从 API 读取 ISO 8601，按当前租户或账单时区展示。
- Node.js 固定使用 24 LTS，Corepack/CI 固定 pnpm 10.34.5；锁文件与 `packageManager` 字段必须同步。
- pnpm 保持依赖最短发布时间策略。遇到新发布的传递依赖时，优先在 `pnpm-workspace.yaml` 固定到已过等待期的兼容版本，不关闭供应链质量门；需要执行安装脚本的包必须通过 `allowBuilds` 明确列出。

前端静态文件由 Reverse Proxy 提供，`/api/v1` 转发至 Spring Boot。登录采用服务端会话、HttpOnly Cookie 和 CSRF Token；前端代码不得读取或持久化认证 Token。

### 2.1 OpenAPI 与 Orval 工作流

- `openapi/auto-invoice.json` 是提交到仓库的生成输入；`frontend/orval.config.ts` 默认读取该文件，也允许通过 `OPENAPI_URL` 临时覆盖。
- `pnpm run api:generate` 输出到 `frontend/src/api/generated/`。生成目录只由 Orval 更新，页面和手写 API 文件不得在其中修改代码。
- 页面查询通过生成的请求函数或 Query Hooks访问 API，并将取消信号传到底层 Axios。认证、客户、仪表盘以及账单、付款、任务、主数据、LibreNMS、模板、报表和运维查询均使用同一生成客户端。
- 命令型薄封装负责 CSRF、幂等键、ETag/`If-Match` 和界面动作语义；其请求体和响应类型仍引用生成模型。
- 共享 mutator 统一设置同源 Cookie、CSRF Header、`X-Request-Id`、Problem Details 错误入口和 `/api/v1` 路径归一化。

## 3. 通用页面规则

### 3.1 列表页

列表页必须支持服务端分页、筛选、排序、列显示配置和当前条件导出。URL 保存分页与筛选条件，刷新或返回时不丢失上下文。

所有租户数据请求都使用当前租户上下文。系统管理员切换租户时，清空页面缓存、选中项和未提交表单。

### 3.2 编辑页

- 可修改资源使用 ETag 或版本号防止覆盖他人修改。
- 保存发生 `VERSION_CONFLICT` 时展示服务器新版本和用户当前输入，不静默覆盖。
- 离开有未保存内容的页面前提示用户。
- 币种、账期、计费规则和模板版本使用明确名称，不只展示 UUID。
- 删除和停用是不同动作；历史已被账单引用的数据通常只能停用或归档。

### 3.3 异步任务

同步、导入、批量生成、重新计算、正式化、PDF 和发送返回 `job_id`。前端展示：

- 当前状态和阶段。
- 已处理、成功和失败数量。
- 最近错误及可下载错误文件。
- 创建时间、开始时间和完成时间。
- 在权限允许且任务可重试时提供重试按钮。

页面关闭不取消持久任务。重新打开时通过任务 ID 恢复状态。

### 3.4 错误展示

前端读取 RFC 9457 Problem Details。字段错误显示在输入项附近，业务冲突和依赖故障显示可操作原因、关联对象和建议动作。不得把后端堆栈或第三方 Token 显示给用户。

## 4. 关键页面

### 4.1 客户与公司

客户详情使用标签页展示公司、联系人、业务、合同、账单配置、正式账单和付款。公司页突出账单抬头、税务信息、默认模板、币种和付款账户。

创建合同或账单配置时，只允许选择同一客户下的公司和业务。

### 4.2 合同与价格规则

合同编辑器分开处理合同基础信息、计费项和附件。每个计费项必须能直接查看：

- 关联业务和价格规则。
- 当前与未来生效的价格版本。
- LibreNMS 映射和最近有效用量。
- 所属账单配置及分摊模式。

已发布价格版本只读。修改价格时创建新版本，并在时间轴上展示有效期冲突。

当前合同工作台提供合同创建与激活、计费项新增、六类价格版本、最低消费、封顶、税率、折扣、用量取整、按天折算、阶梯编辑、校验和发布。阶梯必须从零开始连续，只有最后一档允许开放上限。

### 4.3 LibreNMS 映射

映射页同时显示 LibreNMS Bill 信息和本地客户、公司、业务、计费项。启用映射前执行连接、Bill 存在性、账期、单位和重复映射检查。

当前用量只能用于观察。预览引用的 Bill History、原始响应、图像和哈希从用量快照页查看。

### 4.4 账单配置

配置编辑器以合同计费项为选择单位，显示 `CHARGE`、百分比分摊、固定分摊和 `DISPLAY_ONLY`。保存前实时提示重复收费、分摊不完整、客户或币种冲突。

右侧摘要固定展示模板、账期、编号规则、收件人、付款账户和自动化开关，避免配置分散在多个页面后失去整体判断。

### 4.5 预览账单

预览详情至少包含：

- 账单抬头、账期、到期日、币种、模板和预览水印。
- 系统费用明细、用量、价格版本和计算过程。
- 人工调整、排除项、异常、审批记录和版本历史。
- 流量图、原始证据、预览 PDF 和后台任务。

系统明细金额不可直接编辑。用户通过“排除明细”或“新增调整”表达变更。任何影响正式内容的操作都增加预览版本并使旧审批失效。

### 4.6 正式账单

正式账单页面默认只读，展示冻结数据、审批、PDF 哈希、模板和渲染器版本。允许的动作只有发送、下载、记录付款、发起作废和创建更正预览。

正式账单处于 `FINALIZING` 时显示渲染任务和重试状态，不允许再次正式化或重新分配编号。

### 4.7 模板中心

模板编辑器分为 HTML、CSS、字段、列表、资源和测试数据。预览在隔离 Render Worker 中执行。发布前展示语法、安全、变量、资源大小和分页检查结果。

已发布版本不能覆盖保存；编辑动作自动创建草稿版本。

### 4.8 业务与服务资源

业务工作台区分产品标准定义、客户业务实例和服务资源引用。创建业务时公司决定客户归属；资源以设备、端口、IP、服务器、机柜、电力或线路等类型登记。资源停用不改写已生成的用量快照和正式账单。

## 5. 可访问性与国际化

- 首版后台至少支持简体中文，所有用户可见文本进入语言包。
- 表单控件具有标签、错误说明和键盘焦点。
- 状态不能只依赖颜色，必须同时显示文字或图标。
- 表格金额按币种精度显示，原始用量和计费用量分别标注。
- 长任务和审批动作向屏幕阅读器提供状态变化提示。

## 6. Docker Compose 部署基线

首版私有化部署包含：

| 服务 | 最小副本 | 持久数据 |
|---|---:|---|
| 外部 TLS Reverse Proxy | 1 | TLS 配置和证书引用；必须覆盖而不是透传客户端伪造的转发头 |
| React Web/Nginx | 1 | 无，容器只包含构建产物、静态安全头和 API 代理配置 |
| Flyway | 每次发布 1 个一次性任务 | 无；成功迁移后退出 |
| API | 1 | 无 |
| Scheduler/Billing Worker | 1 | Quartz 只调度，持久任务在 PostgreSQL |
| Sync/Render/Notify/Import Worker | 各 1 | 无 |
| PostgreSQL 16+ | 1 | 数据目录与备份 |
| MinIO | 1 | 模板、证据、PDF 和附件 |

当前实现没有运行时 Redis 依赖；后续加入缓存或分布式限速时，Redis 仍只能保存可丢失状态，不能成为账单、付款或任务事实来源。

生产环境必须使用外部秘密注入，不把数据库密码、LibreNMS Token、SMTP 密码或对象存储密钥写入 Compose 文件或 Git。数据库管理员、迁移和应用密码必须分离；MinIO root 只供初始化且只有 `minio-init` 创建 Bucket，API/Worker 运行时不会尝试建桶。API 只允许读取已登记对象并写入 `{tenant_id}/uploads/{user_id}/*`，Render 只写 `*/invoices/*`，Sync 只写 `*/usage/*`，Notification 只读 `*/invoices/*`，Import 只读 `*/uploads/*` 且只写 `*/imports/*`，Billing 使用无对象权限账号。单次对象读取上限为 `64 MiB`。账号轮换后必须验证旧凭据失效，并用每个运行账号分别验证越权前缀被拒绝。

Compose 要求显式设置 `LIBRENMS_ALLOWED_ORIGINS`。其值为逗号分隔的 HTTP(S) exact origins，例如
`https://librenms-a.example.com,https://librenms-b.example.com:8443`；不得包含 `/api` 等路径、凭据、query 或 fragment。
API 和 Sync Worker 必须接收同一值。默认端口会规范化，空值不允许创建数据源或发起同步。生产还应以出口网络策略限制
Sync Worker 到这些 LibreNMS 地址，降低 DNS 劫持或重绑定风险。

Compose 默认只把 Web 绑定到 `127.0.0.1`，由同机外部 TLS Proxy 暴露服务。Web 默认把 `AUTO_INVOICE_TRUSTED_PROXY_CIDR` 设为不可达的 TEST-NET 地址，等价于不信任任何转发代理；生产部署必须把它改为 Web 容器实际看到的、紧邻 TLS Proxy 的精确地址或最窄 CIDR。Nginx 只对该受信来源解析 `X-Forwarded-For`，认证限流使用解析后的真实地址，并覆盖发往 API 的 `Forwarded`、`X-Forwarded-*`，因此客户端直接伪造的转发头不会进入 Spring。`X-Forwarded-Proto` 也只接受受信 Proxy 提供的 `http` 或 `https`，否则回退到当前连接协议；仅在可信 HTTPS 链路上添加 HSTS。若显式改为公网绑定但没有配置受信 Proxy，系统会安全地退化为按直连地址限流且不信任外部协议头。

Nginx 对普通 API 请求使用 `1 MiB` 请求体上限，仅精确的 `POST /api/v1/files` 上传入口放宽到 `26 MiB`，并由 API 再执行 `25 MiB` 文件、扩展名、魔数和哈希校验。新增大请求接口时必须单独评审并显式配置 location，不得扩大全局上限。

API 和 Worker 默认 `FLYWAY_ENABLED=false`，发布流程先运行独立 Flyway 服务，再启动运行容器。PostgreSQL 初始化阶段由管理员预创建 `btree_gist`，业务迁移以非超级用户执行。API 和 Worker 启用只读根文件系统（需要写入的 `/tmp` 使用 tmpfs）、移除 Linux capabilities、禁止提权、配置健康检查并使用 `restart: unless-stopped`。Web/Nginx 同样以非 root 和只读根文件系统运行，移除全部 capabilities 并禁止提权；仅将 `/etc/nginx/conf.d`、`/var/cache/nginx`、`/var/run` 和 `/tmp` 挂为限额 tmpfs，用于渲染受信 Proxy 模板和 Nginx 运行时文件。API、Worker 和 Web 均配置可通过环境变量调优的 CPU、内存和 PID 硬上限；Render Worker 另有较大的 PID/共享内存额度，预置只读 Playwright Node 以兼容 `noexec /tmp`，并加载与 Playwright 镜像版本一致、仅为 user-namespace Chromium 沙箱放行 `chroot` syscall 的固定哈希 seccomp profile。容器仍保持 `CapEff=0`，不得通过 `CAP_SYS_CHROOT`、`--no-sandbox` 或 unconfined seccomp 放宽边界。

Render Worker 的应用层再施加 30 秒单次渲染硬超时、120,000 CSS px 实际布局高度、100 页和 16 MiB PDF 上限，并在超时、中断或异常时清理本次任务新建的 Chromium/Node 子孙进程。生产镜像变更或 Playwright/Chromium 升级时必须重跑无网络、只读根、`noexec /tmp`、固定 seccomp 和 `cap_drop: ALL` 的真实容器冒烟。

动态 OpenAPI 与 Swagger UI 默认关闭，生产 Web 不代理 `/v3/api-docs` 或 Swagger UI。更新静态契约时，只在隔离的本地或 CI 导出进程设置 `OPENAPI_DOCS_ENABLED=true`；正式环境不得为了前端运行而开启动态文档，因为前端构建只读取仓库提交的 `openapi/auto-invoice.json`。

Mailpit 仅属于 `dev` profile，管理端口只绑定 `127.0.0.1`。生产必须显式提供 SMTP Host 和发件地址，默认启用认证、强制 STARTTLS 并校验证书主机名；Mailpit 等无 TLS 本地服务必须同时显式关闭三项 TLS 设置并启用 `SMTP_ALLOW_INSECURE=true`，该豁免不得进入生产。会话 Cookie 默认 `Secure=true`，本地纯 HTTP 调试必须显式关闭且不得沿用到生产。

## 7. 配置分组

| 分组 | 主要内容 |
|---|---|
| 应用 | 外部 URL、租户模式、默认时区、语言 |
| 数据库 | JDBC、连接池、迁移检查 |
| Redis | 地址、TLS、缓存 TTL、限速 |
| 对象存储 | Endpoint、Bucket、加密和签名 URL 有效期 |
| LibreNMS | `LIBRENMS_ALLOWED_ORIGINS` exact-origin allowlist、超时、并发和重试 |
| PDF | Chromium 路径、字体目录、30 秒硬超时、HTML/CSS/data URI、120,000 CSS px、100 页和 16 MiB 上限 |
| 通知 | SMTP、Webhook 超时、重试和发送域名 |
| 安全 | 会话、MFA、OIDC、CORS、CSRF、加密密钥 |
| 任务 | 租约、重试次数、退避、死信和保留期 |

应用启动时验证必要配置。配置无效时快速失败，不使用危险默认值继续运行。

## 8. 定时任务

```text
每 5 分钟
  - 扫描待发送和重试通知
  - 回收过期任务租约

每小时
  - 同步当前账期用量
  - 检查 LibreNMS、流量和同步异常

每天
  - 检查待生成账单、合同到期和付款到期
  - 标记逾期账单并创建提醒任务

账期结束后
  - 同步上一完整 Bill History
  - 归档原始响应和流量图
  - 创建账单批次和预览
```

Quartz 只创建任务。任务领取、租约、重试和死信以 PostgreSQL `background_jobs` 为准。

自动生成和自动发送有租户级独立开关，并受紧急停用控制。自动任务使用显式系统用户执行；紧急停用必须记录原因，不取消已经完成的领域事务或删除历史 Outbox/通知记录。

## 9. 监控与告警

### 9.1 必备指标

- HTTP 请求量、错误率和延迟。
- 数据库连接池、慢查询、锁等待和磁盘使用率。
- 各类任务的排队数、最老等待时间、成功率和死信数。
- LibreNMS 同步延迟、Bill 缺失、采样覆盖率和连续失败。
- 预览生成、正式化和 PDF 渲染耗时与失败率。
- 通知发送率、退信和重试。
- 对象存储容量、请求错误和哈希校验失败。

### 9.2 首版告警

- 账期结束后 2 小时仍未取得完整 Bill History。
- 任一正式账单在 `FINALIZING` 停留超过 15 分钟。
- 后台任务死信数大于 0。
- 通知重试队列最老记录超过 30 分钟。
- 数据库或对象存储不可用。
- 备份失败或最近一次成功备份超过 24 小时。

结构化日志必须包含 `tenant_id`、`request_id`、`correlation_id`、`job_id` 和业务对象 ID。秘密、完整 Token、密码、MFA 秘钥和敏感付款资料禁止写入日志。

## 10. 备份与恢复

- PostgreSQL 每日全量备份并保留连续 WAL，以支持时间点恢复。
- MinIO/S3 开启版本控制或等价保护，正式 PDF 和证据文件禁止生命周期规则提前删除。
- 加密密钥、部署配置和恢复说明单独备份。
- 至少每季度在隔离环境完成一次数据库和对象存储联合恢复演练。
- 恢复后抽样校验正式账单数据库哈希、PDF 文件哈希和对象引用。

建议首版恢复目标：`RPO <= 24 小时`，`RTO <= 4 小时`。进入生产前由业务根据账单量和付款时效确认是否收紧。

## 11. 发布与回滚

1. 在预发布环境执行 Flyway 迁移和完整测试。
2. 备份 PostgreSQL、对象存储配置和当前应用镜像版本。
3. 先执行向后兼容数据库迁移，再滚动更新 API 和 Worker。
4. 验证健康检查、任务领取、LibreNMS 只读同步、预览和 PDF 冒烟场景。
5. 观察错误率、任务积压和正式化状态后再开放自动生成和自动发送。

应用回滚不能回滚已执行的数据迁移。迁移必须采用扩展、切换、清理三步法，清理迁移放在后续版本。

## 12. 常见故障处置

| 故障 | 处置 |
|---|---|
| LibreNMS 不可用 | 暂停依赖新用量的生成，保留已有快照审核能力，重试同步任务 |
| PDF 一直失败 | 检查模板双重校验、字体、Chromium、30 秒/布局/页数/字节上限、残留子进程和 MinIO；重试原任务，不重新编号 |
| 重复任务 | 检查唯一键和幂等记录，禁止手工复制数据库记录 |
| 通知失败 | 保持账单状态，修复通道后重试通知日志 |
| Redis 不可用 | 允许缓存退化，检查限速影响，不从 Redis 恢复账单事实 |
| 对象文件缺失 | 阻止发送和正式确认，依据数据库哈希从备份恢复并记录审计 |

## 13. 相关文档

- 架构和故障边界：[`02-architecture.md`](02-architecture.md)
- 账单生命周期：[`06-invoice-lifecycle.md`](06-invoice-lifecycle.md)
- 模板安全与 PDF：[`07-template-and-pdf.md`](07-template-and-pdf.md)
- 安全与审计：[`08-security-and-audit.md`](08-security-and-audit.md)
- 测试与上线：[`12-testing-and-roadmap.md`](12-testing-and-roadmap.md)
