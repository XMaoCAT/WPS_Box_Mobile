import struct, subprocess, re

def disasm(path):
    """返回 [(地址, 指令文本)]"""
    out = subprocess.run(['objdump','-d',path], capture_output=True, text=True).stdout
    rows = []
    for line in out.splitlines():
        m = re.match(r'\s*([0-9a-f]+):\t([0-9a-f]+)\s+\t?(.*)$', line)
        if m:
            rows.append((int(m.group(1),16), m.group(3).strip()))
    return rows

def find_sites(path, scno):
    """找 x8=scno 之后最近的一条 svc"""
    rows = disasm(path)
    sites = []
    cur_sc = None
    for addr, ins in rows:
        m = re.match(r'mov\s+x8,\s*#0x([0-9a-f]+)', ins)
        if m:
            cur_sc = int(m.group(1),16)
            continue
        if ins.startswith('svc') and cur_sc == scno:
            sites.append(addr)
            cur_sc = None
    return sites

def patch(path, scnos, dry=False):
    data = bytearray(open(path,'rb').read())
    total = 0
    detail = []
    for sc in scnos:
        for addr in find_sites(path, sc):
            if addr+4 > len(data): continue
            cur = struct.unpack('<I', data[addr:addr+4])[0]
            if cur == 0xd4000001:
                detail.append((sc, hex(addr)))
                if not dry:
                    data[addr:addr+4] = struct.pack('<I', 0xd503201f)
                total += 1
    if not dry:
        open(path,'wb').write(data)
    return total, detail
