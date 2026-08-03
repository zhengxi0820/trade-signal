#!/usr/bin/env python3
"""workday.py — 从 stock_quote 生成 work_day 交易日历

口径：按市场 distinct TRADE_DATE；市场取 stock_info.MARKET（stock_quote 不冗余市场字段，
靠 CODE join stock_info 取得）。ID = MD5(market:trade_date)，CREATE_DATE 填当日 yyyymmdd。
只追加，重跑幂等。

用法（在 scripts/ 目录下）：
    python -m adjust.workday
"""

import sys
from datetime import date

from common.db import get_conn, unix_ts, workday_id


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


def main() -> int:
    n = generate_work_days()
    print(f"[workday] 新增 {n} 个交易日历行")
    return 0


if __name__ == "__main__":
    sys.exit(main())
