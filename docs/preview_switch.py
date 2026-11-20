"""按 UiStyle.drawSwitchTrack / drawSwitchKnob 的参数离线栅格化，
用于在没有真机的情况下检查明暗关系是否成立（BlurMaskFilter 用高斯模糊近似）。"""
from PIL import Image, ImageDraw, ImageFont, ImageFilter

S = 6  # 超采样倍率
BG = (0xE6, 0xE9, 0xEF)
PRIMARY = (0x2F, 0x6B, 0xFF)


def lerp(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def px(v):
    return v * S


def rounded_mask(size, radius):
    m = Image.new('L', size, 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, size[0] - 1, size[1] - 1],
                                        radius=radius, fill=255)
    return m


def strokes(size, radius, stroke, color, blur, offset):
    layer = Image.new('RGBA', size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    d.rounded_rectangle([offset[0], offset[1], size[0] + offset[0], size[1] + offset[1]],
                        radius=radius, outline=color + (255,), width=round(stroke))
    layer = layer.filter(ImageFilter.GaussianBlur(blur * 0.8))
    # 只保留主体范围内的部分
    layer.putalpha(Image.composite(layer.getchannel('A'),
                                   Image.new('L', size, 0),
                                   rounded_mask(size, radius)))
    return layer


def vgrad(size, stops, radius):
    w, h = size
    grad = Image.new('RGB', (1, h))
    for y in range(h):
        t = y / max(1, h - 1)
        lo = 0.0
        for i in range(len(stops) - 1):
            p0, c0 = stops[i]
            p1, c1 = stops[i + 1]
            if p0 <= t <= p1:
                lo = (t - p0) / max(1e-6, p1 - p0)
                color = lerp(c0, c1, lo)
                break
        else:
            color = stops[-1][1]
        grad.putpixel((0, y), color)
    body = grad.resize(size)
    out = Image.new('RGBA', size, (0, 0, 0, 0))
    out.paste(body, (0, 0), rounded_mask(size, radius))
    return out


def track(fill):
    w, h = round(px(54)), round(px(32))
    r = h / 2
    body_c = lerp((0xD4, 0xDA, 0xE4), PRIMARY, fill)
    img = Image.new('RGBA', (w, h), (0, 0, 0, 0))

    img = Image.alpha_composite(img, vgrad(
        (w, h),
        [(0.0, lerp(body_c, (0, 0, 0), 0.10)),
         (0.46, body_c),
         (1.0, lerp(body_c, (0xFF, 0xFF, 0xFF), 0.14))],
        r))

    push = px(1)
    ring = px(2.6)
    dark = strokes((w, h), r, ring, (0x0F, 0x17, 0x2A), px(2), (-push, -push))
    dark.putalpha(dark.getchannel('A').point(lambda a: round(a * 0x4D / 255)))
    img = Image.alpha_composite(img, dark)
    light = strokes((w, h), r, ring, (0xFF, 0xFF, 0xFF), px(1.8), (push, push))
    light.putalpha(light.getchannel('A').point(lambda a: round(a * 0x9E / 255)))
    img = Image.alpha_composite(img, light)

    rim = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    rim_line = vgrad((w, h), [(0.0, (0x14, 0x14, 0x14)),
                              (0.45, (0x08, 0x08, 0x08)),
                              (1.0, (0xFF, 0xFF, 0xFF))], r)
    mask = Image.new('L', (w, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, w - 1, h - 1], radius=r,
                                           outline=255, width=max(1, round(px(1))))
    rim.paste(rim_line, (0, 0), mask)
    img = Image.alpha_composite(img, rim)
    return img


def knob():
    d = round(px(26))
    r = d / 2
    img = Image.new('RGBA', (d, d), (0, 0, 0, 0))
    shadow = Image.new('RGBA', (d, d), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).ellipse(
        [r - r * 0.95, r + px(1.6) - r * 0.95, r + r * 0.97, r + px(1.6) + r * 0.95],
        fill=(0x1B, 0x23, 0x33, 0x4D))
    img = Image.alpha_composite(img, shadow.filter(ImageFilter.GaussianBlur(px(3.2) * 0.8)))

    # 径向渐变：中心偏左上
    cx, cy, rad = r - r * 0.28, r - r * 0.42, r * 2.1
    sphere = Image.new('RGBA', (d, d), (0, 0, 0, 0))
    px_map = sphere.load()
    for y in range(d):
        for x in range(d):
            dist = ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5 / rad
            if dist <= 0.5:
                c = lerp((0xFF, 0xFF, 0xFF), (0xF7, 0xF9, 0xFC), dist / 0.5)
            elif dist <= 1.0:
                c = lerp((0xF7, 0xF9, 0xFC), (0xDD, 0xE4, 0xEE), (dist - 0.5) / 0.5)
            else:
                c = (0xDD, 0xE4, 0xEE)
            if (x - r) ** 2 + (y - r) ** 2 <= r * r:
                px_map[x, y] = c + (255,)
    img = Image.alpha_composite(img, sphere)

    ring = Image.new('RGBA', (d, d), (0, 0, 0, 0))
    ImageDraw.Draw(ring).ellipse([px(0.5), px(0.5), d - px(0.5), d - px(0.5)],
                                 outline=(0xFF, 0xFF, 0xFF, 0x8C), width=max(1, round(px(1))))
    return Image.alpha_composite(img, ring)


def compose(fill, checked, label):
    w, h = round(px(54)), round(px(32))
    pad = round(px(14))
    canvas = Image.new('RGBA', (w + pad * 2, h + pad * 2 + round(px(22))), BG + (255,))
    canvas.alpha_composite(track(fill), (pad, pad))
    kx = px(27) if checked else px(3)
    canvas.alpha_composite(knob(), (pad + round(kx), pad + round((h - px(26)) / 2)))
    if checked:
        d = ImageDraw.Draw(canvas)
        try:
            font = ImageFont.truetype('C:/Windows/Fonts/seguisym.ttf', round(px(13)))
        except Exception:
            font = ImageFont.load_default()
        d.text((pad + px(10), pad + px(9)), '✓', font=font, fill=(255, 255, 255, 255))
    d = ImageDraw.Draw(canvas)
    d.text((pad, pad + h + round(px(6))), label, fill=(0x56, 0x60, 0x72, 255))
    return canvas


off = compose(0.0, False, 'off')
on = compose(1.0, True, 'on')
gap = round(px(16))
out = Image.new('RGBA', (off.width + on.width + gap, max(off.height, on.height)), BG + (255,))
out.paste(off, (0, 0))
out.paste(on, (off.width + gap, 0))
out = out.resize((out.width // 3, out.height // 3), Image.LANCZOS)
out.save(r'D:\08_roms\WorkBuddy\2026-11-16-19-43-59\pilot-app\docs\switch-preview.png')
print('saved', out.size)
