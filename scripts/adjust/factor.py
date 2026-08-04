"""factor.py — 复权因子反推：阶梯识别、事件判定、事件因子 k 拟合

口径（因子反推法）：
- factor(t) = qfq(t) / raw(t)，用收盘价计算；理论上是阶梯函数，只在除权日跳变
- 平台段内的日际波动来自行情源两位小数舍入，属噪声；平台段因子取段内中位数
- 事件判定五道闸（前四道记录丢弃现场）：
  ① 相对阈值：factor 日际相对变化 > 0.1%（原始双阈值之一，静默）
  ② 金额阈值：相对变化 × 当日股价 > 0.005 元（原始双阈值之二，静默）
  ③ 噪声自适应幅度下限：|相对变化| > 1.5 × 0.01 元 ÷ min(raw, qfq)——factor 由两个各自
     0.01 元舍入的价格相除得到，常数平台上的舍入状态游走可达 ~1 个价位/较小价；
     分母取 min(raw, qfq)：早期 qfq 被压缩至分角级时噪声由 qfq 侧主导
     （601880 实测伪事件 ≤1.05 个价位、真事件 ≥2.2 个价位；000002 早期段事故后修正）。
  ④ 方向过滤：只有升向跳变（factor 向 1 靠）才可能是除权（记录 'direction'）
  ⑤ 持续性后验：跳变后 5 日 factor 中位数须保持在新平台半程以上（记录 'persistence'）
  （③④⑤ 是 601880 实测后新增：低价股 qfq/raw 双序列 0.01 元异步舍入可产生
  ±0.55% 的 factor 振荡与持续多日的舍入状态迁移，①② 会被同时击穿）
- 事件因子 k = 跳变点前 ~10 日 factor 中位数 / 跳变点后 ~10 日 factor 中位数
  （除权后历史 qfq 整体下沉，factor 向 1 方向跳升，故 k = 前/后 < 1；
  每日增量流程里"历史 qfq 行 ×= k"依赖这个方向约定，勿改）

全部为纯函数，不依赖数据库，方便单测对拍。
"""

import statistics
from dataclasses import dataclass, field

from common.const import (
    FACTOR_REL_THRESHOLD,
    IMPLIED_AMOUNT_THRESHOLD,
    K_WINDOW,
    PERSIST_WINDOW,
    PERSIST_RATIO,
    PRICE_TICK,
    QUANTUM_NOISE_FACTOR,
    SOURCE_DERIVE,
)


@dataclass
class DividendEvent:
    """一次识别出的除权除息事件。"""
    ex_date: str      # 跳变发生的交易日（yyyymmdd），即除权日
    k: float          # 综合复权因子 = 跳变后平台 / 跳变前平台
    rel_change: float # 当日 factor 相对变化（带符号，除权为负）
    source: str = SOURCE_DERIVE  # derive / announce / derive+announce（双轨合并时改写）


@dataclass
class RejectedCandidate:
    """被过滤掉的跳变候选（保留现场，便于审计与回归验证）。"""
    date: str         # 候选跳变日（yyyymmdd）
    reason: str       # 'direction'=方向过滤 / 'persistence'=持续性后验
    rel_change: float # 当日 factor 相对变化（带符号）


@dataclass
class FactorSeries:
    """逐日 factor 序列 + 识别结果。"""
    dates: list = field(default_factory=list)     # yyyymmdd 升序
    factors: list = field(default_factory=list)   # 逐日 factor = qfq/raw
    closes: list = field(default_factory=list)    # 逐日 raw 收盘价（用于金额阈值）
    events: list = field(default_factory=list)    # list[DividendEvent]
    rejected: list = field(default_factory=list)  # list[RejectedCandidate]
    segments: list = field(default_factory=list)  # list[(start_idx, end_idx, level)] 半开区间 [s, e)


def build_factor_series(raw_rows: list, qfq_rows: list) -> FactorSeries:
    """对齐 none/qfq 两口径（按 trade_date 内连接），算逐日 factor。

    raw_rows/qfq_rows 元素需含 trade_date(yyyymmdd 字符串) 与 close。
    两口径交易日不完全一致时（如除权日源站延迟），只保留交集，丢弃的日期在对拍阶段暴露。
    """
    qfq_close = {r["trade_date"]: r["close"] for r in qfq_rows}
    fs = FactorSeries()
    for r in sorted(raw_rows, key=lambda x: x["trade_date"]):
        d = r["trade_date"]
        qc = qfq_close.get(d)
        if qc is None or r["close"] <= 0 or qc <= 0:
            continue
        fs.dates.append(d)
        fs.closes.append(r["close"])
        fs.factors.append(qc / r["close"])
    return fs


