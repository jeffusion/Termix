# Arch Linux support research notes

This document captures the community references used before implementing initial Arch Linux support in ReTerminal.

## Similar projects and references

1. Termux `proot-distro` Arch plugin  
   https://github.com/termux/proot-distro/blob/master/distro-plugins/archlinux.sh

2. Termux `proot-distro` Arch build pipeline  
   https://github.com/termux/proot-distro/blob/master/distro-build/archlinux.sh

3. Arch Linux ARM downloads (official rootfs source)  
   https://archlinuxarm.org/about/downloads

4. `os-release` distro detection standard (`ID`, `ID_LIKE`)  
   https://www.freedesktop.org/software/systemd/man/os-release.html

5. `termux-arch` implementation reference  
   https://github.com/sdrausty/termux-arch

6. `jorexdeveloper/termux-arch` installer reference  
   https://github.com/jorexdeveloper/termux-arch/blob/main/install-arch.sh

## Key patterns adopted

- Use a dedicated Arch rootfs tarball for Android ABI targets (aarch64 / armv7).
- Keep the same proot launch pattern as existing Alpine flow (`--link2symlink`, `--sysvipc`, bind mounts).
- Add Arch-specific init scripts instead of overloading Alpine scripts.
- Configure pacman for proot compatibility on first boot:
  - enable `DisableSandbox` in `/etc/pacman.conf`
  - run `pacman-key --init`
  - populate keyring (`archlinuxarm` on ARM)
- Maintain mode-based session bootstrapping (Alpine/Android/Arch/Root variants) through `WorkingMode`.

## Scope for initial implementation

- Initial support targets Arch Linux ARM workflows on Android devices.
- x86_64 Arch mode is intentionally not enabled in downloader mapping yet.
- Packaging to AUR/PKGBUILD is out-of-scope for this Android app integration phase.
