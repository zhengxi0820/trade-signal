#!/bin/bash
# rebuild_period_bar.sh — 物化表一次性全量重建（对账修复，非常规维护操作）
#
# 用途：物化表与 stock_quote 现场聚合对账不一致（脏数据）时，全量重建整张表。
# 行为：持 .run_daily.lock（flock -n）防与 run_daily / ensure_period_bar 并发；
#       先删该类型全部旧行，再按最新口径流式重建（--full，约 2.5 小时）。
# 用法（在 scripts/ 目录下）：
#     nohup ./rebuild_period_bar.sh > period_bar_full_rebuild.log 2>&1 &
set -uo pipefail

exec 9> /home/ops/scripts/.run_daily.lock
if ! flock -n 9; then
  echo "===== $(date "+%Y-%m-%d %H:%M:%S") 锁被占用（同步/自愈运行中），跳过重建 =====" >> /home/ops/scripts/period_bar_full_rebuild.log
  exit 1
fi

export NO_PROXY="*"
export DB_PASSWORD="$(sudo grep "^TRADE_SIGNAL_DB_PASSWORD" /etc/trade-signal.env | cut -d= -f2)"
cd /home/ops/scripts

echo "===== $(date "+%Y-%m-%d %H:%M:%S") 全量重建开始 =====" >> /home/ops/scripts/period_bar_full_rebuild.log
.venv/bin/python -m aggregate.period_bar --full
rc=$?
echo "===== $(date "+%Y-%m-%d %H:%M:%S") 全量重建结束 rc=$rc =====" >> /home/ops/scripts/period_bar_full_rebuild.log
exit $rc
