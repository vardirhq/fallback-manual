"""Add a picture: converts it to WebP, shrinks it under the size limit, and registers it.

Usage:
    python -m tools.add_image --id cpr-hand-position --file ~/Downloads/fig4-12.png \\
        --license public-domain --creator "US Department of the Army" \\
        --source "https://.../FM-21-76.pdf, figure 4-12" --not-ai-generated

Then put it in an article, on its own line directly under the step, bullet or paragraph it shows:
    ![Hands stacked on the center of the chest, arms straight](image:cpr-hand-position)
"""

from __future__ import annotations

import argparse
import io
import re
import sys
from pathlib import Path

import yaml

from tools.build_kb import IMAGE_LICENSES, IMAGE_MAX_BYTES, IMAGES_DIR, IMAGES_FILE, ROOT

ID_RE = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")
MAX_WIDTH = 1080


def to_webp(src: Path) -> bytes:
    from PIL import Image

    img = Image.open(src)
    img.load()
    if img.mode not in ("RGB", "RGBA"):
        img = img.convert("RGBA" if "transparency" in img.info or img.mode in ("LA", "P") else "RGB")
    width = min(img.width, MAX_WIDTH)
    while True:
        frame = img if img.width == width else img.resize((width, round(img.height * width / img.width)), Image.LANCZOS)
        for quality in (85, 75, 65, 55):
            buf = io.BytesIO()
            frame.save(buf, "WEBP", quality=quality, method=6)
            if buf.tell() <= IMAGE_MAX_BYTES:
                return buf.getvalue()
        if width <= 480:
            raise SystemExit(f"error: could not get {src.name} under {IMAGE_MAX_BYTES // 1024} KB. "
                             "Use a simpler picture (a line drawing works best).")
        width = int(width * 0.8)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--id", required=True, help="lowercase words joined by hyphens, e.g. cpr-hand-position")
    ap.add_argument("--file", required=True, type=Path, help="the picture to add (PNG, JPEG, WebP, GIF, ...)")
    ap.add_argument("--license", required=True, choices=sorted(IMAGE_LICENSES))
    ap.add_argument("--creator", required=True, help="who made it, e.g. 'US Department of the Army'")
    ap.add_argument("--source", required=True, help="where it came from: a URL, plus page or figure number")
    ap.add_argument("--not-ai-generated", action="store_true",
                    help="required: confirms the picture was not made by an AI image generator")
    args = ap.parse_args(argv)

    problems = []
    if not args.not_ai_generated:
        problems.append("pass --not-ai-generated to confirm the picture was not made by an AI. "
                        "AI-generated pictures are never allowed.")
    if not ID_RE.match(args.id):
        problems.append(f"id '{args.id}' is invalid: use lowercase words joined by hyphens")
    if not args.file.is_file():
        problems.append(f"{args.file} does not exist")
    data = yaml.safe_load(IMAGES_FILE.read_text(encoding="utf-8")) if IMAGES_FILE.exists() else {}
    entries = (data or {}).get("images") or []
    if any(e.get("id") == args.id for e in entries if isinstance(e, dict)):
        problems.append(f"a picture with id '{args.id}' already exists in {IMAGES_FILE.relative_to(ROOT)}")
    if problems:
        for p in problems:
            print(f"error: {p}", file=sys.stderr)
        return 1

    webp = to_webp(args.file)
    IMAGES_DIR.mkdir(parents=True, exist_ok=True)
    out = IMAGES_DIR / f"{args.id}.webp"
    out.write_bytes(webp)
    entries.append({"id": args.id, "file": out.name, "license": args.license, "creator": args.creator.strip(),
                    "source": args.source.strip(), "ai_generated": False})
    header = "".join(l + "\n" for l in IMAGES_FILE.read_text(encoding="utf-8").splitlines()
                     if l.startswith("#")) if IMAGES_FILE.exists() else ""
    IMAGES_FILE.write_text(header + "\n" + yaml.safe_dump({"images": entries}, sort_keys=False, allow_unicode=True),
                           encoding="utf-8")
    print(f"Added {out.relative_to(ROOT)} ({len(webp) // 1024} KB).")
    print("Next: put it in an article, on its own line directly under the step, bullet or paragraph it shows:")
    print(f"  ![<what the picture shows>](image:{args.id})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
