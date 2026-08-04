# LibreNMS 集成

## 1. 职责边界

LibreNMS 负责设备、端口、采样、Bill、Bill History、95 值和流量图。Auto Invoice 负责把这些数据映射到合同计费项，并转换为金额和账单证据。

## 2. 数据源

每个 LibreNMS 实例配置：

- 名称、基础 URL、加密 API Token。
- API 版本、时区、连接和读取超时。
- 最大并发、TLS 校验、启用状态。
- 最近成功、失败和连续失败次数。

Token 只返回遮罩值，禁止写入日志。

数据源 `base_url` 必须是根路径 HTTP(S) origin，禁止用户名密码、查询参数、片段和非根路径。部署通过
`LIBRENMS_ALLOWED_ORIGINS` 配置逗号分隔的 exact-origin allowlist；协议、规范化主机和非默认端口必须精确匹配，
显式 `http://host:80` 与 `https://host:443` 会归一化为无端口的默认 origin。空 allowlist 默认拒绝所有目标。API 在保存数据源前校验并保存规范化 origin，
Sync Worker 在每次创建 HTTP 客户端前重新校验数据库中的 origin，以防 API 与 Worker 配置漂移或旧数据绕过。

## 3. 官方 API 边界

| 用途 | 路径 |
|---|---|
| Bill 列表 | `GET /api/v0/bills` |
| 上一周期 | `GET /api/v0/bills?period=previous` |
| Bill 详情 | `GET /api/v0/bills/:id` |
| 按客户引用 | `GET /api/v0/bills?custid=:custid` |
| 按业务引用 | `GET /api/v0/bills?ref=:ref` |
| 当前图像 | `GET /api/v0/bills/:id/graphs/:graph_type` |
| 当前 graphdata | `GET /api/v0/bills/:id/graphdata/:graph_type` |
| Bill History | `GET /api/v0/bills/:id/history` |
| 历史图像 | `GET /api/v0/bills/:id/history/:hist_id/graphs/:graph_type` |
| 历史 graphdata | `GET /api/v0/bills/:id/history/:hist_id/graphdata/:graph_type` |

适配器按 LibreNMS 版本做字段兼容。关键字段缺失时必须报错，不能默认为零。

## 4. Bill 映射

Bill 映射到合同计费项，并关联客户、公司和业务用于一致性校验。

推荐命名：

```text
bill_custid = customer_no
bill_ref    = service_no 或 contract_item_no
bill_name   = customer-region-service
```

同一计费项同一时间只能有一个有效主映射。多端口聚合优先在 LibreNMS Bill 中定义。

## 5. 用量留存分层

### 当前观察值

每小时覆盖更新，用于控制台和趋势提示，不作为正式账单永久证据。

### 不可变快照

生成预览时创建。保存结构化指标、周期、方向、单位、异常、适配器版本和数据哈希。

### 原始证据

正式账单引用的原始响应、图像和必要 graphdata 存对象存储，数据库保存文件 ID 和 SHA-256。

## 6. 同步计划

| 时机 | 行为 |
|---|---|
| 每小时 | 当前账期 Bill，更新观察值 |
| 每天 | 映射、端口、设备和连续失败检查 |
| 账期结束 | 上一完整账期 History、图像和证据 |
| 生成预览前 | 对相关计费项强制同步或选用已验证快照 |
| 正式化前 | 校验快照未被标记无效，不重新计算快照内容 |

## 7. 快照内容

- 入向、出向和最终 95 bps。
- 平均和峰值 bps。
- 入向、出向和总 byte。
- 原始、转换、取整和计费值。
- 周期、时区、方向和单位。
- Bill ID、History ID 和映射 ID。
- 采样覆盖率、异常和数据哈希。
- 原始响应、图像和 graphdata 文件引用。

## 8. 异常

阻断型：

- Bill/History 不存在。
- 关键字段缺失或周期不匹配。
- 采样覆盖率低于阈值。
- 计费值无法确定。
- 映射关联的客户、业务或计费项不一致。

警告型：

- 图像缺失。
- 95 为零但合同允许零用量。
- 相比历史账期变化超过阈值。
- 当前设备或端口状态异常但 History 已完整。

## 9. 幂等和重试

```text
SYNC_USAGE:{instance_id}:{bill_id}:{period_start}:{period_end}:{purpose}
```

同一键只运行一个任务。网络超时、429 和临时 5xx 重试；Token 无效、权限不足和数据校验失败不自动重试。
重定向不自动跟随，避免已允许的 LibreNMS origin 把请求转向其他地址。
单次 LibreNMS HTTP 响应最多读取 `16 MiB`；同时校验 `Content-Length` 和实际流式读取字节数，超限以
`LIBRENMS_RESPONSE_TOO_LARGE` 失败。非 2xx 错误只记录上游状态码，不回显响应正文或认证 Token。

## 10. 测试策略

- CI 使用脱敏固定响应覆盖 Bill、History、图像、graphdata、空字段、超时和畸形响应。
- 覆盖空 allowlist、exact-origin 不匹配、默认端口规范化、凭据/query/fragment/path 拒绝，以及 API 保存与 Worker 请求时的双重校验。
- 覆盖有/无 `Content-Length` 的 `16 MiB` 边界、超限响应和非 2xx 响应脱敏。
- 预发布每天对真实 LibreNMS 执行只读冒烟测试。
- 适配器升级必须保存新旧响应样本并跑回归。
