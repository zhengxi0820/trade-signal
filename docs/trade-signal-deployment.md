# 部署运维手册 — trade-signal

> 线上环境速查与运维操作。安全基线与上线检查单见 [SECURITY.md](SECURITY.md)。

## 环境

- 服务器：腾讯云轻量 `43.138.158.123`（Ubuntu 22.04，4C4G），域名 `zhengxi.online` / `www.zhengxi.online`
- 访问链路：公网 → Caddy（80/443，自动 HTTPS + 安全头 + HSTS）→ 应用 `127.0.0.1:8080` → MySQL `127.0.0.1:3306`
- 认证：密钥弹窗 → `POST /auth/login` 换 Cookie；密钥在服务器 `/etc/trade-signal.env` 的 `TRADE_SIGNAL_ACCESS_KEY`

## 服务器布局

| 项 | 位置 |
|---|---|
| 应用 jar | `/opt/trade-signal/app.jar` |
| 凭据文件（600, root） | `/etc/trade-signal.env`（DB 账号密码 + 访问密钥） |
| systemd 服务 | `trade-signal.service`（用户 `tradesignal`，Restart=on-failure） |
| Caddy 配置 | `/etc/caddy/Caddyfile` |
| 备份 | `/var/backups/trade-signal/`（cron `/etc/cron.d/trade-signal-backup`，每日 03:17，保留 7 天） |
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
- [ ] 全市场扫描接口（gold-cross/trade-signal/all-stocks）窗口优化：全量数据后全历史实时计算超时（120s+），需限制扫描窗口

## 已完成里程碑

- 2026-08-05 全量历史回填完成（5534 只，5499 成功 0 失败，33.4M 行，双轨事件 56634 个；689009 CDR 剔除）；work_day 全市场重建
- 2026-08-05 每日增量 cron 上线：`/etc/cron.d/trade-signal-daily`，工作日 19:17 跑 `fetch.daily --all`（东财窗口源，预计 4~8h），日志 `/home/ops/scripts/daily.log`
