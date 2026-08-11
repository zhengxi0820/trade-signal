#!/usr/bin/env python3
"""daily.py — 增量抓取（生产口径：周频 + log 表中转 + 2 路并行，2026-08-10 起）

分片阶段（--shard i/n，run_daily.sh 并行起 n 个进程，各占独立 DB 连接）：
    每股爬 10 日回看窗口 none+qfq（新浪）→ 取主表中尚无的交易日 → adjust.incremental
    逐日应用，**全部写 stock_quote_log 中转表**，主表 stock_quote/stock_dividend 零写入；
    确认的除权事件只暂存到 .event_stage_shard{i}.jsonl（不落库、不 rescale）。

收尾阶段（--finalize，两路都完成后由 wrapper 调用一次）：
    1. 事件统一执行：读各分片暂存文件 → stock_dividend 追加 + 主表 qfq 历史行 ×= k
       （此时主表还全是本轮之前的数据，rescale 语义与旧实时路径一致）
    2. INSERT INTO stock_quote SELECT FROM stock_quote_log ON DUPLICATE KEY UPDATE（幂等）
    3. 对账：log 行数 vs 主表命中行数，不符 → 报警退出、**不 truncate**
    4. 调用 /usr/local/bin/trade-signal-backup.sh 即时备份（失败同样不 truncate）
    5. TRUNCATE stock_quote_log，删除暂存文件

源策略（2026-08-08 用户拍板）：新浪 stock_zh_a_daily 唯一行情源（每次返回全历史，
客户端过滤窗口）；东财行情口退出生产，datacenter 公告日历保留（双轨见 merge.py）。
分片模式限流每线程 SHARD_INTERVAL=3s（2 路等效 1.5s）。

用法（在 scripts/ 目录下）：
    python -m fetch.daily --shard 0/2      # 分片 0（写 log 表 + 暂存事件）
    python -m fetch.daily --shard 1/2
    python -m fetch.daily --finalize       # 收尾（两路完成后调一次）
    python -m fetch.daily --codes 600519   # 手工调试：直写主表（旧行为）
    python -m fetch.daily --all            # 单线程直写主表（应急兜底，不推荐周跑用）
"""

import argparse
import json
import os
import subprocess
import sys
from datetime import date, timedelta

os.environ["NO_PROXY"] = "*"

from adjust.incremental import apply_daily, rescale_history_qfq
from adjust.backfill import upsert_dividends
from adjust.factor import DividendEvent
from common.db import get_conn
from fetch.history import fetch_pair, upsert_quote_rows

LOOKBACK_DAYS = 10   # 每次回看窗口，覆盖周末/节假日/漏跑
INTERVAL = 2.5       # 单线程（手工/兜底）限流
SHARD_INTERVAL = 3.0 # 分片模式每线程 3s（2 路等效 1.5s）
BACKUP_SCRIPT = "/usr/local/bin/trade-signal-backup.sh"


def stage_path(shard: str) -> str:
    # shard 形如 "0/2"，文件名里换成下划线
    return os.path.join(os.path.dirname(os.path.abspath(__file__)),
                        "..", f".event_stage_shard{shard.replace('/', '_')}.jsonl")


def known_dates(conn, code: str, adjust: str, since: date) -> set:
    with conn.cursor() as cur:
        cur.execute(
            "SELECT TRADE_DATE FROM stock_quote WHERE CODE=%s AND ADJUST=%s AND TRADE_DATE>=%s",
            (code, adjust, since.strftime("%Y%m%d")),
        )
        return {r[0] for r in cur.fetchall()}


