# 股票挂牌徽标（代码列图标）方案

> 状态：方案评审稿（2026-08-13）。后端暂不改代码，前端已有原型。
> 关联：`docs/prototype/stock-badge-prototype.html`（原型）、`docs/trade-signal-schema.sql`（表结构权威）、`docs/trade-signal-api.md`（接口权威）、`scripts/common/const.py`（板块前缀规则）。

## 1. 需求

三个扫描列表（所有股票 / 金叉 / 交易位）的「代码」列在代码后追加一个小徽标，区分五类挂牌：

| 徽标 | 含义 |
|---|---|
| 北 | 北交所 |
| 创 | 创业板 |
| 科 | 科创板 |
| 深 | 深交所主板 |
| 沪 | 上交所主板 |

数据由 `stock_info` 新增字段维护，扫描接口查询时带出。

## 2. 现状与差距

- `stock_info` 已有 `BOARD_TYPE CHAR(1)`：`0`=沪深主板（**合并，分不出沪/深**）、`1`=科创板、`2`=创业板、`3`=北交所。`MARKET` 为 SH/SZ/BJ 字母码。
- **仓库里没有 `BADGE_TYPE` 字段**——上轮方案里提到的 `BADGE_TYPE` 是拟新增字段名，系统现网不存在该字段（已全仓库核实）。
- 板块由 `scripts/common/const.py:board_type_of(code)` 按代码前缀推导（688→科创、300/301/302→创业、4xx/8xx/92x→北、其余→主板），`stock_list.py` 全量 upsert 维护。
- Java 查询链路 `StockInfoMapper.queryAll → KDJServiceImpl.stockInfoMap → buildCrossStockVO` 目前**只带出 name/market，board_type 未使用**；`CrossStockVO` 无板块字段。
- 前端三张表共用 `allColumns` 动态列，代码列是纯文本渲染（`src/main/resources/static/js/app.js:97`）。
- `BOARD_TYPE` 全仓库消费方只有：scripts 写入时推导、Java DO/XML 映射（未带出）、测试插入数据（无断言依赖）——**没有任何业务读取方**，变更字典零运行时风险。

结论：既然 `BOARD_TYPE` 无消费方，**直接复用并变更其字典**（比新增 `BADGE_TYPE` 列更优：不加冗余列、不加 DO 字段，存量可用全量 upsert 自动对齐）。

## 3. 字段设计（复用 BOARD_TYPE，变更字典）

字典（`BOARD_TYPE`，科创/创/北原值**保持不变**，仅拆分主板）：

| 值 | 含义 | 徽标 | 代码段（推导规则） |
|---|---|---|---|
| `0` | 上交所主板 | 沪 | 60x/601/603/605（非 688）——原「沪深主板」沪部分，值不变、语义收窄 |
| `1` | 科创板 | 科 | 688（不变） |
| `2` | 创业板 | 创 | 300/301/302（不变） |
| `3` | 北交所 | 北 | 4xx/8xx/92x（不变） |
| `4` | 深交所主板 | 深 | 000/001/002/003（非 300 段）——新值，从原 `0` 拆出 |

设计理由：`1/2/3`（科创/创/北）的既有数字约定全部保留，`AGENTS.md` 与存量数据只需动两块——`0` 的注释从「沪深主板」收窄为「上交所主板」，新增 `4`（深交所主板）；存量**仅深主板**需要迁移（`BOARD_TYPE` `'0'` + `MARKET='SZ'` → `'4'`），沪主板与科创/创/北一行不用动。

## 4. 改动清单（按依赖排序，先文档后代码）

### 4.1 数据库（schema.sql 先行）

`docs/trade-signal-schema.sql` 的 `stock_info.BOARD_TYPE` 注释改为新字典（**不加列**）；生产迁移（二选一，推荐前者）：

1. 改完 scripts 后跑一次 `python -m fetch.stock_list`（全量幂等 upsert，按新规则重推所有行的 BOARD_TYPE，顺带刷新 NAME）；
2. 或等价手写迁移：`UPDATE stock_info SET BOARD_TYPE='4' WHERE MARKET='SZ' AND BOARD_TYPE='0';`（沪主板保持 `0`）。

### 4.2 scripts（数据维护）

- `scripts/common/const.py`：新增 `BOARD_SZ_MAIN = "4"` 常量；`board_type_of(code)` 改签名 `board_type_of(market, code)`——主板（非 688/300 段/北段）按 `market` 拆沪/深（SH→`0`、SZ→`4`），688→`1`、300/301/302→`2`、4xx/8xx/92x→`3` 不变。
- `scripts/fetch/stock_list.py:upsert_stock_info` 与统计处：调用 `board_type_of(market, code)` 传入 market。
- `scripts/fetch/new_stocks.py` 打印处：同步传 market（仅日志，不影响登记）。
- 转板新代码经 `fetch/new_stocks.py` 走同一 `upsert_stock_info`，板块/徽标自动正确，无额外维护。

### 4.3 Java（查询带出）

