#!/usr/bin/env python3
"""Fake iPhone companion server — protocol v1.

Stands in for the real iPhone app so the Android head unit can be developed
and tested without any iOS build. Implements:

  - receives {"type":"track",...}  -> real artwork lookup, replies with
    {"type":"artwork",...} JSON header + ONE binary frame (JPEG, 600x600).
    Lookup chain: YouTube Music (InnerTube, via ytmusicapi — matches
    YT-exclusive uploads/remixes) -> iTunes Search API fallback.
  - streams {"type":"gps",...} at 1 Hz (simulated car driving a loop ~60 km/h)
  - {"type":"ping"}/{"type":"pong"} keepalive

Usage:  pip install websockets ytmusicapi
        python fake_iphone_server.py [--port 8777] [--no-ytmusic]
"""

import argparse
import asyncio
import json
import math
import time
import urllib.parse
import urllib.request

PORT_DEFAULT = 8777

# Simple in-memory artwork cache: "artist|title" -> (format, bytes, source)
_cache: dict[str, tuple[str, bytes, str]] = {}

_ytmusic = None
USE_YTMUSIC = True


def _get_ytmusic():
    global _ytmusic
    if _ytmusic is None:
        from ytmusicapi import YTMusic
        _ytmusic = YTMusic()
    return _ytmusic


def _download(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=10) as r:
        return r.read()


def ytmusic_lookup(artist: str, title: str) -> bytes | None:
    """YouTube Music search (unofficial InnerTube API) -> jpeg bytes or None.
    Matches YT-exclusive uploads and remixes that iTunes doesn't have."""
    results = _get_ytmusic().search(f"{artist} {title}", filter="songs", limit=1)
    if not results:
        return None
    thumbs = results[0].get("thumbnails") or []
    if not thumbs:
        return None
    url = thumbs[-1]["url"]
    # googleusercontent thumbnails accept arbitrary size suffixes.
    if "googleusercontent.com" in url and "=w" in url:
        url = url.rsplit("=w", 1)[0] + "=w600-h600-l90-rj"
    return _download(url)


def itunes_lookup_img(artist: str, title: str) -> bytes | None:
    """iTunes Search API -> jpeg bytes or None."""
    term = urllib.parse.quote(f"{artist} {title}")
    url = f"https://itunes.apple.com/search?term={term}&entity=song&limit=1"
    data = json.loads(_download(url))
    results = data.get("results") or []
    art_url = results[0].get("artworkUrl100") if results else None
    if not art_url:
        return None
    return _download(art_url.replace("100x100", "600x600"))


def artwork_lookup(artist: str, title: str) -> tuple[str, bytes, str] | None:
    """Lookup chain: cache -> YouTube Music -> iTunes. Returns (mime, bytes, source)."""
    key = f"{artist}|{title}".lower()
    if key in _cache:
        mime, img, _ = _cache[key]
        return (mime, img, "cache")
    if USE_YTMUSIC:
        try:
            img = ytmusic_lookup(artist, title)
            if img:
                _cache[key] = ("image/jpeg", img, "ytmusic")
                return _cache[key]
        except Exception as e:
            print(f"[artwork] ytmusic lookup failed, falling back to iTunes: {e}")
    img = itunes_lookup_img(artist, title)
    if img:
        _cache[key] = ("image/jpeg", img, "itunes")
        return _cache[key]
    return None


def simulated_gps(t: float) -> dict:
    """A car driving a 1 km-radius circle at ~60 km/h near Riyadh."""
    base_lat, base_lon = 24.7136, 46.6753
    speed_mps = 16.7  # ~60 km/h
    radius_m = 1000.0
    omega = speed_mps / radius_m  # rad/s
    ang = omega * t
    lat = base_lat + (radius_m / 111_320.0) * math.sin(ang)
    lon = base_lon + (radius_m / (111_320.0 * math.cos(math.radians(base_lat)))) * math.cos(ang)
    heading = (math.degrees(ang) + 90.0) % 360.0
    return {
        "type": "gps",
        "ts": time.time(),
        "lat": round(lat, 6),
        "lon": round(lon, 6),
        "speedMps": round(speed_mps + 0.3 * math.sin(t / 5), 2),  # gentle jitter
        "headingDeg": round(heading, 1),
        "altitudeM": 612.0,
        "hAccM": 5.0,
    }


async def gps_stream(ws):
    t0 = time.monotonic()
    while True:
        await ws.send(json.dumps(simulated_gps(time.monotonic() - t0)))
        await asyncio.sleep(1.0)


async def ping_loop(ws):
    while True:
        await asyncio.sleep(10.0)
        await ws.send('{"type":"ping"}')


async def handle_track(ws, msg: dict):
    seq = msg.get("seq", -1)
    artist = msg.get("artist") or ""
    title = msg.get("title") or ""
    print(f"[track] seq={seq}  {artist} - {title}")
    try:
        result = await asyncio.to_thread(artwork_lookup, artist, title)
    except Exception as e:
        print(f"[artwork] lookup error: {e}")
        await ws.send(json.dumps({"type": "artwork", "seq": seq, "status": "error"}))
        return
    if result is None:
        print("[artwork] not found")
        await ws.send(json.dumps({"type": "artwork", "seq": seq, "status": "not_found"}))
        return
    mime, img, source = result
    header = {
        "type": "artwork", "seq": seq, "status": "ok",
        "format": mime, "bytes": len(img), "source": source,
    }
    await ws.send(json.dumps(header))
    await ws.send(img)  # binary frame
    print(f"[artwork] sent {len(img)} bytes for seq={seq} (source={source})")


async def handler(ws):
    peer = ws.remote_address
    print(f"[conn] head unit connected: {peer}")
    tasks = [asyncio.create_task(gps_stream(ws)), asyncio.create_task(ping_loop(ws))]
    try:
        async for raw in ws:
            if isinstance(raw, bytes):
                continue  # head unit never sends binary
            try:
                msg = json.loads(raw)
            except json.JSONDecodeError:
                continue
            mtype = msg.get("type")
            if mtype == "track":
                asyncio.create_task(handle_track(ws, msg))
            elif mtype == "ping":
                await ws.send('{"type":"pong"}')
            elif mtype == "pong":
                pass
    finally:
        for t in tasks:
            t.cancel()
        print(f"[conn] head unit disconnected: {peer}")


async def main():
    import websockets
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=PORT_DEFAULT)
    ap.add_argument("--no-ytmusic", action="store_true",
                    help="skip YouTube Music, use iTunes only")
    args = ap.parse_args()
    global USE_YTMUSIC
    USE_YTMUSIC = not args.no_ytmusic
    async with websockets.serve(handler, "0.0.0.0", args.port, max_size=4 * 1024 * 1024):
        print(f"Fake iPhone server listening on ws://0.0.0.0:{args.port}")
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())
