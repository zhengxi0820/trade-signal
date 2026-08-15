# 变更记录（Change Log）

按日期倒序追加条目，格式：日期 + 一句话标题 + 改动清单 + 验证 + 回归用例编号 + 部署注意事项。

## 2026-08-14 — 前端工具栏控件苹果风 + 自选股 UPDATED_AT 修复

### 改动清单

- `src/main/resources/static/index.html`：样式缓存版本 v6→v13；查询按钮移除 `icon="Search"`（与参数设置纯文字块对齐）。
- `src/main/resources/static/css/style.css`：追加苹果风工具栏控件层样式（`--apple-*` 令牌）：
  - 截止周期标签/日期/周/月/季度选择器、代码名称筛选输入框：白底 10px 圆角细边框、聚焦蓝圈；
  - 板块设置/参数设置描边胶囊按钮 + 查询实心胶囊按钮（三按钮等高 32px、间距 9px）；
  - 日线/周线/月线/季线分段控件：选中白胶囊 + 苹果蓝字，选项间距 10px；
  - 板块设置弹层：12px 圆角白卡、苹果蓝勾选框；
  - 参数设置面板：仅保留复权类型分段控件（间距 8px、上移 4px）与开关苹果蓝背景，其余面板样式回滚原版。
- `AGENTS.md`：测试约定补充「每次改动需补充回归用例」条目。
- `docs/regression-test-cases.md`：新建回归测试用例库（含约定说明与本次 11 条用例）。

### 问题修复

- 收藏报错 `Unknown column 'UPDATED_AT'`：本地 dev 库 `user_watchlist` 缺少 UPDATED_AT 列（代码自 2026-08-13 起按新表结构写入）。已执行：
  `ALTER TABLE user_watchlist ADD COLUMN UPDATED_AT DECIMAL(15,0) DEFAULT NULL COMMENT '更新时间(UNIX秒)' AFTER CREATED_AT;`
  （生产库如缺同列，发版前需执行同样 DDL；schema 权威口径见 `docs/trade-signal-schema.sql`。）

### 验证

- `./mvnw test`：44/44 通过。
- API 冒烟：登录 204、未授权 401、periods/all-stocks/series/cache-refresh 200、收藏增删 204 幂等。
- UI 计算样式对拍 + 390px 移动端无横向溢出。
- 回归用例编号：R-20260814-01 ~ R-20260814-11。

## 2026-08-14 — 修复线上缓存旧版页面导致 Vue 未挂载（{{ toast.text }} 原样展示）

### 现象

- 线上打开页面时出现弹窗样式的 `{{ toast.text }}` 原样文本（登录提示条模板未编译）。

### 根因

- 全新浏览器实测线上页面正常、Vue 正常挂载；问题来自浏览器缓存了旧版 `index.html`
  （旧版含内联脚本，被生产 CSP `script-src 'self' 'unsafe-eval'` 拦截后 Vue 无法初始化，
  模板原样渲染，`.auth-toast` 因 `position:fixed` 看起来像一个弹窗）。

### 修复

- 新增 `com.xi.web.HtmlCacheHeaderFilter`：对 `/` 与 `*.html` 响应下发
  `Cache-Control: no-cache, no-store, must-revalidate`（含 Pragma/Expires 兼容头），
  浏览器每次回源校验 index.html；css/js 等静态资源不受影响（继续走 `?v=` 版本号失效）。

### 验证

- 本地：`/` 响应头含 no-store；css/api 路径无该头；`./mvnw test` 44/44 通过。
- 线上：全新浏览器 Vue 挂载正常（auth 卡片渲染，无 `{{ }}` 残留）；发版后响应头含 no-store。
- 回归用例编号：R-20260814-12。

## 2026-08-14 — 周期物化「未完结周期」判定修复（停牌股部分周期提前入表）

### 背景

- 审计 2026-08-14 同步日志时发现：物化表 max(period_end) 被顶到 20260812，
  根因是 300176（08-13 停牌）等停牌股的「未完结」周/月/季部分周期被提前物化。

### 根因

