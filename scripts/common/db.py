"""db.py — 数据库连接、ID/时间戳工具

连接参数走环境变量（SECURITY.md 2.7：凭据不设代码默认值）：
    DB_HOST(127.0.0.1) DB_PORT(3306) DB_NAME(trade_signal) DB_USER(trade_signal) 有缺省；
    DB_PASSWORD 无缺省，必须显式设置，否则报错。
    注意本机 shell 可能全局驻留其他项目的 DB_*，运行时务必显式覆盖全套。
"""

import hashlib
import os
import time

import pymysql


def get_conn():
    """新建一个连接（调用方负责关闭）。脚本串行使用，不做连接池。"""
    password = os.environ.get("DB_PASSWORD")
    if not password:
        raise RuntimeError("DB_PASSWORD 未设置：凭据不走代码默认值，请显式 export 后重跑")
    return pymysql.connect(
        host=os.environ.get("DB_HOST", "127.0.0.1"),
        port=int(os.environ.get("DB_PORT", "3306")),
        user=os.environ.get("DB_USER", "trade_signal"),
        password=password,
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
