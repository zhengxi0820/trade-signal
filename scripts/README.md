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

# 交易日历：从 stock_quote 生成 work_day
python -m adjust.workday

# 每日增量（实现未重点验证）：factor 比对 → 有除权则追加事件并下沉历史 qfq 行
python -m fetch.daily --codes 600519 600030
```

## 目录

```
common/  db.py（连接/MD5/UNIX时间戳） const.py（ADJUST、板块前缀、阈值、SOURCE 枚举）
fetch/   stock_list.py（灌 stock_info） history.py（历史回填） daily.py（每日增量）
         dividend.py（东财公告除权日历：分红+配股）
adjust/  factor.py（阶梯+五道闸事件判定+k拟合，纯函数） merge.py（双轨合并）
         backfill.py（初算+对拍+事件数保险） incremental.py（每日增量复权）
         workday.py（交易日历）
pilot.py 试点编排入口
verify_fix.py 事件判定只读验证（纯反推回归 + 万科双轨）
full_backfill.py 全量历史回填（断点续跑 + 事件数保险）
cdp_probe.py 前端无头调试探针（CDP 收集 console/异常/网络请求，排障用）
```
