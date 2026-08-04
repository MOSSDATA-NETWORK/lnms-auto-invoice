# 模板中心与 PDF

## 1. 模板模型

- `invoice_template`：名称、代码、类型、状态和当前发布版本。
- `invoice_template_version`：HTML、CSS、变量 Schema、字段和列表配置。
- `template_asset`：Logo、图片、字体和盖章资源。
- `template_binding`：客户、公司、业务、合同、账单配置、币种或语言默认规则。

已发布版本不可修改，编辑必须创建新草稿版本。

## 2. 首版能力

- HTML 和受控 CSS。
- Handlebars 变量、循环和条件。
- 注册的日期、金额、数量和单位格式化函数。
- 多个明细列表及独立列配置。
- 页眉、页脚、Logo、付款信息和流量图。
- 测试数据预览、复制、导入、导出、发布和回滚。

## 3. 安全沙箱

禁止：

- `<script>`、事件属性和 JavaScript URL。
- 远程 HTTP/HTTPS、`file://` 和本地路径。
- CSS 远程 `url()`、表达式和非白名单能力。
- `@page size` 只允许 `A3`、`A4`、`A5`、`Letter`、`Legal`、`auto` 及横竖方向组合；自定义或极小纸张拒绝发布。
- CSS escape、HTML entity、空白拆分、动态 Handlebars URL 和畸形/未闭合 `<style>` 都必须先规范化再校验，不能借此绕过 URL、脚本或资源上限。
- 任意 Handlebars helper。
- 环境变量、数据库、内部 API 和云元数据访问。

允许的资源必须上传对象存储并登记为模板资产。

## 4. Render Worker 限制

- 非 root 用户。
- 只读根文件系统。
- 默认无网络。
- Handlebars 只能通过 `MapValueResolver` 读取冻结 JSON 模型，禁止 JavaBean、方法和 `class` 反射属性解析。
- 渲染文档注入 deny-by-default CSP；脚本、连接、对象、表单和 base URL 全部禁用，仅允许内联样式及 `data:` 图片/字体。
- Playwright BrowserContext 显式关闭 JavaScript、下载和 Service Worker，强制 offline，并对所有网络请求执行 abort；模板发布前和 Handlebars 展开后各执行一次安全校验，动态数据生成的远程 URL 同样会被拒绝。
- Chromium 显式启用沙箱；Render Worker 以 `pwuser`、`CapEff=0` 和 `no-new-privileges` 运行，加载与 Playwright `v1.59.0` 同版本且校验过 SHA-256 的 seccomp profile。该 profile 只相对官方版本无条件放行 `chroot` syscall，使 Chromium 在自己的 user namespace 内完成沙箱初始化，不向容器增加 `CAP_SYS_CHROOT`。Playwright Java 的固定版本 Node 二进制预置在只读镜像层并通过 `PLAYWRIGHT_NODEJS_PATH` 使用，因此 `/tmp` 可保持 `noexec`；禁止使用 `--no-sandbox`、`seccomp=unconfined` 或增加系统能力规避问题。
- 单次渲染使用独立 daemon executor，整个操作硬超时 30 秒；超时、中断或异常时取消任务，并强制清理本次渲染新建的 Node/Chromium 子孙进程。
- 源 HTML 最多 1,000,000 字符、CSS 最多 500,000 字符、展开后 HTML 最多 4,000,000 字符；单个 `data:` URI 最多 512,000 字符、合计最多 1,000,000 字符、最多 100 个且 header 最多 256 字符。
- 声明布局高度和 Chromium 实际布局高度均不得超过 120,000 CSS px；最终 PDF 必须以 `%PDF-` 开头、最多 100 页且不超过 16 MiB。

## 5. 模板变量

```text
system.*
customer.*
company.*
invoice.*
invoice.items[]
invoice.adjustments[]
invoice.payments[]
service.*
contract.*
usage.*
custom.*
```

变量通过版本化 JSON Schema 定义。发布时发现未知变量必须失败。

## 6. 自定义列表

列表可以分别用于带宽、托管、IP、人工调整和付款记录。

每个列表配置：

- 数据源、过滤、分组和排序。
- 分页和空列表隐藏。
- 列标题、字段绑定、顺序、宽度和对齐。
- 日期、金额、数量、单位和小数位格式。
- 小计和总计。

条件和过滤使用受控 DSL，不执行任意代码。

## 7. 标准字段与显示名称

| 内部字段 | 中文 | 英文 |
|---|---|---|
| `item_name` | 服务项目 | Description |
| `billing_period` | 账期 | Billing Period |
| `raw_usage` | 原始 95 值 | Raw 95th |
| `billing_usage` | 计费带宽 | Billable Usage |
| `quantity` | 数量 | Quantity |
| `unit_price` | 单价 | Rate |
| `net_amount` | 未税金额 | Net Amount |
| `tax_amount` | 税额 | Tax |
| `total_amount` | 应付金额 | Amount Due |

模板只能修改显示名称，不能修改字段语义。

## 8. 预览水印

```text
预览账单 / PROFORMA
非正式账单
数据截至：YYYY-MM-DD HH:mm:ss ZONE
预览编号：PRE-...
```

## 9. 正式 PDF 证据

正式账单保存：

- 模板版本及内容哈希。
- 模板资源清单和资源哈希。
- 渲染数据 JSON 哈希。
- 渲染器镜像、Chromium 和字体版本。
- PDF SHA-256、大小和 MIME 类型。

## 10. 发布校验

发布模板前检查：

- HTML/CSS 安全规则。
- 变量和列表字段存在。
- 中文、英文、长公司名和多页明细。
- 零税、含税、无流量图和空列表。
- 图片、字体、分页、页眉页脚和金额对齐。
- `@page` 白名单、HTML/CSS/展开后 HTML、`data:` URI、声明/实际布局高度、30 秒硬超时、100 页和 16 MiB 上限。
- 超时和渲染异常后不得残留本次任务创建的 Chromium/Node 进程；真实生产镜像冒烟必须确认沙箱参数未被关闭。
