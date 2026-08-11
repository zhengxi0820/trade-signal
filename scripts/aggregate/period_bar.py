#!/usr/bin/env python3
"""period_bar.py — stock_period_bar 周/月/季物化聚合（周频同步收尾阶段调用）

口径与 Java 侧 KDJHandler.aggregate 完全一致：
- 周 = ISO 周（周一起，YEARWEEK(...,3)）；月 = 自然月；季 = 自然季
- OPEN = 周期首交易日开盘、CLOSE = 周期末交易日收盘、HIGH/LOW = 周期内极值
- PERIOD_START/END = 周期首/末真实交易日（yyyymmdd）
- **未完结周期不入表**：判定规则 = 周期末交易日 < 全库最大交易日（HAVING PE < max）。
  即含最新交易日的那个周期（本周/本月/本季）永远不物化，下周数据进来后它自然变成
  已完结周期被补物化——与"接受一周延迟"的周频口径自洽。
- 表无 VOLUME 列（schema 契约），ID 自增，幂等靠唯一键 (PERIOD_TYPE,CODE,ADJUST,PERIOD_END)。

批量口径：SQL 聚合，每 BATCH_SIZE=200 只一批 GROUP BY，不逐股 python 循环。

模式：
- 常规周（默认）：每股只重算近期窗口内的周期（周 21 天/月 62 天/季 200 天，覆盖最新
  1-2 个已完结周期）；本周有除权事件的股（stock_dividend.EX_DATE 在近 EVENT_LOOKBACK_DAYS
  内，历史 qfq 行被 rescale 过）**全周期重算覆盖**（无窗口）。
- 首次启用（--full）：全量物化，打印耗时与各 PERIOD_TYPE 行数。

用法（在 scripts/ 目录下）：
    python -m aggregate.period_bar          # 常规周（增量物化）
    python -m aggregate.period_bar --full   # 首次全量物化
"""

import argparse
import os
import sys
import time
from datetime import date, timedelta

from common.db import get_conn, unix_ts

BATCH_SIZE = 200
EVENT_LOOKBACK_DAYS = 10  # 本周有除权事件的股全周期重算（比 7 天略宽，覆盖漏跑）

# 周期类型 → (分组键 SQL, 常规周重算窗口天数)
# 注意：pymysql 用 % 格式化参数，SQL 字面量里的 % 必须写成 %%
PERIOD_CONF = {
    "1": ("YEARWEEK(STR_TO_DATE(TRADE_DATE,'%%Y%%m%%d'),3)", 21),   # ISO 周（周一起）
    "2": ("LEFT(TRADE_DATE,6)", 62),                                 # 自然月
    "3": ("CONCAT(LEFT(TRADE_DATE,4),'Q',CEIL(MID(TRADE_DATE,5,2)/3))", 200),  # 自然季
}

AGG_SQL = """
    SELECT g.CODE, g.ADJUST, g.PS, g.PE, o.OPEN, g.H, g.L, c.CLOSE
    FROM (
        SELECT CODE, ADJUST, {pkey} AS PKEY,
               MIN(TRADE_DATE) AS PS, MAX(TRADE_DATE) AS PE,
               MAX(HIGH) AS H, MIN(LOW) AS L
        FROM stock_quote
        WHERE CODE IN ({codes_ph}) {cutoff}
        GROUP BY CODE, ADJUST, PKEY
        HAVING PE < (SELECT MAX(TRADE_DATE) FROM stock_quote)
    ) g
    JOIN stock_quote o ON o.CODE=g.CODE AND o.ADJUST=g.ADJUST AND o.TRADE_DATE=g.PS
    JOIN stock_quote c ON c.CODE=g.CODE AND c.ADJUST=g.ADJUST AND c.TRADE_DATE=g.PE
"""

# 全量物化专用：单遍全表 GROUP BY（实测 8m/类型，比分批随机 IO 快 ~25 倍）+ 流式读取防内存爆
FULL_AGG_SQL = """
    SELECT g.CODE, g.ADJUST, g.PS, g.PE, o.OPEN, g.H, g.L, c.CLOSE
    FROM (
        SELECT CODE, ADJUST, {pkey} AS PKEY,
               MIN(TRADE_DATE) AS PS, MAX(TRADE_DATE) AS PE,
               MAX(HIGH) AS H, MIN(LOW) AS L
        FROM stock_quote
        GROUP BY CODE, ADJUST, PKEY
        HAVING PE < (SELECT MAX(TRADE_DATE) FROM stock_quote)
    ) g
    JOIN stock_quote o ON o.CODE=g.CODE AND o.ADJUST=g.ADJUST AND o.TRADE_DATE=g.PS
    JOIN stock_quote c ON c.CODE=g.CODE AND c.ADJUST=g.ADJUST AND c.TRADE_DATE=g.PE
"""

UPSERT_SQL = """
    INSERT INTO stock_period_bar
        (PERIOD_TYPE, CODE, ADJUST, PERIOD_START, PERIOD_END, OPEN, HIGH, LOW, CLOSE, CREATED_AT, UPDATED_AT)
    VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
    ON DUPLICATE KEY UPDATE
        PERIOD_START=VALUES(PERIOD_START), OPEN=VALUES(OPEN), HIGH=VALUES(HIGH),
        LOW=VALUES(LOW), CLOSE=VALUES(CLOSE), UPDATED_AT=VALUES(UPDATED_AT)
"""


