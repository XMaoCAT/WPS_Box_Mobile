import subprocess, os, re, shutil, sys

R = "/root/mi wps/rootfs"
DST = sys.argv[1] if len(sys.argv) > 1 else "/tmp/croot/display"
LIBDIRS = [R+"/lib/aarch64-linux-gnu", R+"/usr/lib/aarch64-linux-gnu",
           R+"/lib", R+"/usr/lib",
           R+"/lib/aarch64-linux-gnu/weston",
           R+"/usr/lib/aarch64-linux-gnu/libweston-9"]

os.makedirs(DST, exist_ok=True)

def needed(path):
    try:
        out = subprocess.run(['aarch64-linux-gnu-readelf', '-d', path],
                             capture_output=True, text=True).stdout
        return re.findall(r'NEEDED.*\[(.*?)\]', out)
    except Exception:
        return []

def find_lib(soname):
    """Find a real file that provides this soname anywhere in the rootfs."""
    for d in LIBDIRS:
        p = os.path.join(d, soname)
        if os.path.exists(p):
            return os.path.realpath(p)
    # fall back: search the whole tree for the soname
    for root, _, files in os.walk(R):
        if soname in files:
            return os.path.realpath(os.path.join(root, soname))
    return None

# seed with the binaries we know we need
seeds = [DST+"/weston", DST+"/Xwayland"]
for extra in ["libexec_weston.so.0.0.0", "rdp-backend.so", "xwayland.so",
              "desktop-shell.so"]:
    p = os.path.join(DST, extra)
    if os.path.exists(p):
        seeds.append(p)

resolved = {}     # soname -> real path
queue = list(seeds)
visited = set()
missing = []

while queue:
    f = queue.pop()
    if f in visited or not os.path.exists(f):
        continue
    visited.add(f)
    for soname in needed(f):
        if soname in resolved:
            continue
        real = find_lib(soname)
        if not real:
            missing.append((os.path.basename(f), soname))
            continue
        resolved[soname] = real
        queue.append(real)

# copy every resolved library under its soname, so the loader finds it
copied = 0
for soname, real in resolved.items():
    dst = os.path.join(DST, soname)
    if os.path.exists(dst):
        continue
    shutil.copy2(real, dst)
    copied += 1

print(f"resolved={len(resolved)} copied={copied}")
if missing:
    print("MISSING:", missing[:10])
