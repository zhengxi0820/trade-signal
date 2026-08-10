# trade-signal 接口文档

> KDJ 交易位信号系统 HTTP 接口参考。业务规则（指标口径、信号定义）见 [kdj-trade-signal-requirements.md](kdj-trade-signal-requirements.md)，本文档是参数与出参的唯一权威来源。

## 通用说明

- **认证**：`/kdj/**` 全部需要认证 Cookie，未认证返回 401。Cookie 为无状态 HMAC token（`subject.expiry.签名`，subject=用户名或 "key"），HttpOnly + SameSite=Strict，默认 7 天。`GET /auth/check` 查状态（204/401）；`POST /auth/logout` 登出。同一 IP 连续失败 5 次锁定 15 分钟（429）。
  - **用户登录**：`POST /auth/login` body `{"username":"...","password":"..."}` → 204 + Cookie；401=用户名或密码错误（含账号被禁用）
  - **密钥登录**（服务器脚本用）：`POST /auth/login` body `{"key":"访问密钥"}`，密钥由 `TRADE_SIGNAL_ACCESS_KEY` 配置
  - **注册**：`POST /auth/register` body `{"username":"...","password":"...","inviteCode":"..."}` → 204 + Cookie（自动登录）；400 带 `{"message":"具体原因"}`（邀请码无效/用户名已存在/参数非法）；403=注册未开放（未配置邀请码）；429=限流（同 IP 失败 5 次锁 15 分钟，或同 IP 当日成功注册超 5 个）。用户名规则 `^[a-zA-Z0-9_]{3,20}$`，密码 8-64 位，用户名大小写不敏感唯一；密码只存 BCrypt 哈希。邀请码由 `TRADE_SIGNAL_INVITE_CODES` 配置（逗号分隔多个，泄露可轮换）
- 业务接口均为 GET，参数以 query string 传递。
- 所有数值入参/出参均为 BigDecimal（JSON 中为数字）。
- 开关类参数为字符串："1" = 启用，"0" = 禁用。
- 日期字段规则（入参与出参一致）：

| kdjType | 含义 | 日期字段用法 |
|---|---|---|
| "0"（默认） | 日线 | `tradeDate` = yyyymmdd |
| "1" | 周线 | `tradeDateMin` / `tradeDateMax` = 该周第一个 / 最后一个交易日，yyyymmdd |
| "2" | 月线 | `tradeDate` = yyyymmdd，后端截位到 yyyyMM 定位当月 |
| "3" | 季线 | `tradeDateMin` / `tradeDateMax` = 季度首月 / 末月，yyyymm，如 202501 / 202503 |

- 不指定截止周期（日期字段全空）= 最新已完结周期。进行中的周期不参与计算。

## 接口一览

| 端点 | 用途 |
|---|---|
| `GET /kdj/series` | 单票某周期 KDJ 序列 + 交叉点标注（KDJ 线图） |
| `GET /kdj/gold-cross` | 某周期截止周期出现金叉的股票列表 |
| `GET /kdj/trade-signal` | 某周期截止周期出现交易位（买入信号）的股票列表 |
| `GET /kdj/all-stocks` | 全部股票的截止周期行情与 KDJ（不过滤），供「所有股票」列表 |
| `GET /kdj/periods` | 可选周期列表（已完结周期），供截止周期选择器 |
| `POST /kdj/cache/refresh` | 清空全市场扫描结果缓存（运维兜底；日常失效靠数据水位自动完成） |

## 入参（KDJParam，series / gold-cross / trade-signal / all-stocks 四个接口共用）

| 参数 | 类型 | 必填 | 默认值 | 含义 |
|---|---|---|---|---|
| code | String | series 必填；其余选填 | 空 = 全市场 | 股票代码 |
| market | String | 否 | — | 市场标识 |
| adjust | String | 否 | "1" | 复权类型："0"=无复权、"1"=前复权、"2"=后复权（预留，以 stock_quote 数据为准） |
| kdjType | String | 否 | "0" | 周期类型："0"=日、"1"=周、"2"=月、"3"=季 |
| tradeDate | String | 否 | 最新已完结周期 | 截止周期（日度、月度使用） |
| tradeDateMin | String | 否 | 同上 | 截止周期下限（周度、季度使用） |
| tradeDateMax | String | 否 | 同上 | 截止周期上限（周度、季度使用） |
| n | BigDecimal | 否 | 9 | RSV 窗口周期 |
| m1 | BigDecimal | 否 | 3 | K 平滑周期 |
| m2 | BigDecimal | 否 | 3 | D 平滑周期 |
| currGoldCrossMax | BigDecimal | 否 | 见下 | 当前金叉交汇上限：trade-signal 缺省 50；其余端点缺省不限 |
| lastGoldCrossMax | BigDecimal | 否 | 20 | 上次金叉交汇上限（仅 trade-signal 生效） |
| lastDeathCrossMax | BigDecimal | 否 | 50 | 两次金叉之间死叉的交汇上限（仅 trade-signal 生效） |
| goldInternalMin | BigDecimal | 否 | 5 | 两次金叉最小间距（K 线下标差，闭区间，仅 trade-signal 生效） |
| goldInternalMax | BigDecimal | 否 | 15 | 两次金叉最大间距（闭区间，仅 trade-signal 生效） |
| openClosePriceLimit | String | 否 | "1" | 开关：要求本次金叉周期收盘价低于上次金叉（仅 trade-signal 生效） |
| goldCrossLimit | String | 否 | "1" | 开关：要求本次金叉交汇点高于上次金叉（仅 trade-signal 生效） |

