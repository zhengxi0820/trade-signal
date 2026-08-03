"""incremental.py — 每日增量的复权处理（实现，试点阶段不重点验证）

流程（每股每日）：
    爬当日 none + qfq
    → factor_当日 = qfq收盘/raw收盘，与库存最新平台因子比较
    → 无变化：当日 raw/qfq 行直接落库（qfq 行 = raw × 库存最新因子）
    → 有变化（须过四道闸，与 factor.py 历史回填同款口径）：
        闸①相对阈值 0.1% + 闸②金额阈值 0.005 元（与历史回填一致）
        闸③噪声自适应幅度下限：|相对变化| > 1.5 个最小价位 ÷ 股价（低价股舍入
          状态游走可达 ~1 个价位/股价，见 const.py 注释）
        闸④方向过滤：只有升向跳变才可能是除权，降向直接视为噪声
        闸⑤持续性后验：依赖调用方传入的 future_factors（daily 10 天回看窗口内
          该日之后的逐日 factor）；证据不足（后续不足 PERSIST_WINDOW 日）则
          **延期判定**——当日行不落库，下次增量窗口前移后重评
      → 确认事件：k = 库存最新因子 / factor_当日（<1）→ stock_dividend 追加（EX_DATE=当日）
                  → 该股历史 qfq 行整体 ×= k（前复权口径下历史行随新除权下沉）
                  → 当日行落库

库存最新因子的来源：stock_quote 里最新一对 (raw收盘, qfq收盘) 之比，不额外建表。
"""

import statistics

from adjust.backfill import upsert_dividends
from adjust.factor import DividendEvent, qfq_close
from common.const import (
    FACTOR_REL_THRESHOLD,
    IMPLIED_AMOUNT_THRESHOLD,
    PERSIST_WINDOW,
    PERSIST_RATIO,
    PRICE_TICK,
    QUANTUM_NOISE_FACTOR,
)
from common.db import quote_id, unix_ts


def latest_stored_factor(conn, code: str):
    """库存最新平台因子 = 最新交易日的 qfq收盘/raw收盘。无数据返回 None。"""
    sql = """
        SELECT q.CLOSE / r.CLOSE
        FROM stock_quote r JOIN stock_quote q
          ON r.CODE=q.CODE AND r.TRADE_DATE=q.TRADE_DATE
        WHERE r.CODE=%s AND r.ADJUST='0' AND q.ADJUST='1'
        ORDER BY r.TRADE_DATE DESC LIMIT 1
    """
    with conn.cursor() as cur:
        cur.execute(sql, (code,))
        row = cur.fetchone()
    return float(row[0]) if row and row[0] is not None else None


def rescale_history_qfq(conn, code: str, k: float) -> int:
    """历史 qfq 行整体 ×= k（新除权导致前复权基准下沉）。返回影响行数。"""
    now = unix_ts()
    sql = """
        UPDATE stock_quote
        SET OPEN=ROUND(OPEN*%s,4), HIGH=ROUND(HIGH*%s,4),
            LOW=ROUND(LOW*%s,4), CLOSE=ROUND(CLOSE*%s,4), UPDATED_AT=%s
        WHERE CODE=%s AND ADJUST='1'
    """
    with conn.cursor() as cur:
        n = cur.execute(sql, (k, k, k, k, now, code))
    conn.commit()
    return n


def apply_daily(conn, code: str, raw_today: dict, qfq_today: dict, upsert_quote_fn,
                future_factors: list = None) -> dict:
    """单日增量主流程。raw_today/qfq_today 为单行 dict（同 fetch_hist 行结构）。

    future_factors：该日之后的逐日 factor（daily 回看窗口内），用于持续性后验；
    不传则只做方向过滤（闸⑤关闭，不推荐）。
    返回 {'event': bool, 'k': float|None, 'deferred': bool}；
    deferred=True 表示候选事件证据不足延期判定，当日行未落库（下次重评）。
    """
    factor_today = qfq_today["close"] / raw_today["close"]
    factor_prev = latest_stored_factor(conn, code)

    event = None
    if factor_prev is not None:
        rel = factor_today / factor_prev - 1.0
        # 闸①相对阈值 + 闸②金额阈值 + 闸③噪声自适应幅度下限（与历史回填同口径）
        rel_floor = QUANTUM_NOISE_FACTOR * PRICE_TICK / raw_today["close"]
        if (abs(rel) > FACTOR_REL_THRESHOLD
                and abs(rel) * raw_today["close"] > IMPLIED_AMOUNT_THRESHOLD
                and abs(rel) > rel_floor):
            # 闸④方向过滤：只有升向（factor 向 1 跳升）才可能是除权
            if rel > 0:
                # 闸⑤持续性后验（有 future_factors 时启用）
                if future_factors is not None:
                    if len(future_factors) < PERSIST_WINDOW:
                        # 证据不足：延期判定，当日行不落库，下次窗口前移后重评
                        return {"event": False, "k": None, "deferred": True}
                    threshold = factor_prev + PERSIST_RATIO * (factor_today - factor_prev)
                    if statistics.median(future_factors[:PERSIST_WINDOW]) < threshold:
                        pass  # 振荡回弹 → 非事件，按无变化落库
                    else:
                        event = _apply_event(conn, code, raw_today, factor_prev, factor_today, rel)
                else:
                    event = _apply_event(conn, code, raw_today, factor_prev, factor_today, rel)
            # rel <= 0：降向候选，振荡噪声下行半周，按无变化处理
        level = factor_today if event else factor_prev
    else:
        # 库里没有历史（未做历史回填），无法判断事件，直接用当日 factor 当平台
        level = factor_today

    qfq_row = {
        "trade_date": raw_today["trade_date"],
        "open": qfq_close(raw_today["open"], level),
        "high": qfq_close(raw_today["high"], level),
        "low": qfq_close(raw_today["low"], level),
        "close": qfq_close(raw_today["close"], level),
        "volume": raw_today["volume"],
    }
    upsert_quote_fn(conn, code, [raw_today], "0")
    upsert_quote_fn(conn, code, [qfq_row], "1")
    return {"event": event is not None, "k": event.k if event else None, "deferred": False}


def _apply_event(conn, code: str, raw_today: dict, factor_prev: float, factor_today: float, rel: float):
    """确认除权事件：写 stock_dividend（k=旧/新<1）并下沉历史 qfq 行。"""
    k = factor_prev / factor_today
    event = DividendEvent(ex_date=raw_today["trade_date"], k=k, rel_change=rel)
    upsert_dividends(conn, code, [event])
    rescale_history_qfq(conn, code, k)
    return event
