#!/usr/bin/env python3
"""period_bar.py 纯函数单元测试（无 pytest 依赖，直接运行）：
    python scripts/tests/test_period_bar.py
覆盖：周期桶 key、增量窗口起点对齐、最大已完结桶（含日历截断兜底）。
"""

import sys
from datetime import date, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from aggregate.period_bar import _period_key, _align_window_start, _max_complete_key


def check(name, got, want):
    assert got == want, f"{name}: got {got!r}, want {want!r}"
    print(f"  ok {name}: {got}")


def test_period_key():
    print("[period_key]")
    check("weekly 20260814 -> ISO 202633", _period_key("20260814", "1"), "202633")
    check("weekly 20260807 -> ISO 202632", _period_key("20260807", "1"), "202632")
    check("month 20260814 -> 202608", _period_key("20260814", "2"), "202608")
    check("quarter 20260814 -> 2026Q3", _period_key("20260814", "3"), "2026Q3")
    check("year-boundary week 20261231 -> 202653", _period_key("20261231", "1"), "202653")
    check("quarter-start 20261001 -> 2026Q4", _period_key("20261001", "3"), "2026Q4")


def test_align_window_start():
    print("[align_window_start]")
    cases = [
        ("20260724", "1", "20260720"),  # 周四 -> 本周周一
        ("20260725", "1", "20260720"),  # 周五 -> 本周周一
        ("20260810", "1", "20260810"),  # 周一 -> 本身
        ("20260724", "2", "20260701"),  # 月中 -> 本月 1 号
        ("20260701", "2", "20260701"),  # 1 号 -> 本身
        ("20260815", "3", "20260701"),  # 8 月中 -> 本季首日 7/1
        ("20261010", "3", "20261001"),  # 10 月 -> Q4 首日
        ("20260101", "3", "20260101"),
    ]
    for c in cases:
        check(f"align({c[0]}, {c[1]})", _align_window_start(c[0], c[1]), c[2])


def test_max_complete_key():
    print("[max_complete_key]")
    # 模拟 work_day：周一至周五、含未来（周六 08-15 看 G=08-14 的场景）
    d0 = date(2026, 8, 15)
    cal = []
    d = d0 - timedelta(days=3000)
    end = d0 + timedelta(days=140)
    while d <= end:
        if d.weekday() < 5:
            cal.append(d.strftime("%Y%m%d"))
        d += timedelta(days=1)
    check("周线最大已完结桶（本周五数据到位）", _max_complete_key("20260814", "1", cal), "202633")
    check("月线最大已完结桶（8 月未完）", _max_complete_key("20260814", "2", cal), "202607")
    check("季线最大已完结桶（Q3 未完）", _max_complete_key("20260814", "3", cal), "2026Q2")
    # 截断兜底：日历无未来日期 -> 当前周期按未完结核对
    truncated = [x for x in cal if x <= "20260814"]
    check("截断日历兜底（周线）", _max_complete_key("20260814", "1", truncated), "202632")


def main():
    test_period_key()
    test_align_window_start()
    test_max_complete_key()
    print("ALL TESTS PASSED")


if __name__ == "__main__":
    main()
