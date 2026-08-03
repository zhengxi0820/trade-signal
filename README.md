# trade-signal

KDJ 交易位信号系统。基于 stock_quote 行情表实时计算日/周/月/季 KDJ，识别金叉与交易位（买入信号），通过 HTTP 接口对外提供。

## 快速开始

```bash
# 1. 配置 MySQL 数据源：src/main/resources/application.yaml
# 2. 启动
./mvnw spring-boot:run
# 3. 调用
curl "http://localhost:8080/kdj/trade-signal"
```

## 文档

- 需求与业务规则：[docs/kdj-trade-signal-requirements.md](docs/kdj-trade-signal-requirements.md)
- 接口参数与示例：[docs/trade-signal-api.md](docs/trade-signal-api.md)
- 数据库表结构：[docs/trade-signal-schema.sql](docs/trade-signal-schema.sql)
- 数据管线（取数与复权）：[docs/trade-signal-data-pipeline.md](docs/trade-signal-data-pipeline.md)
- 开发约定：[AGENTS.md](AGENTS.md)
