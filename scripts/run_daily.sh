#!/bin/bash
# run_daily.sh — 行情同步 cron wrapper（/etc/cron.d/trade-signal-daily 调用，每日 00:00）
# 口径（2026-08-08 起）：行情源唯一新浪；2026-08-11 起 cron 每日 00:00 触发，
#   探针比对新浪最新交易日 vs 主表水位（max trade_date）：无新数据（非交易日/数据未发布）直接跳过；
#   另加 3 天闸门（防新浪封 IP，先降频为三日一同步）：最新日期 - 水位 < 3 天也不跑重爬
# 流程（2026-08-10 起，log 表中转 + 2 路并行）：
#   flock → 探针（无新数据/间隔不足跳过） → 新股维护 → 分片 0/2 ‖ 分片 1/2（都写 stock_quote_log，主表零写入）
#   → finalize（事件统一执行 → 并入主表 → 对账 → 即时备份 → truncate）
#   → workday 刷新 → stock_period_bar 周期物化 → warm_cache 预热
# 物化自愈：/etc/cron.d/trade-signal-period-bar 每小时 ensure_period_bar.sh（与 run_daily 共用锁）
# 人工手动触发（非常规）：FORCE=1 ./run_daily.sh —— 仅绕过"三日闸门"，探针失败/无新数据仍跳过；
#   常规 cron 不带 FORCE，行为不变。日志会标记"人工手动触发（FORCE=1）"。
# cron 非交互环境：环境变量在这里显式装配，日志按日期分隔追加
set -uo pipefail

exec 9> /home/ops/scripts/.run_daily.lock
if ! flock -n 9; then
  echo "===== $(date "+%Y-%m-%d %H:%M:%S") 上一轮仍在运行，本轮跳过 =====" >> /home/ops/scripts/daily.log
  exit 0
fi

export NO_PROXY="*"
export DB_PASSWORD="$(sudo grep "^TRADE_SIGNAL_DB_PASSWORD" /etc/trade-signal.env | cut -d= -f2)"

cd /home/ops/scripts

# 探针：抓探针股（600519）最近 10 日新浪最新交易日 vs 主表水位。
# 最新日期 ≤ 水位 = 无新数据（非交易日或新浪尚未发布），直接跳过，省 5h+ 空跑；
# 3 天闸门：最新日期 - 水位 < 3 天同样跳过（探针单请求压力可忽略，重爬才是限流风险）。
# 探针本身失败（网络/接口异常）同样跳过并置非零退出码，避免在不可靠状态下起大任务。
PROBE_OUT=$(.venv/bin/python - <<'PY' 2>&1 | tail -n 1
from datetime import date, timedelta
from fetch.history import fetch_hist_sina

rows = fetch_hist_sina("600519", "qfq", date.today() - timedelta(days=10), date.today())
print(max(r["trade_date"] for r in rows))
PY
)
PROBE_RC=$?
WATERMARK=$(sudo mysql -N -e "SELECT MAX(TRADE_DATE) FROM trade_signal.stock_quote" 2>/dev/null)

if [ "$PROBE_RC" -ne 0 ]; then
  echo "===== $(date "+%Y-%m-%d %H:%M:%S") 探针失败，跳过本轮：$PROBE_OUT =====" >> daily.log 2>&1
  exit 1
fi
if [ -n "$WATERMARK" ] && [ "$PROBE_OUT" -le "$WATERMARK" ]; then
  echo "===== $(date "+%Y-%m-%d %H:%M:%S") 探针最新 $PROBE_OUT ≤ 水位 $WATERMARK，无新数据，跳过 =====" >> daily.log 2>&1
  exit 0
fi
MIN_SYNC_GAP_DAYS=3
if [ -n "$WATERMARK" ]; then
  PROBE_EPOCH=$(date -d "${PROBE_OUT:0:4}-${PROBE_OUT:4:2}-${PROBE_OUT:6:2}" +%s)
  WM_EPOCH=$(date -d "${WATERMARK:0:4}-${WATERMARK:4:2}-${WATERMARK:6:2}" +%s)
  GAP_DAYS=$(( (PROBE_EPOCH - WM_EPOCH) / 86400 ))
  if [ "$GAP_DAYS" -lt "$MIN_SYNC_GAP_DAYS" ]; then
    if [ "${FORCE:-0}" = "1" ]; then
      echo "===== $(date "+%Y-%m-%d %H:%M:%S") 人工手动触发（FORCE=1），跳过三日闸门（距水位 ${GAP_DAYS} 天） =====" >> daily.log 2>&1
    else
      echo "===== $(date "+%Y-%m-%d %H:%M:%S") 距水位 ${GAP_DAYS} 天 < ${MIN_SYNC_GAP_DAYS} 天，跳过（三日一同步，防新浪限流） =====" >> daily.log 2>&1
      exit 0
    fi
  fi
