#!/bin/bash
# ensure_period_bar.sh — 周/月/季物化自愈：每小时检查物化是否落后于 work_day 最新已完结周期，
# 落后则补跑 aggregate.period_bar（分钟级）。与 run_daily.sh 共用 .run_daily.lock（flock -n）：
# run_daily 运行中则跳过（其收尾会物化），杜绝并发写 stock_period_bar；多实例间也由同一锁去重。
# cron：/etc/cron.d/trade-signal-period-bar → 25 * * * * ops /home/ops/scripts/ensure_period_bar.sh
set -uo pipefail

exec 9> /home/ops/scripts/.run_daily.lock
if ! flock -n 9; then
  echo "===== $(date "+%Y-%m-%d %H:%M:%S") run_daily 运行中，跳过物化自愈 =====" >> /home/ops/scripts/daily.log
  exit 0
fi

export NO_PROXY="*"
export DB_PASSWORD="$(sudo grep "^TRADE_SIGNAL_DB_PASSWORD" /etc/trade-signal.env | cut -d= -f2)"
cd /home/ops/scripts

# 日历种子（每日一次守卫，akshare 网络；失败不影响存量，下次再试）：
# work_day 预置未来交易日，供"周期最后一个交易日"完结判定使用
SEED_MARKER=/home/ops/scripts/.workday_seed.ts
if [ ! -f "$SEED_MARKER" ] || [ $(( $(date +%s) - $(stat -c %Y "$SEED_MARKER") )) -gt 72000 ]; then
  if .venv/bin/python -m adjust.workday --seed >> daily.log 2>&1; then
    touch "$SEED_MARKER"
  fi
fi
# 对账清理（每小时，与同步与否无关）：删"已过且全市场无行情"的日历行，修正临时休市/节假日调整
.venv/bin/python -m adjust.workday --reconcile >> daily.log 2>&1

# 落后判定（与 period_bar 同口径）：work_day 推导最新已完结周期末 vs 物化表 max(period_end)
BEHIND=$(.venv/bin/python - <<'PY' 2>&1 | tail -n 1
from common.db import get_conn

def latest_completed_end(conn, ptype):
    with conn.cursor() as cur:
        cur.execute("SELECT DISTINCT TRADE_DATE FROM work_day ORDER BY TRADE_DATE")
        dates = [r[0] for r in cur.fetchall()]
        cur.execute("SELECT MAX(TRADE_DATE) FROM stock_quote")
        maxd = cur.fetchone()[0]
    if not dates or not maxd:
        return None
    from datetime import date as _d, timedelta as _td
    def pkey(d):
        y, m, day = d[:4], d[4:6], d[6:8]
        if ptype == "1":  # ISO 周（周一起），与 YEARWEEK(...,3) 对齐
            dt = _d(int(y), int(m), int(day))
            return (dt - _td(days=dt.weekday())).strftime("%Y%m%d")
        if ptype == "2":
            return y + m
        return y + f"Q{(int(m) - 1) // 3 + 1}"
    groups = {}
    for d in dates:
        groups.setdefault(pkey(d), []).append(d)
    cal_max = max(dates)
    ends = [max(v) for v in groups.values() if max(v) <= maxd and max(v) < cal_max]
    return max(ends) if ends else None

conn = get_conn()
try:
    behind = False
    for ptype in ("1", "2", "3"):
        want = latest_completed_end(conn, ptype)
        with conn.cursor() as cur:
            cur.execute("SELECT MAX(PERIOD_END) FROM stock_period_bar WHERE PERIOD_TYPE=%s", (ptype,))
            have = cur.fetchone()[0]
        if want is None:
            continue
        if have is None or have < want:
            print(f"[ensure] type={ptype} 最新已完结={want} 物化表={have} 落后", flush=True)
            behind = True
    print("BEHIND=1" if behind else "BEHIND=0", flush=True)
finally:
    conn.close()
PY
)

case "$BEHIND" in
  BEHIND=0|BEHIND=1) ;;
  *)
    echo "===== $(date "+%Y-%m-%d %H:%M:%S") 自愈检查失败：$BEHIND =====" >> daily.log 2>&1
    exit 1
    ;;
esac

if [ "$BEHIND" = "BEHIND=1" ]; then
  echo "===== $(date "+%Y-%m-%d %H:%M:%S") 物化落后，自愈触发物化 =====" >> daily.log 2>&1
  .venv/bin/python -m aggregate.period_bar >> daily.log 2>&1
  rc=$?
  echo "===== $(date "+%Y-%m-%d %H:%M:%S") 自愈物化结束 rc=$rc =====" >> daily.log 2>&1
else
  echo "===== $(date "+%Y-%m-%d %H:%M:%S") 物化无落后（$BEHIND） =====" >> daily.log 2>&1
fi
