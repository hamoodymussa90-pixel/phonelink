#!/usr/bin/env python3
"""Protocol smoke test — pretends to be the Android head unit.

Connects to the fake iPhone server, sends a track, expects an artwork header
plus a binary JPEG frame, and checks a few GPS messages. Exits 0 on success.

Usage:  python fake_headunit_client.py [ws://127.0.0.1:8777]
"""

import asyncio
import json
import sys


async def main(url: str) -> int:
    import websockets
    got_artwork = False
    gps_count = 0

    async with websockets.connect(url, max_size=4 * 1024 * 1024) as ws:
        await ws.send(json.dumps({
            "type": "track", "seq": 1,
            "title": "Get Lucky", "artist": "Daft Punk",
            "album": "Random Access Memories", "durationMs": 369000,
        }))
        pending_header = None
        try:
            async with asyncio.timeout(30):
                while not (got_artwork and gps_count >= 3):
                    raw = await ws.recv()
                    if isinstance(raw, bytes):
                        assert pending_header is not None, "binary frame without header"
                        assert len(raw) == pending_header["bytes"], "size mismatch"
                        assert raw[:2] == b"\xff\xd8", "not a JPEG"
                        with open("artwork_test.jpg", "wb") as f:
                            f.write(raw)
                        print(f"OK artwork: {len(raw)} bytes JPEG (saved artwork_test.jpg), "
                              f"source={pending_header['source']}")
                        got_artwork = True
                        pending_header = None
                        continue
                    msg = json.loads(raw)
                    t = msg.get("type")
                    if t == "artwork":
                        assert msg["seq"] == 1 and msg["status"] == "ok", f"artwork failed: {msg}"
                        pending_header = msg
                    elif t == "gps":
                        gps_count += 1
                        print(f"OK gps #{gps_count}: lat={msg['lat']} lon={msg['lon']} "
                              f"speed={msg['speedMps']} m/s heading={msg['headingDeg']}°")
                    elif t == "ping":
                        await ws.send('{"type":"pong"}')
        except TimeoutError:
            print("FAIL: timed out waiting for artwork/GPS")
            return 1

    print("SMOKE TEST PASSED")
    return 0


if __name__ == "__main__":
    url = sys.argv[1] if len(sys.argv) > 1 else "ws://127.0.0.1:8777"
    sys.exit(asyncio.run(main(url)))
