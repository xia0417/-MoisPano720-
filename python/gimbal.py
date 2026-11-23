#!/usr/bin/env python3
"""
Moispano720 / TL-6A 蓝牙云台 —— 命令行控制

用法：
    pip install bleak
    python gimbal.py scan
    python gimbal.py turn 90            # 右转 90°
    python gimbal.py turn -45 --speed 1 # 左转 45°，最慢档
    python gimbal.py home               # 按累计角度回中
    python gimbal.py status
    python gimbal.py hold               # 心跳保活，Ctrl+C 退出
    python gimbal.py raw "11 01 55 01 02 08 00 00 01 03 88 88 88 88 00 0D 0A"

协议见同仓库 PROTOCOL.md。核心：17 字节帧，先发唤醒帧、600ms 后发执行帧。
"""

import argparse
import asyncio
import json
import os
import sys
import time
from pathlib import Path

SVC = "00001910-0000-1000-8000-00805f9b34fb"
CH_W = "0000fff1-0000-1000-8000-00805f9b34fb"
CH_R = "0000fff4-0000-1000-8000-00805f9b34fb"
NAME_PREFIX = "TL-6A"

# 实测 毫秒/度。注意不是单调序：3 最快，2 最慢
MS_PER_DEG = {1: 105, 2: 164, 3: 31, 4: 80}
WAKE_DELAY_S = 0.6
DEFAULT_SPEED = 3

STATE_PATH = Path(os.path.expanduser("~")) / ".gimbal_state.json"


# --------------------------------------------------------------------------- 协议

def frame(deg_abs: float, right: bool, mode: int) -> bytes:
    """构造 17 字节指令帧。deg_abs 只取大小，方向由 right 决定。"""
    v = int(round(abs(deg_abs) / 360.0 * 4096.0))
    v = max(0, min(4096, v))
    mode = max(1, min(4, int(mode)))
    return bytes([
        0x11, 0x01, 0x55, 0x01, 0x02, 0x08,
        (v >> 8) & 0xFF, v & 0xFF,
        0x01 if right else 0x00,
        mode,
        0x88, 0x88, 0x88, 0x88,
        0x00, 0x0D, 0x0A,
    ])


def estimate_s(deg: float, mode: int) -> float:
    return WAKE_DELAY_S + abs(deg) * MS_PER_DEG.get(mode, 31) / 1000.0


# --------------------------------------------------------------------------- 状态

def load_state():
    try:
        return json.loads(STATE_PATH.read_text(encoding="utf-8"))
    except Exception:
        return {"accumulated": 0.0, "pan": {"dir": None}}


def save_state(st):
    try:
        STATE_PATH.write_text(json.dumps(st, ensure_ascii=False, indent=2), encoding="utf-8")
    except Exception as e:
        print(f"[warn] 状态没写进去：{e}", file=sys.stderr)


# --------------------------------------------------------------------------- BLE

def _bleak():
    try:
        from bleak import BleakClient, BleakScanner
    except ImportError:
        print("缺依赖：pip install bleak", file=sys.stderr)
        raise SystemExit(2)
    return BleakClient, BleakScanner


async def discover(timeout=8.0):
    _, BleakScanner = _bleak()
    return await BleakScanner.discover(timeout=timeout, return_adv=True)


def adv_name(adv, dev):
    """Windows 上 adv.local_name 常为空，依次回退。"""
    for v in (adv.local_name, getattr(adv, "complete_local_name", None),
              getattr(adv, "short_local_name", None), dev.name):
        if v:
            return v
    return ""


def pick_device(devs, name_prefix=NAME_PREFIX, mac=None):
    """从 discover() 结果里挑出云台；优先 MAC，其次名字前缀。"""
    for addr, (dev, adv) in devs.items():
        nm = adv_name(adv, dev)
        if mac and addr.upper() == mac.upper():
            return dev, nm
        if not mac and nm.startswith(name_prefix):
            return dev, nm
    return None, None


async def connect(args, timeout=8.0, tries=3):
    """扫描 + 连接；连不上重试（云台偶尔需要多试几次）。"""
    BleakClient, _ = _bleak()
    for i in range(tries):
        if args.mac:
            dev = args.mac
            nm = ""
        else:
            devs = await discover(timeout)
            dev, nm = pick_device(devs, args.name or NAME_PREFIX, args.mac)
            if not dev:
                print(f"[{i + 1}/{tries}] 没扫到 {args.name or NAME_PREFIX}，重试…", file=sys.stderr)
                continue
        try:
            cli = BleakClient(dev, timeout=timeout)
            await cli.connect()
            if cli.is_connected:
                print(f"已连接：{nm or dev}" + (f" ({dev})" if nm else ""), file=sys.stderr)
                return cli
        except Exception as e:
            print(f"[{i + 1}/{tries}] 连接失败：{e}", file=sys.stderr)
        await asyncio.sleep(1)
    print("连不上。检查：云台开关是否打开 / 别的 App 是否占着（BLE 独占）/ 是否已休眠（拨一下开关）",
          file=sys.stderr)
    raise SystemExit(1)


async def write_frame(cli, data: bytes):
    await cli.write_gatt_char(CH_W, data, response=False)  # 必须 without-response


