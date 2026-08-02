# KDJ 交易位信号系统 需求文档

> 版本：v1.0（经需求评审修订）
> 本文档是后期维护的唯一依据，与原始口述需求有出入处以本文档为准。

## 1. 系统概述

基于 KDJ 指标的股票交易位（买入信号）识别系统。后端提供 KDJ 序列、金叉列表、交易位股票列表、全部股票列表、可选周期五类查询能力；日/周/月/季四个周期完全独立计算。前端为内嵌静态页面（`src/main/resources/static/`，原型见 `docs/prototype/`）。

**核心架构决策：**

- KDJ 值与金叉/死叉/交易位事件**均不落库**，全部基于 `stock_quote` 表现有数据**实时计算**（复权数据重刷、参数调整天然生效，无失效同步逻辑）。
- **无定时任务**：用户打开页面时默认展示最新已完结交易日的日线全市场金叉列表 + 交易位股票列表；其余参数由用户在前端选择，实时计算。
- 外部行情数据接入不在本系统范围（假设 `stock_quote`、`work_day` 已有数据）。

## 2. KDJ 指标计算

### 2.1 参数

| 参数 | 含义 | 类型 | 默认值 |
|---|---|---|---|
| N | RSV 窗口周期数 | BigDecimal | 9 |
| M1 | K 平滑周期 | BigDecimal | 3 |
| M2 | D 平滑周期 | BigDecimal | 3 |

所有参与运算的数值统一使用 BigDecimal。

### 2.2 计算公式（经典口径）

```
RSV = (C - LLV(L, N)) / (HHV(H, N) - LLV(L, N)) × 100
K   = (前K × (M1 - 1) + RSV) / M1
D   = (前D × (M2 - 1) + K)   / M2
J   = 3K - 2D
```

- `LLV(L, N)` = 最近 N 个周期（含当期）最低价的最小值；`HHV(H, N)` = 最近 N 个周期最高价的最大值。
- 序列开头不足 N 根时，按已有根数计算（有几个算几个）。
- **RSV 分母为 0**（窗口内最高价 = 最低价，如一字涨停）时，**RSV 取 50**。
- **种子值**：序列第一根 K = D = 50（行业惯例），全历史递推。

### 2.3 四个周期（完全独立计算）

| 周期 | C | H / L 来源 |
|---|---|---|
| 日线 | 当日收盘价 | 当日最高 / 最低 |
| 周线 | 当周最后一个交易日收盘价 | 周内最高 = 各日 high 最大值；周内最低 = 各日 low 最小值 |
| 月线 | 当月最后一个交易日收盘价 | 同上，按月聚合 |
| 季线 | 当季最后一个交易日收盘价 | 同上，按**自然季**（1–3 / 4–6 / 7–9 / 10–12 月）聚合 |

- 周/月/季 K 线由日线聚合生成。
- 周期内无任何交易日的，该周期直接跳过，不参与计算。

### 2.4 已完结周期规则

任何周期（含日线）必须**已获取到该周期最后一个交易日的数据**，才视为已完结、可参与计算：

- 日线：库内最新交易日即最新已完结日。
- 周/月/季线：当前自然日已越过该周期的自然结束日（周日 / 月末 / 季末），且库内该周期存在交易日数据 → 已完结，最后一个交易日 = 周期内 max(tradeDate)。
- 进行中的周期（如本周三查本周）**不参与** KDJ 计算与信号判断，避免信号随盘中价格闪烁。

可选周期（前端截止周期选择器）以 `work_day` **交易日历表**为准（market + trade_date，由数据链路写入）：`/kdj/periods` 按同一套完结口径推导可选周期；日历中的未来日期一律剔除，周期内无交易日的自然跳过。

## 3. 金叉 / 死叉判断

### 3.1 定义（端点严格不等口径）

- **金叉**：上一周期 K < D，且当前周期 K > D。
- **死叉**：上一周期 K > D，且当前周期 K < D。
- 边界（K = D）不算交叉。

### 3.2 交汇点 crossValue 计算（附A 修正版）

K、D 视为相邻两周期的线段，求线性插值交点：

```java
public CrossPoint calcKdCrossValue(BigDecimal preK, BigDecimal preD, BigDecimal currK, BigDecimal currD) {
    BigDecimal A = preD.subtract(preK);
    BigDecimal B = preD.subtract(currD).subtract(preK.subtract(currK));

    // B = 0：K、D 平行，无交点
    if (B.compareTo(BigDecimal.ZERO) == 0) {
        return null;
    }

    BigDecimal t = A.divide(B, 16, RoundingMode.HALF_UP);
    // t 不在开区间 (0,1)：线段不相交（t=0、t=1 为端点触碰，不算交叉）
    if (t.compareTo(BigDecimal.ZERO) <= 0 || t.compareTo(BigDecimal.ONE) >= 0) {
        return null;
    }

    BigDecimal crossVal = preK.add(t.multiply(currK.subtract(preK)));
    return new CrossPoint(t, crossVal);
}
```

对原始伪代码的三处修正：

