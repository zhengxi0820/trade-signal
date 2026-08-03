#!/usr/bin/env python3
"""CDP 诊断：观察 test-b.html（日线选中股票出图 → 切季线）卡死时的页面状态

启动无头 Edge（远程调试端口），通过 CDP 收集 console / JS 异常，
并在切换前后探测 JS 线程是否响应（Runtime.evaluate 心跳）。
"""
import json
import subprocess
import sys
import time
import urllib.request

import websocket

import os

EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
PORT = 9333
URL = os.environ.get("CDP_URL", "http://localhost:8081/test-b.html")
PROFILE = "/tmp/edge-cdp"

proc = None
if not os.environ.get("CDP_ATTACH_ONLY"):
    proc = subprocess.Popen([
        EDGE, "--headless", "--disable-gpu", f"--remote-debugging-port={PORT}",
        f"--user-data-dir={PROFILE}", "--window-size=1400,1400", URL,
    ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

try:
    # 等 DevTools 起来（页面可能先列扩展页，重试并打印全部标签辅助排查）
    ws_url = None
    for attempt in range(45):
        try:
            tabs = json.load(urllib.request.urlopen(f"http://127.0.0.1:{PORT}/json", timeout=2))
            pages = [t for t in tabs if t.get("type") == "page"]
            if attempt % 10 == 0:
                print(f"tabs@{attempt}s:", [(t.get("type"), t.get("url", "")[:50]) for t in tabs])
            if pages:
                hit = [t for t in pages if "test-b.html" in t.get("url", "")]
                ws_url = (hit or pages)[0]["webSocketDebuggerUrl"]
                if not hit:
                    print("WARN: 未找到 test-b.html 标签，附着到:", pages[0].get("url", "")[:80])
                break
        except Exception as e:
            if attempt % 10 == 0:
                print(f"tabs@{attempt}s: 端口未就绪 ({type(e).__name__})")
        time.sleep(1)
    if not ws_url:
        print("FATAL: 拿不到页面 WS 地址")
        sys.exit(1)

    ws = websocket.create_connection(ws_url, timeout=10)
    mid = 0

    def send(method, params=None):
        global mid
        mid += 1
        ws.send(json.dumps({"id": mid, "method": method, "params": params or {}}))
        return mid

    def drain(seconds, tag):
        """收集 seconds 秒内的 CDP 事件（console/异常/网络请求）"""
        end = time.time() + seconds
        ws.settimeout(0.5)
        while time.time() < end:
            try:
                msg = json.loads(ws.recv())
            except websocket.WebSocketTimeoutException:
                continue
            m = msg.get("method", "")
            if m == "Runtime.consoleAPICalled":
                args = [a.get("value", a.get("description", "")) for a in msg["params"]["args"]]
                print(f"[{tag}] console.{msg['params']['type']}:", *args, flush=True)
            elif m == "Runtime.exceptionThrown":
                d = msg["params"]["exceptionDetails"]
                txt = d.get("exception", {}).get("description", d.get("text", ""))
                print(f"[{tag}] JS EXCEPTION: {txt[:600]}", flush=True)
            elif m == "Network.requestWillBeSent":
                url = msg["params"]["request"]["url"]
                if "/kdj/" in url:
                    print(f"[{tag}] HTTP → {url}", flush=True)

    def probe(tag):
        """探测 JS 线程是否响应"""
        ws.settimeout(5)
        try:
            rid = send("Runtime.evaluate", {"expression": "1+1", "returnByValue": True})
            end = time.time() + 5
            while time.time() < end:
                msg = json.loads(ws.recv())
                if msg.get("id") == rid:
                    print(f"[{tag}] JS 线程响应正常 → {msg['result']['result'].get('value')}")
                    return True
        except Exception as e:
            pass
        print(f"[{tag}] JS 线程无响应（5s 超时）→ 疑似死循环")
        return False

    send("Runtime.enable")
    send("Page.enable")
    send("Network.enable")
    print("== 页面加载中（日线 + 自动选中 600030 出图）==")
    drain(8, "daily")
    probe("daily")

    print("== 等待 harness 切季线（约 2s 后）+ 观察 15s ==")
    drain(15, "q3")
    probe("q3")

    # 若在死循环中，采样 JS 调用栈
    print("== 尝试抓取调用栈 ==")
    ws.settimeout(3)
    try:
        send("Debugger.enable")
        send("Debugger.pause")
        end = time.time() + 5
        while time.time() < end:
            msg = json.loads(ws.recv())
            if msg.get("method") == "Debugger.paused":
                frames = msg["params"]["callFrames"][:8]
                for f in frames:
                    print(f"  {f['functionName'] or '(anon)'} @ {f['url'].split('/')[-1]}:{f['location']['lineNumber']}")
                break
    except Exception:
        print("  无法 pause（线程可能未在执行 JS 或被阻塞）")

    ws.close()
finally:
    if proc:
        proc.kill()
print("done")
