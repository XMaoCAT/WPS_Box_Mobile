#!/usr/bin/env python3
"""Compute the minimal library set needed to run wps/et/wpp offline.

Strategy:
  - seed from the three binaries
  - follow NEEDED edges across office6/, addons/*, and rootfs system libs
  - glibc family is taken from the HOST (rootfs glibc 2.35 would break)
  - report what is required, what is merely present, and the size split
"""
import subprocess, os, collections

ROOT = "/root/mi wps/rootfs"
OFFICE = os.path.join(ROOT, "opt/kingsoft/wps-office/office6")
SYSLIB = os.path.join(ROOT, "lib/aarch64-linux-gnu")

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
    return [l.split("[")[1].split("]")[0] for l in out.splitlines()
            if "(NEEDED)" in l and "[" in l]


def search_dirs():
    dirs = [OFFICE, SYSLIB]
    for base in (os.path.join(OFFICE, "addons"), OFFICE):
        try:
            for e in sorted(os.listdir(base)):
                p = os.path.join(base, e)
                if os.path.isdir(p):
                    dirs.append(p)
        except OSError:
            pass
    return dirs


def find(name, dirs):
    for d in dirs:
        p = os.path.join(d, name)
        if os.path.exists(p):
            return p
    return None


def main():
    dirs = search_dirs()
    import glob as _g
    seeds = [os.path.join(OFFICE, s) for s in ("wps", "et", "wpp")]
    seeds += [f for f in _g.glob(os.path.join(OFFICE, "*.so"))]
    seeds = [s for s in seeds if os.path.exists(s)]

    seen = {}
    queue = collections.deque(seeds)
    for s in seeds:
        seen[os.path.basename(s)] = ("bin", s)

    while queue:
        cur = queue.popleft()
        for n in needed(cur):
            if n in seen:
                continue
            if n in HOST_OVERRIDE:
                seen[n] = ("host", n)
                continue
            f = find(n, dirs)
            if f:
                seen[n] = ("rootfs", f)
                queue.append(f)
            else:
                seen[n] = ("missing", n)

    groups = collections.defaultdict(list)
    for n, (kind, path) in seen.items():
        if kind == "rootfs":
            if path.startswith(OFFICE + "/addons/"):
                groups["addons"].append((n, path))
            elif path.startswith(OFFICE):
                groups["office6-核心"].append((n, path))
            else:
                groups["rootfs-系统库"].append((n, path))
        elif kind == "host":
            groups["改用宿主"].append((n, path))
        elif kind == "missing":
            groups["缺失"].append((n, path))

    print("=" * 62)
    print("WPS 三大组件（writer/sheets/slides）最小依赖闭包")
    print("=" * 62)
    total = 0
    for g in ("office6-核心", "addons", "rootfs-系统库", "改用宿主", "缺失"):
        items = groups.get(g, [])
        if not items:
            continue
        sz = 0
        if g not in ("改用宿主", "缺失"):
            sz = sum(os.path.getsize(p) for _, p in items)
            total += sz
        print("\n[%s] %d 个, %.0f MiB" % (g, len(items), sz / 1048576))
        if g in ("缺失",):
            for n, _ in sorted(items):
                print("     !", n)
        elif g == "改用宿主":
            print("     ", ", ".join(sorted(n for n, _ in items)))
        else:
            for n, p in sorted(items, key=lambda x: -os.path.getsize(x[1]))[:15]:
                print("     %7.1f MiB  %s" % (os.path.getsize(p) / 1048576, n))
            if len(items) > 15:
                print("     ... 另外 %d 个" % (len(items) - 15))

    print("\n" + "=" * 62)
    print("随包分发的实际体积: %.0f MiB (未压缩)" % (total / 1048576))
    print("按实测 28%% 压缩率推算: %.0f MiB" % (total / 1048576 * 0.28))


main()
