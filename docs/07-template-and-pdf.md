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
- 任意 Handlebars helper。
- 环境变量、数据库、内部 API 和云元数据访问。

允许的资源必须上传对象存储并登记为模板资产。

## 4. Render Worker 限制

- 非 root 用户。
- 只读根文件系统。
- 默认无网络。
- 临时目录、CPU、内存、页数和超时限制。
- HTML 大小、图片数量、像素和字体大小限制。

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
