# 交接手册 — trade-signal（新 agent / 新人入场第一课）

> 读完这份再动手。权威细节在各专项文档（见文末指针），本手册负责让你 10 分钟内知道系统长什么样、现在是什么状态、坑在哪。
> 最近更新：2026-08-16（周期完结口径改"最后一个交易日数据到位即完结"+ work_day 未来日历种子/每小时对账；run_daily 支持 FORCE=1 人工触发；物化准确性修复：增量窗口对齐周期边界 + 窗口内先删后插 + 全量重建对账，并新增 rebuild_period_bar.sh；回归测试用例库约定）。

## 1. 这是什么

KDJ 交易位信号系统。A 股全市场（沪深北，上市股票数随时间变动：2026-08-05 回填时 5534 只、08-10/11 实测 5535 只）的日/周/月/季 KDJ 实时计算，识别金叉与交易位（买入信号）。线上：`https://zhengxi.online`（腾讯云轻量 43.138.158.123，4C4G）。

技术栈：Spring Boot 4 / Java 17 / MyBatis / MySQL 8（Java 主体，只读库）；python 数据管线（`scripts/`，只写库）。两边通过数据库解耦。

## 2. 架构一页纸

```
公网 → Caddy(443, HTTPS+CSP) → 应用(127.0.0.1:8080, systemd trade-signal) → MySQL(127.0.0.1)
                                     ↑ 只读
新浪行情 ─→ scripts(每日 00:00 cron + 探针 + 3 天闸门) ─→ 写 stock_quote_log → finalize 并入主表
东财公告日历 ─（除权事件双轨判定）──────────┘              → 物化 stock_period_bar → 预热缓存
```

八张表：`stock_quote`（行情主表，raw+自算前复权两口径）、`stock_quote_log`（同步中转）、`stock_info`（名单/name/market 唯一来源）、`stock_dividend`（除权事件）、`work_day`（交易日历）、`stock_period_bar`（周/月/季物化 K 线）、`app_user`（用户，BCrypt）、`user_watchlist`（自选股）。

## 3. 性能体系（前阵子慢的根源都在这解决了）

- **两层内存缓存**：结果缓存（接口+全参数为 key，命中毫秒级；**同 key 并发未命中单飞共享一次计算**，防冷缓存并发风暴）→ bars 缓存（132 根窗口，key=code|adjust|kdjType，调参只重递推不取数）。均以 `max(trade_date)` 为水位自动失效，`POST /kdj/cache/refresh` 手动清。
- **物化表**：周/月/季 bars 由 scripts 物化，扫描直读（冷算：周 35s/月 10s/季 6s）；日线批量窗口读原始行（行即 bar）。
- **扫描批量装载**：每 200 只一条 SQL 按索引顺序读（单股随机 IO 是云盘大坑，600ms/股的窗口函数写法已禁用，用自连接取首尾价）。
- **重启自动预热**：cron 每 10 分钟 `ensure_warm.sh` 比对 systemd 启动时间戳 vs `.warm_marker`，应用重启（OOM/异常/手动）后自动触发一次 `warm_cache.sh`（`.warm.lock` 防并发；冷窗口由单飞兜底）。4C4G 已加 **2GB swap** 防 OOM（2026-08-12 事故后，详见部署里程碑）。
- 实测对比见 `docs/trade-signal-deployment.md` 里程碑 2026-08-11。

## 4. 数据管线（每日 00:00 探针触发 + 3 天闸门，run_daily.sh；每小时物化自愈）

flock 防重叠 → 探针（600519 最新交易日 ≤ 主表水位、或距水位 <3 天则跳过本轮，防新浪封 IP；人工触发用 `FORCE=1 ./run_daily.sh`，仅绕过闸门）→ 新股检测（交易所官方名单接口（沪 `stock_info_sh_name_code` / 深 `sz` / 北 `bj`）vs stock_info，新股全历史双轨回填后登记）→ 2 路并行分片写 `stock_quote_log`（新浪唯一行情源，~6h）→ finalize（除权事件统一 rescale → 并入主表 → 逐行对账 → 备份 → truncate）→ work_day → 物化 → 预热缓存。另有每小时 `ensure_period_bar.sh` 物化自愈（先跑日历种子（每日守卫）+ 对账清理（每小时，与同步与否无关），再按 BEHIND 补物化；与 run_daily 共用锁）。数据新鲜度 = 最近一个已完结交易日。

**周期完结与物化口径（2026-08-16 修订，权威在需求 §2.4 与管线文档）**：周/月/季线完结 = 该周期最后一个计划交易日（work_day 日历，含未来预置）≤ 库内最新交易日，且日历覆盖该周期之后（截断兜底）；每股周期内 ≥1 交易日即物化（停牌股不丢 K 线，首/末交易日 = 该股真实首/末交易日）。物化增量窗口起点对齐周期第一天（周→周一/月→1号/季→季首），写入为**窗口内先删后插**（同事务，数据后补不残留旧行）；`--full` 全量重建同样先删该类型全部行再流式插入，脏数据修复用 `./rebuild_period_bar.sh`（持锁防并发）。`work_day` 未来日期由 akshare 日历种子预置（`adjust.workday --seed`），每小时对账清理（`--reconcile`）修正临时休市。

