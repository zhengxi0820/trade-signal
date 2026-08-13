#!/usr/bin/env python3
"""stock_list.py — 灌 stock_info（全量 A 股基础信息）

数据源：akshare 三个交易所名单接口
    stock_info_sh_name_code()  沪市（含科创板）
    stock_info_sz_name_code()  深市（含创业板）
    stock_info_bj_name_code()  北交所
板块不由接口给出，统一按代码前缀推导（见 common.const.board_type_of）。

口径：
- ID = MD5(market:code)，唯一键 (MARKET, CODE)，重跑幂等（存在则更新名称/板块）
- 只灌 A 股：沪市接口含 B 股（900 开头），深市接口含 B 股（200 开头），按前缀过滤

用法（在 scripts/ 目录下）：
    python -m fetch.stock_list
"""

import os
import sys

# 交易所名单接口为国内站点，强制直连（本机系统代理会导致抓取失败）
os.environ["NO_PROXY"] = "*"

import akshare as ak

from common.const import MARKET_SH, MARKET_SZ, MARKET_BJ, board_type_of
from common.db import get_conn, info_id, unix_ts


def _find_col(df, candidates):
    """akshare 列名随版本会变，按候选名自适应匹配。"""
    for c in candidates:
        if c in df.columns:
            return c
    raise KeyError(f"未找到列 {candidates}，实际列: {list(df.columns)}")


def _is_a_share(code: str) -> bool:
    """过滤 B 股：沪 B=900xxx，深 B=200xxx。"""
    return not (code.startswith("900") or code.startswith("200"))


def load_stock_list() -> list:
    """抓取三个交易所名单，返回 [(market, code, name), ...]。"""
    stocks = []

    df = ak.stock_info_sh_name_code(symbol="主板A股")
    code_col = _find_col(df, ["证券代码", "A股代码"])
    name_col = _find_col(df, ["证券简称", "A股简称"])
    for _, r in df.iterrows():
        code = str(r[code_col]).strip()
        if _is_a_share(code):
            stocks.append((MARKET_SH, code, str(r[name_col]).strip()))

    # 科创板单独一个 symbol，主板接口不含 688
    df = ak.stock_info_sh_name_code(symbol="科创板")
    code_col = _find_col(df, ["证券代码", "A股代码"])
    name_col = _find_col(df, ["证券简称", "A股简称"])
    for _, r in df.iterrows():
        code = str(r[code_col]).strip()
        stocks.append((MARKET_SH, code, str(r[name_col]).strip()))

    df = ak.stock_info_sz_name_code(symbol="A股列表")
    code_col = _find_col(df, ["A股代码", "证券代码"])
    name_col = _find_col(df, ["A股简称", "证券简称"])
    for _, r in df.iterrows():
        code = str(r[code_col]).strip().zfill(6)  # 深市接口的代码可能丢前导零
        if _is_a_share(code):
            stocks.append((MARKET_SZ, code, str(r[name_col]).strip()))

    df = ak.stock_info_bj_name_code()
    code_col = _find_col(df, ["证券代码", "A股代码"])
    name_col = _find_col(df, ["证券简称", "A股简称"])
    for _, r in df.iterrows():
        code = str(r[code_col]).strip().zfill(6)
        stocks.append((MARKET_BJ, code, str(r[name_col]).strip()))

    return stocks


def upsert_stock_info(stocks: list) -> int:
    """按 (MARKET, CODE) upsert，返回写入行数。"""
    now = unix_ts()
    sql = """
        INSERT INTO stock_info (ID, CODE, NAME, MARKET, BOARD_TYPE, CREATED_AT, UPDATED_AT)
        VALUES (%s, %s, %s, %s, %s, %s, %s)
        ON DUPLICATE KEY UPDATE NAME=VALUES(NAME), BOARD_TYPE=VALUES(BOARD_TYPE), UPDATED_AT=VALUES(UPDATED_AT)
    """
    params = [
        (info_id(market, code), code, name, market, board_type_of(market, code), now, now)
        for market, code, name in stocks
    ]
    conn = get_conn()
    try:
        with conn.cursor() as cur:
            cur.executemany(sql, params)
        conn.commit()
        return len(params)
    finally:
        conn.close()


def main() -> int:
    stocks = load_stock_list()
    if not stocks:
        print("[stock_list] 未取到任何股票", file=sys.stderr)
        return 1
    n = upsert_stock_info(stocks)
    by_market = {}
    by_board = {}
    for market, code, _ in stocks:
        by_market[market] = by_market.get(market, 0) + 1
        b = board_type_of(market, code)
        by_board[b] = by_board.get(b, 0) + 1
    print(f"[stock_list] 共 {n} 只入 stock_info；按市场 {by_market}；按板块 {by_board}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
