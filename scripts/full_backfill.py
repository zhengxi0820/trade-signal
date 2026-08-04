#!/usr/bin/env python3
"""full_backfill.py — 全量历史回填：stock_info 全表逐只跑双轨因子反推管线

- years=40：新浪全历史单次返回、客户端按区间过滤，40 年覆盖全部 A 股 IPO
  （最老 1990-12），即每只都从它自己的上市日起，满足"按 IPO 日期"口径。
- 双轨：每股额外 2 次东财请求（分红+配股公告日历）；东财失败自动回退纯反推。
- 保险：单股合并后事件数 > 市龄×3 → backfill 内中止（不写事件/qfq 行），
  本脚本把该股记入 full_backfill_review.txt 人工复核（000002 事故防御）。
- 断点续跑：stock_quote(ADJUST='0') 中已有近 7 日数据的票视为已完成，直接跳过；
  中途杀掉重跑不会重复爬。
- 单只失败不阻断，失败清单落 full_backfill_failed.txt。
- 预计耗时 17h+（每股 4 次请求 × 2.5s 限流 + 处理），nohup 跑：
    nohup .venv/bin/python full_backfill.py > full_backfill.log 2>&1 &
"""

import os
import sys
import time
from datetime import date, timedelta

os.environ["NO_PROXY"] = "*"

from common.db import get_conn
from fetch.history import run_history

RESUME_DAYS = 7


def main() -> int:
    years = int(sys.argv[1]) if len(sys.argv) > 1 else 40
    conn = get_conn()
    with conn.cursor() as cur:
        cur.execute("SELECT CODE FROM stock_info ORDER BY CODE")
        codes = [r[0] for r in cur.fetchall()]
        cutoff = (date.today() - timedelta(days=RESUME_DAYS)).strftime("%Y%m%d")
        cur.execute("SELECT DISTINCT CODE FROM stock_quote WHERE ADJUST='0' AND TRADE_DATE >= %s", (cutoff,))
        done = {r[0] for r in cur.fetchall()}

    todo = [c for c in codes if c not in done]
    print(f"[full] 总数={len(codes)} 已完成={len(done)} 待跑={len(todo)}", flush=True)

    failed, review = [], []
    for i, code in enumerate(todo, 1):
        t0 = time.time()
        try:
            r = run_history(code, years, conn=conn)
            if r["aborted"]:
                # 事件数超 市龄×3：backfill 已中止该股写库，人工复核
                review.append(code)
                print(f"[full] {i}/{len(todo)} {code}: **中止** 事件={len(r['events'])} "
                      f"超市龄×3，待人工复核 {time.time() - t0:.0f}s", flush=True)
            else:
                dev = f"{r['max_dev']:.4f}" if r["max_dev"] is not None else "N/A"
                print(f"[full] {i}/{len(todo)} {code}: raw={r['raw_rows']} 事件={len(r['events'])}"
                      f"({r['mode']},suspect={len(r['suspect'])},review={len(r['review'])}) "
                      f"偏差={dev} 源={r['source']} {time.time() - t0:.0f}s", flush=True)
        except Exception as e:
            failed.append(code)
            print(f"[full] {i}/{len(todo)} {code} 失败: {type(e).__name__}: {str(e)[:120]}", flush=True)

    with open("full_backfill_failed.txt", "w") as f:
        f.write("\n".join(failed))
    with open("full_backfill_review.txt", "w") as f:
        f.write("\n".join(review))
    print(f"[full] DONE 成功={len(todo) - len(failed) - len(review)} 失败={len(failed)} "
          f"待复核={len(review)}: {review[:50]}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
