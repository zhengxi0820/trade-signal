# 部署运维手册 — trade-signal

> 线上环境速查与运维操作。安全基线与上线检查单见 [SECURITY.md](SECURITY.md)。

## 环境

- 服务器：腾讯云轻量 `43.138.158.123`（Ubuntu 22.04，4C4G），域名 `zhengxi.online` / `www.zhengxi.online`
- 访问链路：公网 → Caddy（80/443，自动 HTTPS + 安全头 + HSTS）→ 应用 `127.0.0.1:8080` → MySQL `127.0.0.1:3306`
- 认证：登录页（用户名+密码 / 访问密钥 / 邀请码注册）→ `POST /auth/login` 换 Cookie；密钥与邀请码在服务器 `/etc/trade-signal.env`（`TRADE_SIGNAL_ACCESS_KEY` / `TRADE_SIGNAL_INVITE_CODES`）

## 服务器布局

| 项 | 位置 |
|---|---|
| 应用 jar | `/opt/trade-signal/app.jar` |
| 凭据文件（600, root） | `/etc/trade-signal.env`（DB 账号密码 + 访问密钥） |
| systemd 服务 | `trade-signal.service`（用户 `tradesignal`，Restart=on-failure，`java -Xmx1536m -jar`） |
| Caddy 配置 | `/etc/caddy/Caddyfile` |
| 每周行情同步 | cron `/etc/cron.d/trade-signal-daily`，**每周六 09:17** 跑 `run_daily.sh`：新股检测回填 → 2 路并行分片写 `stock_quote_log`（新浪源，实测 5h42m）→ finalize（事件 rescale → 并入主表 → 对账 → 备份 → truncate）→ work_day → 周期物化 → 缓存预热。日志 `/home/ops/scripts/daily.log` |
| 备份 | `/var/backups/trade-signal/`（**主备份在 finalize 里随同步完成即时执行**；另有 cron `/etc/cron.d/trade-signal-backup` 每周一 03:47 兜底，**只保留最新一份**；cron.d 文件必须 root:root 644，否则被拒跑） |
| 运维账号 | `ops`（密钥登录 + sudo NOPASSWD）；root 直登已禁，密码/扫码认证已关 |

## 常用操作

```bash
ssh ops@43.138.158.123

# 应用
sudo systemctl restart trade-signal
sudo journalctl -u trade-signal -f          # 日志（含 AUTH 审计）
sudo systemctl status trade-signal caddy mysql

# 发版：本地 ./mvnw package -DskipTests 后
scp target/trade-signal-0.0.1-SNAPSHOT.jar ops@43.138.158.123:/home/ops/
ssh ops@43.138.158.123 'sudo cp /home/ops/trade-signal-0.0.1-SNAPSHOT.jar /opt/trade-signal/app.jar && sudo systemctl restart trade-signal'

# 手动备份 / 恢复
sudo /usr/local/bin/trade-signal-backup.sh
zcat /var/backups/trade-signal/trade_signal-<日期>.sql.gz | sudo mysql trade_signal

# 手动清缓存 / 手动预热（平时不需要，水位自动失效）
KEY=$(sudo grep '^TRADE_SIGNAL_ACCESS_KEY' /etc/trade-signal.env | cut -d= -f2)
curl -c /tmp/ck -H 'Content-Type: application/json' -d "{\"key\":\"$KEY\"}" http://127.0.0.1:8080/auth/login
curl -b /tmp/ck -X POST http://127.0.0.1:8080/kdj/cache/refresh
nohup /home/ops/scripts/warm_cache.sh > /home/ops/scripts/warm_cache.log 2>&1 &
```

## 安全口径（已落实，变更时同步本表）

- 端口：公网仅 22/80/443（云防火墙 + ufw 双层）；3306/8080/2019 仅 127.0.0.1
- SSH：仅密钥（ed25519），`PermitRootLogin no`，fail2ban 5 次封 24h
- 凭据：全部在 `/etc/trade-signal.env`（600），DB 密码与网站密钥均为生产独立随机值，不入仓
- 登录防爆破：同 IP 失败 5 次锁 15 分钟（429），审计日志 `AUTH login fail ip=...` 带真实 IP
- Caddy 安全头：X-Frame-Options / X-Content-Type-Options / Referrer-Policy / HSTS / CSP
- **CSP 已下发**（2026-08-04 前端拆分为外置 css/js + vendor 本地化后）：`default-src 'self'; script-src 'self' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; img/font-src 'self' data:; connect-src 'self'; object-src 'none'; frame-ancestors 'none'`。两个 unsafe 是刻意取舍：Vue 全量版运行时编译模板需 eval，Element Plus 大量用内联样式；改动前端构建方式（预编译模板）后可去掉
- 自动安全更新：unattended-upgrades 已开

