"""merge.py — 双轨合并：公告日历（权威事件日历）× 因子反推（k 值测量 + 校验 + 兜底）

规则（按批准设计，2026-08-04 增补晋升规则）：
- 公告除权日 ±ANNOUNCE_MATCH_DAYS 个交易日内有反推事件 → 用反推 k，SOURCE='derive+announce'；
  事件日期取反推跳变日（保证落在 fs.dates 内，分段可用）
- 公告日无反推事件 → 理论公式算 k，SOURCE='announce'：
    k = (P前收 − 派/10 + 配股数/10 × 配股价) ÷ (P前收 × (1 + (送+转)/10 + 配股数/10))
  （纯送转派时退化为 (1−D/P)/(1+s)；纯配股时为标准配股除权公式）
- 反推事件不在公告日历 → **晋升落库，SOURCE='derive'**，suspect 清单仍输出仅作提示。
  依据：修后五道闸实证零误报（万科 1005→32 存活全部对上公告或股改）；东财分红日历
  对股改对价/缩股/重组是系统性缺口（000027 股改对价 20060426 实测坐实），
  丢弃真事件反而把平台段切碎、污染自算 qfq（对拍超差报警）
- 交叉校验：|k_理论/k_反推 − 1| > K_CROSSCHECK_TOL → 人工复核清单（仍用反推 k 落库）

全部为纯函数，不依赖数据库。
"""

from adjust.factor import DividendEvent
from common.const import (
    ANNOUNCE_MATCH_DAYS,
    K_CROSSCHECK_TOL,
    SOURCE_ANNOUNCE,
    SOURCE_DERIVE,
    SOURCE_DERIVE_ANNOUNCE,
)


def theoretical_k(ann: dict, p_pre: float) -> float:
    """理论综合复权因子。ann 结构见 fetch.dividend；p_pre = 除权日前一交易日 raw 收盘。"""
    if p_pre <= 0:
        return None
    d = ann["pai"] / 10.0
    s = (ann["song"] + ann["zhuan"]) / 10.0
    n = ann["pei"] / 10.0
    pe = ann["pei_price"] or 0.0
    return (p_pre - d + n * pe) / (p_pre * (1.0 + s + n))


def merge_events(fs, derive_events: list, announce_rows: list):
    """双轨合并。返回 (events, suspect, review)：
    events  — 最终落库事件（DividendEvent，source 已标）
    suspect — 晋升落库的反推事件（不在公告日历，SOURCE='derive'），仅作提示
    review  — 人工复核清单 dict（k 对不上 / 公告缺前收等）
    """
    idx_of = {d: i for i, d in enumerate(fs.dates)}
    matched_derive = set()
    events, suspect, review = [], [], []

    for ann in announce_rows:
        ex = ann["ex_date"]
        # 公告除权日定位：取序列中 ≥ex 的首个交易日（ex 本身多为交易日；停牌时顺延）
        ex_idx = next((idx_of[d] for d in fs.dates if d >= ex), None)
        if ex_idx is None:
            continue  # 公告在数据窗口之后，忽略

        # ±ANNOUNCE_MATCH_DAYS 个交易日内找最近的反推事件
        best, best_dist = None, ANNOUNCE_MATCH_DAYS + 1
        for j, de in enumerate(derive_events):
            if j in matched_derive or de.ex_date not in idx_of:
                continue
            dist = abs(idx_of[de.ex_date] - ex_idx)
            if dist <= ANNOUNCE_MATCH_DAYS and dist < best_dist:
                best, best_dist = j, dist

        # 理论 k（匹不匹配都算：匹配用于交叉校验，未匹配用于落库）
        p_pre = fs.closes[ex_idx - 1] if ex_idx >= 1 else None
        k_theory = theoretical_k(ann, p_pre) if p_pre else None

        if best is not None:
            matched_derive.add(best)
            de = derive_events[best]
            if k_theory is not None and abs(k_theory / de.k - 1.0) > K_CROSSCHECK_TOL:
                review.append({"code_date": de.ex_date, "type": "k_mismatch",
                               "k_derive": de.k, "k_theory": k_theory, "announce": ann})
            events.append(DividendEvent(ex_date=de.ex_date, k=de.k,
                                        rel_change=de.rel_change,
                                        source=SOURCE_DERIVE_ANNOUNCE))
        else:
            if k_theory is None:
                review.append({"code_date": ex, "type": "announce_no_ppre", "announce": ann})
                continue
            # 理论 k 落库，事件日取 ≥ex 的首个交易日（分段必须落在 fs.dates 内）
            events.append(DividendEvent(ex_date=fs.dates[ex_idx], k=k_theory,
                                        rel_change=0.0, source=SOURCE_ANNOUNCE))

    # 未匹配的反推事件 → 晋升落库（SOURCE='derive'），suspect 清单仅作提示输出。
    # 东财日历对股改对价/缩股/重组是系统性缺口，五道闸已保证这些候选是真事件
    for j, de in enumerate(derive_events):
        if j not in matched_derive:
            de.source = SOURCE_DERIVE
            events.append(de)
            suspect.append({"ex_date": de.ex_date, "k": de.k, "rel_change": de.rel_change})

    events.sort(key=lambda e: e.ex_date)
    return events, suspect, review
