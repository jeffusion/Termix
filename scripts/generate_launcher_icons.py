#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path
from typing import cast

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
BACKGROUND_DISTANCE_THRESHOLD = 28


def get_rgba_pixel(image: Image.Image, x: int, y: int) -> tuple[int, int, int, int]:
    return cast(tuple[int, int, int, int], image.getpixel((x, y)))


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
    monochrome = Image.new("RGBA", source.size, (255, 255, 255, 0))

    edge_samples: list[tuple[int, int, int]] = []
    width = source.width
    height = source.height

    for x in range(width):
        for y in (0, height - 1):
            r, g, b, a = get_rgba_pixel(source, x, y)
            if a > 0:
                edge_samples.append((r, g, b))

    for y in range(height):
        for x in (0, width - 1):
            r, g, b, a = get_rgba_pixel(source, x, y)
            if a > 0:
                edge_samples.append((r, g, b))

    background_color: tuple[int, int, int] | None = None
    if edge_samples:
        red, green, blue = (
            round(sum(channel) / len(edge_samples)) for channel in zip(*edge_samples)
        )
        background_color = (red, green, blue)

    for y in range(height):
        for x in range(width):
            r, g, b, a = get_rgba_pixel(source, x, y)
            if a == 0:
                continue

            if background_color is None:
                monochrome.putpixel((x, y), (255, 255, 255, 255))
                continue

            color_distance = sum(
                abs(channel - background_channel)
                for channel, background_channel in zip((r, g, b), background_color)
            )
            if color_distance >= BACKGROUND_DISTANCE_THRESHOLD:
                monochrome.putpixel((x, y), (255, 255, 255, 255))

    bbox = monochrome.getbbox()
    if bbox is None:
        alpha_mask = source.getchannel("A")
        bbox = alpha_mask.getbbox()
        if bbox is None:
            raise RuntimeError(
                "Unable to derive monochrome launcher icon from source logo"
            )

        monochrome = Image.new("RGBA", source.size, (255, 255, 255, 0))
        monochrome.putalpha(alpha_mask)

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
