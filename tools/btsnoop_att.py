#!/usr/bin/env python3
"""Print the ATT / SMP / link-level timeline from an Android Bluetooth HCI snoop log.

Usage: python3 -I tools/btsnoop_att.py <btsnoop file>

The file comes from `adb bugreport` after enabling "Bluetooth HCI snoop log" in Developer
options (under FS/data/misc/bluetooth/logs/, named btsnoop_hci.log or hci_snoop<date>.cfa).
It is btsnoop format with H4 framing. The output is one line per ATT request/response, SMP
message and LE connection event, enough to see what the Apple TV reads after it connects;
this is how the tvOS caching behaviour in CLAUDE.md was established. Snoop logs can contain
pairing keys: keep them out of the repository.
"""
import struct, sys, datetime

ATT = {0x01:"Error", 0x02:"MTU req", 0x03:"MTU rsp", 0x04:"FindInfo req", 0x05:"FindInfo rsp",
       0x06:"FindByTypeValue req", 0x07:"FindByTypeValue rsp", 0x08:"ReadByType req", 0x09:"ReadByType rsp",
       0x0A:"Read req", 0x0B:"Read rsp", 0x0C:"ReadBlob req", 0x0D:"ReadBlob rsp", 0x10:"ReadByGroupType req",
       0x11:"ReadByGroupType rsp", 0x12:"Write req", 0x13:"Write rsp", 0x52:"Write cmd", 0x1B:"Notification",
       0x1D:"Indication", 0x1E:"Confirmation", 0x18:"ExecWrite req", 0x16:"PrepWrite req"}
SMP = {1:"Pairing req",2:"Pairing rsp",3:"Confirm",4:"Random",5:"Failed",6:"Enc info",7:"Master id",8:"Identity info",9:"Identity addr",0x0A:"Signing info",0x0B:"Security req",0x0C:"Public key",0x0D:"DHKey check"}

def main(path, addr_filter=None):
    f = open(path, "rb"); hdr = f.read(16)
    assert hdr[:8] == b"btsnoop\0", "not a btsnoop file"
    ver, dl = struct.unpack(">II", hdr[8:])
    epoch = datetime.datetime(1, 1, 1)
    while True:
        rec = f.read(24)
        if len(rec) < 24: break
        olen, ilen, flags, drops, ts = struct.unpack(">IIIIq", rec)
        data = f.read(ilen)
        t = (epoch + datetime.timedelta(microseconds=ts)).strftime("%H:%M:%S.%f")[:-3]
        direction = "<-" if flags & 1 else "->"   # 1 = received from controller
        if dl == 1002:  # H4: packet type byte first
            ptype, data = data[0], data[1:]
        else:
            ptype = 2 if (flags & 2) == 0 else 4
        if ptype == 4:  # HCI event
            code, plen = data[0], data[1]
            p = data[2:2+plen]
            if code == 0x05:
                print(f"{t} {direction} HCI Disconnection complete handle={p[1]|(p[2]<<8):#x} reason={p[3]:#x}")
            elif code == 0x08:
                print(f"{t} {direction} HCI Encryption change status={p[0]} enabled={p[3]}")
            elif code == 0x3E and p[0] in (0x01, 0x0A):
                role = "central" if p[3] == 0 else "peripheral"
                peer = ":".join(f"{b:02X}" for b in p[5:11][::-1])
                print(f"{t} {direction} HCI LE connection complete status={p[1]} handle={p[2]|(p[3+0]<<8) if False else p[2]|(p[3]<<8):#x} role={role} peer={peer}")
            continue
        if ptype != 2: continue
        handle = struct.unpack("<H", data[0:2])[0] & 0x0FFF
        pb = (struct.unpack("<H", data[0:2])[0] >> 12) & 3
        if pb == 1: continue  # continuation fragment
        l2len, cid = struct.unpack("<HH", data[4:8])
        payload = data[8:8+l2len]
        if not payload: continue
        op = payload[0]
        if cid == 0x0004:
            name = ATT.get(op, f"ATT {op:#x}")
            detail = ""
            if op in (0x04, 0x08, 0x10) and len(payload) >= 5:
                s, e = struct.unpack("<HH", payload[1:5]); detail = f"range {s:#x}-{e:#x}"
                if op in (0x08, 0x10) and len(payload) >= 7: detail += f" uuid {struct.unpack('<H', payload[5:7])[0]:#06x}"
            elif op in (0x0A, 0x0C, 0x12, 0x52, 0x1B, 0x1D) and len(payload) >= 3:
                detail = f"handle {struct.unpack('<H', payload[1:3])[0]:#x}"
                if op in (0x12, 0x52, 0x1B, 0x1D): detail += " value " + payload[3:3+8].hex()
            elif op == 0x01 and len(payload) >= 5:
                detail = f"req {payload[1]:#x} handle {struct.unpack('<H', payload[2:4])[0]:#x} code {payload[4]:#x}"
            elif op == 0x11 and len(payload) >= 2:
                sz = payload[1]; items = payload[2:]
                groups = []
                for i in range(0, len(items) - sz + 1, sz):
                    s, e = struct.unpack("<HH", items[i:i+4]); u = items[i+4:i+sz]
                    groups.append(f"{s:#x}-{e:#x}:{u[::-1].hex() if len(u)>2 else struct.unpack('<H',u)[0]:#06x}" if len(u)==2 else f"{s:#x}-{e:#x}:{u[::-1].hex()}")
                detail = " ".join(groups)
            elif op == 0x09 and len(payload) >= 2:
                detail = f"len {payload[1]} n={ (len(payload)-2)//payload[1] }"
            print(f"{t} {direction} ATT {name} {detail}")
        elif cid == 0x0006:
            print(f"{t} {direction} SMP {SMP.get(op, op)}")

if __name__ == "__main__":
    main(sys.argv[1])
