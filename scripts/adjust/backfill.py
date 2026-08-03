"""backfill.py — 历史回填的复权计算段：factor 阶梯 → 事件落库 → 自算 qfq → 对拍

被 fetch.history 调用；本模块只负责"算"与"写 stock_dividend / 对拍"，
行情行落库通过调用方注入的 upsert 函数完成（避免环形依赖）。
"""

from adjust.factor import (
    FactorSeries, build_factor_series, detect_events, build_segments,
    plateau_fluctuation, qfq_close,
)
from common.const import SOURCE_DERIVE, VERIFY_TOLERANCE
from common.db import dividend_id, unix_ts


def upsert_dividends(conn, code: str, events: list) -> int:
    """除权事件写 stock_dividend（只追加语义；重跑同 EX_DATE 覆盖 FACTOR）。"""
    if not events:
        return 0
    now = unix_ts()
    sql = """
        INSERT INTO stock_dividend (ID, CODE, EX_DATE, FACTOR, SOURCE, CREATED_AT, UPDATED_AT)
        VALUES (%s, %s, %s, %s, %s, %s, %s)
        ON DUPLICATE KEY UPDATE FACTOR=VALUES(FACTOR), SOURCE=VALUES(SOURCE), UPDATED_AT=VALUES(UPDATED_AT)
    """
    params = [
        (dividend_id(code, e.ex_date), code, e.ex_date, f"{e.k:.12f}", SOURCE_DERIVE, now, now)
        for e in events
    ]
    with conn.cursor() as cur:
        cur.executemany(sql, params)
    conn.commit()
    return len(params)


def build_qfq_rows(raw_rows: list, fs: FactorSeries) -> list:
    """自算前复权行：OHLC 同乘平台因子（K 线整体缩放），成交量保持原始口径。"""
    level_by_date = {}
    for s, e, level in fs.segments:
        for i in range(s, e):
            level_by_date[fs.dates[i]] = level
    rows = []
    for r in raw_rows:
        level = level_by_date.get(r["trade_date"])
        if level is None:
            continue  # 该日无 qfq 对齐数据（已在 factor 序列阶段丢弃）
        rows.append({
            "trade_date": r["trade_date"],
            "open": qfq_close(r["open"], level),
            "high": qfq_close(r["high"], level),
            "low": qfq_close(r["low"], level),
            "close": qfq_close(r["close"], level),
            "volume": r["volume"],
        })
    return rows


def verify_qfq(fs: FactorSeries, qfq_rows_crawled: list) -> tuple:
    """对拍：自算 qfq 收盘价 vs 爬取 qfq 收盘价，全序列最大相对偏差。

    返回 (最大相对偏差, 最差日期)。自算值在 build_qfq_rows 里已 round 到 4 位，
    这里按同口径重算一遍收盘价比较（round 本身贡献的误差远小于 0.1% 容差）。
    """
    computed = {}
    for s, e, level in fs.segments:
        for i in range(s, e):
            computed[fs.dates[i]] = qfq_close(fs.closes[i], level)
    max_dev, worst_date = 0.0, None
    for r in qfq_rows_crawled:
        d = r["trade_date"]
        if d not in computed:
            continue
        dev = abs(computed[d] / r["close"] - 1.0)
        if dev > max_dev:
            max_dev, worst_date = dev, d
    return max_dev, worst_date


def backfill_stock(conn, code: str, raw_rows: list, qfq_rows: list, upsert_quote_fn) -> dict:
    """因子反推主流程（raw 行已由调用方落库）。返回试点报告。"""
    fs = build_factor_series(raw_rows, qfq_rows)
    if len(fs.dates) < 2:
        raise RuntimeError(f"{code} none/qfq 对齐后不足 2 个交易日，无法反推")

    events = detect_events(fs)
    build_segments(fs)
    upsert_dividends(conn, code, events)

    qfq_rows_self = build_qfq_rows(raw_rows, fs)
    upsert_quote_fn(conn, code, qfq_rows_self, "1")

    fluct = plateau_fluctuation(fs)
    max_dev, worst_date = verify_qfq(fs, qfq_rows)

    return {
        "code": code,
        "raw_rows": len(raw_rows),
        "qfq_rows": len(qfq_rows_self),
        "aligned_days": len(fs.dates),
        "segments": len(fs.segments),
        "plateau_fluct": fluct,
        "events": events,
        "max_dev": max_dev,
        "worst_date": worst_date,
        "verify_ok": max_dev <= VERIFY_TOLERANCE,
    }
