#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image


LAUNCHER_SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

ADAPTIVE_SIZES = {
    "mipmap-mdpi": 108,
    "mipmap-hdpi": 162,
    "mipmap-xhdpi": 216,
    "mipmap-xxhdpi": 324,
    "mipmap-xxxhdpi": 432,
}

BACKGROUND_COLOR = (12, 15, 16, 255)
FOREGROUND_SCALE = 0.80
MONOCHROME_SCALE = 0.55


def render_contain(
    image: Image.Image,
    canvas_size: int,
    scale: float = 1.0,
    background=(255, 255, 255, 0),
) -> Image.Image:
    canvas = Image.new("RGBA", (canvas_size, canvas_size), background)
    target_size = int(canvas_size * scale)
    fitted = image.copy()
    fitted.thumbnail((target_size, target_size), Image.Resampling.LANCZOS)
    x = (canvas_size - fitted.width) // 2
    y = (canvas_size - fitted.height) // 2
    canvas.alpha_composite(fitted, (x, y))
    return canvas


def build_monochrome_mask(source: Image.Image) -> Image.Image:
    rgba = source.load()
    monochrome = Image.new("RGBA", source.size, (255, 255, 255, 0))
    mono_pixels = monochrome.load()

    for y in range(source.height):
        for x in range(source.width):
            r, g, b, a = rgba[x, y]
            if a == 0:
                continue

            luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
            if g > 110 and g > r + 25 and g > b + 20 and luminance > 70:
                mono_pixels[x, y] = (255, 255, 255, 255)

    bbox = monochrome.getbbox()
    if bbox is None:
        raise RuntimeError("Unable to derive monochrome launcher icon from source logo")

    return monochrome.crop(bbox)


def generate(source_path: Path, res_root: Path) -> None:
    source = Image.open(source_path).convert("RGBA")
    monochrome_source = build_monochrome_mask(source)

    for density, size in LAUNCHER_SIZES.items():
        output = render_contain(source, size)
        output.save(res_root / density / "ic_launcher.png")

    for density, size in ADAPTIVE_SIZES.items():
        background = Image.new("RGBA", (size, size), BACKGROUND_COLOR)
        background.save(res_root / density / "ic_launcher_background.png")

        foreground = render_contain(source, size, scale=FOREGROUND_SCALE)
        foreground.save(res_root / density / "ic_launcher_foreground.png")

        monochrome = render_contain(monochrome_source, size, scale=MONOCHROME_SCALE)
        monochrome.save(res_root / density / "ic_launcher_monochrome.png")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate Android launcher icons from the versioned project logo"
    )
    parser.add_argument(
        "--source",
        default="branding/logo.png",
        type=Path,
        help="Path to the versioned source logo asset (default: branding/logo.png)",
    )
    parser.add_argument(
        "--res-root",
        default="core/main/src/main/res",
        type=Path,
        help="Android resource root containing mipmap-* directories",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    generate(args.source.resolve(), args.res_root.resolve())
    print(f"Generated launcher icons from {args.source}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
