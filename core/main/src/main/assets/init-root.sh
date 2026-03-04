#!/system/bin/sh
# Root-based Alpine Linux launcher
# This script uses real root (su) to run proot with elevated privileges

ALPINE_DIR=$PREFIX/local/alpine
PROOT_BIN=$PREFIX/local/bin/proot
LIB_DIR=$PREFIX/local/lib
INIT_BIN=$PREFIX/local/bin/init

mkdir -p $ALPINE_DIR

if [ -z "$(ls -A "$ALPINE_DIR" | grep -vE '^(root|tmp)$')" ]; then
    echo "Extracting files..."
    tar -xf "$PREFIX/files/alpine.tar.gz" -C "$ALPINE_DIR"
fi

[ ! -e "$PROOT_BIN" ] && cp "$PREFIX/files/proot" "$PROOT_BIN"
chmod +x "$PROOT_BIN"

for sofile in "$PREFIX/files/"*.so.2; do
    dest="$LIB_DIR/$(basename "$sofile")"
    [ ! -e "$dest" ] && cp "$sofile" "$dest"
done

# Build proot arguments (matching old root mode behavior)
ARGS="--kill-on-exit"
ARGS="$ARGS -w /"

for system_mnt in /apex /odm /product /system /system_ext /vendor \
 /linkerconfig/ld.config.txt \
 /linkerconfig/com.android.art/ld.config.txt \
 /plat_property_contexts /property_contexts; do

 if [ -e "$system_mnt" ]; then
  system_mnt=$(realpath "$system_mnt")
  ARGS="$ARGS -b ${system_mnt}"
 fi
done
unset system_mnt

ARGS="$ARGS -b /sdcard"
ARGS="$ARGS -b /storage"
ARGS="$ARGS -b /dev"
ARGS="$ARGS -b /data"
ARGS="$ARGS -b /sys"
ARGS="$ARGS -b /dev/urandom:/dev/random"
ARGS="$ARGS -b /proc"

if [ ! -d "$ALPINE_DIR/tmp" ]; then
 mkdir -p "$ALPINE_DIR/tmp"
 chmod 1777 "$ALPINE_DIR/tmp"
fi
ARGS="$ARGS -b $ALPINE_DIR/tmp:/dev/shm"

ARGS="$ARGS -r $ALPINE_DIR"
ARGS="$ARGS -0"
ARGS="$ARGS --link2symlink"
ARGS="$ARGS --sysvipc"
ARGS="$ARGS -L"

# Use su to run proot with real root privileges
# Key differences from non-root mode:
# 1. Run proot directly without $LINKER (root has full execution permissions)
# 2. Don't set PROOT_LOADER* variables (those are for unprivileged proot)
# 3. Set LD_LIBRARY_PATH for proot's dependencies
# Note: /dev/stdin,stdout,stderr bindings are omitted for root mode because
#   su changes the process context, causing proot's realpath() on /proc/self/fd/*
#   to fail. These are unnecessary anyway since -b /dev and -b /proc already
#   provide /dev/stdin -> /proc/self/fd/0 mapping in the guest.
su -c "mkdir -p $PROOT_TMP_DIR && export LD_LIBRARY_PATH=$LIB_DIR && export PROOT_TMP_DIR=$PROOT_TMP_DIR && export TERM=xterm-256color && export LANG=C.UTF-8 && export HOME=/root && $PROOT_BIN $ARGS sh $INIT_BIN"
