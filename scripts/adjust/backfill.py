"""backfill.py — 历史回填的复权计算段：factor 阶梯 → 双轨合并 → 事件落库 → 自算 qfq → 对拍

被 fetch.history 调用；本模块只负责"算"与"写 stock_dividend / 对拍"，
行情行落库通过调用方注入的 upsert 函数完成（避免环形依赖）。

双轨口径（2026-08-04 批准设计）：announce_rows 为东财公告除权日历（fetch.dividend）；
传 None 表示公告不可用 → 回退纯因子反推（五道闸），SOURCE='derive'。
"""

from adjust.factor import (
    FactorSeries, build_factor_series, detect_events, build_segments,
    plateau_fluctuation, qfq_close,
)
from adjust.merge import merge_events
from common.const import (
    ADAPTIVE_VERIFY_TICKS,
    MAX_EVENTS_PER_YEAR,
    PRICE_TICK,
    SOURCE_DERIVE,
    VERIFY_TOLERANCE,
)
from common.db import dividend_id, unix_ts


def upsert_dividends(conn, code: str, events: list) -> int:
    """除权事件写 stock_dividend（只追加语义；重跑同 EX_DATE 覆盖 FACTOR/SOURCE）。"""
    if not events:
        return 0
    now = unix_ts()
    sql = """
        INSERT INTO stock_dividend (ID, CODE, EX_DATE, FACTOR, SOURCE, CREATED_AT, UPDATED_AT)
        VALUES (%s, %s, %s, %s, %s, %s, %s)
        ON DUPLICATE KEY UPDATE FACTOR=VALUES(FACTOR), SOURCE=VALUES(SOURCE), UPDATED_AT=VALUES(UPDATED_AT)
    """
    params = [
        (dividend_id(code, e.ex_date), code, e.ex_date, f"{e.k:.12f}", e.source, now, now)
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


def _day_tolerance(raw_close: float, qfq_close_crawled: float) -> float:
    """对拍逐日容差：max(固定 0.1%, ADAPTIVE_VERIFY_TICKS 个价位 ÷ min(raw, qfq))。

    早期 qfq 低价段的"偏差"本质是爬取 qfq 自身的两位小数舍入抖动
    （自算值是段中位数去噪后的平滑序列，反而更接近真值），固定容差在该段无意义。
    """
    return max(VERIFY_TOLERANCE,
               ADAPTIVE_VERIFY_TICKS * PRICE_TICK / min(raw_close, qfq_close_crawled))


def verify_qfq(fs: FactorSeries, qfq_rows_crawled: list) -> dict:
    """对拍：自算 qfq 收盘价 vs 爬取 qfq 收盘价。

    返回 {max_dev, worst_date, max_excess, excess_date, ok}：
    max_dev 为全序列最大相对偏差（展示用）；ok 按逐日自适应容差判定，
    max_excess = max(dev/容差)，>1 才算超差（早期低价段容差放宽，近年段仍 0.1%）。
    """
    raw_close = dict(zip(fs.dates, fs.closes))
    computed = {}
    for s, e, level in fs.segments:
        for i in range(s, e):
            computed[fs.dates[i]] = qfq_close(fs.closes[i], level)
    max_dev, worst_date = 0.0, None
    max_excess, excess_date = 0.0, None
    for r in qfq_rows_crawled:
        d = r["trade_date"]
        if d not in computed:
            continue
        dev = abs(computed[d] / r["close"] - 1.0)
        if dev > max_dev:
            max_dev, worst_date = dev, d
        excess = dev / _day_tolerance(raw_close[d], r["close"])
        if excess > max_excess:
            max_excess, excess_date = excess, d
    return {"max_dev": max_dev, "worst_date": worst_date,
            "max_excess": max_excess, "excess_date": excess_date,
            "ok": max_excess <= 1.0}


def backfill_stock(conn, code: str, raw_rows: list, qfq_rows: list, upsert_quote_fn,
                   announce_rows: list = None) -> dict:
    """因子反推 + 双轨合并主流程（raw 行已由调用方落库）。返回试点报告。

    announce_rows：东财公告除权日历；None → 回退纯反推（SOURCE='derive'）。
    保险：合并后事件数 > 市龄(年)×MAX_EVENTS_PER_YEAR → 中止（不写事件/qfq 行），
    报告 aborted=True 由调度层标记人工复核。
    """
    fs = build_factor_series(raw_rows, qfq_rows)
    if len(fs.dates) < 2:
        raise RuntimeError(f"{code} none/qfq 对齐后不足 2 个交易日，无法反推")

    derive_events = detect_events(fs)

    if announce_rows is None:
        # 兜底：公告不可用，纯反推（修复后五道闸）
        for e in derive_events:
            e.source = SOURCE_DERIVE
        events, suspect, review = derive_events, [], []
        mode = "derive-only"
    else:
        events, suspect, review = merge_events(fs, derive_events, announce_rows)
        mode = "dual-track"

    # 全量回填保险：事件数超 市龄×3 → 中止（000002 事故是每年 50+ 个，正常 ≤2~3）
    listing_years = max(1.0, (int(fs.dates[-1][:4]) - int(fs.dates[0][:4]) + 1))
    aborted = len(events) > listing_years * MAX_EVENTS_PER_YEAR

    if not aborted:
        fs.events = events  # build_segments 依赖 fs.events 切段
        build_segments(fs)
        upsert_dividends(conn, code, events)
        qfq_rows_self = build_qfq_rows(raw_rows, fs)
        upsert_quote_fn(conn, code, qfq_rows_self, "1")
        fluct = plateau_fluctuation(fs)
        verify = verify_qfq(fs, qfq_rows)
    else:
        build_segments(fs)  # 仅供报告看段数
        qfq_rows_self = []
        fluct = plateau_fluctuation(fs)
        verify = {"max_dev": None, "worst_date": None, "max_excess": None,
                  "excess_date": None, "ok": False}

    sources = {}
    for e in events:
        sources[e.source] = sources.get(e.source, 0) + 1

    return {
        "code": code,
        "mode": mode,
        "raw_rows": len(raw_rows),
        "qfq_rows": len(qfq_rows_self),
        "aligned_days": len(fs.dates),
        "segments": len(fs.segments),
        "plateau_fluct": fluct,
        "events": events,
        "derive_candidates": len(derive_events),
        "sources": sources,
        "suspect": suspect,
        "review": review,
        "aborted": aborted,
        "max_dev": verify["max_dev"],
        "worst_date": verify["worst_date"],
        "max_excess": verify["max_excess"],
        "excess_date": verify["excess_date"],
        "verify_ok": verify["ok"],
    }
