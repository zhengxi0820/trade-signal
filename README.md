# trade-signal

KDJ 交易位信号系统。基于 stock_quote 行情表实时计算日/周/月/季 KDJ，识别金叉与交易位（买入信号），通过 HTTP 接口与内置 Web 前端（Vue + ECharts）对外提供。全市场扫描读 scripts 物化的周期 K 线表 stock_period_bar（周/月/季）提速，单票序列始终全历史实时计算。带用户体系（邀请码注册 + 用户名密码/密钥登录，见 `auth/`）与自选股（`/watchlist`）。

## 快速开始

```bash
# 1. 配置 MySQL 数据源与认证密钥：src/main/resources/application.yaml（凭据走环境变量）
# 2. 启动
./mvnw spring-boot:run
# 3. 登录取 Cookie（或浏览器打开 http://localhost:8080 用页面登录）
curl -c /tmp/ck -H 'Content-Type: application/json' -d '{"key":"访问密钥"}' http://localhost:8080/auth/login
# 4. 调用（/kdj/** 需认证）
curl -b /tmp/ck "http://localhost:8080/kdj/trade-signal"
```

## 文档

- 需求与业务规则：[docs/kdj-trade-signal-requirements.md](docs/kdj-trade-signal-requirements.md)
- 接口参数与示例：[docs/trade-signal-api.md](docs/trade-signal-api.md)
- 数据库表结构：[docs/trade-signal-schema.sql](docs/trade-signal-schema.sql)
- 数据管线（取数与复权）：[docs/trade-signal-data-pipeline.md](docs/trade-signal-data-pipeline.md)
- 开发约定：[AGENTS.md](AGENTS.md)