def run_daily_one(conn, code: str, interval: float = INTERVAL,
                  table: str = "stock_quote") -> dict:
    """单股增量。table='stock_quote_log' 时走分片语义：事件暂存（返回值带 staged），
    平台因子在内存里跟踪（主表全程不变）。"""
    end = date.today()
    start = end - timedelta(days=LOOKBACK_DAYS)
    # 默认源序（新浪唯一行情源，东财行情口已退出）
    raw_rows, qfq_rows, source = fetch_pair(code, start, end, interval)
    qfq_by_date = {r["trade_date"]: r for r in qfq_rows}
    done = known_dates(conn, code, "0", start)

    # 窗口内逐日 factor（供持续性后验：候选跳变日之后 PERSIST_WINDOW 日的 factor）
    from adjust.factor import build_factor_series
    from common.const import PERSIST_WINDOW
    fs = build_factor_series(raw_rows, qfq_rows)
    idx_of = {d: i for i, d in enumerate(fs.dates)}

    shard_mode = table == "stock_quote_log"
    upsert_fn = (lambda c, cd, rows, adj: upsert_quote_rows(c, cd, rows, adj, table=table))
    staged = []
    level_override = None  # 分片模式内存跟踪平台因子；首日为 None → 查主表

    applied, deferred, events = 0, 0, []
    for r in raw_rows:
        d = r["trade_date"]
        if d in done or d not in qfq_by_date or d not in idx_of:
            continue
        i = idx_of[d]
        future = fs.factors[i + 1:i + 1 + PERSIST_WINDOW]
        res = apply_daily(conn, code, r, qfq_by_date[d], upsert_fn,
                          future_factors=future,
                          stage=staged if shard_mode else None,
                          factor_prev_override=level_override)
        if res.get("deferred"):
            # 候选事件证据不足：当日行不落库、不计 done，下次增量窗口前移后重评。
            # 后续日期照常落库无妨——若事件最终确认，rescale 会整体 ×= k 修正它们。
            deferred += 1
            continue
        applied += 1
        if res["event"]:
            events.append((d, res["k"]))
            if shard_mode and staged:
                level_override = staged[-1]["new_level"]
    return {"applied": applied, "deferred": deferred, "events": events,
            "source": source, "staged": staged}


def load_codes(conn, args) -> list:
    if args.codes:
        codes = list(args.codes)  # --codes 可与 --shard 叠加：手工验证分片链路（写 log 表）
    else:
        with conn.cursor() as cur:
            cur.execute("SELECT CODE FROM stock_info ORDER BY CODE")
            codes = [r[0] for r in cur.fetchall()]
    if args.shard:
        i, n = (int(x) for x in args.shard.split("/"))
        codes = codes[i::n]
    return codes


def run_shard_or_codes(args) -> int:
    conn = get_conn()
    try:
        codes = load_codes(conn, args)
        if not codes:
            print("需要 --codes 或 --all 或 --shard", file=sys.stderr)
            return 1
        shard_mode = bool(args.shard)
        table = "stock_quote_log" if shard_mode else "stock_quote"
        interval = SHARD_INTERVAL if shard_mode else INTERVAL
        stage_fh = None
        if shard_mode:
            # 覆盖写：新一轮分片从空暂存开始（上轮 finalize 已消费删除；上轮若崩在
            # finalize 前，残留暂存对应的事件下周会重新确认——upsert/rescale 均幂等）
            stage_fh = open(stage_path(args.shard), "w", encoding="utf-8")

        ok, failed = 0, []
        try:
            for i, code in enumerate(codes, 1):
                try:
                    res = run_daily_one(conn, code, interval, table=table)
                    ok += 1
                    for ev in res["staged"]:
                        stage_fh.write(json.dumps(ev) + "\n")
                    if stage_fh:
                        stage_fh.flush()
                    tag = f" 事件={res['events']}" if res["events"] else ""
                    if res.get("deferred"):
                        tag += f" 延期={res['deferred']}"
                    print(f"[daily] {i}/{len(codes)} {code} +{res['applied']} 行({res['source']}){tag}", flush=True)
                except Exception as e:
                    failed.append(code)
                    print(f"[daily] {i}/{len(codes)} {code} 失败: {e}", flush=True)
        finally:
            if stage_fh:
                stage_fh.close()
        print(f"[daily] 完成：成功 {ok}/{len(codes)}，失败 {len(failed)} 只: {failed}", flush=True)
        return 0 if not failed else 2
    finally:
        conn.close()


