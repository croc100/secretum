"""안경 펌웨어 단독 검증 도구 (폰 앱 없이 노트북에서).

  pip install bleak
  python tools/ble_probe.py capture --size xga --out frame.jpg   # 촬영 → 파일 저장, 전송 속도 출력
  python tools/ble_probe.py haptic YES                            # 햅틱 패턴 재생
  python tools/ble_probe.py buttons                               # 버튼 제스처 이벤트 모니터

프로토콜은 docs/PROTOCOL.md 참고.
"""
from __future__ import annotations

import argparse
import asyncio
import struct
import sys
import time
import zlib

from bleak import BleakClient, BleakScanner

SVC = "8b6e0001-5a3c-4d8e-9f21-6a1b2c3d4e5f"
CTRL = "8b6e0002-5a3c-4d8e-9f21-6a1b2c3d4e5f"
IMG = "8b6e0003-5a3c-4d8e-9f21-6a1b2c3d4e5f"
BTN = "8b6e0004-5a3c-4d8e-9f21-6a1b2c3d4e5f"

HAPTIC = {"ACK": 0x01, "READY": 0x02, "ERROR": 0x03, "YES": 0x04, "NO": 0x05, "UNSURE": 0x06}
SIZES = {"vga": 0, "svga": 1, "xga": 2}
GESTURE = {1: "SHORT", 2: "DOUBLE", 3: "LONG"}


async def find():
    print("scanning for secretum glasses...")
    dev = await BleakScanner.find_device_by_filter(
        lambda d, adv: SVC.lower() in [u.lower() for u in adv.service_uuids], timeout=15
    )
    if not dev:
        sys.exit("not found (전원/광고 상태 확인)")
    print(f"found {dev.name} {dev.address}")
    return dev


async def cmd_capture(size: str, quality: int, out: str):
    dev = await find()
    done = asyncio.Event()
    st: dict = {}

    def on_img(_, data: bytearray):
        t = data[0]
        if t == 0xA0:
            st.update(id=data[1], len=struct.unpack_from("<I", data, 2)[0], crc=struct.unpack_from("<I", data, 6)[0],
                      buf=bytearray(), next=0, t0=time.monotonic())
        elif t == 0xA1:
            idx = struct.unpack_from("<H", data, 2)[0]
            if idx != st["next"]:
                print(f"chunk order error: got {idx}, want {st['next']}")
            st["buf"] += data[4:]
            st["next"] += 1
        elif t == 0xA2:
            st["t1"] = time.monotonic()
            done.set()
        elif t == 0xAF:
            st["err"] = data[2] if len(data) > 2 else -1
            done.set()

    async with BleakClient(dev) as c:
        print(f"connected, mtu={c.mtu_size}")
        await c.start_notify(IMG, on_img)
        t_req = time.monotonic()
        await c.write_gatt_char(CTRL, bytes([0x01, SIZES[size], quality]), response=True)
        await asyncio.wait_for(done.wait(), 15)

    if "err" in st:
        sys.exit(f"glasses error code {st['err']}")
    buf = bytes(st["buf"])
    ok = len(buf) == st["len"] and zlib.crc32(buf) == st["crc"]
    dt = st["t1"] - st["t0"]
    print(f"{len(buf)} bytes, {st['next']} chunks, crc {'OK' if ok else 'MISMATCH'}")
    print(f"capture latency {st['t0'] - t_req:.2f}s, transfer {dt:.2f}s ({len(buf) / 1024 / dt:.1f} KB/s)")
    if ok:
        open(out, "wb").write(buf)
        print(f"saved {out}")


async def cmd_haptic(name: str):
    dev = await find()
    async with BleakClient(dev) as c:
        await c.write_gatt_char(CTRL, bytes([0x02, HAPTIC[name]]), response=True)
        print(f"sent {name}")


async def cmd_buttons():
    dev = await find()
    async with BleakClient(dev) as c:
        await c.start_notify(BTN, lambda _, d: print(f"button {'AB'[d[0]]} {GESTURE.get(d[1], d[1])}"))
        print("press buttons (Ctrl+C to quit)")
        while True:
            await asyncio.sleep(1)


def main():
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("capture")
    c.add_argument("--size", choices=SIZES, default="vga")
    c.add_argument("--quality", type=int, default=12)
    c.add_argument("--out", default="frame.jpg")
    h = sub.add_parser("haptic")
    h.add_argument("pattern", choices=HAPTIC)
    sub.add_parser("buttons")
    a = p.parse_args()
    if a.cmd == "capture":
        asyncio.run(cmd_capture(a.size, a.quality, a.out))
    elif a.cmd == "haptic":
        asyncio.run(cmd_haptic(a.pattern))
    else:
        asyncio.run(cmd_buttons())


if __name__ == "__main__":
    main()
