#!/usr/bin/env python3
"""
CallShare P2P - Official App Logo & Icon Generation Script
Takes UI/croped.png and generates all Android mipmap icon densities,
adaptive icon XML definitions, and high-res drawable resources.
"""

import os
from PIL import Image, ImageOps

def main():
    root_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    src_icon = os.path.join(root_dir, "UI", "croped.png")
    src_banner = os.path.join(root_dir, "UI", "banner.png")
    res_dir = os.path.join(root_dir, "android-app", "app", "src", "main", "res")

    if not os.path.exists(src_icon):
        raise FileNotFoundError(f"Source icon not found at {src_icon}")

    print(f"[+] Loading master brand icon: {src_icon}")
    img = Image.open(src_icon).convert("RGBA")

    # Mipmap densities mapping for standard Android launcher icons
    densities = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }

    # 1. Generate Mipmap densities for square and round icons
    for folder, size in densities.items():
        out_folder = os.path.join(res_dir, folder)
        os.makedirs(out_folder, exist_ok=True)

        # High-quality Lanczos resampling
        resized = img.resize((size, size), Image.Resampling.LANCZOS)

        # Standard launcher icon
        square_path = os.path.join(out_folder, "ic_launcher.png")
        resized.save(square_path, "PNG", optimize=True)

        # Round launcher icon
        round_path = os.path.join(out_folder, "ic_launcher_round.png")
        resized.save(round_path, "PNG", optimize=True)

        print(f"  -> Generated {folder}: {size}x{size}px (ic_launcher.png, ic_launcher_round.png)")

    # 2. Copy high-res croped.png and banner.png to res/drawable
    drawable_dir = os.path.join(res_dir, "drawable")
    os.makedirs(drawable_dir, exist_ok=True)

    dest_croped = os.path.join(drawable_dir, "croped.png")
    img.save(dest_croped, "PNG", optimize=True)
    print(f"[+] Saved master drawable: {dest_croped}")

    if os.path.exists(src_banner):
        banner_img = Image.open(src_banner).convert("RGBA")
        dest_banner = os.path.join(drawable_dir, "banner.png")
        banner_img.save(dest_banner, "PNG", optimize=True)
        print(f"[+] Saved master banner drawable: {dest_banner}")

    # 3. Create high-res foreground layer for adaptive icon (432x432 with 72px padding)
    foreground_size = 432
    padding = 54
    inner_size = foreground_size - (padding * 2)
    foreground_img = Image.new("RGBA", (foreground_size, foreground_size), (0, 0, 0, 0))
    resized_inner = img.resize((inner_size, inner_size), Image.Resampling.LANCZOS)
    foreground_img.paste(resized_inner, (padding, padding), resized_inner)

    dest_foreground = os.path.join(drawable_dir, "ic_launcher_foreground.png")
    foreground_img.save(dest_foreground, "PNG", optimize=True)
    print(f"[+] Generated Adaptive Icon Foreground: {dest_foreground} ({foreground_size}x{foreground_size}px)")

    # 4. Generate Adaptive Icon XMLs for API 26+ (mipmap-anydpi-v26)
    anydpi_dir = os.path.join(res_dir, "mipmap-anydpi-v26")
    os.makedirs(anydpi_dir, exist_ok=True)

    adaptive_xml_content = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/black" />
    <foreground android:drawable="@drawable/croped" />
</adaptive-icon>
"""
    with open(os.path.join(anydpi_dir, "ic_launcher.xml"), "w", encoding="utf-8") as f:
        f.write(adaptive_xml_content)

    with open(os.path.join(anydpi_dir, "ic_launcher_round.xml"), "w", encoding="utf-8") as f:
        f.write(adaptive_xml_content)

    print(f"[+] Generated Adaptive XMLs in mipmap-anydpi-v26/ (ic_launcher.xml, ic_launcher_round.xml)")
    print("[✓] Android app icons generated successfully!")

if __name__ == "__main__":
    main()
