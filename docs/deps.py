#!/usr/bin/env python3
"""Walk ELF NEEDED edges to compute the real dependency closure of WPS.

Uses readelf only (no ld.so), so the host loader is never involved.
"""
import subprocess, sys, os, collections

ROOT = "/root/mi wps/rootfs"
OFFICE = os.path.join(ROOT, "opt/kingsoft/wps-office/office6")
SYSLIB = os.path.join(ROOT, "lib/aarch64-linux-gnu")

# Libraries we deliberately take from the HOST instead of the rootfs,
# because pulling the rootfs glibc breaks everything (2.35 vs host 2.41).
HOST_OVERRIDE = {
    "libc.so.6", "libm.so.6", "libdl.so.2", "libpthread.so.0",
    "librt.so.1", "libresolv.so.2", "libnsl.so.1", "libutil.so.1",
    "ld-linux-aarch64.so.1", "libstdc++.so.6", "libgcc_s.so.1",
}


def needed(path):
    try:
        out = subprocess.run(["readelf", "-d", path], capture_output=True,
                             text=True, timeout=20).stdout
    except Exception:
        return []
    res = []
    for line in out.splitlines():
        if "(NEEDED)" in line and "[" in line:
            res.append(line.split("[")[1].split("]")[0])
    return res


def find(name, search):
    for d in search:
        p = os.path.join(d, name)
        if os.path.exists(p):
            return os.path.realpath(p)
    return None


def main():
    seeds = ["wps", "et", "wpp"]
    search = [OFFICE, os.path.join(OFFICE, "addons/cef"),
              os.path.join(OFFICE, "addons/kcef"), SYSLIB]
    seen, queue = {}, collections.deque()
    for s in seeds:
        p = os.path.join(OFFICE, s)
        if os.path.exists(p):
            queue.append(p)
    while queue:
        cur = queue.popleft()
        for n in needed(cur):
            if n in seen:
                continue
            if n in HOST_OVERRIDE:
                seen[n] = ("host", n)
                continue
            f = find(n, search)
            if f:
                seen[n] = ("rootfs", f)
                queue.append(f)
            else:
                seen[n] = ("MISSING", n)
    off = sysl = miss = host = 0
    for n, (kind, path) in sorted(seen.items()):
        if kind == "rootfs":
            if "office6" in path:
                off += 1
            else:
                sysl += 1
        elif kind == "host":
            host += 1
        else:
            miss += 1
    print("=== WPS 依赖闭包统计 ===")
    print("office6 自带库 :", off)
    print("rootfs 系统库  :", sysl)
    print("改用宿主库     :", host)
    print("缺失           :", miss)
    print()
    print("=== 需要随包分发的 rootfs 系统库清单 ===")
    for n, (kind, path) in sorted(seen.items()):
        if kind == "rootfs" and "office6" not in path:
            sz = os.path.getsize(path) / 1048576
            print("  %-28s %8.2f MiB" % (n, sz))
    print()
    print("=== 缺失 ===")
    for n, (kind, path) in sorted(seen.items()):
        if kind == "MISSING":
            print("  ", n)


main()