async def do_turn(cli, deg: float, mode: int, wait: bool):
    """一次完整转向：唤醒帧 → 600ms → 执行帧。"""
    await write_frame(cli, frame(0, True, mode))
    await asyncio.sleep(WAKE_DELAY_S)
    await write_frame(cli, frame(abs(deg), deg >= 0, mode))
    if wait:
        await asyncio.sleep(estimate_s(deg, mode) - WAKE_DELAY_S + 0.3)


async def do_home(cli, mode=3, wait=True):
    st = load_state()
    acc = float(st.get("accumulated", 0.0))
    if abs(acc) < 3.0:
        print(f"累计 {acc:.1f}°，不用回中")
        return
    print(f"回中：反向 {acc:.1f}°（mode {mode}）")
    await do_turn(cli, -acc, mode, wait)
    st["accumulated"] = 0.0
    save_state(st)


# --------------------------------------------------------------------------- 命令

async def cmd_scan(args):
    devs = await discover(args.scan_time)
    if not devs:
        print("没扫到任何 BLE 设备。检查蓝牙是否打开。")
        return
    print(f"{'地址':<20} {'RSSI':>5}  名称")
    for addr, (dev, adv) in sorted(devs.items(), key=lambda kv: -(kv[1][1].rssi or -999)):
        nm = adv_name(adv, dev)
        mark = "  ← 云台" if nm.startswith(args.name or NAME_PREFIX) else ""
        if args.only and not mark:
            continue
        print(f"{addr:<20} {str(adv.rssi):>5}  {nm}{mark}")


async def cmd_turn(args):
    cli = await connect(args)
    try:
        st = load_state()
        mode = args.speed
        print(f"转向 {args.deg:+.1f}°（mode {mode}，约 {estimate_s(args.deg, mode):.1f}s）")
        await do_turn(cli, args.deg, mode, wait=not args.no_wait)
        st["accumulated"] = float(st.get("accumulated", 0.0)) + args.deg
        save_state(st)
        print(f"累计 {st['accumulated']:+.1f}°")
    finally:
        await cli.disconnect()


async def cmd_home(args):
    cli = await connect(args)
    try:
        await do_home(cli, args.speed, wait=not args.no_wait)
    finally:
        await cli.disconnect()


async def cmd_status(args):
    st = load_state()
    print(json.dumps(st, ensure_ascii=False, indent=2))
    print(f"提示：累计角度仅本机记账（{STATE_PATH}），不代表真实朝向")


async def cmd_hold(args):
    cli = await connect(args)
    interval = args.interval
    print(f"心跳保活：每 {interval}s 发一帧零偏移（不会动）。Ctrl+C 退出。")
    try:
        while True:
            await write_frame(cli, frame(0, True, 1))
            await asyncio.sleep(interval)
    except KeyboardInterrupt:
        pass
    finally:
        await cli.disconnect()


async def cmd_raw(args):
    data = bytes(int(x, 16) for x in args.hex.replace(",", " ").split())
    if len(data) != 17:
        print(f"帧长 {len(data)}，协议要求 17 字节", file=sys.stderr)
        raise SystemExit(2)
    cli = await connect(args)
    try:
        await write_frame(cli, data)
        print("已写入：", data.hex(" ").upper())
    finally:
        await cli.disconnect()


# --------------------------------------------------------------------------- CLI

def build_parser():
    p = argparse.ArgumentParser(description="Moispano720 / TL-6A 蓝牙云台控制")
    p.add_argument("--mac", help="设备 MAC（不填则按名字扫描）")
    p.add_argument("--name", default=NAME_PREFIX, help=f"广播名前缀，默认 {NAME_PREFIX}")
    sub = p.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("scan", help="扫描附近的 BLE 设备")
    s.add_argument("--scan-time", type=float, default=8.0)
    s.add_argument("--only", action="store_true", help="只显示名字前缀匹配的设备")
    s.set_defaults(fn=cmd_scan)

    t = sub.add_parser("turn", help="转向：正=右，负=左")
    t.add_argument("deg", type=float)
    t.add_argument("--speed", type=int, default=DEFAULT_SPEED, choices=[1, 2, 3, 4])
    t.add_argument("--no-wait", action="store_true", help="不等它转完就返回")
    t.set_defaults(fn=cmd_turn)

    h = sub.add_parser("home", help="按累计角度回中")
    h.add_argument("--speed", type=int, default=3, choices=[1, 2, 3, 4])
    h.add_argument("--no-wait", action="store_true")
    h.set_defaults(fn=cmd_home)

    sub.add_parser("status", help="查看本机记账的累计角度").set_defaults(fn=cmd_status)

    k = sub.add_parser("hold", help="心跳保活（防休眠）")
    k.add_argument("--interval", type=float, default=20.0)
    k.set_defaults(fn=cmd_hold)

    r = sub.add_parser("raw", help="直接注入一帧（调试）")
    r.add_argument("hex", help="17 字节，空格分隔")
    r.set_defaults(fn=cmd_raw)
    return p


def main():
    args = build_parser().parse_args()
    try:
        asyncio.run(args.fn(args))
    except KeyboardInterrupt:
        print("\n已退出")


if __name__ == "__main__":
    main()