1. `t.compareTo(BigDecimal.ONE) >= 1` → `>= 0`（原写法漏判 t = 1）；
2. `BigDecimal.ROUND_HALF_UP`（已废弃）→ `RoundingMode.HALF_UP`；
3. eps(1e-9) 判平行 → `B.compareTo(ZERO) == 0`。

注：端点严格不等与线段相交 t∈(0,1) 在数学上等价，系统统一「端点判断有无交叉，附A 公式算 crossValue」。

### 3.3 当前金叉交汇上限（currGoldCrossMax）

- 复用参数字段 `currGoldCrossMax`，同时承担两个角色：KDJ 线图展示 / 金叉列表的全局交汇上限过滤，以及交易位判断的条件 2（见 4.2）——两者针对的是同一个「当前周期金叉」。
- 过滤规则：金叉 crossValue ≤ currGoldCrossMax 才视为有效金叉。
- **默认值按端点区分**：`/kdj/trade-signal` 缺省 50，其余端点缺省 null = 不设上限。0 就是字面 0，无特殊语义。

### 3.4 死叉在信号中的角色

两次连续金叉之间，数据维度上应当**恰好出现一次唯一的死叉**，且该死叉 crossValue ≤ lastDeathCrossMax（默认 50，可配置）。这是交易位判断的必要条件（见 4.2 条件 4）。

## 4. 交易位（买入信号）判断

### 4.1 前提

金叉必须**发生在截止周期本身**：截止周期 K > D 且上一相邻周期 K < D。截止周期没有金叉，则无交易位可谈。

- 截止周期 = KDJ 序列终点，由用户通过日期字段指定（见 5.1 的三字段规则），默认最新已完结周期。
- 当前金叉记为 y（截止周期），上一次金叉记为 x。

### 4.2 判断条件（全部满足才是交易位）

| # | 条件 | 参数 | 默认值 |
|---|---|---|---|
| 1 | x 的 crossValue ≤ 上次金叉上限 | lastGoldCrossMax | 20 |
| 2 | y 的 crossValue ≤ 当前金叉上限 | currGoldCrossMax | 50 |
| 3 | x、y 的下标差 ∈ [最小间距, 最大间距]，**闭区间** | goldInternalMin / goldInternalMax | 5 / 15 |
| 4 | x、y 之间恰好一次死叉，且死叉 crossValue ≤ 上限 | lastDeathCrossMax | 50 |
| 5 | y 周期收盘价 < x 周期收盘价（开关） | openClosePriceLimit | "1"（开） |
| 6 | y 的 crossValue > x 的 crossValue（开关） | goldCrossLimit | "1"（开） |

补充规则：

- 条件 1、2 的「KD 的值」统一指**交汇点 crossValue**。
- 条件 3 的间距按**对应周期 K 线根数的下标差**计量（非自然日）。例：x 在下标 1，y 合法范围为下标 [6, 16]。
- 条件 5、6 为字符串开关，"1" = 启用，"0" = 禁用，默认均启用。
- 条件 1、2 仅交易位计算使用；currGoldCrossMax（3.3）在展示类端点缺省不限。
- 条件 5 的收盘价比较以各自周期最后一个交易日的收盘价为准。

## 5. 接口清单

| 端点 | 用途 | 说明 |
|---|---|---|
| `GET /kdj/series` | 单票某周期 KDJ 序列 + 交叉点标注 | 供前端价格走势图（OHLC）+ KDJ 线图展示；金叉标注受 currGoldCrossMax 过滤（缺省不限） |
| `GET /kdj/gold-cross` | 某周期出现金叉的股票列表 | code 为空 = 全市场扫描；页面默认展示调用（kdjType=0 + qfq + 最新已完结交易日） |
| `GET /kdj/trade-signal` | 某周期出现交易位的股票列表 | 同上，先判金叉再按 4.2 六条过滤 |
| `GET /kdj/all-stocks` | 全部股票的截止周期行情与 KDJ | 不过滤；截止周期有交叉时 crossValue 有值；供「所有股票」列表 |
| `GET /kdj/periods` | 可选周期列表（已完结周期） | 基于 work_day 交易日历推导，供前端截止周期选择器 |

### 5.1 入参与出参

参数表、出参字段、日期字段规则、调用示例以 [trade-signal-api.md](trade-signal-api.md) 为唯一权威来源，本节不再重复。与业务规则相关的默认值：kdjType="0"、adjust=qfq、n/m1/m2=9/3/3、lastGoldCrossMax=20、currGoldCrossMax（交易位 50 / 展示不限）、lastDeathCrossMax=50、goldInternalMin/Max=5/15、两个开关默认 "1"。

## 6. 性能

- 单票全历史 KDJ 计算为 O(n) 递推，毫秒级，直接实时计算。
- 暂不做缓存；全市场扫描每次实时计算。后续如有性能问题再评估缓存（届时 key 需含 kdjType、adjust、截止周期、全部信号参数）。

## 7. 非目标

- 不做定时任务。
- 不做独立前端工程（前端为内嵌静态页）。
- KDJ 值、金叉/死叉/交易位事件不落库。
- 不做外部行情数据接入（假设 stock_quote 数据已由其他链路写入，含不复权/前复权/后复权三类；work_day 交易日历同理）。
