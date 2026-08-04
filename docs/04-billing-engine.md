# 计费引擎

## 1. 设计原则

- 计费函数必须是确定性的纯函数：输入相同，输出相同。
- 金额使用 Java `BigDecimal`，禁止 `float` 和 `double`。
- API 中的金额、数量、单价、税率和汇率使用字符串。
- 数据库保存高精度计算值和最终最小货币单位。
- 每条账单明细必须保存完整 `calculation_snapshot`。

## 2. 计算流水线

```text
确定账期和服务有效范围
  -> 选择价格版本
  -> 读取数量或用量快照
  -> 单位转换
  -> 用量取整
  -> 计算基础和超量金额
  -> 应用最低消费与封顶
  -> 物化折扣和人工调整
  -> 计算税基与税额
  -> 按币种精度量化
  -> 输出账单明细和计算快照
```

## 3. 单位约定

- 带宽原始单位：`bps`。
- 默认展示：十进制 `Mbps = 1,000,000 bps`。
- 流量原始单位：`byte`。
- GB/TB 或 GiB/TiB 的换算方式必须进入价格规则和快照。
- 币种使用 ISO 4217；最终金额按币种 `minor_unit` 量化。

## 4. 95 带宽

### Max

```text
raw_usage = max(rate_95th_in, rate_95th_out)
```

### Inbound

```text
raw_usage = rate_95th_in
```

### Outbound

```text
raw_usage = rate_95th_out
```

### Aggregate

```text
sample[i] = inbound[i] + outbound[i]
rate_95th = percentile95(sample[])
```

Aggregate 必须来自 LibreNMS 聚合 Bill 或完整采样序列，不能用两个方向的 95 值相加。

## 5. 保底加超量

```text
overage = max(billing_usage - committed_quantity, 0)
gross = base_fee + overage * overage_unit_price
```

示例：

```text
保底带宽      100 Mbps
基础费用      CNY 2,000.00
原始 95       176.32 Mbps
取整           向上到 10 Mbps
计费带宽      180 Mbps
超量带宽       80 Mbps
超量单价      CNY 15.00/Mbps
总额          CNY 3,200.00
```

## 6. 全量单价

```text
billing_usage = max(rounded_usage, minimum_billable_quantity)
gross = billing_usage * unit_price
```

## 7. 固定费和数量计费

```text
fixed_monthly = fixed_fee
quantity_price = quantity * unit_price
```

数量计费适用于 IP、服务器、机柜、U 位、电力、端口和许可证。

## 8. 总流量计费

```text
billing_traffic = convert(traffic_bytes, configured_unit)
chargeable = max(billing_traffic - free_allowance, 0)
gross = base_fee + chargeable * unit_price
```

规则需明确入向、出向或双向合计。

## 9. 阶梯价格

- `GRADUATED`：各区间分别收费。
- `VOLUME`：最终数量命中一档，全部数量使用该档价格。

阶梯必须起点递增、不重叠，除最后一档外具有上限。

## 10. 最低消费和封顶

```text
after_floor = max(gross, minimum_charge)
final_before_discount = maximum_charge == null
  ? after_floor
  : min(after_floor, maximum_charge)
```

封顶不能低于最低消费。

## 11. 按天折算

账期和服务有效期均为半开区间。支持：

- `ACTUAL_DAYS`：按自然月实际天数。
- `THIRTY_DAYS`：固定按 30 天。
- `NO_PRORATION`：不足月不折算。
- `FULL_MONTH_IF_ACTIVE`：账期内有效即收整月。

服务有效期与账期没有交集时，所有折算模式的费用都必须为零；`NO_PRORATION` 和 `FULL_MONTH_IF_ACTIVE` 也不能对账期外服务收取整月费用。

## 12. 取整

- `NONE`
- `DECIMAL_SCALE`
- `HALF_UP_INTEGER`
- `CEIL_INTEGER`
- `CEIL_STEP`

价格版本中的 `unit_price`、`base_fee`、`committed_quantity`、`overage_unit_price`、
`minimum_charge` 和 `maximum_charge` 均允许为空，但非空时必须大于等于零。
合同计费项默认数量、阶梯下界和阶梯单价也不得为负数。`rounding_scale` 非空时必须位于
`0..12`，且 `DECIMAL_SCALE` 必须提供该值；数据库约束与领域校验同时执行，禁止绕过 API
直接写入非法计费参数。

系统分别保存原始值、转换值、取整值和计费值。

## 13. 折扣、税费和调整

折扣和人工调整必须成为独立明细行，不能只改账单总额。

调整类型包括优惠、SLA、余额抵扣、上月结转、滞纳金、补差、安装、临时带宽、汇率和税费修正。

每条调整保存：金额、税务分类、是否参与税基、原因、操作者、审核人、附件和时间。

## 14. 币种与汇率

- 一张账单只使用一个结算币种。
- 第一阶段要求计费项币种一致，不一致则阻止自动生成。
- 第二阶段支持换算，并冻结汇率来源、方向、精度和人工覆盖原因。

## 15. 计算快照

```json
{
  "schema_version": 1,
  "billing_type": "COMMITTED_PLUS_OVERAGE",
  "usage": {
    "rate_95th_in_bps": 856320000,
    "rate_95th_out_bps": 216740000,
    "direction": "MAX",
    "raw_mbps": "856.32",
    "rounding_mode": "CEIL_STEP",
    "rounding_step": "10",
    "billing_mbps": "860"
  },
  "price": {
    "currency": "CNY",
    "committed_mbps": "500",
    "base_fee": "5000.00",
    "overage_unit_price": "15.00"
  },
  "result": {
    "overage_mbps": "360",
    "overage_amount": "5400.00",
    "subtotal": "10400.00",
    "tax": "0.00",
    "total": "10400.00"
  }
}
```

## 16. 必测边界

- 95 为零、负值、空值和超过端口速率。
- 取整刚好位于步长边界及超出一小数位。
- 保底等于、低于和高于实际用量。
- 阶梯边界、空档、重叠和最后一档。
- 账期跨价格版本、服务开通日和停用日。
- 最低消费、封顶、折扣和负调整组合。
- 零小数币种、两位币种和汇率精度。