## GET /kdj/series

单票某周期完整序列（OHLC + KDJ + 交叉点标注），供前端绘制价格走势图与 KDJ 线图。金叉标注受 `currGoldCrossMax` 过滤（缺省不限）。

示例：

```
GET /kdj/series?code=600519&kdjType=1&tradeDateMin=20240101&tradeDateMax=20240105
```

响应 `List<KDJVO>`，时间升序：

| 字段 | 类型 | 含义 |
|---|---|---|
| open / high / low / close | BigDecimal | 该周期开 / 高 / 低 / 收（供价格走势图蜡烛） |
| k / d / j | BigDecimal | KDJ 值 |
| crossType | String | gold=金叉、death=死叉、null=无交叉 |
| crossValue | BigDecimal | 交汇点数值（该根发生交叉时） |
| tradeDate | String | 日/月度：该周期日期 yyyymmdd |
| tradeDateMin / tradeDateMax | String | 周度：首/末交易日 yyyymmdd；季度：首/末月 yyyymm |

## GET /kdj/gold-cross

某周期截止周期出现金叉的股票列表，不进行交易位判断。code 为空 = 全市场扫描。

示例（页面默认展示：日线、前复权、最新已完结交易日）：

```
GET /kdj/gold-cross
```

响应 `List<CrossStockVO>`：

| 字段 | 类型 | 含义 |
|---|---|---|
| code / name / market | String | 股票代码 / 股票名称 / 市场标识 |
| open / high / low / close | BigDecimal | 截止周期开 / 高 / 低 / 收 |
| k / d / j | BigDecimal | 截止周期 K、D、J 值 |
| crossValue | BigDecimal | 截止周期金叉交汇点数值 |
| tradeDate | String | 日/月度：截止周期日期 |
| tradeDateMin / tradeDateMax | String | 周/季度：截止周期范围（规则同上） |

## GET /kdj/trade-signal

某周期截止周期出现交易位的股票列表。先判金叉，再按需求文档 4.2 的六条规则过滤。

示例：

```
GET /kdj/trade-signal?kdjType=1&goldInternalMax=20&openClosePriceLimit=0
```

响应同 `/kdj/gold-cross`，`List<CrossStockVO>`。

## GET /kdj/all-stocks

全部股票的截止周期行情与 KDJ，**不做金叉/交易位过滤**，供「所有股票」列表。code 为空 = 全市场扫描。

示例：

```
GET /kdj/all-stocks?kdjType=0
```

响应同 `/kdj/gold-cross`，`List<CrossStockVO>`。区别：`crossValue` 仅在截止周期发生交叉时有值（金叉受 `currGoldCrossMax` 过滤、缺省不限，否则死叉），无交叉时为 null。

## GET /kdj/periods

可选周期列表（已完结周期），基于 `work_day` 交易日历推导，供前端截止周期选择器（日线置灰、周线选项、月/季可选范围）。交易日历可能含未来日期，一律剔除。

入参（WorkDayParam）：

| 参数 | 类型 | 必填 | 默认值 | 含义 |
|---|---|---|---|---|
| kdjType | String | 否 | "0" | 周期类型："0"=日、"1"=周、"2"=月、"3"=季 |
| market | String | 否 | 全部市场 | 市场标识 |

示例：

```
GET /kdj/periods?kdjType=1&market=SH
```

响应 `List<WorkDayVO>`，**时间倒序**（最新周期在前），日期字段遵循三字段规则：

| kdjType | tradeDate | tradeDateMin / tradeDateMax |
|---|---|---|
| "0" | 交易日 yyyymmdd | — |
| "1" | — | 该周首 / 末交易日 yyyymmdd |
| "2" | 该月最后一个交易日 yyyymmdd | — |
| "3" | — | 季度首月 / 末月 yyyymm |

## POST /kdj/cache/refresh

清空三个全市场扫描接口（gold-cross / trade-signal / all-stocks）的结果缓存，返回 `{"cleared": N}`。

运维兜底用，日常不需要调：缓存按「接口 + 全部生效参数」为 key，并以 `stock_quote` 的 `max(trade_date)` 为数据水位——新行情入库后水位变化，缓存自动整表失效，下一请求重算。

## 备注

- 全市场扫描（code 为空）走两层内存缓存：结果层（接口+全部参数为 key，命中毫秒级）+ bars 层（每股票每周期 132 根窗口 K 线，key 不含 n/m1/m2）。因此：调阈值/间距/开关等过滤参数毫秒级；调 n/m1/m2 只重递推不取数（秒级）；调 adjust 或回看超出窗口的截止周期才触发真实重算。新行情入库后按 `max(trade_date)` 水位自动失效。日/周线扫描走窗口裁剪，月/季线走 SQL 预聚合，信号判定均与全历史计算一致（H2 对拍保证）；单票 `/kdj/series` 不受影响，始终全历史实时计算。
- `currGoldCrossMax` 传 0 就是字面 0（等于过滤掉几乎所有信号），无特殊语义；想"不限"，series / gold-cross / all-stocks 不传即可，trade-signal 传一个足够大的值。
