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
| 行情同步 | cron `/etc/cron.d/trade-signal-daily`，**每日 00:00** 跑 `run_daily.sh`（探针判断新浪是否有新数据且距水位 ≥3 天，无/不足则跳过；有才执行：新股检测回填 → 2 路并行分片写 `stock_quote_log`（新浪源，实测 5h42m）→ finalize（事件 rescale → 并入主表 → 对账 → 备份 → truncate）→ work_day → 周期物化 → 缓存预热）。日志 `/home/ops/scripts/daily.log` |
| 物化自愈 | cron `/etc/cron.d/trade-signal-period-bar`，**每小时 :25** 跑 `ensure_period_bar.sh`：work_day 最新已完结周期 vs 物化表 max(period_end)，落后则补跑物化；与 run_daily 共用 `.run_daily.lock`（flock -n）防并发 |
| 重启自动预热 | cron `/etc/cron.d/trade-signal-warm`，**每 10 分钟**跑 `ensure_warm.sh`：比对 systemd `ActiveEnterTimestamp` 与标记文件 `.warm_marker`，应用重启（OOM/异常/手动）后自动触发一次 `warm_cache.sh`（与运行中预热共用 `.warm.lock` 防并发；冷缓存窗口由扫描单飞兜底） |
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
- 登录防爆破：同 IP 失败 5 次锁 15 分钟（429），审计日志 `AUTH login fail ip=...` 带真实 IP（取 X-Forwarded-For **末值**，首值可伪造）；限流/注册计数 Map 容量上限 1 万 IP，内存恒有界
- 用户 token 可吊销：禁用/改密执行 `update app_user set STATUS='0', UPDATED_AT=unix_timestamp() where USERNAME='...'` 即全量失效（检查缓存 60s）；token 格式 `subject.issuedAt.expiry.签名`（2026-08-19 起，旧三段 Cookie 全部失效需重登）
- `POST /kdj/cache/refresh` 仅密钥登录（subject=key）可调，注册用户 403
- Caddy 安全头：X-Frame-Options / X-Content-Type-Options / Referrer-Policy / HSTS / CSP
- **CSP 已下发**（2026-08-04 前端拆分为外置 css/js + vendor 本地化后）：`default-src 'self'; script-src 'self' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; img/font-src 'self' data:; connect-src 'self'; object-src 'none'; frame-ancestors 'none'`。两个 unsafe 是刻意取舍：Vue 全量版运行时编译模板需 eval，Element Plus 大量用内联样式；改动前端构建方式（预编译模板）后可去掉
- 自动安全更新：unattended-upgrades 已开

## 待办

- [ ] 干净基线快照（腾讯云控制台手动打，部署验证完成后）
- [ ] 前端「数据更新至 yyyymmdd」展示（数据延迟下用户需要知道数据到哪一天；用户已定：后续先做原型图）

## 已完成里程碑

- 2026-08-20 前端苹果风整页改造发版（v19，commit 2780cc0，apple-design-skill 规范落地）：毛玻璃吸顶导航栏 + 黑色胶囊页签、#F5F5F7 画布 + 18px 圆角白卡、透明表头表格、登录页纯白卡片重做、分段控件无底轨蓝底白字（间距 12px）、图表配色 K=#EA7A38/D=#8A51C3/J=#4CA2F7、蜡烛红涨 #E64340 绿跌 #27AE60；Element Plus 主题 CSS 变量桥接 #0066CC；系统字体栈不引外链（CSP 兼容）；纯静态资源无业务逻辑改动。发版验证：公网 200/v19/CSP 头完整/密钥登录 204/series 200/登录页样式完整渲染（R-20260820-05 通过；图表配色 R-04 线上目视）。回滚：`app.jar.bak-20260820`
- 2026-08-19 安全加固发版（报告 `docs/security-review-20260819.md`，S-01~S-08/S-10/S-11 已修复）：邀请码默认改空（未配置=注册关闭，本机开发需显式 export）；clientIp 改取 XFF 末值（防伪造绕过限流）；限流 Map 容量上限 1 万（防内存耗尽）；`/kdj/series` 强制 code 必填 400（防全表聚合重查询）；cache/refresh 仅密钥登录；token 内嵌签发时间 + 用户 token 服务端吊销检查（禁用/改密即失效，60s 缓存）；登录时延拉平防用户名枚举 + 重名文案去枚举 + 畸形哈希容错 + 密码 UTF-8 ≤72 字节；接入 Dependabot；删除根目录遗留 `ifind_ohlcv_sync.py`（自带偏离权威 schema 的 DDL）。**注意：token 格式变更，发版后所有用户需重新登录**
- 2026-08-12 重启自动预热：systemd `OnFailure=` 方案实测**不随 `Restart=` 触发**（失败后立即重启不激活 OnFailure），改用 cron 看门狗 `ensure_warm.sh`（每 10 分钟比对启动时间戳 vs 标记文件）；kill 演练验证通过：模拟 OOM → 自动重启 → 10 分钟内自动触发预热（日线冷 514s 后全部命中）。另确认 10:35 发版 jar 已含前端 B 版双图（其他参数双开关 + 单卡双图 + 持久化），无需再发版
- 2026-08-12 OOM 事故与恢复：04:31 应用被 OOM killer 击杀（4C4G 无 swap，Java RSS 1.8G + MySQL 1.4G 顶满 3.6G）→ systemd 重启后两层缓存全冷 → 并发全市场日线扫描在 MySQL 堆叠（同批 200 只查询 9+ 并发、负载 7、登录转圈）。处理：新增 **2GB swap**（`/swapfile` + fstab 持久化）；**扫描结果缓存加单飞**（同 key 并发请求共享一次计算，ScanResultCache.computeIfAbsent + 单飞并发测试）；重启 + 串行预热后恢复（负载 0.13、命中毫秒级）。教训：4C4G 无 swap 是内存刀尖，任何缓存全冷 + 并发访问都会演变成 OOM；单飞是把"冷缓存风暴"从根上拆掉的关键
- 2026-08-11 同步触发改版：cron 每周六 09:17 → 每日 00:00 + 探针（600519 最新交易日 ≤ 主表水位即跳过；另加 3 天闸门防新浪封 IP）+ 每小时物化自愈（ensure_period_bar.sh，与 run_daily 共用锁）；run_daily.sh 日志 rc 记录修复（原 `$(date)` 后取 `$?` 恒为 0，掩盖真实退出码）
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
