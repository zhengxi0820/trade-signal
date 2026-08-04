#!/usr/bin/env python3
"""dividend.py — 东财公告除权日历抓取（双轨设计的"权威事件日历"轨）

数据源：akshare stock_history_dividend_detail(symbol, indicator="分红"/"配股")。
服务器实测（2026-08-04，akshare 1.18.81）：
- 分红列：公告日期/送股/转增/派息/进度/除权除息日/股权登记日/红股上市日
  （送股/转增/派息均为每 10 股口径，派息为税前元；"不分配"行除权日为 NaT，过滤）
- 配股列：公告日期/配股方案(每10股)/配股价格/基准股本/除权日/...
- 坑：个别行公告日期是 1900-01-01（垃圾字段），只依赖除权日；
  同一除权日可能同时有分红与配股（如万科 1991-06-08），按除权日合并为一条
- 东财接口本机（Windows 开发机）被墙，仅服务器可用；调用方必须处理失败兜底

返回结构（按 ex_date 升序）：
    [{"ex_date": "yyyymmdd", "song": 送/10股, "zhuan": 转/10股,
      "pai": 派/10股(元), "pei": 配/10股, "pei_price": 配股价(元|None)}]

用法：
    python -m fetch.dividend --code 000002
"""

import argparse
import os
import sys
import time

os.environ["NO_PROXY"] = "*"

import akshare as ak

RETRY = 3


def _num(v, default=0.0):
    """东财数值字段可能是 NaN/空串，统一转 float。"""
    try:
        f = float(v)
        return default if f != f else f  # NaN 判断
    except (TypeError, ValueError):
        return default


def _date_str(v):
    """NaT/Timestamp/字符串 → yyyymmdd；无效返回 None。"""
    if v is None or str(v) in ("NaT", "nan", ""):
        return None
    return str(v)[:10].replace("-", "")


def fetch_dividend_calendar(code: str, interval: float = 2.5) -> list:
    """抓单股公告除权日历（分红 + 配股），按 ex_date 合并。失败抛异常（调用方兜底）。"""
    events = {}

    df = ak.stock_history_dividend_detail(symbol=code, indicator="分红")
    time.sleep(interval)  # 串行真限流
    for _, r in df.iterrows():
        ex = _date_str(r.get("除权除息日"))
        if not ex:
            continue  # "不分配"及缺除权日行
        e = events.setdefault(ex, {"ex_date": ex, "song": 0.0, "zhuan": 0.0,
                                   "pai": 0.0, "pei": 0.0, "pei_price": None})
        e["song"] += _num(r.get("送股"))
        e["zhuan"] += _num(r.get("转增"))
        e["pai"] += _num(r.get("派息"))

    df = ak.stock_history_dividend_detail(symbol=code, indicator="配股")
    time.sleep(interval)
    for _, r in df.iterrows():
        ex = _date_str(r.get("除权日"))
        if not ex:
            continue
        e = events.setdefault(ex, {"ex_date": ex, "song": 0.0, "zhuan": 0.0,
                                   "pai": 0.0, "pei": 0.0, "pei_price": None})
        e["pei"] += _num(r.get("配股方案"))
        price = _num(r.get("配股价格"), default=None)
        if price:
            e["pei_price"] = price

    return sorted(events.values(), key=lambda x: x["ex_date"])


def fetch_dividend_calendar_retry(code: str, interval: float = 2.5) -> list:
    """指数退避重试 RETRY 次；全部失败抛 RuntimeError（调用方回退纯反推）。"""
    last_err = None
    for attempt in range(RETRY):
        try:
            return fetch_dividend_calendar(code, interval)
        except Exception as e:
            last_err = e
            wait = 2 ** attempt * 5
            print(f"  [retry] {code} 东财公告日历 第{attempt + 1}次失败({type(e).__name__}: {str(e)[:80]})，{wait}s 后重试")
            time.sleep(wait)
    raise RuntimeError(f"{code} 东财公告日历抓取失败: {last_err}")


def main() -> int:
    parser = argparse.ArgumentParser(description="抓取单股东财公告除权日历（打印，不写库）")
    parser.add_argument("--code", required=True)
    args = parser.parse_args()
    events = fetch_dividend_calendar_retry(args.code)
    print(f"[dividend] {args.code} 共 {len(events)} 条公告除权事件")
    for e in events:
        print(f"  {e['ex_date']} 送{e['song']:g} 转{e['zhuan']:g} 派{e['pai']:g} "
              f"配{e['pei']:g}@{e['pei_price']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