## 待办

- [ ] 干净基线快照（腾讯云控制台手动打，部署验证完成后）
- [ ] 前端「数据更新至 yyyymmdd」展示（周频延迟后用户需要知道数据到哪一天；用户已定：后续先做原型图）

## 已完成里程碑

- 2026-08-11 扫描进入物化表时代：周/月/季直读 `stock_period_bar` + 批量装载（200 只/批）+ **新股误回退修复**（窗口未满=全历史已在窗口内，此前次新股被误判逐股全历史重算，是月/季线 846s+ 的元凶）。实测冷算：周 1309s→35s、月 846s→10s、季 3600s+→6s，缓存命中全亚秒级
- 2026-08-11 管线 log 中转 + 2 路并行上线：分片期主表零写入（水位只翻一次），收尾统一事件 rescale + 并入 + 值级对账 + 即时备份 + truncate；全市场同步 23~30h→**5h42m**；`aggregate/period_bar.py` 物化首启 2h39m（周 708 万/月 168 万/季 56.6 万行），对拍 0 差异；数据对齐 20260810
- 2026-08-11 自选股上线：`user_watchlist` 表 + `/watchlist` 端点（按 token 用户名隔离）+ 行首星标 + 居中页签（全市场/我的自选）+ 代码/名称本地筛选 + 查询触发制（改条件不发请求，按钮带提示点）
- 2026-08-10 MySQL 性能修复：`innodb_buffer_pool_size` 默认 128M → 768M（`/etc/mysql/mysql.conf.d/zz-trade-signal.cnf`）。根因实录：stock_quote 数据+索引 ~11GB，128M 缓冲池导致全市场扫描全走磁盘（冷 ~1s/股 vs 热 ~40ms/股），月/季线深历史扫描超时 3600s 的元凶
- 2026-08-10 14 只试点股历史补齐（agent-0 双轨重灌，0 失败）：茅台/中信/包钢等 10 只从 3-5 年试点窗补到 IPO 全历史（另 4 只试点起点即 IPO 日本就完整）；两口径 1:1、对拍全部舍入级；副作用：这 14 只水位到 20260807（全市场 20260806），周六周频首跑自动对齐
- 2026-08-10 前端性能修复：所有股票表 el-table 全量渲染 5534 行卡顿 → 前端分页（100/页）；后端接口本就毫秒级，卡顿是浏览器 DOM 渲染瓶颈
- 2026-08-10 用户体系上线：邀请码注册制（`app_user` 表 + `TRADE_SIGNAL_INVITE_CODES` 环境变量，值在 /etc/trade-signal.env 不入仓）+ 用户名密码登录（密钥登录保留给脚本）+ 登录页 A 版（浮空玻璃卡）；admin 账号已建；token 带 subject，旧 Cookie 已失效一轮
- 2026-08-10 扫描性能二级缓存上线：ScanBarsCache（132 根窗口 bars，调过滤参数毫秒级、调 n/m1/m2 秒级）+ JVM -Xmx1536m；warm_cache.sh 复刻前端全量参数预热（key 才能对上）+ --max-time 3600（防僵尸计算叠加）
- 2026-08-08 数据源策略切换：东财行情口限流 3 天（全量回填后疑似被拉黑名单），行情源改为**新浪周频**（每周六 09:17，接受数据延迟至最近周五）；东财 datacenter 公告日历保留（双轨不变）；熔断器/盘中行过滤方案随之作废
- 2026-08-08 数据对齐清理：停掉重叠爬取任务，删除 20260807 半成品行 7156 行，全市场 5527 只对齐 20260806；删除前手动备份（1.67GB）
- 2026-08-08 备份 cron 修复：/etc/cron.d/trade-signal-backup 属主 ops 被 cron 拒跑（WRONG FILE OWNER，8-05 起 4 天无备份），已 chown root:root
- 2026-08-08 全市场扫描性能上线：结果缓存（ScanResultCache 水位失效）+ 日/周线窗口裁剪 + **月/季线 SQL 预聚合**（queryMonthlyBars/queryQuarterlyBars，与 KDJHandler.aggregate 逐 bar 对拍）；POST /kdj/cache/refresh
- 2026-08-05 全量历史回填完成（5534 只，5499 成功 0 失败，33.4M 行，双轨事件 56634 个；689009 CDR 剔除）；work_day 全市场重建
- 2026-08-05 每日增量 cron 上线（已于 2026-08-08 改为每周六 09:17 周频）
