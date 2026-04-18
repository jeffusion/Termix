# Branding Assets

`/branding/logo.png` is the version-controlled source logo asset for this repository.

## Rules

- Treat `branding/logo.png` as the single source of truth for the Android launcher logo.
- Do not hand-edit generated launcher resources under `core/main/src/main/res/mipmap-*`.
- Regenerate launcher resources with `make icons` after updating `branding/logo.png`.
- Rebuild a test package with `make debug-apk` or `make all`.

## Commands

```bash
make icons
make debug-apk
make all
```