复权是自建体系（因子反推 + 东财公告日历双轨），**别绕过它直接写 qfq 数据**；口径权威在 `docs/trade-signal-data-pipeline.md`。

## 5. 凭据与环境

- 全部在服务器 `/etc/trade-signal.env`（root 600）：DB 账号密码、访问密钥、注册邀请码。**明文不进仓、不写进文档**。
- 本机 Windows 开发机注意：shell 里有别的项目的 `DB_*` 环境变量，跑 scripts 必须显式覆盖全套，否则串库（Java 侧用 `TRADE_SIGNAL_` 前缀就是防这个）。
- 本机 MySQL CLI：`/c/Program Files/MySQL/MySQL Server 8.4/bin/mysql`（root 免密，库只有试点数据）；东财接口本机被墙、服务器可用。

## 6. 日常操作速查

```bash
./mvnw test                 # 本地全部测试（H2，不连真库）
# 发版
./mvnw package -DskipTests  # 注意先杀掉本地 8082 验证实例，否则 Windows 文件锁打包失败
scp target/trade-signal-0.0.1-SNAPSHOT.jar ops@43.138.158.123:/home/ops/
ssh ops@43.138.158.123 'sudo cp /home/ops/trade-signal-*.jar /opt/trade-signal/app.jar && sudo systemctl restart trade-signal'
# 日志 / 缓存 / 预热 / 备份：见 docs/trade-signal-deployment.md 常用操作节
```

## 7. 血泪教训速查（都是实测踩过的）

- cron.d 文件必须 `root:root 644`，否则静默拒跑（WRONG FILE OWNER）
- curl 超时断开 ≠ 服务端停算——僵尸计算会叠加，预热脚本已用 `--max-time 3600` 防
- 缓存 key 必须复刻前端**全量显式参数**，只传 kdjType 预热等于白热
- "窗口不够就回退全历史"的规则会误伤次新股（窗口=全历史，直接用，别再回退）
- H2 严格性：GROUP BY 别名要派生真实列、JOIN 要索引、CONCAT 只收 2 参；MySQL 私货语法会挂测试
- pgrep -f 会匹配到自己（用 `pgrep -f "fetch[.]daily"` 括号技巧）；编辑服务器上正在跑的脚本必须原子替换（新文件 + mv）
- MySQL `innodb_buffer_pool_size` 默认 128M，11GB 的表全市场扫描全走磁盘——已调 768M（`/etc/mysql/mysql.conf.d/zz-trade-signal.cnf`）
- **增量物化窗口 cutoff 必须对齐周期边界**（2026-08-14 曾因 cutoff 落在周中把全市场 07-20 周截成单日 bar 并覆盖正确行，导致金叉误筛）；改过 `period_bar.py` 后先跑 `scripts/tests/test_period_bar.py`
- **物化写入必须"先删后插"**：upsert 按 PERIOD_END 键，数据后补会插新行留旧行（600635 同周期两行事故）

## 8. 当前待办 / 已知边界

- [ ] 腾讯云控制台手动打干净基线快照（提醒用户）
- [ ] 前端「数据更新至 yyyymmdd」展示（原型先行）
- [ ] 退市股清理（stock_info 不自动剔除退市股，增量抓空无害但会累积）
- [ ] 延期判定路径（除权日附近）尚无真实案例压测
- [ ] 可选：每周低频全历史一致性校验（先按每股哈希快筛、不一致再细修，成本 ~2.5h）
- 日线冷算 ~8 分钟是大表 IO 物理下限（每周预热承担一次，用户无感）
- 用户体系无权限/角色、无 token 主动吊销（STATUS 只拦登录）——需要时再立

## 9. 文档指针

| 文档 | 何时读 |
|---|---|
| `AGENTS.md` | 硬性约定（BigDecimal/枚举/日期三字段/不落库/缓存纪律），动手前必读 |
| `docs/kdj-trade-signal-requirements.md` | 业务规则（信号六条件、勘误） |
| `docs/trade-signal-api.md` | 接口参数/出参/认证 |
| `docs/trade-signal-schema.sql` | 表结构（改表先改它） |
| `docs/trade-signal-data-pipeline.md` | 管线口径（双轨、五道闸、探针触发流程、验证记录） |
| `docs/trade-signal-deployment.md` | 运维（发版/备份/里程碑） |
| `docs/SECURITY.md` | 安全基线（上线检查单 3.1） |
| `scripts/README.md` | 管线命令用法 |
| `docs/prototype/` | UI 原型留档（登录页、自选股导航、板块筛选、股票徽标、KDJ 图表同步、登出 UI 等，`ls` 看全量；最新两个 `kdj-chart-sync-prototype.html`、`logout-ui-prototype.html` 尚未 git add） |
| `docs/security-review-20260819.md` | 安全审查报告（发现清单与修复优先级） |
