#!/usr/bin/env python3
"""daily.py — 每日增量抓取（生产口径，2026-08-05 起）

流程：对股票清单逐只爬近几日 none+qfq → 取库中尚无的交易日 → adjust.incremental 逐日应用。
串行真限流 sleep + 指数退避重试，与 fetch.history 同款。

源策略（与回填不同的关键点）：
- **东财优先**（SOURCES_EM_FIRST）：stock_zh_a_hist 支持 start/end 日期区间，10 天窗口
  payload 极小，每股 ~3-6s；新浪 stock_zh_a_daily 无视日期参数每次返回全历史（~15-20s/股），
  只做兜底。none/qfq 必须同源（复权基期一致），源切换以"股"为单位整体切换。
- 东财 qfq 与新浪同为等比前复权（锚定最新价），增量只看最近 factor（≈1 附近），
  与回填期的新浪基线兼容；东财成交量为手已 ×100 转股，与新浪口径一致。
- 限流 sleep 降到 1s（窗口小、请求轻），指数退避重试保留。

用法（在 scripts/ 目录下）：
    python -m fetch.daily --codes 600519 600030
    python -m fetch.daily --all        # stock_info 全量（5535 只，预计 4~8h，cron 日跑）
"""

import argparse
import os
import sys
from datetime import date, timedelta

os.environ["NO_PROXY"] = "*"

from adjust.incremental import apply_daily
from common.db import get_conn
from fetch.history import fetch_pair, upsert_quote_rows, SOURCES_EM_FIRST

LOOKBACK_DAYS = 10  # 每次回看窗口，覆盖周末/节假日/漏跑
INTERVAL = 1.0      # 窗口请求轻，限流降到 1s（回填仍 2.5s）


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
    raw_rows, qfq_rows, source = fetch_pair(code, start, end, interval,
                                            sources=SOURCES_EM_FIRST)
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
