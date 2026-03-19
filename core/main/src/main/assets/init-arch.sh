#!/bin/sh
set -e

export PATH=/bin:/sbin:/usr/bin:/usr/sbin:/usr/local/bin:/usr/local/sbin:/system/bin:/system/xbin
export HOME=/root

mkdir -p /etc
if [ -L /etc/resolv.conf ]; then
    rm -f /etc/resolv.conf
fi

if [ ! -s /etc/resolv.conf ]; then
    rm -f /etc/resolv.conf
    printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > /etc/resolv.conf
fi

if [ -f /etc/pacman.conf ]; then
    sed -i 's/^#DisableSandbox/DisableSandbox/' /etc/pacman.conf || true
fi

ARCH_INIT_DONE=/etc/reterminal-arch-init.done
ARCH_INIT_LOCK=/etc/reterminal-arch-init.lock
ARCH_INIT_FAILED=/etc/reterminal-arch-init.failed
ARCH_INIT_LOG=/tmp/reterminal-arch-init.log

run_arch_init_in_foreground() {
    ARCH_INIT_RUNNER=/tmp/reterminal-arch-init.runner.sh

    cat > "$ARCH_INIT_RUNNER" <<'EOF'
#!/bin/sh
set +e

ARCH_INIT_DONE="$1"
ARCH_INIT_LOCK="$2"
ARCH_INIT_FAILED="$3"

keyring_ready=1

echo "[ReTerminal] Initializing Arch Linux keyring (first boot)..."
pacman-key --init || keyring_ready=0

case "$(uname -m)" in
    aarch64|armv7l|armv6l)
        pacman-key --populate archlinuxarm || keyring_ready=0
        ;;
    *)
        pacman-key --populate archlinux || keyring_ready=0
        ;;
esac

if [ -f /etc/locale.gen ]; then
    sed -i -E "s/^#\s*(en_US.UTF-8\s+UTF-8)/\1/" /etc/locale.gen || true
    locale-gen || true
fi

if [ "$keyring_ready" -eq 1 ]; then
    touch "$ARCH_INIT_DONE"
    rm -f "$ARCH_INIT_FAILED"
    echo "[ReTerminal] Arch keyring bootstrap completed."
else
    date +%s > "$ARCH_INIT_FAILED"
    echo "[ReTerminal] Arch keyring bootstrap failed; retry on next launch."
fi

rm -f "$ARCH_INIT_LOCK"
rm -f "$0"
EOF

    chmod 700 "$ARCH_INIT_RUNNER" || true

    if command -v tee >/dev/null 2>&1; then
        /bin/sh "$ARCH_INIT_RUNNER" "$ARCH_INIT_DONE" "$ARCH_INIT_LOCK" "$ARCH_INIT_FAILED" 2>&1 | tee "$ARCH_INIT_LOG"
    else
        /bin/sh "$ARCH_INIT_RUNNER" "$ARCH_INIT_DONE" "$ARCH_INIT_LOCK" "$ARCH_INIT_FAILED" > "$ARCH_INIT_LOG" 2>&1
        cat "$ARCH_INIT_LOG"
    fi

    if [ -f "$ARCH_INIT_LOCK" ] && [ ! -f "$ARCH_INIT_DONE" ] && [ ! -f "$ARCH_INIT_FAILED" ]; then
        date +%s > "$ARCH_INIT_FAILED"
        rm -f "$ARCH_INIT_LOCK"
    fi
}