fi
echo "===== $(date "+%Y-%m-%d %H:%M:%S") 探针最新 $PROBE_OUT > 水位 ${WATERMARK:-空}（距 ${GAP_DAYS:-?} 天），开始同步 =====" >> daily.log 2>&1

# 新股维护：交易所名单 vs stock_info 检新增 → 全历史回填 → 登记（幂等，无新增 0 成本）
{
  echo "===== $(date "+%Y-%m-%d %H:%M:%S") 新股维护开始 ====="
  .venv/bin/python -m fetch.new_stocks
  rc=$?
  echo "===== $(date "+%Y-%m-%d %H:%M:%S") 新股维护结束 rc=$rc ====="
} >> daily.log 2>&1

# 分片阶段：2 路并行，各带独立 DB 连接，只写 log 表 + 事件暂存文件
{
  echo "===== $(date "+%Y-%m-%d %H:%M:%S") 分片阶段开始（2 路并行） ====="
  .venv/bin/python -m fetch.daily --shard 0/2 &
  P0=$!
  .venv/bin/python -m fetch.daily --shard 1/2 &
  P1=$!
  RC0=0; RC1=0
  wait $P0 || RC0=$?
  wait $P1 || RC1=$?
  echo "===== $(date "+%Y-%m-%d %H:%M:%S") 分片阶段结束 rc0=$RC0 rc1=$RC1 ====="
} >> daily.log 2>&1

# 收尾阶段：任一分片进程异常退出（rc>2，0=全成 2=个别股失败）则不 finalize，保现场待人工
{
  if [ "${RC0:-9}" -le 2 ] && [ "${RC1:-9}" -le 2 ]; then
    echo "===== $(date "+%Y-%m-%d %H:%M:%S") finalize 开始 ====="
    .venv/bin/python -m fetch.daily --finalize
    rc=$?
    echo "===== $(date "+%Y-%m-%d %H:%M:%S") finalize 结束 rc=$rc ====="
  else
    echo "===== $(date "+%Y-%m-%d %H:%M:%S") **分片异常 rc0=$RC0 rc1=$RC1，跳过 finalize，log 表现场保留** ====="
  fi
} >> daily.log 2>&1

# 交易日历刷新（幂等 INSERT IGNORE）
{
  echo "===== $(date "+%Y-%m-%d %H:%M:%S") workday 刷新开始 ====="
  .venv/bin/python -m adjust.workday
  rc=$?
  echo "===== $(date "+%Y-%m-%d %H:%M:%S") workday 结束 rc=$rc ====="
} >> daily.log 2>&1

# 日历种子（每日守卫，akshare 网络）与对账清理（不管同步与否都校正日历）
SEED_MARKER=/home/ops/scripts/.workday_seed.ts
if [ ! -f "$SEED_MARKER" ] || [ $(( $(date +%s) - $(stat -c %Y "$SEED_MARKER") )) -gt 72000 ]; then
  if .venv/bin/python -m adjust.workday --seed >> daily.log 2>&1; then
    touch "$SEED_MARKER"
  fi
fi
.venv/bin/python -m adjust.workday --reconcile >> daily.log 2>&1

# 周期物化（常规周增量：每股最新 1-2 个已完结周期 + 本周除权股全周期重算）
{
  echo "===== $(date "+%Y-%m-%d %H:%M:%S") period_bar 物化开始 ====="
  .venv/bin/python -m aggregate.period_bar
  rc=$?
  echo "===== $(date "+%Y-%m-%d %H:%M:%S") period_bar 结束 rc=$rc ====="
} >> daily.log 2>&1

# 缓存预热（数据水位已变，旧缓存自动失效，这里提前把默认参数重算好）
{
  echo "===== $(date "+%Y-%m-%d %H:%M:%S") 缓存预热开始 ====="
  /home/ops/scripts/warm_cache.sh
  rc=$?
  echo "===== $(date "+%Y-%m-%d %H:%M:%S") 预热结束 rc=$rc ====="
} >> daily.log 2>&1
