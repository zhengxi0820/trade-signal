# scripts/ — 数据灌入管线

薄脚本风格，与 `C:/stock/fetch` 套件一致：`NO_PROXY=*` 强制直连、串行真限流 sleep、
指数退避重试 3 次、失败清单汇总。表结构以 `docs/trade-signal-schema.sql` 为唯一权威；
**数据源实测口径、因子反推法机制、已知风险与验证记录见 `docs/trade-signal-data-pipeline.md`**。

## 环境

```bash
cd scripts
python -m venv .venv
.venv/Scripts/python.exe -m pip install -r requirements.txt   # akshare/pymysql/tqdm
```

数据库连接走环境变量：`DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USER` 有本机开发缺省，
**`DB_PASSWORD` 无缺省必须显式设置**（凭据不落代码，SECURITY.md 2.7）。本机 shell 全局
驻留了其他项目的 `DB_*`，每次运行都要显式覆盖全套，例：`DB_NAME=trade_signal
DB_USER=trade_signal DB_PASSWORD=<本机 dev 库密码> python -m fetch.stock_list`。

## 用法

均在 `scripts/` 目录下、用 `.venv/Scripts/python.exe` 执行：

```bash
# M2：灌 stock_info（全量 A 股，沪/深/北三所名单）
python -m fetch.stock_list

# M3：单股历史回填（none 落库 → 公告日历+因子反推双轨 → 事件 → 自算 qfq 落库 → 对拍）
python -m fetch.history --code 600519 --years 3

# 东财公告除权日历（分红+配股，打印不写库；双轨的权威事件日历）
python -m fetch.dividend --code 000002

# M3：试点编排（6 只试点股批量跑 + 汇总报告）
python pilot.py --years 3

# 全量历史回填（40 年，断点续跑；事件数>市龄×3 中止待复核，nohup 跑）
nohup .venv/bin/python full_backfill.py > full_backfill.log 2>&1 &

# 只读验证（事件判定回归：4 只纯反推 + 万科双轨；不写库）
python verify_fix.py

# 交易日历：实际日从 stock_quote 生成；未来日由 akshare 全年日历预置（--seed，每日一次守卫）
#           每小时对账清理（--reconcile，删"已过且全市场无行情"的预置行，与同步与否无关）
python -m adjust.workday
python -m adjust.workday --seed
python -m adjust.workday --reconcile

# 新股维护（同步前置步骤）：名单刷新 → 新增/自愈检测 → 全历史回填 → 登记 stock_info
python -m fetch.new_stocks --dry-run    # 只检测打印不写库；--simulate CODE 模拟新增分支
python -m fetch.new_stocks              # 生产执行（幂等，无新增 0 成本）

# 增量（生产，三日一同步 + log 表中转 + 2 路并行）：
python -m fetch.daily --shard 0/2    # 分片：写 stock_quote_log + 事件暂存（每线程限流 3s）
python -m fetch.daily --shard 1/2
python -m fetch.daily --finalize     # 收尾：事件统一执行+rescale → 并入主表 → 对账 → 备份 → truncate
python -m fetch.daily --codes 600519 # 手工调试：单股直写主表（旧行为）
python -m fetch.daily --all          # 单线程直写主表（应急兜底，不推荐周跑用）

# 周期物化（stock_period_bar 周/月/季）：
#   增量窗口起点对齐周期第一天（周→周一/月→1号/季→季首），写入为"窗口内先删后插"（同事务），
#   数据后补不残留旧行、同一周期绝不两行；每股周期内 ≥1 交易日即物化（停牌股不丢 K 线）
python -m aggregate.period_bar
python -m aggregate.period_bar --full
# 物化表脏数据修复（全量重建，持 .run_daily.lock 防并发）：
nohup ./rebuild_period_bar.sh > period_bar_full_rebuild.log 2>&1 &
# 纯函数单元测试（窗口对齐 / 周期桶 / 最大已完结桶，无 pytest 依赖）：
python tests/test_period_bar.py
# 服务器由 /etc/cron.d/trade-signal-daily 触发（每日 00:00 探针 + 3 天闸门，防新浪封 IP；wrapper run_daily.sh：
# flock → 探针（无新数据/间隔不足跳过）→ fetch.new_stocks 新股维护 → 分片 0/2 ‖ 1/2 → finalize → adjust.workday
# → aggregate.period_bar → warm_cache 预热，日志 daily.log；人工强制触发：FORCE=1 ./run_daily.sh（仅绕过三日闸门，非常规）
# 另有每小时 ensure_period_bar.sh 物化自愈（日历种子+对账清理+BEHIND 补物化，与 run_daily 共用锁）、
# 每 10 分钟 ensure_warm.sh 重启自动预热（应用重启后自动触发 warm_cache，防并发用 .warm.lock）；
# 东财行情口已退出生产（限流实录），公告日历保留
```

## 目录

```
common/  db.py（连接/MD5/UNIX时间戳） const.py（ADJUST、板块前缀、阈值、SOURCE 枚举）
fetch/   stock_list.py（灌 stock_info） history.py（历史回填） daily.py（每日增量）
         dividend.py（东财公告除权日历：分红+配股）
adjust/  factor.py（阶梯+五道闸事件判定+k拟合，纯函数） merge.py（双轨合并）
         backfill.py（初算+对拍+事件数保险） incremental.py（增量复权，支持分片暂存）
         workday.py（交易日历）
aggregate/ period_bar.py（周/月/季物化聚合，--full 全量）
tests/ test_period_bar.py（物化纯函数单元测试）
rebuild_period_bar.sh 物化表全量重建（持锁）
pilot.py 试点编排入口
verify_fix.py 事件判定只读验证（纯反推回归 + 万科双轨）
full_backfill.py 全量历史回填（断点续跑 + 事件数保险）
cdp_probe.py 前端无头调试探针（CDP 收集 console/异常/网络请求，排障用）
```
