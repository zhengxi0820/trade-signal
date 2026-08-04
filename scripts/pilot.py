#!/usr/bin/env python3
"""pilot.py — M3 试点编排入口：对试点股票清单逐只跑历史回填并汇总报告

试点股票（覆盖各板块与典型除权形态）：
    600519 贵州茅台   年度现金分红，小跳变（k≈0.98~0.99）
    600030 中信证券   2022 年 1 月配股
    688981 中芯国际   科创板
    300750 宁德时代   创业板，曾有送转
    920185 贝特瑞     北交所（原 835185，北交所代码已整体迁移 920 段）
    600221 海航控股   低价股（验证双阈值不误判）

每股打印：factor 平台段波动、事件清单（EX_DATE+k）、对拍最大相对偏差。
单只失败不阻断后续，最后统一汇总失败清单。

用法（在 scripts/ 目录下）：
    python pilot.py [--years 3] [--codes 600519 600030 ...]
"""

import argparse
import sys

from fetch.history import run_history

PILOT_CODES = ["600519", "600030", "688981", "300750", "920185", "600221"]


def main() -> int:
    parser = argparse.ArgumentParser(description="M3 试点：批量历史回填 + 对拍报告")
    parser.add_argument("--years", type=int, default=3, help="回溯年数，默认 3")
    parser.add_argument("--codes", nargs="*", default=PILOT_CODES, help="试点股票清单")
    args = parser.parse_args()

    reports, failed = [], []
    for i, code in enumerate(args.codes, 1):
        print(f"\n[pilot] ===== {i}/{len(args.codes)} {code} =====")
        try:
            r = run_history(code, args.years)
            reports.append(r)
            dev = f"{r['max_dev']:.6f}" if r["max_dev"] is not None else "N/A"
            print(f"[pilot] {code}({r['mode']}): raw={r['raw_rows']} qfq={r['qfq_rows']} 对齐={r['aligned_days']} "
                  f"平台段={r['segments']} 平台波动={r['plateau_fluct']:.6f} "
                  f"对拍最大偏差={dev}({r['worst_date']}) {'OK' if r['verify_ok'] else '超差/中止!'}")
            for e in r["events"]:
                print(f"  事件 {e.ex_date}  k={e.k:.8f}  source={e.source}  当日相对变化={e.rel_change:+.4%}")
            if r["suspect"]:
                print(f"  suspect {len(r['suspect'])} 个: {[s['ex_date'] for s in r['suspect']][:10]}")
            if r["review"]:
                print(f"  review {len(r['review'])} 个")
        except Exception as e:
            failed.append(code)
            print(f"[pilot] {code} 失败: {type(e).__name__}: {e}")

    print("\n[pilot] ===== 汇总 =====")
    print(f"{'代码':<8}{'平台波动':>10}{'对拍偏差':>10}  事件(EX_DATE:k)")
    for r in reports:
        evs = "  ".join(f"{e.ex_date}:{e.k:.6f}" for e in r["events"]) or "-"
        dev = f"{r['max_dev']:10.6f}" if r["max_dev"] is not None else "      N/A "
        print(f"{r['code']:<8}{r['plateau_fluct']:>10.6f}{dev}  {evs}")
    if failed:
        print(f"[pilot] 失败清单: {failed}")
    return 0 if not failed else 2


if __name__ == "__main__":
    sys.exit(main())
