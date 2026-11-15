#!/usr/bin/env python3
"""
CallShare P2P - Offline LAN Test Harness & Simulator
Run directly on your PC with standard Python (no Android Studio required).

Usage:
    python test_harness.py --discover         # Listen for beacons from phones on Wi-Fi
    python test_harness.py --simulate-phone   # Broadcast as a simulated phone beacon
    python test_harness.py --send-call <IP>   # Send simulated incoming call to target phone
"""

import sys
import time
import json
import socket
import struct
import argparse

DISCOVERY_PORT = 45781
SIGNALING_PORT = 45782
MEDIA_PORT = 45783
MULTICAST_GROUP = "239.255.255.250"

def get_local_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        # Doesn't need internet, just finds default outbound interface
        s.connect(('10.255.255.255', 1))
        ip = s.getsockname()[0]
    except Exception:
        ip = '127.0.0.1'
    finally:
        s.close()
    return ip

def run_discover():
    print(f"[*] Starting CallShare P2P Discovery Listener on port {DISCOVERY_PORT}...")
    local_ip = get_local_ip()
    print(f"[*] Local PC IP: {local_ip}")

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(('', DISCOVERY_PORT))

    # Join multicast group
    mreq = struct.pack("4sl", socket.inet_aton(MULTICAST_GROUP), socket.INADDR_ANY)
    try:
        sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
    except Exception as e:
        print(f"[!] Note: Multicast membership join warning: {e}")

    print("[*] Listening for beacon packets from Android phones... (Press Ctrl+C to stop)")
    while True:
        try:
            data, addr = sock.recvfrom(2048)
            try:
                beacon = json.loads(data.decode('utf-8'))
                print(f"\n[+] DISCOVERED PEER from {addr[0]}:")
                print(f"    - Device Name : {beacon.get('device_name')}")
                print(f"    - Device ID   : {beacon.get('device_id')}")
                print(f"    - Carrier     : {beacon.get('carrier_name')} (SIM Slot {beacon.get('sim_slot')})")
                print(f"    - Battery     : {beacon.get('battery_pct')}% (Charging: {beacon.get('is_charging')})")
                print(f"    - Signaling   : {addr[0]}:{beacon.get('signaling_port')}")
                print(f"    - Media Port  : {addr[0]}:{beacon.get('media_port')}")
            except Exception:
                print(f"[+] Raw packet received from {addr}: {data[:64]}...")
        except KeyboardInterrupt:
            print("\n[*] Stopping listener.")
            break

def run_simulate_phone(name="Simulated Pixel 8 Pro"):
    local_ip = get_local_ip()
    print(f"[*] Broadcasting simulated phone beacon as '{name}' from {local_ip}...")
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)

    beacon = {
        "device_id": "sim-pc-" + str(int(time.time())),
        "device_name": name,
        "ip": local_ip,
        "signaling_port": SIGNALING_PORT,
        "media_port": MEDIA_PORT,
        "sim_active": True,
        "sim_slot": 0,
        "carrier_name": "Test Cellular",
        "battery_pct": 98,
        "is_charging": True,
        "protocol_version": 1
    }
    payload = json.dumps(beacon).encode('utf-8')

    while True:
        try:
            sock.sendto(payload, ('255.255.255.255', DISCOVERY_PORT))
            sock.sendto(payload, (MULTICAST_GROUP, DISCOVERY_PORT))
            print(f"[*] Beacon broadcasted at {time.strftime('%X')}")
            time.sleep(1.0)
        except KeyboardInterrupt:
            print("\n[*] Broadcast stopped.")
            break

def send_simulated_call(target_ip, caller_number="+1 (555) 019-2834", caller_name="Boss"):
    print(f"[*] Sending simulated incoming call alert to {target_ip}:{SIGNALING_PORT}...")
    session_id = f"test-call-{int(time.time())}"
    msg = {
        "action": "CallIncoming",
        "data": {
            "session_id": session_id,
            "caller_number": caller_number,
            "caller_name": caller_name,
            "sim_slot": 0,
            "timestamp": int(time.time() * 1000)
        }
    }
    serialized = json.dumps(msg).encode('utf-8')
    prefix = struct.pack(">I", len(serialized))

    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(3.0)
    try:
        s.connect((target_ip, SIGNALING_PORT))
        s.sendall(prefix + serialized)
        print(f"[+] Call alert dispatched successfully! Session ID: {session_id}")
        print("[*] Target phone's Always-On Display should pulse with incoming call alert.")
    except Exception as e:
        print(f"[-] Failed to connect to {target_ip}:{SIGNALING_PORT}: {e}")
    finally:
        s.close()

def main():
    parser = argparse.ArgumentParser(description="CallShare P2P LAN Test Harness")
    parser.add_argument("--discover", action="store_true", help="Listen for phone discovery beacons on local Wi-Fi")
    parser.add_argument("--simulate-phone", action="store_true", help="Broadcast simulated phone beacon")
    parser.add_argument("--send-call", type=str, metavar="PHONE_IP", help="Send simulated incoming call to target phone IP")
    parser.add_argument("--caller-name", type=str, default="Boss", help="Caller display name for simulation")
    parser.add_argument("--caller-number", type=str, default="+1 (555) 019-2834", help="Caller phone number")

    args = parser.parse_args()

    if args.discover:
        run_discover()
    elif args.simulate_phone:
        run_simulate_phone()
    elif args.send_call:
        send_simulated_call(args.send_call, args.caller_number, args.caller_name)
    else:
        parser.print_help()

if __name__ == "__main__":
    main()
