# AGENTS.md — trade-signal

KDJ 交易位信号系统（Spring Boot 4 / Java 17 / MyBatis / MySQL）。基于 stock_quote 行情表实时计算 KDJ 与买入信号，不落库、无定时任务、无缓存。

## 构建与测试

```bash
./mvnw compile   # 编译
./mvnw test      # 全部测试（纯计算单测 + Spring 上下文测试，后者用 H2 内存库）
```

正式运行需要先在 `src/main/resources/application.yaml` 配置可用的 MySQL 数据源，否则应用无法启动。

## 目录结构

- `controller/` — HTTP 端点（`/kdj/series`、`/kdj/gold-cross`、`/kdj/trade-signal`、`/kdj/all-stocks`、`/kdj/periods`）
- `service/` — 编排层：查行情 → 聚合 → 计算 → 判断 → 组装出参，不写数学逻辑
- `handler/KDJHandler.java` — 全部核心计算（聚合、KDJ 递推、交叉点、交易位判断），**纯函数，不依赖 Spring 与数据库**
- `orm/` — MyBatis：entity + mapper 接口；XML 在 `resources/mapper/**`
- `model/` — param（入参）/ dto / vo（出参）/ query（查询条件）
- `convert/` — MapStruct DTO↔VO 转换
- `scripts/` — python 数据灌入管线（akshare 取数 + 因子反推复权），与 Java 主体通过数据库解耦，用法见 `scripts/README.md`

## 数据体系

- **name/market 唯一来源是 `stock_info`**，`stock_quote` 不冗余这两列；出参 name/market 由 service 查 stock_info 填充。
- **复权自建**：`stock_quote.ADJUST='0'` 原始行只追加；`ADJUST='1'` 前复权行由 `scripts/adjust/` 因子反推自算（factor(t)=爬取qfq÷raw 的阶梯函数，跳变点即除权日，事件存 `stock_dividend`），可重算覆盖。新除权事件只需该股历史 qfq 行乘新因子。
- 数据源实测口径（新浪主源等比复权、腾讯为等差复权不可用、东财直连受限）见 `docs/trade-signal-data-pipeline.md`。

## 硬性约定

- **运算统一 BigDecimal**，禁用 double/float（包括 n/m1/m2 这类周期参数）。
- 开关参数用字符串 `"1"`/`"0"`，不用 boolean。
- 周期类型用 `kdjType`："0"=日、"1"=周、"2"=月、"3"=季，不新增平行的 period 字段。
- 复权类型用 `adjust`："0"=无复权、"1"=前复权（默认）、"2"=后复权（预留）；板块用 `boardType`："0"=沪深主板、"1"=科创板、"2"=创业板、"3"=北交所。
- 日期用三字段规则：日/月度 `tradeDate`(yyyymmdd)；周度 `tradeDateMin/tradeDateMax`(yyyymmdd)；季度 `tradeDateMin/tradeDateMax`(yyyymm)。入参出参结构一致，不引入新日期字段。
- KDJ 值与金叉/死叉/交易位事件**不落库、不缓存**，一律实时计算。
- 金叉/死叉判断用端点严格不等，交汇点用 `KDJHandler.calcKdCrossValue`（附A 修正版），不要重造。
- `KDJHandler` 保持纯函数：要测试直接 JUnit 对拍，不要在里头注入任何 bean。

## 安全

- 凭据只走环境变量（Java 侧 `TRADE_SIGNAL_DB_USER/PASSWORD`，scripts 侧 `DB_*` 且 `DB_PASSWORD` 无代码默认值），明文不进仓；yaml 里的缺省值仅是本机 dev 库。
- 入参白名单校验在 `KDJServiceImpl.validateParam`，新增枚举/开关参数要同步加。
- **上公网前必办**（详见 `docs/SECURITY.md` 3.1 检查单）：接口加认证与限流、HTTPS+安全头（注意 `static/index.html` 是内联脚本+CDN 形态，严格 CSP 会白屏，需先改造再下发）、生产错误收口、凭据全部换新。

## 测试约定

- 计算逻辑改动必须同步 `KDJHandlerTest`（构造合成 K/D 序列逐条规则对拍）。
- 测试数据源是 H2（`src/test/resources/application.yaml`），不要在测试里连真实 MySQL。

## 深入文档

| 文档 | 内容 |
|---|---|
| `docs/kdj-trade-signal-requirements.md` | 业务需求权威口径（指标定义、信号规则、勘误记录） |
| `docs/trade-signal-api.md` | 接口参数/出参/示例的唯一权威来源 |
| `docs/trade-signal-schema.sql` | 数据库表结构（stock_quote / work_day / stock_info / stock_dividend），与 orm/entity 一一对应 |
| `docs/trade-signal-data-pipeline.md` | 数据管线权威口径（数据源实测、因子反推法、风险与验证记录）；命令用法在 `scripts/README.md` |

改业务规则先改需求文档，改参数先改接口文档，改表结构先改 schema.sql，代码跟随文档。