- `StockInfoDO` / `StockInfoMapper.xml` **不用动**（`BOARD_TYPE` 已映射为 `boardType`）。
- `CrossStockVO` 加 `boardType` 字段（三个扫描列表出参共用）。
- `KDJServiceImpl.buildCrossStockVO` 在 `setMarket` 旁补 `vo.setBoardType(info.getBoardType())`。

### 4.4 接口文档

`docs/trade-signal-api.md` 三个列表接口（gold-cross / trade-signal / all-stocks）出参补 `boardType` 字段与新字典说明（AGENTS.md 约定：改出参先改接口文档）。

### 4.5 硬性约定同步

`AGENTS.md` 板块枚举行更新为新字典（`"0"=上交所主板、"1"=科创板、"2"=创业板、"3"=北交所、"4"=深交所主板`），`docs/trade-signal-data-pipeline.md` 中提及 board_type 的语句同步。

### 4.6 前端（原型见 `docs/prototype/stock-badge-prototype.html`）

- 三张表的代码列从 `el-table-column v-for` 拆出，改固定列 + `template #default` 渲染 `{{ row.code }} + <span class="x-badge x-{boardType}">沪/科/创/北/深</span>`；三表共用同一模板与样式。
- `BADGE_MAP = {0:'沪', 1:'科', 2:'创', 3:'北', 4:'深'}`；空值返回空不渲染。
- 样式：18px 圆角小色块、白字单字，五色互不撞色（原型已配：沪蓝/深红/科绿/创橙/北紫，可再调）。

### 4.7 缓存与发版

- `ScanResultCache` 的 key 不含该字段，**无需改缓存逻辑**。
- 上线后旧缓存对象没有 `boardType`，前端会短暂渲染空徽标（无害）——发版后手动 `POST /kdj/cache/refresh` 一次，或等水位自动失效。

## 5. 转板（北交所 → 科创板/创业板）同步现状与策略

子 agent 调研结论（已对照代码核实）：

1. **新代码自动覆盖**：`fetch/new_stocks.py` 的「新增 = 交易所名单有、stock_info 无」会捕获转板后的新代码（实测 301192 泰祥、301321 翰博已在库且 `MARKET=SZ, BOARD_TYPE=2`），走 `run_history` 全历史回填后登记；`BOARD_TYPE` 随 upsert 按新前缀自动派生（688→`1` 科、300→`2` 创），**转板后徽标天然正确，无需人工**。
2. **旧代码无下线机制**：转板后旧 BSE 代码从名单消失，但 `stock_info`/`stock_quote` 只增不删（`stock_list.py` upsert 不删行），旧代码会永久残留、扫描里继续显示（数据停在转板日）。三个真实转板案例（832317→688287、833874→301192、833994→301321）均发生在系统建库前，旧代码从未入库，**当前无幽灵股票**；但机制缺口真实存在，与交接手册「退市股清理」待办同一根因。
3. **历史不合并**：新浪对新代码只返回转板上市日起的数据（实测 301192 为 20220811 起），北交所时期 KDJ 前史无法从新代码取得，代码里也没有旧→新映射表。**本次不做跨代码 K 线合并**（符合「单票按 code 实时计算」口径）；如需前史合并另立任务。
4. **建议同步策略（本方案不实施，单独立项）**：名单快照 diff + 同名校对（NAME 相同但 code/market 与名单不一致 → 旧 code 下线），`stock_info.STATUS CHAR(1)`（1=正常 0=下线），扫描 `targetCodes` 只取正常、增量抓取跳过下线 code（顺带解决退市股新浪 JSONDecodeError 噪音与 work_day 陈旧交易日问题）；`user_watchlist` 旧 code 前端提示失效。

## 6. 实施步骤（确认后执行）

1. 改 `docs/trade-signal-schema.sql`（注释）+ `AGENTS.md`（枚举行）+ `docs/trade-signal-api.md`（出参）——文档先行。
2. scripts：`const.py` 改 `board_type_of(market, code)` + `stock_list.py`/`new_stocks.py` 传 market；本机跑一次 `python -m fetch.stock_list` 对齐试点库。
3. Java：`CrossStockVO` 加 `boardType` + `buildCrossStockVO` 填充（DO/XML 不动）。
4. 前端：三表代码列模板 + BADGE_MAP + 样式（按原型）。
5. 测试 `./mvnw test` → 打包发版 → 生产跑一次 `stock_list` 回填（或手写迁移 UPDATE）+ 手动刷缓存。

## 7. 风险

- 唯一功能性风险：发版后忘刷缓存，徽标短暂不显示（无害）。
- `BOARD_TYPE` 字典变更是既有枚举语义调整（`0` 收窄、新增 `4`），须同步 AGENTS.md/schema 注释，且发版前完成存量迁移；无任何业务读取方依赖旧值，KDJ 计算与缓存 key 不受影响。
- 测试无断言依赖 BOARD_TYPE 值，`KDJScanWindowCacheTest` 插入的 `"0"` 在新字典下仍合法（沪主板）。
