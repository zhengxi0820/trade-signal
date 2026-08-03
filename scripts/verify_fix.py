#!/usr/bin/env python3
"""verify_fix.py — 伪事件过滤修复（方向过滤 + 持续性后验）的只读验证

**只读**：重新爬取 none+qfq，在内存里跑修复后的判定并打印，绝不写库
（stock_dividend / stock_quote 均不动；601880 的 41 个历史事件是保留的失效现场）。

用例与期望：
    601880（1.3~1.8 元低价股）：修复前 41 事件（38 伪），修复后应恰好 3 个真事件
        （20240717 / 20250722 / 20260715），逐候选打印丢弃原因
    600519 茅台：回归，应仍为 6 个事件（持续性后验不得误杀真事件）
    600030 中信：回归（5 年窗口），应仍为 9 个事件（含 20220127 配股 k≈0.94）
    600221（1.37 元无分红）：应 0 事件

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
    end = date.today()
    start = end - timedelta(days=years * 365)
    raw_rows, qfq_rows, source = fetch_pair(code, start, end)
    fs = build_factor_series(raw_rows, qfq_rows)
    detect_events(fs)
    build_segments(fs)
    fluct = plateau_fluctuation(fs)
    max_dev, worst_date = verify_qfq(fs, qfq_rows)

    got = {e.ex_date for e in fs.events}
    ok = got == expected
    print(f"\n[verify] ===== {code}（{source}，{years} 年，{len(fs.dates)} 交易日）=====")
    print(f"[verify] 事件 {len(fs.events)} 个（期望 {len(expected)}）："
          + "  ".join(f"{e.ex_date}:k={e.k:.6f}" for e in fs.events) if fs.events else f"\n[verify] 事件 0 个（期望 {len(expected)}）")
    print(f"[verify] 丢弃候选 {len(fs.rejected)} 个：")
    for r in fs.rejected:
        print(f"  丢弃 {r.date}  原因={REASON_CN.get(r.reason, r.reason)}  当日相对变化={r.rel_change:+.4%}")
    print(f"[verify] 平台波动={fluct:.6f}  对拍最大偏差={max_dev:.6f}({worst_date})")
    if ok:
        print(f"[verify] {code} 判定与期望完全一致 ✓")
    else:
        print(f"[verify] {code} 不一致! 多判={sorted(got - expected)} 漏判={sorted(expected - got)}")
    return ok


def main() -> int:
    results = {}
    for code, years, expected in CASES:
        try:
            results[code] = verify_one(code, years, expected)
        except Exception as e:
            results[code] = False
            print(f"[verify] {code} 执行失败: {type(e).__name__}: {e}")
    print("\n[verify] ===== 汇总 =====")
    for code, ok in results.items():
        print(f"  {code}: {'PASS' if ok else 'FAIL'}")
    return 0 if all(results.values()) else 2


if __name__ == "__main__":
    sys.exit(main())
