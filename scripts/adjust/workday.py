"""workday.py — work_day 交易日历维护

三个动作（幂等，可组合）：
- 默认：从 stock_quote 生成实际交易日（市场取 stock_info.MARKET），只追加、重跑幂等。
- --seed：用 akshare 交易所日历（含未来日期，全年）预置 SH/SZ/BJ 交易日，
  供"周期最后一个交易日"完结判定使用；INSERT IGNORE 幂等，失败不影响存量。
- --reconcile：清理"已过且该市场全市场无行情"的日历行（临时休市/节假日调整），
  不管同步是否执行都应每小时跑（挂在 ensure_period_bar.sh）。

用法（在 scripts/ 目录下）：
    python -m adjust.workday                 # 实际交易日刷新（同步收尾调用）
    python -m adjust.workday --seed          # 日历种子（每日一次，akshare 网络）
    python -m adjust.workday --reconcile     # 对账清理（每小时）
"""

import argparse
import sys
from datetime import date

from common.db import get_conn, unix_ts, workday_id

MARKETS = ("SH", "SZ", "BJ")


def _fmt(d) -> str:
    """日期 → yyyymmdd（兼容 datetime.date / Timestamp / str）。"""
    if hasattr(d, "strftime"):
        return d.strftime("%Y%m%d")
    return str(d).replace("-", "")


def generate_work_days() -> int:
    conn = get_conn()
    now = unix_ts()
    today = date.today().strftime("%Y%m%d")
    try:
        with conn.cursor() as cur:
            cur.execute("""
                SELECT DISTINCT i.MARKET, q.TRADE_DATE
                FROM stock_quote q JOIN stock_info i ON q.CODE = i.CODE
            """)
            pairs = cur.fetchall()
            sql = """
                INSERT IGNORE INTO work_day (ID, MARKET, TRADE_DATE, CREATE_DATE, CREATED_AT, UPDATED_AT)
                VALUES (%s, %s, %s, %s, %s, %s)
            """
            n = cur.executemany(sql, [
                (workday_id(m, d), m, d, today, now, now) for m, d in pairs
            ])
        conn.commit()
        return n if n and n > 0 else 0
    finally:
        conn.close()


def seed_future_calendar() -> int:
    """akshare 交易所日历（含未来）→ SH/SZ/BJ 三市场预置全年交易日。"""
    import akshare as ak

    df = ak.tool_trade_date_hist_sina()
    dates = [_fmt(d) for d in df["trade_date"].tolist()]
    today = date.today().strftime("%Y%m%d")
    now = unix_ts()
    conn = get_conn()
    try:
        with conn.cursor() as cur:
            sql = """
                INSERT IGNORE INTO work_day (ID, MARKET, TRADE_DATE, CREATE_DATE, CREATED_AT, UPDATED_AT)
                VALUES (%s, %s, %s, %s, %s, %s)
            """
            n = cur.executemany(sql, [
                (workday_id(m, d), m, d, today, now, now) for m in MARKETS for d in dates
            ])
        conn.commit()
        return n if n and n > 0 else 0
    finally:
        conn.close()


def reconcile_past_dates() -> int:
    """删除"已过（≤ 最新交易日 G）且该市场当日全市场无行情"的日历行：
    临时休市/节假日调整时修正日历，保证周期完结判定正确。纯 SQL、无网络，可每小时跑。"""
    conn = get_conn()
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT MAX(TRADE_DATE) FROM stock_quote")
            g = cur.fetchone()[0]
            if not g:
                return 0
            n = cur.execute("""
                DELETE w FROM work_day w
                WHERE w.TRADE_DATE <= %s
                  AND NOT EXISTS (
                      SELECT 1 FROM stock_quote q JOIN stock_info i ON q.CODE = i.CODE
                      WHERE i.MARKET = w.MARKET AND q.TRADE_DATE = w.TRADE_DATE
                  )
            """, (g,))
        conn.commit()
        return n
    finally:
        conn.close()


def main() -> int:
    parser = argparse.ArgumentParser(description="work_day 交易日历维护")
    parser.add_argument("--seed", action="store_true", help="akshare 全年交易日预置（含未来，每日一次）")
    parser.add_argument("--reconcile", action="store_true", help="清理已过且无行情的日历行（每小时）")
    args = parser.parse_args()

    if args.seed:
        n = seed_future_calendar()
        print(f"[workday] 日历种子完成：{n} 行（{len(MARKETS)} 市场 × {n // len(MARKETS)} 交易日）")
    if args.reconcile:
        n = reconcile_past_dates()
        print(f"[workday] 对账清理完成：删除 {n} 行")
    if not args.seed and not args.reconcile:
        n = generate_work_days()
        print(f"[workday] 新增 {n} 个交易日历行")
    return 0


if __name__ == "__main__":
    sys.exit(main())