def batched(seq, n):
    for i in range(0, len(seq), n):
        yield seq[i:i + n]


def _materialize_full(conn) -> dict:
    """首次全量物化：单遍全表 GROUP BY + SSCursor 流式读取、每 5000 行一批 upsert。

    注意：SSCursor 流式读取期间同一连接上任何操作（含 commit）都会掐断结果流，
    upsert 必须走第二个独立连接。
    """
    import pymysql
    wconn = get_conn()
    stats = {}
    try:
        for ptype, (pkey, _) in PERIOD_CONF.items():
            t0 = time.time()
            now = unix_ts()
            total = 0
            # 全量查询不带绑定参数，pymysql 不做 % 转义，需把 %% 还原为 %
            sql = FULL_AGG_SQL.format(pkey=pkey.replace("%%", "%"))
            cur = conn.cursor(pymysql.cursors.SSCursor)
            try:
                cur.execute(sql)
                buf = []
                for row in cur:
                    code, adjust, ps, pe, o, h, l, c = row
                    buf.append((ptype, code, adjust, ps, pe, o, h, l, c, now, now))
                    if len(buf) >= 5000:
                        with wconn.cursor() as w:
                            w.executemany(UPSERT_SQL, buf)
                        wconn.commit()
                        total += len(buf)
                        print(f"[period_bar] type={ptype} 流式 upsert 累计 {total} 行 {time.time()-t0:.0f}s", flush=True)
                        buf = []
                if buf:
                    with wconn.cursor() as w:
                        w.executemany(UPSERT_SQL, buf)
                    wconn.commit()
                    total += len(buf)
            finally:
                cur.close()
            stats[ptype] = total
            print(f"[period_bar] PERIOD_TYPE={ptype} upsert {total} 行，本类型耗时 {time.time()-t0:.0f}s", flush=True)
    finally:
        wconn.close()
    return stats


def materialize(conn, full: bool) -> dict:
    """物化主流程。返回 {period_type: upsert_rows}。"""
    if full:
        return _materialize_full(conn)
    with conn.cursor() as cur:
        cur.execute("SELECT DISTINCT CODE FROM stock_quote")
        codes = [r[0] for r in cur.fetchall()]
        since = (date.today() - timedelta(days=EVENT_LOOKBACK_DAYS)).strftime("%Y%m%d")
        cur.execute("SELECT DISTINCT CODE FROM stock_dividend WHERE EX_DATE >= %s", (since,))
        event_codes = {r[0] for r in cur.fetchall()}
    if event_codes:
        print(f"[period_bar] 本周除权股 {len(event_codes)} 只全周期重算: {sorted(event_codes)[:10]}...", flush=True)

    stats = {}
    now = unix_ts()
    for ptype, (pkey, window_days) in PERIOD_CONF.items():
        cutoff_date = None if full else (date.today() - timedelta(days=window_days)).strftime("%Y%m%d")
        total = 0
        nb = (len(codes) + BATCH_SIZE - 1) // BATCH_SIZE
        for bi, batch in enumerate(batched(codes, BATCH_SIZE), 1):
            t_b = time.time()
            batch_events = [c for c in batch if c in event_codes]
            # 事件股无窗口（全周期重算）；其余按窗口。两组分别聚合
            groups = [(batch_events, None)] if batch_events else []
            rest = [c for c in batch if c not in event_codes]
            if rest:
                groups.append((rest, cutoff_date))
            for group_codes, cutoff in groups:
                if not group_codes:
                    continue
                sql = AGG_SQL.format(
                    pkey=pkey,
                    codes_ph=",".join(["%s"] * len(group_codes)),
                    # 注意：这里的 %s 是留给 pymysql 的占位符，不要用 Python % 预填日期
                    cutoff="AND TRADE_DATE >= %s" if cutoff else "",
                )
                params = list(group_codes) + ([cutoff] if cutoff else [])
                with conn.cursor() as cur:
                    cur.execute(sql, params)
                    rows = cur.fetchall()
                    if rows:
                        cur.executemany(UPSERT_SQL, [
                            (ptype, code, adjust, ps, pe, o, h, l, c, now, now)
                            for code, adjust, ps, pe, o, h, l, c in rows
                        ])
                total += len(rows)
            print(f"[period_bar] type={ptype} 批 {bi}/{nb} +{total} 行 {time.time()-t_b:.1f}s", flush=True)
        conn.commit()
        stats[ptype] = total
        print(f"[period_bar] PERIOD_TYPE={ptype} upsert {total} 行", flush=True)
    return stats


def main() -> int:
    parser = argparse.ArgumentParser(description="stock_period_bar 周/月/季物化聚合")
    parser.add_argument("--full", action="store_true", help="首次全量物化（默认常规周增量）")
    args = parser.parse_args()

    t0 = time.time()
    conn = get_conn()
    try:
        stats = materialize(conn, args.full)
    finally:
        conn.close()
    elapsed = time.time() - t0
    print(f"[period_bar] 完成({'全量' if args.full else '增量'})：{stats}，耗时 {elapsed:.1f}s", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