- 物化判定原为 `HAVING PE < (SELECT MAX(TRADE_DATE) FROM stock_quote)`（每股末交易日 < 全局最大日）：
  正常股等价于"等周期完结"，但停牌股 PE=其停牌前最后交易日 < 全局最大日，条件成立即提前入表，
  违反"该周期最后一个交易日数据获取到后才物化"的口径，并虚高就绪水位。

### 修复

- `scripts/aggregate/period_bar.py`：判定改为按周期桶比较
  `HAVING PKEY < '<全局最新交易日所在周期桶 key>'`（周=ISO 年周、月=yyyymm、季=yyyyQn，
  key 由 Python 按 max(trade_date) 计算，与 SQL 分组键同口径）；增量/全量两条 SQL 路径同步修改。
- 服务器清理误物化行 32 行（周 202633 桶 6 行 + 月 202608 桶 12 行 + 季 2026Q3 桶 14 行），
  水位回到正确值：周 20260807 / 月 20260731 / 季 20260630。
- 文档：`docs/trade-signal-data-pipeline.md` 周期物化口径更新。

### 验证

- 本地：py_compile 通过；三条类型 SQL（周/月/季）在本地 MySQL 实测语法与判定正确。
- 服务器：部署修复版脚本后重跑增量物化 1554.7s rc=0，当前周期桶 0 行，
  水位 周 20260807 / 月 20260731 / 季 20260630，已完结周期完好（详见回归用例 R-20260814-13）。

## 2026-08-15 — run_daily.sh 新增 FORCE 手动触发开关（非常规操作）

### 背景

- 用户需要立即补拉 08-14 数据，但三日闸门（距水位 1 天 <3）会跳过常规 cron。

### 改动

- `scripts/run_daily.sh`（此前仅存在于服务器，本次纳入仓库）：新增 `FORCE=1` 环境变量——
  仅绕过三日闸门（日志标记"人工手动触发（FORCE=1）"）；探针失败/无新数据仍跳过；
  常规 cron 不带 FORCE，行为不变。
- `docs/trade-signal-data-pipeline.md`：补人工手动触发说明。

### 执行与验证

- 2026-08-15 11:38 `FORCE=1 ./run_daily.sh` 触发，日志确认跳过闸门、探针 20260814 > 水位 20260813，
  两路分片启动；finalize 后水位应到 20260814（详见回归用例 R-20260815-01）。

## 2026-08-15 — 周期完结口径修订：最后一个交易日数据已获取即完结

### 背景

- 原 §2.4 口径为"自然日越过周期自然结束日（周日/月末/季末）"，导致周五收盘后（周六）
  无法看到本周周线；且物化脚本判定（周期桶 < 最新交易日所在桶）比 Java 口径更严一个交易日，
  周末/周期边界出现"列表有本周、扫描补齐中"的不一致。

### 改动

- 需求 §2.4 修订：周/月/季线完结 = 该周期最后一个计划交易日（work_day 日历，含未来预置）
  ≤ 库内最新交易日；日历截断兜底（未覆盖该周期之后按未完结核对，绝不提前完结）。
- Java：`KDJHandler.aggregate/aggregateDates` 锚点从 `LocalDate.now()` 改为 `asOf=G`，
  `isPeriodFinished` 改日历比较（新增 `PeriodCalendar` 纯数据载体，保持 KDJHandler 纯函数）；
  `KDJServiceImpl` 新增 `asOfDate()`/`periodCalendar()`（60s 缓存），5 处调用点统一。
- scripts：`workday.py` 新增 `--seed`（akshare 全年交易日预置 SH/SZ/BJ）与 `--reconcile`
  （每小时删"已过且无行情"日历行，与同步与否无关）；`period_bar.py` 判定改
  `HAVING PKEY <= 最大已完结桶`（日历口径）；`ensure_period_bar.sh` 自愈接入种子+对账，
  BEHIND 同口径；`run_daily.sh` 收尾同样执行种子+对账。
- 文档：需求 §2.4、API、数据管线、回归用例 R-20260815-02~12。

### 验证

- `./mvnw test` 45/45 通过（含新增周/月/季/截断兜底/就绪用例）。
- 线上：周六 G=20260814 → 周线 08-10~08-14 应出现，月/季停在已完结期（详见 R-20260815-12）。
