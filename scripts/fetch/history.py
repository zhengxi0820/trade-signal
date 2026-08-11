#!/usr/bin/env python3
"""history.py — 历史回填：每股完整跑一遍因子反推管线

流程（每股，严格按设计顺序）：
    爬 none 全历史 → raw 行落库(ADJUST='0')
    → 爬 qfq 全历史（原料 + 对拍基准，不直接落库）
    → 逐日 factor → 阶梯识别事件 → stock_dividend(SOURCE='derive')
    → qfq 行 = raw × 平台因子 落库(ADJUST='1')
    → 对拍（自算 qfq vs 爬取 qfq 全序列，相对偏差 >0.1% 报警）

数据源（2026-08 实测口径，重要）：
- 主源新浪 stock_zh_a_daily：等比前复权，factor=qfq/raw 是干净阶梯（平台波动 ~1e-5），
  沪深北通吃（bj920xxx 实测可取），成交量单位为股，全历史单次返回、客户端按区间过滤。
- 东财 stock_zh_a_hist：2026-08-08 起因服务器端限流实录**退出生产**（此前本机直连被重置、
  Clash 代理 502），仅留应急兜底代码；东财 datacenter 公告日历（fetch.dividend）不受
  影响，双轨不变。
- 腾讯 newfqkline 弃用：其 qfq 是【等差（减法）复权】——实测 600519 段内 qfq=raw-79.58，
  段间常数跳变（差值=每股分红），factor=qfq/raw 随价格日内漂移 ±0.1%，
  不是阶梯函数，无法用于因子反推（会产生上百个伪事件）。

因子反推要求 none/qfq 出自同一源（复权基期必须一致），源切换以"股"为单位整体切换。

限流/重试与 C:/stock/fetch 套件一致：串行 sleep + 指数退避 3 次。

用法（在 scripts/ 目录下）：
    python -m fetch.history --code 600519 --years 3
"""

import argparse
import os
import sys
import time
from datetime import date, timedelta

os.environ["NO_PROXY"] = "*"

import akshare as ak

from adjust.backfill import backfill_stock
from common.db import get_conn

RETRY = 3


def _sina_symbol(code: str) -> str:
    """代码 → 新浪 symbol：6xx→sh，0/3→sz，4/8/92→bj。"""
    if code.startswith("6"):
        return f"sh{code}"
    if code[0] in ("0", "3"):
        return f"sz{code}"
    return f"bj{code}"


def fetch_hist_sina(code: str, adjust: str, start: date, end: date) -> list:
    """新浪日线（stock_zh_a_daily），adjust="" 不复权 / "qfq" 前复权（等比）。

    全历史单次返回，客户端按 [start, end] 过滤；成交量单位为股，无需换算。
    """
    df = ak.stock_zh_a_daily(symbol=_sina_symbol(code), adjust=adjust)
    s, e = start.strftime("%Y-%m-%d"), end.strftime("%Y-%m-%d")
    rows = []
    for _, r in df.iterrows():
        d = str(r["date"])[:10]
        if not (s <= d <= e):
            continue
        o, h, l, c = float(r["open"]), float(r["high"]), float(r["low"]), float(r["close"])
        # 零价/非法行过滤（2026-08-11 北交所 920 段 185 行停牌占位伪行事故）：
        # 新浪 raw 序列对停牌日输出 OHLC=0 占位行而 qfq 省略，落入库会导致两口径失配。
        # 真实行情 OHLC 必全 > 0；这里过滤可同时保护下游 factor 计算（避免除零）。
        if o <= 0 or h <= 0 or l <= 0 or c <= 0:
            print(f"  [fetch] {code} {d} 零价/非法行跳过 o={o} h={h} l={l} c={c}", flush=True)
            continue
        rows.append({
            "trade_date": d.replace("-", ""),
            "open": o,
            "high": h,
            "low": l,
            "close": c,
            "volume": int(r["volume"]),
        })
    rows.sort(key=lambda x: x["trade_date"])
    return rows


def fetch_hist_em(code: str, adjust: str, start: date, end: date) -> list:
    """东财日线（stock_zh_a_hist），兜底源：本机当前不可达，网络恢复时自动启用。

    东财成交量单位为手，统一 ×100 转股。北交所股票同接口可抓。
    """
    df = ak.stock_zh_a_hist(
        symbol=code,
        period="daily",
        start_date=start.strftime("%Y%m%d"),
        end_date=end.strftime("%Y%m%d"),
        adjust=adjust,
    )
    rows = []
    for _, r in df.iterrows():
        d = r["日期"]
        o, h, l, c = float(r["开盘"]), float(r["最高"]), float(r["最低"]), float(r["收盘"])
        # 与新浪同口径的零价/非法行过滤（见 fetch_hist_sina 注释）
        if o <= 0 or h <= 0 or l <= 0 or c <= 0:
            print(f"  [fetch] {code} {d} 零价/非法行跳过 o={o} h={h} l={l} c={c}", flush=True)
            continue
        rows.append({
            "trade_date": d.strftime("%Y%m%d") if hasattr(d, "strftime") else str(d).replace("-", ""),
            "open": o,
            "high": h,
            "low": l,
            "close": c,
            "volume": int(r["成交量"]) * 100,
        })
    rows.sort(key=lambda x: x["trade_date"])
    return rows


SOURCES = [("sina", fetch_hist_sina), ("eastmoney", fetch_hist_em)]
# 注（2026-08-08）：东财行情口（push2his）因限流实录已退出生产（日增/回填均新浪），
# fetch_hist_em 仅作应急兜底保留；东财 datacenter 公告日历（fetch.dividend）不受影响。