def run_finalize() -> int:
    """收尾：事件统一执行 → log 并入主表 → 对账 → 备份 → truncate。"""
    import glob
    conn = get_conn()
    try:
        # 1. 除权事件统一执行（此时主表全是本轮之前的数据，rescale 语义与旧实时路径一致）
        staged = []
        for path in sorted(glob.glob(stage_path("*"))):
            with open(path, encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if line:
                        staged.append((path, json.loads(line)))
        for path, ev in staged:
            event = DividendEvent(ex_date=ev["ex_date"], k=ev["k"],
                                  rel_change=ev.get("rel_change", 0.0))
            upsert_dividends(conn, ev["code"], [event])
            n = rescale_history_qfq(conn, ev["code"], ev["k"])
            print(f"[finalize] 事件 {ev['code']} {ev['ex_date']} k={ev['k']:.8f} rescale {n} 行", flush=True)

        # 2. log → 主表（幂等）
        with conn.cursor() as cur:
            cur.execute("SELECT COUNT(*) FROM stock_quote_log")
            log_cnt = cur.fetchone()[0]
        if log_cnt == 0:
            print("[finalize] log 表为空，跳过并入/备份/truncate", flush=True)
        else:
            with conn.cursor() as cur:
                cur.execute("""
                    INSERT INTO stock_quote (ID, CODE, OPEN, HIGH, LOW, CLOSE, VOLUME, TRADE_DATE, ADJUST, CREATED_AT, UPDATED_AT)
                    SELECT ID, CODE, OPEN, HIGH, LOW, CLOSE, VOLUME, TRADE_DATE, ADJUST, CREATED_AT, UPDATED_AT
                    FROM stock_quote_log
                    ON DUPLICATE KEY UPDATE
                        OPEN=VALUES(OPEN), HIGH=VALUES(HIGH), LOW=VALUES(LOW), CLOSE=VALUES(CLOSE),
                        VOLUME=VALUES(VOLUME), UPDATED_AT=VALUES(UPDATED_AT)
                """)
            conn.commit()

            # 3. 对账：log 每行都必须在主表命中且关键值一致
            with conn.cursor() as cur:
                cur.execute("""
                    SELECT COUNT(*) FROM stock_quote_log l
                    JOIN stock_quote q ON q.CODE=l.CODE AND q.ADJUST=l.ADJUST AND q.TRADE_DATE=l.TRADE_DATE
                      AND q.OPEN=l.OPEN AND q.HIGH=l.HIGH AND q.LOW=l.LOW AND q.CLOSE=l.CLOSE
                """)
                matched = cur.fetchone()[0]
            if matched != log_cnt:
                print(f"[finalize] **对账失败** log={log_cnt} 主表命中={matched}，"
                      f"报警退出，不备份不 truncate", flush=True)
                return 2
            print(f"[finalize] 并入主表 {log_cnt} 行，对账一致", flush=True)

            # 4. 同步后即时备份（失败则不 truncate，保现场）。
            # 备份脚本写 /var/backups 且 mysqldump 走 root socket，需 sudo（ops 免密）
            if os.path.exists(BACKUP_SCRIPT):
                rc = subprocess.run(["sudo", "-n", BACKUP_SCRIPT]).returncode
                if rc != 0:
                    print(f"[finalize] **备份失败 rc={rc}**，不 truncate，报警退出", flush=True)
                    return 2
                print("[finalize] 备份完成", flush=True)
            else:
                print(f"[finalize] 警告：{BACKUP_SCRIPT} 不存在，跳过备份", flush=True)

            # 5. truncate + 清理暂存
            with conn.cursor() as cur:
                cur.execute("TRUNCATE stock_quote_log")
            conn.commit()
            print("[finalize] stock_quote_log 已 truncate", flush=True)

        for path in sorted({p for p, _ in staged}):
            os.remove(path)
        print(f"[finalize] 完成：事件 {len(staged)} 个，log 行 {log_cnt}", flush=True)
        return 0
    finally:
        conn.close()


def main() -> int:
    parser = argparse.ArgumentParser(description="增量抓取（周频 + log 表中转 + 分片并行）")
    parser.add_argument("--codes", nargs="*", help="股票代码列表（直写主表，手工调试用）")
    parser.add_argument("--all", action="store_true", help="stock_info 全量（单线程直写主表，应急）")
    parser.add_argument("--shard", metavar="i/n", help="分片模式：写 log 表 + 事件暂存，如 0/2")
    parser.add_argument("--finalize", action="store_true", help="收尾：事件执行+并入主表+对账+备份+truncate")
    args = parser.parse_args()

    if args.finalize:
        return run_finalize()
    return run_shard_or_codes(args)


if __name__ == "__main__":
    sys.exit(main())
