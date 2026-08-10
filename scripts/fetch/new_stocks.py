#!/usr/bin/env python3
"""new_stocks.py — 新股维护：名单刷新 → 新增检测 → 全历史回填（周频同步前置步骤）

由 run_daily.sh 在 fetch.daily --all 之前调用，补上两个缺口：
- stock_info 名单不更新（stock_list 只在全量回填跑过一次，新上市股票永远进不来）
- 新股若只走增量（10 天回看窗口），上市超 10 天才被发现就会缺最早历史，
  必须走 fetch.history 全历史回填（双轨、新浪源、IPO 起）

检测口径：
- 新增 = 在交易所名单里但不在 stock_info
- 自愈 = 在 stock_info 但 stock_quote 零行情行（上次在"回填→登记"之间中断）→ 重走回填分支

时序（幂等关键）：**先 run_history 回填、后 upsert stock_info 登记**。
中断在两者之间 → 下周该股仍被识别为新增 → 回填幂等重跑（raw 只追加、qfq 重算覆盖、
事件按 EX_DATE upsert），不重复不出错。无新股时 0 成本通过（两次 SELECT + 一次名单抓取）。

用法（在 scripts/ 目录下）：
    python -m fetch.new_stocks                    # 生产：检测并回填新增
    python -m fetch.new_stocks --dry-run          # 只检测打印，不写库
    python -m fetch.new_stocks --dry-run --simulate 600519   # 模拟某 code 为新增，验证分支
"""

import argparse
import os
import sys

os.environ["NO_PROXY"] = "*"

from common.const import board_type_of
from common.db import get_conn
from fetch.stock_list import load_stock_list, upsert_stock_info

BACKFILL_YEARS = 40  # 新浪自然截到上市首日（与 full_backfill 同口径）

# 永久排除名单：数据源不支持、确认不接入的代码
# 689009 九号公司：CDR，新浪 stock_zh_a_daily 不支持 689 段（2026-08 已从 stock_info 剔除，
# 不加排除会被每周重复识别为新增、回填失败、刷日志）
SKIP_CODES = {"689009"}


def find_new_stocks(conn, stocks: list) -> tuple:
    """返回 (新增列表, 自愈列表)，元素为 (market, code, name)。SKIP_CODES 不参与。"""
    stocks = [s for s in stocks if s[1] not in SKIP_CODES]
    with conn.cursor() as cur:
        cur.execute("SELECT CODE FROM stock_info")
        registered = {r[0] for r in cur.fetchall()}
        cur.execute("SELECT DISTINCT CODE FROM stock_quote")
        have_quotes = {r[0] for r in cur.fetchall()}
    new = [s for s in stocks if s[1] not in registered]
    orphan = [s for s in stocks if s[1] in registered and s[1] not in have_quotes]
    return new, orphan


def main() -> int:
    parser = argparse.ArgumentParser(description="新股维护：检测新上市股票并走全历史回填")
    parser.add_argument("--dry-run", action="store_true", help="只检测打印，不写库")
    parser.add_argument("--simulate", metavar="CODE", help="模拟指定 code 为新增（配合 --dry-run 验证分支）")
    args = parser.parse_args()

    stocks = load_stock_list()
    print(f"[new] 交易所名单 {len(stocks)} 只")

    conn = get_conn()
    try:
        new, orphan = find_new_stocks(conn, stocks)
        if args.simulate:
            hit = [s for s in stocks if s[1] == args.simulate]
            sim = hit[0] if hit else ("SH" if args.simulate.startswith("6") else "SZ",
                                      args.simulate, "(模拟股)")
            new = [sim] + [s for s in new if s[1] != args.simulate]
            print(f"[new] 模拟模式：{args.simulate} 被当作新增（仅打印分支，不写库）")

        todo = new + orphan
        if not todo:
            print("[new] 无新增股票，0 成本通过")
            return 0
        for market, code, name in todo:
            tag = "新增" if (market, code, name) in new else "自愈(已登记无行情)"
            print(f"[new] {tag}: {market}:{code} {name} board_type='{board_type_of(code)}' "
                  f"→ 全历史回填(years={BACKFILL_YEARS}) + 登记 stock_info")

        if args.dry_run:
            print(f"[new] dry-run，共 {len(todo)} 只待处理，未写库")
            return 0

        from fetch.history import run_history
        ok, failed = 0, []
        for i, (market, code, name) in enumerate(todo, 1):
            try:
                r = run_history(code, BACKFILL_YEARS, conn=conn)
                # 先回填后登记：中断则下周自愈重跑
                upsert_stock_info([(market, code, name)])
                ok += 1
                print(f"[new] {i}/{len(todo)} {code} {name}: raw={r['raw_rows']} "
                      f"事件={len(r['events'])}({r['mode']}) 已登记 stock_info", flush=True)
            except Exception as e:
                # 回填失败不登记（下周自动重试）；新浪不支持的段（如 689 CDR）会每周重试并在此留痕
                failed.append(code)
                print(f"[new] {i}/{len(todo)} {code} {name} 回填失败(未登记，下周重试): "
                      f"{type(e).__name__}: {str(e)[:120]}", flush=True)
        print(f"[new] 完成：成功 {ok}/{len(todo)}，失败 {len(failed)}: {failed}")
        return 0 if not failed else 2
    finally:
        conn.close()


if __name__ == "__main__":
    sys.exit(main())
