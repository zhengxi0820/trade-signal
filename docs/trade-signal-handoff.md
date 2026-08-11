# 交接手册 — trade-signal（新 agent / 新人入场第一课）

> 读完这份再动手。权威细节在各专项文档（见文末指针），本手册负责让你 10 分钟内知道系统长什么样、现在是什么状态、坑在哪。
> 最近更新：2026-08-11（物化表 + 自选股 + 管线中转并行、三日一同步 + 物化自愈 + X-Data-Not-Ready 标记、北交所零价伪行清理）。

## 1. 这是什么

KDJ 交易位信号系统。A 股全市场（5534 只，沪深北）的日/周/月/季 KDJ 实时计算，识别金叉与交易位（买入信号）。线上：`https://zhengxi.online`（腾讯云轻量 43.138.158.123，4C4G）。

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

- **两层内存缓存**：结果缓存（接口+全参数为 key，命中毫秒级）→ bars 缓存（132 根窗口，key=code|adjust|kdjType，调参只重递推不取数）。均以 `max(trade_date)` 为水位自动失效，`POST /kdj/cache/refresh` 手动清。
- **物化表**：周/月/季 bars 由 scripts 物化，扫描直读（冷算：周 35s/月 10s/季 6s）；日线批量窗口读原始行（行即 bar）。
- **扫描批量装载**：每 200 只一条 SQL 按索引顺序读（单股随机 IO 是云盘大坑，600ms/股的窗口函数写法已禁用，用自连接取首尾价）。
- 实测对比见 `docs/trade-signal-deployment.md` 里程碑 2026-08-11。

## 4. 数据管线（每日 00:00 探针触发 + 3 天闸门，run_daily.sh；每小时物化自愈）

flock 防重叠 → 探针（600519 最新交易日 ≤ 主表水位、或距水位 <3 天则跳过本轮，防新浪封 IP）→ 新股检测（巨潮名单 vs stock_info，新股全历史双轨回填后登记）→ 2 路并行分片写 `stock_quote_log`（新浪唯一行情源，~6h）→ finalize（除权事件统一 rescale → 并入主表 → 逐行对账 → 备份 → truncate）→ work_day → 物化（常规周增量，除权股全删全插）→ 预热缓存。另有每小时 `ensure_period_bar.sh` 物化自愈（物化失败缺口 ≤1 小时补齐，与 run_daily 共用锁）。数据新鲜度 = 最近一个已完结交易日。

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

## 8. 当前待办 / 已知边界

- [ ] 腾讯云控制台手动打干净基线快照（提醒用户）
- [ ] 前端「数据更新至 yyyymmdd」展示（原型先行）
- [ ] 退市股清理（stock_info 不自动剔除退市股，增量抓空无害但会累积）
- [ ] 延期判定路径（除权日附近）尚无真实案例压测
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
| `docs/prototype/` | UI 原型留档（登录页、自选股导航） |
