"""db.py — 数据库连接、ID/时间戳工具

连接参数走环境变量，缺省为本机开发值：
    DB_HOST(127.0.0.1) DB_PORT(3306) DB_NAME(trade_signal)
    DB_USER(trade_signal) DB_PASSWORD(trade_signal)
"""

import hashlib
import os
import time

import pymysql


def get_conn():
    """新建一个连接（调用方负责关闭）。脚本串行使用，不做连接池。"""
    return pymysql.connect(
        host=os.environ.get("DB_HOST", "127.0.0.1"),
        port=int(os.environ.get("DB_PORT", "3306")),
        user=os.environ.get("DB_USER", "trade_signal"),
        password=os.environ.get("DB_PASSWORD", "trade_signal"),
        database=os.environ.get("DB_NAME", "trade_signal"),
        charset="utf8mb4",  # 股票名称含中文，必须 utf8mb4
    )


def md5_id(*parts: str) -> str:
    """ID 规则：MD5("part1:part2:...")，如 code:yyyymmdd:adjust。"""
    return hashlib.md5(":".join(parts).encode("utf-8")).hexdigest()


def quote_id(code: str, trade_date: str, adjust: str) -> str:
    return md5_id(code, trade_date, adjust)


def dividend_id(code: str, ex_date: str) -> str:
    return md5_id(code, ex_date)


def workday_id(market: str, trade_date: str) -> str:
    return md5_id(market, trade_date)


def info_id(market: str, code: str) -> str:
    return md5_id(market, code)


def unix_ts() -> int:
    """CREATED_AT/UPDATED_AT 统一 UNIX 秒（DECIMAL(15,0)）。"""
    return int(time.time())
