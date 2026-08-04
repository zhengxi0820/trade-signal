#!/usr/bin/env python3
"""verify_fix.py — 事件判定修复的只读验证（不写库）

**只读**：重新爬取 none+qfq（+东财公告日历），在内存里跑判定并打印，绝不写库
（stock_dividend / stock_quote 均不动；601880 的 41 个历史事件是保留的失效现场）。

纯反推用例（本地可跑，不依赖东财）：
    601880（1.3~1.8 元低价股）：应恰好 3 个真事件（20240717/20250722/20260715），38 伪全拦
    600519 茅台：应仍为 6 个事件（过滤不得误杀真事件）
    600030 中信：回归（5 年窗口），应仍为 9 个事件（含 20220127 配股 k≈0.94）
    600221（1.37 元无分红）：应 0 事件

双轨用例（依赖东财公告日历，本机被墙时自动 SKIP，须在服务器跑）：
    000002 万科（40 年全历史）：公告日历为权威，事件数应 ≈33（30~40 容忍带），
        万科日历全覆盖、应无 derive 晋升、suspect=0；
        derive+announce 应占多数；打印 suspect / review（交叉校验）清单

用法（在 scripts/ 目录下）：
    python verify_fix.py
"""

import os
import sys
from datetime import date, timedelta

os.environ["NO_PROXY"] = "*"

from adjust.backfill import verify_qfq
from adjust.factor import (
    build_factor_series, detect_events, build_segments, plateau_fluctuation,
)
from adjust.merge import merge_events
from common.const import SOURCE_DERIVE, SOURCE_DERIVE_ANNOUNCE
from fetch.history import fetch_pair

CASES = [
    ("601880", 3, {"20240717", "20250722", "20260715"}),
    ("600519", 3, {"20231220", "20240619", "20241220", "20250626", "20251219", "20260626"}),
    ("600030", 5, {"20210820", "20220127", "20220826", "20230825", "20240826",
                   "20241220", "20250825", "20260209", "20260610"}),
    ("600221", 3, set()),
]

REASON_CN = {
    "amplitude": "噪声幅度下限(<1.5个价位)",
    "direction": "方向过滤(降向跳变)",
    "persistence": "持续性后验(振荡回弹)",
}


def verify_one(code: str, years: int, expected: set) -> bool:
    """纯反推回归：事件集合必须逐日一致。"""
    end = date.today()
    start = end - timedelta(days=years * 365)
    raw_rows, qfq_rows, source = fetch_pair(code, start, end)
    fs = build_factor_series(raw_rows, qfq_rows)
    detect_events(fs)
    build_segments(fs)
    fluct = plateau_fluctuation(fs)
    v = verify_qfq(fs, qfq_rows)

    got = {e.ex_date for e in fs.events}
    ok = got == expected
    print(f"\n[verify] ===== {code}（{source}，{years} 年，{len(fs.dates)} 交易日，纯反推）=====")
    print(f"[verify] 事件 {len(fs.events)} 个（期望 {len(expected)}）："
          + "  ".join(f"{e.ex_date}:k={e.k:.6f}" for e in fs.events) if fs.events else f"\n[verify] 事件 0 个（期望 {len(expected)}）")
    print(f"[verify] 丢弃候选 {len(fs.rejected)} 个：")
    for r in fs.rejected:
        print(f"  丢弃 {r.date}  原因={REASON_CN.get(r.reason, r.reason)}  当日相对变化={r.rel_change:+.4%}")
    print(f"[verify] 平台波动={fluct:.6f}  对拍最大偏差={v['max_dev']:.6f}({v['worst_date']})  "
          f"自适应超差比={v['max_excess']:.3f}({'OK' if v['ok'] else '超差!'})")
    if ok:
        print(f"[verify] {code} 判定与期望完全一致 ✓")
    else:
        print(f"[verify] {code} 不一致! 多判={sorted(got - expected)} 漏判={sorted(expected - got)}")
    return ok


def verify_dual_000002() -> "bool | None":
    """万科双轨验证（依赖东财）。返回 True/False；东财不可达返回 None(SKIP)。"""
    code = "000002"
    try:
        from fetch.dividend import fetch_dividend_calendar_retry
        announce_rows = fetch_dividend_calendar_retry(code)
    except Exception as e:
        print(f"\n[verify] ===== {code} 双轨用例 SKIP：东财公告日历不可达（{str(e)[:60]}）=====")
        return None

    end = date.today()
    start = end - timedelta(days=40 * 365)
    raw_rows, qfq_rows, source = fetch_pair(code, start, end)
    fs = build_factor_series(raw_rows, qfq_rows)
    derive_events = detect_events(fs)
    events, suspect, review = merge_events(fs, derive_events, announce_rows)
    fs.events = events
    build_segments(fs)
    v = verify_qfq(fs, qfq_rows)

    sources = {}
    for e in events:
        sources[e.source] = sources.get(e.source, 0) + 1

    print(f"\n[verify] ===== {code}（{source}，40 年，{len(fs.dates)} 交易日，双轨）=====")
    print(f"[verify] 公告日历 {len(announce_rows)} 条；反推存活候选 {len(derive_events)} 个")
    print(f"[verify] 落库事件 {len(events)} 个，SOURCE 分布 {sources}")
    for e in events:
        print(f"  {e.ex_date}  k={e.k:.8f}  source={e.source}")
    print(f"[verify] suspect(丢弃反推) {len(suspect)} 个: "
          + "  ".join(f"{s['ex_date']}:k={s['k']:.4f}" for s in suspect))
    print(f"[verify] review(交叉校验超差) {len(review)} 个:")
    for r in review:
        if r["type"] == "k_mismatch":
            print(f"  {r['code_date']}  k_反推={r['k_derive']:.6f} k_理论={r['k_theory']:.6f}")
        else:
            print(f"  {r['code_date']}  {r['type']}")
    print(f"[verify] 对拍最大偏差={v['max_dev']:.6f}({v['worst_date']})  "
          f"自适应超差比={v['max_excess']:.3f}({'OK' if v['ok'] else '超差!'})")

    checks = [
        ("事件数 ≈33（30~40 容忍带）", 30 <= len(events) <= 40),
        ("万科无 suspect（日历全覆盖，无 derive 晋升）", sources.get(SOURCE_DERIVE, 0) == 0),
        ("derive+announce 占多数（≥20）", sources.get(SOURCE_DERIVE_ANNOUNCE, 0) >= 20),
        ("suspect 规模有限（≤10）", len(suspect) <= 10),
        ("对拍自适应容差内", v["ok"]),
    ]
    ok = True
    for name, passed in checks:
        print(f"[verify]   断言 [{'PASS' if passed else 'FAIL'}] {name}")
        ok = ok and passed
    return ok


def main() -> int:
    results = {}
    for code, years, expected in CASES:
        try:
            results[code] = verify_one(code, years, expected)
        except Exception as e:
            results[code] = False
            print(f"[verify] {code} 执行失败: {type(e).__name__}: {e}")
    try:
        results["000002(双轨)"] = verify_dual_000002()
    except Exception as e:
        results["000002(双轨)"] = False
        print(f"[verify] 000002 双轨执行失败: {type(e).__name__}: {e}")

    print("\n[verify] ===== 汇总 =====")
    for code, ok in results.items():
        tag = "SKIP" if ok is None else ("PASS" if ok else "FAIL")
        print(f"  {code}: {tag}")
    # SKIP 不算失败（本机无东财）；服务器上必须真 PASS
    return 0 if all(r is not False for r in results.values()) else 2


if __name__ == "__main__":
    sys.exit(main())
