"""Мини-CDP для WebView приложения на эмуляторе: выполнить JS и напечатать результат.

    python tools/cdp_eval.py "document.title"
    python tools/cdp_eval.py --file script.js
"""
import asyncio
import json
import sys
import urllib.request

import websockets


def target_ws():
    with urllib.request.urlopen("http://127.0.0.1:9222/json", timeout=10) as r:
        targets = json.load(r)
    pages = [t for t in targets if t.get("type") == "page"]
    if not pages:
        raise SystemExit("нет page-таргетов в /json")
    return pages[0]["webSocketDebuggerUrl"]


async def run(expression: str) -> None:
    ws_url = target_ws()
    async with websockets.connect(ws_url, max_size=8 * 1024 * 1024) as ws:
        await ws.send(json.dumps({
            "id": 1,
            "method": "Runtime.evaluate",
            "params": {"expression": expression, "awaitPromise": True, "returnByValue": True},
        }))
        while True:
            message = json.loads(await asyncio.wait_for(ws.recv(), timeout=30))
            if message.get("id") != 1:
                continue
            result = message.get("result", {})
            if "exceptionDetails" in result:
                print("EXCEPTION:", json.dumps(result["exceptionDetails"], ensure_ascii=False)[:1500])
                return
            value = result.get("result", {}).get("value")
            print(json.dumps(value, ensure_ascii=False, indent=2) if not isinstance(value, str) else value)
            return


if __name__ == "__main__":
    args = sys.argv[1:]
    if not args:
        raise SystemExit(__doc__)
    if args[0] == "--file":
        expr = open(args[1], encoding="utf-8").read()
    else:
        expr = args[0]
    asyncio.run(run(expr))
