#!/bin/bash
R="/root/mi wps/rootfs"
O6="$R/opt/kingsoft/wps-office/office6"
LP="$O6:$O6/addons/cef:$R/lib/aarch64-linux-gnu:$R/lib:$R/usr/lib/aarch64-linux-gnu"
export LD_LIBRARY_PATH="$LP"
export QT_PLUGIN_PATH="$O6/qt/plugins"
export QT_QPA_PLATFORM="${QT_QPA_PLATFORM:-offscreen}"
export HOME=/tmp/wpshome
exec "$R/lib/ld-linux-aarch64.so.1" --library-path "$LP" "$@"
