#!/usr/bin/env python3
"""
iFinD 全量 A股 OHLCV 数据下载入库脚本
功能：下载沪深北所有股票从上市到 2026-07-31 的不复权日K数据，批量插入 MySQL

用法：
    1. 先生成股票列表：python ifind_ohlcv_sync.py --gen-list
    2. 执行数据同步：  python ifind_ohlcv_sync.py --sync
    3. 断点续跑：      python ifind_ohlcv_sync.py --sync --resume
    4. 单股精确模式：  python ifind_ohlcv_sync.py --sync --single

依赖：
    pip install pymysql pandas tqdm
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any, Dict, List, Tuple

import pymysql
from pymysql.cursors import DictCursor

# ==================== 用户配置区 ====================

MYSQL_CONFIG = {
    "host": "127.0.0.1",
    "port": 3306,
    "user": "root",
    "password": "your_password",   # ← 修改为你的 MySQL 密码
    "database": "stock_db",        # ← 修改为你的数据库名
    "charset": "utf8mb4",
}

# iFinD 插件脚本路径（自动推导，一般无需修改）
IFIND_TOOL_PATH = Path(__file__).parent / "ifind_tool.py"
if not IFIND_TOOL_PATH.exists():
    _plugin_dir = Path.home() / (
        "AppData/Roaming/kimi-desktop/daimon-share/daimon/runtime/"
        "kimi-code/home/plugins/managed/ifind/scripts/ifind_tool.py"
    )
    if _plugin_dir.exists():
        IFIND_TOOL_PATH = _plugin_dir

# 数据截止日
END_DATE = "2026-07-31"
# API 单次最大时间跨度（年）
MAX_YEARS_PER_QUERY = 3
# 单次 API 最多股票数
MAX_STOCKS_PER_QUERY = 3
# 并发线程数
MAX_WORKERS = 4
# 每次 API 调用间隔（秒）
API_DELAY = 1.5
# 批量插入大小
BATCH_INSERT_SIZE = 500
# 股票列表文件
STOCK_LIST_FILE = Path(__file__).parent / "stock_list.json"
# 断点记录文件
CHECKPOINT_FILE = Path(__file__).parent / "checkpoint.json"
# 临时 CSV 保存目录
CSV_TEMP_DIR = Path(__file__).parent / "csv_temp"

# ==================================================


def get_db_connection():
    """获取 MySQL 连接"""
    return pymysql.connect(
        host=MYSQL_CONFIG["host"],
        port=MYSQL_CONFIG["port"],
        user=MYSQL_CONFIG["user"],
        password=MYSQL_CONFIG["password"],
        database=MYSQL_CONFIG["database"],
        charset=MYSQL_CONFIG["charset"],
        cursorclass=DictCursor,
        autocommit=False,
    )


def ensure_table():
    """确保目标表存在"""
    create_sql = """
    CREATE TABLE IF NOT EXISTS stock_quote (
        ID          VARCHAR(64)   NOT NULL COMMENT 'ID',
        CODE        VARCHAR(16)   NOT NULL COMMENT '股票代码',
        NAME        VARCHAR(64)   DEFAULT NULL COMMENT '股票名称',
        MARKET      VARCHAR(8)    NOT NULL COMMENT '市场标识',
        OPEN        DECIMAL(12,4) NOT NULL COMMENT '开盘价',
        HIGH        DECIMAL(12,4) NOT NULL COMMENT '最高价',
        LOW         DECIMAL(12,4) NOT NULL COMMENT '最低价',
        CLOSE       DECIMAL(12,4) NOT NULL COMMENT '收盘价',
        VOLUME      BIGINT        DEFAULT NULL COMMENT '成交量',
        TRADE_DATE  VARCHAR(8)    NOT NULL COMMENT '交易日期(yyyymmdd)',
        ADJUST      VARCHAR(8)    DEFAULT NULL COMMENT '复权类型',
        CREATED_AT  DATETIME      DEFAULT NULL,
        UPDATED_AT  DATETIME      DEFAULT NULL,
        PRIMARY KEY (ID),
        UNIQUE KEY uk_code_adjust_date (CODE, ADJUST, TRADE_DATE),
        KEY idx_trade_date (TRADE_DATE)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='股票行情';
    """
    conn = get_db_connection()
    try:
        with conn.cursor() as cur:
            cur.execute(create_sql)
        conn.commit()
        print("[OK] 数据表 stock_quote 已就绪")
    finally:
        conn.close()


def generate_id(code: str, trade_date: str, adjust: str) -> str:
    """生成唯一 ID：MD5(code + trade_date + adjust)"""
    raw = f"{code}:{trade_date}:{adjust}"
    return hashlib.md5(raw.encode("utf-8")).hexdigest()


def split_date_ranges(start_date: str, end_date: str, max_years: int = 3) -> List[Tuple[str, str]]:
    """
    将大的时间范围拆分为多个不超过 max_years 年的小区间
    返回 [(start1, end1), (start2, end2), ...]
    """
    ranges = []
    start = datetime.strptime(start_date, "%Y-%m-%d")
    end = datetime.strptime(end_date, "%Y-%m-%d")

    while start <= end:
        seg_end = start.replace(year=start.year + max_years) - timedelta(days=1)
        if seg_end > end:
            seg_end = end
        ranges.append((start.strftime("%Y-%m-%d"), seg_end.strftime("%Y-%m-%d")))
        start = seg_end + timedelta(days=1)

    return ranges


def call_ifind_price(
    tickers: List[str],
    start_date: str,
    end_date: str,
    csv_path: Path,
    adjust: str = "none",
) -> bool:
    """
    调用 iFinD API 下载价格数据，保存为 CSV
    返回是否成功
    """
    ticker_str = ",".join(tickers)
    params = {
        "ticker": ticker_str,
        "start_date": start_date,
        "end_date": end_date,
        "adjust": adjust,
        "file_path": str(csv_path),
    }

    # 写入临时参数文件
    params_file = CSV_TEMP_DIR / f"params_{int(time.time()*1000)}.json"
    params_file.write_text(json.dumps(params, ensure_ascii=False), encoding="utf-8")

    cmd = (
        f'python3 "{IFIND_TOOL_PATH}" call '
        f'--api-name ifind_get_price '
        f'--params-file "{params_file}"'
    )

    ret = os.system(cmd)
    params_file.unlink(missing_ok=True)
    return ret == 0


def parse_csv_and_prepare_rows(csv_path: Path) -> List[Dict[str, Any]]:
    """
    解析 iFinD 返回的 CSV，转换为数据库行字典列表
    """
    import csv

    rows = []
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

    with open(csv_path, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            thscode = row.get("thscode", "").strip()
            if not thscode:
                continue

            # 解析代码和市场
            if "." in thscode:
                code, market = thscode.rsplit(".", 1)
            else:
                code, market = thscode, ""

            trade_date_raw = row.get("time", "").strip()
            if len(trade_date_raw) == 8:
                trade_date = trade_date_raw
            else:
                try:
                    dt = datetime.strptime(trade_date_raw, "%Y%m%d")
                    trade_date = dt.strftime("%Y%m%d")
                except ValueError:
                    continue

            name = row.get("thsname_cn", "").strip() or row.get("thsname_en", "").strip()
            adjust = "none"

            record_id = generate_id(code, trade_date, adjust)

            def safe_decimal(val):
                try:
                    return float(val)
                except (ValueError, TypeError):
                    return 0.0

            def safe_int(val):
                try:
                    return int(float(val))
                except (ValueError, TypeError):
                    return 0

            rows.append({
                "ID": record_id,
                "CODE": code,
                "NAME": name,
                "MARKET": market,
                "OPEN": safe_decimal(row.get("open", 0)),
                "HIGH": safe_decimal(row.get("high", 0)),
                "LOW": safe_decimal(row.get("low", 0)),
                "CLOSE": safe_decimal(row.get("close", 0)),
                "VOLUME": safe_int(row.get("volume", 0)),
                "TRADE_DATE": trade_date,
                "ADJUST": adjust,
                "CREATED_AT": now,
                "UPDATED_AT": now,
            })

    return rows


def batch_insert_mysql(rows: List[Dict[str, Any]]) -> Tuple[int, int]:
    """
    批量插入 MySQL，使用 INSERT IGNORE 跳过重复
    返回 (成功插入数, 跳过数)
    """
    if not rows:
        return 0, 0

    conn = get_db_connection()
    inserted = 0
    skipped = 0

    sql = """
    INSERT IGNORE INTO stock_quote
    (ID, CODE, NAME, MARKET, OPEN, HIGH, LOW, CLOSE, VOLUME, TRADE_DATE, ADJUST, CREATED_AT, UPDATED_AT)
    VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
    """

    try:
        with conn.cursor() as cur:
            for i in range(0, len(rows), BATCH_INSERT_SIZE):
                batch = rows[i : i + BATCH_INSERT_SIZE]
                values = [
                    (
                        r["ID"], r["CODE"], r["NAME"], r["MARKET"],
                        r["OPEN"], r["HIGH"], r["LOW"], r["CLOSE"],
                        r["VOLUME"], r["TRADE_DATE"], r["ADJUST"],
                        r["CREATED_AT"], r["UPDATED_AT"],
                    )
                    for r in batch
                ]
                cur.executemany(sql, values)
                inserted += cur.rowcount
                skipped += len(batch) - cur.rowcount
        conn.commit()
    except Exception as e:
        conn.rollback()
        print(f"[ERROR] 数据库插入失败: {e}")
        raise
    finally:
        conn.close()

    return inserted, skipped


def load_checkpoint() -> set:
    """加载断点记录（已完成的分组）"""
    if CHECKPOINT_FILE.exists():
        data = json.loads(CHECKPOINT_FILE.read_text(encoding="utf-8"))
        return set(data.get("completed", []))
    return set()


def save_checkpoint(completed: set):
    """保存断点记录"""
    CHECKPOINT_FILE.write_text(
        json.dumps({"completed": sorted(list(completed))}, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


def download_and_insert_batch(
    tickers: List[str],
    start_date: str,
    end_date: str,
    batch_key: str,
) -> Tuple[str, int, int]:
    """
    下载一批股票的某时间段数据并入库
    返回 (batch_key, 插入数, 跳过数)
    """
    csv_path = CSV_TEMP_DIR / f"{batch_key.replace('/', '_')}.csv"

    try:
        success = call_ifind_price(tickers, start_date, end_date, csv_path)
        if not success:
            return batch_key, 0, 0

        if not csv_path.exists():
            return batch_key, 0, 0

        rows = parse_csv_and_prepare_rows(csv_path)
        inserted, skipped = batch_insert_mysql(rows)
        csv_path.unlink(missing_ok=True)

        return batch_key, inserted, skipped

    except Exception as e:
        print(f"[ERROR] 批次 {batch_key} 失败: {e}")
        return batch_key, 0, 0


def generate_stock_list():
    """
    生成 A股全量代码列表，保存为 stock_list.json
    使用 akshare 获取（需要先安装：pip install akshare）
    """
    try:
        import akshare as ak
    except ImportError:
        print("[ERROR] 缺少 akshare，请先安装: pip install akshare")
        print("或者手动准备 stock_list.json 文件，格式如下:")
        print(json.dumps([
            {"code": "000001", "market": "SZ", "name": "平安银行", "list_date": "1991-04-03"},
            {"code": "600000", "market": "SH", "name": "浦发银行", "list_date": "1999-11-10"},
        ], ensure_ascii=False, indent=2))
        sys.exit(1)

    print("[INFO] 正在从 akshare 获取 A股全量代码列表...")

    df_sz = ak.stock_info_sz_name_code()
    df_sh = ak.stock_info_sh_name_code()
    df_bj = ak.stock_info_bj_name_code()

    stocks = []

    # 深市
    for _, row in df_sz.iterrows():
        code = str(row.get("A股代码", "")).strip()
        name = str(row.get("A股简称", "")).strip()
        list_date = str(row.get("A股上市日期", "")).strip()
        if code and code.isdigit():
            stocks.append({
                "code": code,
                "market": "SZ",
                "name": name,
                "list_date": list_date if len(list_date) == 10 else "1990-01-01",
            })

    # 沪市
    for _, row in df_sh.iterrows():
        code = str(row.get("证券代码", "")).strip()
        name = str(row.get("证券简称", "")).strip()
        list_date = str(row.get("上市日期", "")).strip()
        if code and code.isdigit():
            stocks.append({
                "code": code,
                "market": "SH",
                "name": name,
                "list_date": list_date if len(list_date) == 10 else "1990-01-01",
            })

    # 北交所
    for _, row in df_bj.iterrows():
        code = str(row.get("证券代码", "")).strip()
        name = str(row.get("证券简称", "")).strip()
        list_date = str(row.get("上市日期", "")).strip()
        if code and code.isdigit():
            stocks.append({
                "code": code,
                "market": "BJ",
                "name": name,
                "list_date": list_date if len(list_date) == 10 else "2021-11-15",
            })

    # 去重
    seen = set()
    unique_stocks = []
    for s in stocks:
        key = f"{s['code']}.{s['market']}"
        if key not in seen:
            seen.add(key)
            unique_stocks.append(s)

    STOCK_LIST_FILE.write_text(
        json.dumps(unique_stocks, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(f"[OK] 共获取 {len(unique_stocks)} 只股票，已保存到 {STOCK_LIST_FILE}")


def sync_all_stocks(resume: bool = False, single_stock_mode: bool = False):
    """
    主同步流程：下载所有股票数据并入库

    Args:
        resume: 是否断点续跑
        single_stock_mode: 为 True 时每次只查 1 只股票，
                          start_date 精确到该股票自己的上市日期。
                          调用次数多但最精确。
    """
    ensure_table()

    if not STOCK_LIST_FILE.exists():
        print(f"[ERROR] 股票列表文件不存在: {STOCK_LIST_FILE}")
        print("请先运行: python ifind_ohlcv_sync.py --gen-list")
        sys.exit(1)

    stocks = json.loads(STOCK_LIST_FILE.read_text(encoding="utf-8"))
    print(f"[INFO] 共 {len(stocks)} 只股票待同步")

    # 按上市日期排序，让上市时间相近的股票分到同一批次，减少 start_date 差异
    stocks.sort(key=lambda s: s.get("list_date", "1990-01-01"))

    # 加载断点
    completed = load_checkpoint() if resume else set()

    # 构建所有任务
    tasks = []  # [(tickers, start, end, batch_key)]

    # 决定每批股票数量
    batch_size = 1 if single_stock_mode else MAX_STOCKS_PER_QUERY

    for i in range(0, len(stocks), batch_size):
        batch = stocks[i : i + batch_size]
        ticker_strs = [f"{s['code']}.{s['market']}" for s in batch]

        # 取本批次最早的上市日期作为查询起点
        # 单股模式下就是该股票自己的 list_date
        list_dates = [s.get("list_date", "1990-01-01") for s in batch]
        earliest = min(list_dates)

        # 拆分时间段（最多 3 年一段）
        date_ranges = split_date_ranges(earliest, END_DATE, MAX_YEARS_PER_QUERY)

        for start, end in date_ranges:
            batch_key = f"{'_'.join(ticker_strs)}_{start}_{end}"
            if batch_key in completed:
                continue
            tasks.append((ticker_strs, start, end, batch_key))

    print(f"[INFO] 共 {len(tasks)} 个下载任务"
          f"（batch_size={batch_size}, 已跳过 {len(completed)} 个已完成）")

    if not tasks:
        print("[OK] 所有任务已完成！")
        return

    CSV_TEMP_DIR.mkdir(parents=True, exist_ok=True)

    total_inserted = 0
    total_skipped = 0

    try:
        with ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
            future_to_key = {}
            for tickers, start, end, key in tasks:
                future = executor.submit(download_and_insert_batch, tickers, start, end, key)
                future_to_key[future] = key
                time.sleep(API_DELAY / MAX_WORKERS)

            from tqdm import tqdm
            pbar = tqdm(total=len(tasks), desc="下载进度", unit="batch")

            for future in as_completed(future_to_key):
                key = future_to_key[future]
                try:
                    _, inserted, skipped = future.result()
                    total_inserted += inserted
                    total_skipped += skipped
                    completed.add(key)
                except Exception as e:
                    print(f"[ERROR] 任务 {key} 异常: {e}")

                pbar.update(1)
                if len(completed) % 10 == 0:
                    save_checkpoint(completed)

            pbar.close()

    except KeyboardInterrupt:
        print("\n[INFO] 用户中断，保存断点...")
    finally:
        save_checkpoint(completed)

    print(f"\n[SUMMARY]")
    print(f"  完成任务数: {len(completed)}")
    print(f"  新插入行数: {total_inserted}")
    print(f"  跳过重复行: {total_skipped}")
    print(f"  断点文件:   {CHECKPOINT_FILE}")


def main():
    parser = argparse.ArgumentParser(description="iFinD A股 OHLCV 数据同步工具")
    parser.add_argument("--gen-list", action="store_true", help="生成股票代码列表 (stock_list.json)")
    parser.add_argument("--sync", action="store_true", help="执行数据同步")
    parser.add_argument("--resume", action="store_true", help="断点续跑（基于 checkpoint.json）")
    parser.add_argument("--single", action="store_true", help="单股单查模式：每只精确到自己的上市日期（调用次数更多）")
    parser.add_argument("--table-only", action="store_true", help="仅创建数据表，不下载数据")
    args = parser.parse_args()

    if args.table_only:
        ensure_table()
        return

    if args.gen_list:
        generate_stock_list()
        return

    if args.sync:
        sync_all_stocks(resume=args.resume, single_stock_mode=args.single)
        return

    parser.print_help()


if __name__ == "__main__":
    main()
