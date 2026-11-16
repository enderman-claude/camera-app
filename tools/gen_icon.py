"""usage: gen_icon.py <out.png> [commit-sha]  -> 432x432 camera logo launcher icon.

If a commit SHA is given, its first 7 characters are printed as one rotated line on the
empty left side of the camera body, rotated. Drawn at 4x and downsampled for smooth edges.
"""
import sys
from PIL import Image, ImageDraw, ImageFont

out = sys.argv[1]
sha = sys.argv[2][:7] if len(sys.argv) > 2 else ""
S = 432
K = 4                      # supersampling factor
W = S * K


def p(v):
    return int(v * K)


def font(size):
    for path in ("/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf",
                 "/usr/share/fonts/truetype/liberation/LiberationMono-Bold.ttf"):
        try:
            return ImageFont.truetype(path, p(size))
        except OSError:
            pass
    return ImageFont.load_default(size=p(size))


img = Image.new("RGBA", (W, W), (0, 0, 0, 0))
d = ImageDraw.Draw(img)

# background: rounded square, dark slate
d.rounded_rectangle([0, 0, W - 1, W - 1], radius=p(96), fill=(28, 30, 36, 255))

# camera body
d.rounded_rectangle([p(56), p(130), p(376), p(330)], radius=p(36), fill=(245, 245, 245, 255))

# divider line between the hash area and the lens
d.rounded_rectangle([p(117), p(150), p(120), p(312)], radius=p(2), fill=(120, 126, 136, 255))

# shutter button extruding from the top edge of the body, above the hash
# shutter button: rounded-rectangle white bump extruding above the top edge of the body
d.rounded_rectangle([p(86), p(118), p(121), p(140)], radius=p(8), fill=(245, 245, 245, 255))

# viewfinder bump on top
d.rounded_rectangle([p(150), p(96), p(262), p(146)], radius=p(14), fill=(245, 245, 245, 255))

# shutter button
d.ellipse([p(316), p(158), p(344), p(186)], fill=(232, 76, 61, 255))

# lens: outer ring, body, inner glass, highlight
cx, cy = S // 2, 232
for r, col in [
    (92, (60, 64, 72, 255)),
    (80, (24, 26, 30, 255)),
    (62, (38, 84, 130, 255)),
    (44, (20, 46, 78, 255)),
]:
    d.ellipse([p(cx - r), p(cy - r), p(cx + r), p(cy + r)], fill=col)

# glass highlight
d.ellipse([p(cx - 36), p(cy - 40), p(cx - 14), p(cy - 18)], fill=(255, 255, 255, 110))

# commit hash on the empty left side of the body, rotated to read bottom-to-top
if sha:
    f = font(18)
    ink = (60, 64, 72, 255)
    tmp = Image.new("RGBA", (p(120), p(30)), (0, 0, 0, 0))
    ImageDraw.Draw(tmp).text((0, 0), sha, font=f, fill=ink)
    rot = tmp.rotate(90, expand=True)
    # centre of the strip sits in the left margin, between the body edge and the lens
    x = p(84) - rot.width // 2
    y = p(232) - rot.height // 2
    img.alpha_composite(rot, (x, y))

img = img.resize((S, S), Image.LANCZOS)
img.save(out)