def fetch_pair(code: str, start: date, end: date, interval: float = 2.5, sources: list = None) -> tuple:
    """抓 none+qfq 两个口径（必须同源），带源切换与指数退避。返回 (raw_rows, qfq_rows, source)。

    sources 默认 SOURCES（新浪优先，东财仅应急兜底）。
    """
    last_err = None
    for source_name, fetch_fn in (sources or SOURCES):
        for attempt in range(RETRY):
            try:
                raw_rows = fetch_fn(code, "", start, end)
                time.sleep(interval)  # 串行真限流：每次请求后 sleep（含成功路径）
                qfq_rows = fetch_fn(code, "qfq", start, end)
                time.sleep(interval)
                if not raw_rows or not qfq_rows:
                    raise RuntimeError(f"空数据 raw={len(raw_rows)} qfq={len(qfq_rows)}")
                return raw_rows, qfq_rows, source_name
            except Exception as e:
                last_err = e
                wait = 2 ** attempt * 5
                print(f"  [retry] {code} {source_name} 第{attempt + 1}次失败({type(e).__name__}: {str(e)[:80]})，{wait}s 后重试")
                time.sleep(wait)
        print(f"  [source] {code} {source_name} 不可用，切换下一源")
    raise RuntimeError(f"{code} 全部数据源失败: {last_err}")


def upsert_quote_rows(conn, code: str, rows: list, adjust: str, table: str = "stock_quote") -> int:
    """按 (CODE, ADJUST, TRADE_DATE) upsert 行情行。

    raw 行('0')只追加、qfq 行('1')可重算覆盖，两者都用同一 upsert 语句（幂等即可满足两种语义）。
    价格保留 4 位小数（DECIMAL(12,4)）。
    table：周频分片阶段写 stock_quote_log（中转表），收尾并入 stock_quote；手工单股调试可直接写主表。
    """
    assert table in ("stock_quote", "stock_quote_log"), f"非法目标表: {table}"
    # 写库总闸门：零价/非法行一律不落（fetch 层已过滤，这里兜底防任何路径漏网）。
    # 停牌占位行（OHLC=0）曾导致 raw/qfq 两口径失配并污染物化 bar（2026-08-11 清理记录）。
    valid = []
    for r in rows:
        if r["open"] <= 0 or r["high"] <= 0 or r["low"] <= 0 or r["close"] <= 0:
            print(f"[upsert] {code} {r['trade_date']} adjust={adjust} 零价/非法行已过滤 "
                  f"o={r['open']} h={r['high']} l={r['low']} c={r['close']}", flush=True)
            continue
        valid.append(r)
    rows = valid
    if not rows:
        return 0
    from common.db import quote_id, unix_ts
    now = unix_ts()
    sql = f"""
        INSERT INTO {table} (ID, CODE, OPEN, HIGH, LOW, CLOSE, VOLUME, TRADE_DATE, ADJUST, CREATED_AT, UPDATED_AT)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
        ON DUPLICATE KEY UPDATE
            OPEN=VALUES(OPEN), HIGH=VALUES(HIGH), LOW=VALUES(LOW), CLOSE=VALUES(CLOSE),
            VOLUME=VALUES(VOLUME), UPDATED_AT=VALUES(UPDATED_AT)
    """
    params = [
        (quote_id(code, r["trade_date"], adjust), code,
         round(r["open"], 4), round(r["high"], 4), round(r["low"], 4), round(r["close"], 4),
         r["volume"], r["trade_date"], adjust, now, now)
        for r in rows
    ]
    with conn.cursor() as cur:
        cur.executemany(sql, params)
    conn.commit()
    return len(params)


def run_history(code: str, years: int = 3, conn=None, interval: float = 2.5,
                announce: bool = True) -> dict:
    """单股历史回填主流程，返回试点报告 dict（事件清单 / 平台波动 / 对拍偏差）。

    announce=True 时先抓东财公告除权日历走双轨（公告=权威日历，反推=测 k+校验）；
    东财不可用（如本机被墙）自动回退纯反推（SOURCE='derive'）。
    """
    end = date.today()
    start = end - timedelta(days=years * 365)

    raw_rows, qfq_rows, source = fetch_pair(code, start, end, interval)

    announce_rows = None
    if announce:
        try:
            from fetch.dividend import fetch_dividend_calendar_retry
            announce_rows = fetch_dividend_calendar_retry(code, interval)
        except Exception as e:
            print(f"  [dividend] {code} 公告日历不可用，回退纯反推: {str(e)[:80]}")

    own_conn = conn is None
    if own_conn:
        conn = get_conn()
    try:
        upsert_quote_rows(conn, code, raw_rows, "0")
        report = backfill_stock(conn, code, raw_rows, qfq_rows, upsert_quote_rows,
                                announce_rows=announce_rows)
        report["source"] = source
    finally:
        if own_conn:
            conn.close()
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description="单股历史回填（因子反推管线）")
    parser.add_argument("--code", required=True, help="股票代码，如 600519")
    parser.add_argument("--years", type=int, default=3, help="回溯年数，默认 3")
    args = parser.parse_args()

    report = run_history(args.code, args.years)
    dev = f"{report['max_dev']:.6f}" if report["max_dev"] is not None else "N/A(中止)"
    print(f"[history] {args.code} 完成({report['source']},{report['mode']}): raw={report['raw_rows']} qfq={report['qfq_rows']} "
          f"事件={len(report['events'])} 对拍最大偏差={dev}")
    for e in report["events"]:
        print(f"  事件 {e.ex_date} k={e.k:.8f} source={e.source}")
    if report["suspect"]:
        print(f"  suspect(丢弃的反推事件) {len(report['suspect'])} 个")
    if report["review"]:
        print(f"  review(人工复核) {len(report['review'])} 个")
    return 0


if __name__ == "__main__":
    sys.exit(main())