if [ ! -f "$ARCH_INIT_DONE" ]; then
    while [ ! -f "$ARCH_INIT_DONE" ]; do
        if [ -f "$ARCH_INIT_LOCK" ]; then
            init_pid=$(cat "$ARCH_INIT_LOCK" 2>/dev/null || true)
            if [ -n "$init_pid" ] && kill -0 "$init_pid" 2>/dev/null; then
                echo "[ReTerminal] Arch first-boot setup already running; waiting for completion..."
                while [ -f "$ARCH_INIT_LOCK" ] && [ ! -f "$ARCH_INIT_DONE" ]; do
                    owner_pid=$(cat "$ARCH_INIT_LOCK" 2>/dev/null || true)
                    if [ -n "$owner_pid" ] && kill -0 "$owner_pid" 2>/dev/null; then
                        sleep 1
                    else
                        rm -f "$ARCH_INIT_LOCK"
                        break
                    fi
                done
                continue
            fi
            rm -f "$ARCH_INIT_LOCK"
        fi

        if ( set -C; printf '%s\n' "$$" > "$ARCH_INIT_LOCK" ) 2>/dev/null; then
            if [ -f "$ARCH_INIT_FAILED" ]; then
                echo "[ReTerminal] Previous setup attempt failed; retrying in foreground."
                rm -f "$ARCH_INIT_FAILED"
            fi

            echo "[ReTerminal] Arch first-boot setup is running in foreground."
            echo "[ReTerminal] Waiting for setup completion before entering shell..."
            run_arch_init_in_foreground
            break
        fi
    done

    if [ -f "$ARCH_INIT_DONE" ]; then
        echo "[ReTerminal] Arch first-boot setup completed."
    elif [ -f "$ARCH_INIT_FAILED" ]; then
        echo "[ReTerminal] Arch first-boot setup failed."
        echo "[ReTerminal] Setup log: $ARCH_INIT_LOG"
        echo "[ReTerminal] Resolve errors and restart session to retry."
        exit 1
    else
        echo "[ReTerminal] Arch first-boot setup did not finish correctly."
        echo "[ReTerminal] Setup log: $ARCH_INIT_LOG"
        echo "[ReTerminal] Resolve errors and restart session to retry."
        exit 1
    fi
fi

printf '\033]0;%s\007' "arch"

arch_bash_ps1="\[\e]0;\u@arch:\w\a\]\[\e[38;5;81m\]\u\[\033[39m\]@reterm \[\033[39m\]\w \[\033[0m\]\\$ "

reattach_tty_streams() {
    tty >/dev/null 2>&1 && return 0

    if [ -e /dev/tty ] && exec 0<>/dev/tty 1>&0 2>&0 && tty >/dev/null 2>&1; then
        return 0
    fi

    fd0_resolved=$(readlink -f /proc/self/fd/0 2>/dev/null || true)
    case "$fd0_resolved" in
        /dev/pts/*|/dev/tty*)
            if [ -e "$fd0_resolved" ] && exec 0<>"$fd0_resolved" 1>&0 2>&0 && tty >/dev/null 2>&1; then
                return 0
            fi
            ;;
    esac

    if [ -n "$RETERM_HOST_TTY" ] && [ -e "$RETERM_HOST_TTY" ] && exec 0<>"$RETERM_HOST_TTY" 1>&0 2>&0 && tty >/dev/null 2>&1; then
        return 0
    fi

    return 1
}

if [ ! -f /linkerconfig/ld.config.txt ]; then
    mkdir -p /linkerconfig
    touch /linkerconfig/ld.config.txt
fi

launch_interactive_shell() {
    set +e

    if [ -z "$RETERM_SHELL" ]; then
        RETERM_SHELL=/bin/sh
    fi

    shell_name=$(basename "$RETERM_SHELL")

    if [ "$shell_name" = "bash" ]; then
        if [ -z "$TERM" ] || [ "$TERM" = "dumb" ]; then
            export TERM=xterm-256color
        fi
        export PS1="$arch_bash_ps1"
    fi

    if [ -n "$RETERM_SHELL" ] && [ -x "$RETERM_SHELL" ]; then
        reattach_tty_streams || true
        case "$shell_name" in
            bash)
                exec "$RETERM_SHELL" -i
                ;;
            zsh|ksh)
                exec "$RETERM_SHELL" -il
                ;;
            sh|ash|dash)
                exec "$RETERM_SHELL" -i
                ;;
            *)
                exec "$RETERM_SHELL"
                ;;
        esac
        echo "[ReTerminal] Failed to exec RETERM_SHELL=$RETERM_SHELL, falling back..."
    fi

    exec /bin/sh -i
}

if [ "$#" -eq 0 ]; then
    cd "$HOME" || cd /
    launch_interactive_shell
else
    exec "$@"
fi
