#!/bin/bash
# Bring up: weston (VNC backend, no TLS) + Xwayland, at 1600x1000.
# Needed because the sandbox emulates setgid as ENOSYS, which breaks
# Xorg's posix_spawn of xkbcomp; fixuid.so shims setgid/setuid to success.
set -u
R="/root/mi wps/rootfs"
export PATH=/usr/bin:/bin
export HOME=/tmp
export XDG_RUNTIME_DIR=/tmp/xdg
export LD_PRELOAD="/tmp/fixuid.so:/opt/dshbox/libdshbox-link.so"
mkdir -p "$XDG_RUNTIME_DIR"; chmod 700 "$XDG_RUNTIME_DIR"

pkill -f "office6/" 2>/dev/null
pkill -f weston 2>/dev/null
sleep 2

setsid nohup weston \
  --config=/tmp/wvnc.ini \
  --disable-transport-layer-security \
  --log=/tmp/wv.log </dev/null >/tmp/wv.out 2>&1 &

for i in $(seq 1 30); do
  sleep 1
  if DISPLAY=:0 xdpyinfo >/dev/null 2>&1; then
    echo "X ready after ${i}s"
    DISPLAY=:0 xdpyinfo 2>/dev/null | grep dimensions
    exit 0
  fi
done
echo "X did NOT come up in 30s"
tail -5 /tmp/wv.log 2>/dev/null
exit 1
