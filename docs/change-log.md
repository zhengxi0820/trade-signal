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
