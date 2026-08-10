#!/usr/bin/env python3
"""daily.py — 增量抓取（生产口径：周频，2026-08-08 起每周六 09:17 cron）

流程：对股票清单逐只爬近几日 none+qfq → 取库中尚无的交易日 → adjust.incremental 逐日应用。
串行真限流 sleep + 指数退避重试，与 fetch.history 同款。

源策略（2026-08-08 用户拍板，此前"东财熔断器+盘中行过滤"方案作废）：
- **新浪 stock_zh_a_daily 是唯一行情源**（fetch_pair 默认源序）。东财行情口（push2his）
  因限流实录彻底退出；东财 datacenter 除权公告日历保留（双轨不变，见 merge.py）。
- 接受一周延迟：新浪无视日期参数、每次返回全历史（~15-20s/股），日频全量不可行，
  故改为**每周六同步本周未爬数据**（flock 防重叠 + 断点幂等，漏跑下周自然补齐）。
- 新浪数据本身滞后一个交易日：周六跑时本周五数据已齐。
- 全量 --all（5535 只）周跑预计 23~30h。

用法（在 scripts/ 目录下）：
    python -m fetch.daily --codes 600519 600030
    python -m fetch.daily --all        # stock_info 全量（周频 cron 触发）
"""

import argparse
import os
import sys
from datetime import date, timedelta

os.environ["NO_PROXY"] = "*"

from adjust.incremental import apply_daily
from common.db import get_conn
from fetch.history import fetch_pair, upsert_quote_rows

LOOKBACK_DAYS = 10  # 每次回看窗口，覆盖周末/节假日/漏跑
INTERVAL = 2.5      # 新浪全历史抓取为重请求，限流与回填一致（2.5s）


def known_dates(conn, code: str, adjust: str, since: date) -> set:
    with conn.cursor() as cur:
        cur.execute(
            "SELECT TRADE_DATE FROM stock_quote WHERE CODE=%s AND ADJUST=%s AND TRADE_DATE>=%s",
            (code, adjust, since.strftime("%Y%m%d")),
        )
        return {r[0] for r in cur.fetchall()}


def run_daily_one(conn, code: str, interval: float = INTERVAL) -> dict:
    end = date.today()
    start = end - timedelta(days=LOOKBACK_DAYS)
    # 默认源序（新浪唯一行情源，东财行情口已退出）
    raw_rows, qfq_rows, source = fetch_pair(code, start, end, interval)
    qfq_by_date = {r["trade_date"]: r for r in qfq_rows}
    done = known_dates(conn, code, "0", start)

    # 窗口内逐日 factor（供持续性后验：候选跳变日之后 PERSIST_WINDOW 日的 factor）
    from adjust.factor import build_factor_series
    from common.const import PERSIST_WINDOW
    fs = build_factor_series(raw_rows, qfq_rows)
    idx_of = {d: i for i, d in enumerate(fs.dates)}

    applied, deferred, events = 0, 0, []
    for r in raw_rows:
        d = r["trade_date"]
        if d in done or d not in qfq_by_date or d not in idx_of:
            continue
        i = idx_of[d]
        future = fs.factors[i + 1:i + 1 + PERSIST_WINDOW]
        res = apply_daily(conn, code, r, qfq_by_date[d], upsert_quote_rows, future_factors=future)
        if res.get("deferred"):
            # 候选事件证据不足：当日行不落库、不计 done，下次增量窗口前移后重评。
            # 后续日期照常落库无妨——若事件最终确认，rescale 会整体 ×= k 修正它们。
            deferred += 1
            continue
        applied += 1
        if res["event"]:
            events.append((d, res["k"]))
    return {"applied": applied, "deferred": deferred, "events": events, "source": source}


def main() -> int:
    parser = argparse.ArgumentParser(description="每日增量抓取（none+qfq → 因子比对 → 落库）")
    parser.add_argument("--codes", nargs="*", help="股票代码列表")
    parser.add_argument("--all", action="store_true", help="stock_info 全量（慎用）")
    args = parser.parse_args()

    conn = get_conn()
    try:
        if args.all:
            with conn.cursor() as cur:
                cur.execute("SELECT CODE FROM stock_info")
                codes = [r[0] for r in cur.fetchall()]
        elif args.codes:
            codes = args.codes
        else:
            print("需要 --codes 或 --all", file=sys.stderr)
            return 1

        ok, failed = 0, []
        for i, code in enumerate(codes, 1):
            try:
                res = run_daily_one(conn, code)
                ok += 1
                tag = f" 事件={res['events']}" if res["events"] else ""
                if res.get("deferred"):
                    tag += f" 延期={res['deferred']}"
                print(f"[daily] {i}/{len(codes)} {code} +{res['applied']} 行({res['source']}){tag}", flush=True)
            except Exception as e:
                failed.append(code)
                print(f"[daily] {i}/{len(codes)} {code} 失败: {e}")
        print(f"[daily] 完成：成功 {ok}/{len(codes)}，失败 {len(failed)} 只: {failed}")
        return 0 if not failed else 2
    finally:
        conn.close()


if __name__ == "__main__":
    sys.exit(main())