def detect_events(fs: FactorSeries) -> list:
    """五道闸识别跳变点，拟合 k；事件写回 fs.events，被弃候选写回 fs.rejected。"""
    events, rejected = [], []
    n = len(fs.dates)
    for i in range(1, n):
        prev, cur = fs.factors[i - 1], fs.factors[i]
        rel = cur / prev - 1.0
        # 闸①相对阈值 闸②金额阈值（用当日 raw 股价折算成元）——原始双阈值，静默
        if abs(rel) <= FACTOR_REL_THRESHOLD:
            continue
        if abs(rel) * fs.closes[i] <= IMPLIED_AMOUNT_THRESHOLD:
            continue
        # 闸③噪声自适应幅度下限：factor 的舍入状态游走最大 ~1 个价位/较小价。
        # 分母取 min(raw, qfq)——等比前复权下早期 qfq 被压缩至分角级，
        # 噪声由 qfq 侧主导，按 raw 价计算的下限在早期形同虚设（000002 事故根因）
        qfq_close_i = fs.factors[i] * fs.closes[i]
        if abs(rel) <= QUANTUM_NOISE_FACTOR * PRICE_TICK / min(fs.closes[i], qfq_close_i):
            rejected.append(RejectedCandidate(fs.dates[i], "amplitude", rel))
            continue
        # 闸④方向过滤：等比前复权下除权日 factor 只会向 1 跳升（k=前/后<1），
        # 降向候选（k≥1）物理上不可能是除权，是舍入振荡的下行半周
        if rel <= 0:
            rejected.append(RejectedCandidate(fs.dates[i], "direction", rel))
            continue
        # 闸⑤持续性后验：真事件稳定在新平台，伪事件数日内振荡回原平台
        if not _persistence_ok(fs.factors, i):
            rejected.append(RejectedCandidate(fs.dates[i], "persistence", rel))
            continue
        k = _fit_k(fs.factors, i)
        events.append(DividendEvent(ex_date=fs.dates[i], k=k, rel_change=rel))
    fs.events = events
    fs.rejected = rejected
    return events


def _persistence_ok(factors: list, i: int) -> bool:
    """持续性后验：跳变点之后 PERSIST_WINDOW 日 factor 中位数 ≥ 旧平台 + PERSIST_RATIO×(新平台−旧平台)。

    新旧平台取自 k 拟合窗口（前后各 K_WINDOW 日中位数）。序列末尾后续不足
    PERSIST_WINDOW 日时取可用部分；完全无后续数据无法证伪，放行。
    """
    n = len(factors)
    before = factors[max(0, i - K_WINDOW):i]
    after = factors[i:min(n, i + K_WINDOW)]
    nxt = factors[i:min(n, i + PERSIST_WINDOW)]
    if not nxt:
        return True
    old_level = statistics.median(before)
    new_level = statistics.median(after)
    threshold = old_level + PERSIST_RATIO * (new_level - old_level)
    return statistics.median(nxt) >= threshold


def _fit_k(factors: list, i: int) -> float:
    """k = 跳变点前窗口中位数 / 后窗口中位数（除权 k<1）。边界处窗口不足时取可用部分（至少 3 个点，否则退化为当日比值）。"""
    n = len(factors)
    before = factors[max(0, i - K_WINDOW):i]
    after = factors[i:min(n, i + K_WINDOW)]
    if len(before) < 3 or len(after) < 3:
        return factors[i - 1] / factors[i]
    return statistics.median(before) / statistics.median(after)


def build_segments(fs: FactorSeries) -> list:
    """按事件把序列切成平台段，每段因子取段内逐日 factor 中位数（抗舍入噪声）。

    返回 [(start_idx, end_idx, level), ...]，半开区间；写回 fs.segments。
    落库的 qfq = raw × level，因此平台段内与爬取 qfq 的残差即舍入噪声，应对拍 <0.1%。
    """
    cuts = [fs.dates.index(e.ex_date) for e in fs.events]
    bounds = [0] + cuts + [len(fs.dates)]
    segments = []
    for s, e in zip(bounds, bounds[1:]):
        if e <= s:
            continue
        level = statistics.median(fs.factors[s:e])
        segments.append((s, e, level))
    fs.segments = segments
    return segments


def plateau_fluctuation(fs: FactorSeries) -> float:
    """平台段稳定性：段内逐日 factor 相对段中位数的最大相对偏差。"""
    worst = 0.0
    for s, e, level in (fs.segments or build_segments(fs)):
        for f in fs.factors[s:e]:
            worst = max(worst, abs(f / level - 1.0))
    return worst


def qfq_close(raw_close: float, level: float) -> float:
    """自算前复权价 = 原始价 × 平台因子，保留 4 位小数（DECIMAL(12,4)）。"""
    return round(raw_close * level, 4)
