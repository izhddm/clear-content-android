#!/usr/bin/env python3
"""Generate SYNTHETIC "AI-generator-like" media fixtures for Clear Content.

usage: make_fixtures.py OUT_DIR

Requires: Pillow (+AVIF), pillow-heif, ffmpeg/ffprobe on PATH (or FFMPEG/FFPROBE env),
ExifTool perl distribution (EXIFTOOL env, ./exiftool-master/exiftool, or
$CLEAR_CONTENT_TOOLS/exiftool-master/exiftool -- see setup_tools.sh).
Output is structurally reproducible but not byte-identical (Pillow noise is unseeded).
"""
import io
import json
import os
import random
import struct
import subprocess
import sys
import zlib

from PIL import Image, ImageCms, ImageDraw, ImageFilter, ImageFont
from PIL.PngImagePlugin import PngInfo

HERE = os.path.dirname(os.path.abspath(__file__))
_TOOLS = [HERE, os.environ.get('CLEAR_CONTENT_TOOLS') or os.path.expanduser('~/.cache/clear-content-tools')]
EXIFTOOL = os.environ.get('EXIFTOOL') or next(
    (os.path.join(d, 'exiftool-master', 'exiftool') for d in _TOOLS
     if os.path.exists(os.path.join(d, 'exiftool-master', 'exiftool'))), 'exiftool-master/exiftool')
FFMPEG = os.environ.get('FFMPEG', 'ffmpeg')
FFPROBE = os.environ.get('FFPROBE', 'ffprobe')

DST_TRAINED = 'http://cv.iptc.org/newscodes/digitalsourcetype/trainedAlgorithmicMedia'
DST_COMPOSITE = 'http://cv.iptc.org/newscodes/digitalsourcetype/compositeWithTrainedAlgorithmicMedia'
C2PA_BMFF_UUID = bytes.fromhex('d8fec3d61b0e483c92975828877ec481')
JUMBF_UUID_TAIL = bytes.fromhex('00110010800000aa00389b71')

random.seed(1234)


# --------------------------------------------------------------------------- helpers
def run(cmd, **kw):
    r = subprocess.run(cmd, capture_output=True, text=True, **kw)
    if r.returncode != 0:
        raise RuntimeError('command failed: %s\n%s\n%s' % (' '.join(cmd), r.stdout, r.stderr))
    return r.stdout


def exiftool(path, *args):
    return run(['perl', EXIFTOOL, '-q', '-overwrite_original', *args, path])


def srgb_icc():
    return ImageCms.ImageCmsProfile(ImageCms.createProfile('sRGB')).tobytes()


def font(size):
    try:
        return ImageFont.load_default(size=size)
    except Exception:  # noqa
        return ImageFont.load_default()


def photo_like(w, h, label='TOP-LEFT', seed=0, alpha=False):
    """Landscape-ish picture with an obvious 'up' direction (for orientation tests)."""
    rnd = random.Random(seed)
    img = Image.new('RGB', (w, h))
    d = ImageDraw.Draw(img)
    horizon = int(h * 0.62)
    for y in range(horizon):  # sky gradient
        t = y / max(1, horizon)
        d.line([(0, y), (w, y)], fill=(int(40 + 120 * t), int(90 + 110 * t), int(200 + 40 * t)))
    for y in range(horizon, h):  # ground gradient
        t = (y - horizon) / max(1, h - horizon)
        d.line([(0, y), (w, y)], fill=(int(60 + 60 * t), int(120 - 40 * t), int(40 + 10 * t)))
    d.ellipse([w * 0.72, h * 0.08, w * 0.72 + h * 0.18, h * 0.26], fill=(255, 230, 120))
    for k in range(3):  # mountains
        x0 = rnd.randint(-w // 4, w // 2)
        pk = rnd.randint(int(h * 0.25), int(h * 0.45))
        wd = rnd.randint(w // 3, w // 2)
        c = 70 + 25 * k
        d.polygon([(x0, horizon), (x0 + wd // 2, pk), (x0 + wd, horizon)], fill=(c, c + 10, c + 30))
    noise = Image.effect_noise((w, h), 28).convert('RGB')
    img = Image.blend(img, noise, 0.10).filter(ImageFilter.GaussianBlur(0.6))
    d = ImageDraw.Draw(img)
    f = font(max(12, h // 12))
    d.rectangle([4, 4, w // 2, h // 7 + 6], fill=(200, 30, 30))
    d.text((10, 6), label, fill=(255, 255, 255), font=f)
    # arrow pointing UP at the right side
    ax = int(w * 0.9)
    d.polygon([(ax, int(h * 0.35)), (ax - w // 20, int(h * 0.5)), (ax + w // 20, int(h * 0.5))], fill=(255, 255, 0))
    d.rectangle([ax - w // 60, int(h * 0.5), ax + w // 60, int(h * 0.75)], fill=(255, 255, 0))
    if alpha:
        a = Image.radial_gradient('L').resize((w, h)).point(lambda v: 255 - int(v * 0.8))
        img = img.convert('RGBA')
        img.putalpha(a)
    return img


def box(typ, payload):
    return struct.pack('>I', 8 + len(payload)) + typ + payload


def jumd(type4, label):
    return box(b'jumd', type4 + JUMBF_UUID_TAIL + b'\x03' + label.encode() + b'\x00')


def fake_jumbf_store(pad_to=0, generator='Fake AI Generator', odd=False):
    """A structurally plausible (NOT cryptographically valid) C2PA JUMBF manifest store."""
    actions = json.dumps({'actions': [{'action': 'c2pa.created', 'digitalSourceType': DST_TRAINED,
                                       'softwareAgent': {'name': generator}}]}).encode()
    act = box(b'jumb', jumd(b'json', 'c2pa.actions.v2') + box(b'json', actions))
    claim = json.dumps({'claim_generator': generator + ' c2pa-fake/0.0',
                        'dc:title': 'fake', 'signature': 'self#jumbf=c2pa.signature'}).encode()
    claim_box = box(b'jumb', jumd(b'c2cl', 'c2pa.claim.v2') + box(b'json', claim))
    sig = box(b'jumb', jumd(b'c2cs', 'c2pa.signature') + box(b'cbor', b'\xd2\x84' + b'\x00' * 64))
    astore = box(b'jumb', jumd(b'c2as', 'c2pa.assertions') + act)
    body = jumd(b'c2ma', 'urn:c2pa:00000000-0000-4000-8000-c1ea4c0de000') + astore + claim_box + sig

    def build(pad_len):
        b = body + (box(b'free', b'\x00' * pad_len) if pad_len is not None else b'')
        return box(b'jumb', jumd(b'c2pa', 'c2pa') + box(b'jumb', b))

    pad = None
    if pad_to:
        pad = max(0, pad_to - len(build(None)) - 8)
    store = build(pad)
    if odd != bool(len(store) & 1):  # flip parity with a 1-byte-longer padding box
        store = build((pad or 0) + 1)
    return store


def xmp_packet(creator_tool, dst=DST_TRAINED, extra=''):
    return ('<?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>\n'
            '<x:xmpmeta xmlns:x="adobe:ns:meta/">\n'
            ' <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">\n'
            '  <rdf:Description rdf:about=""\n'
            '    xmlns:xmp="http://ns.adobe.com/xap/1.0/"\n'
            '    xmlns:Iptc4xmpExt="http://iptc.org/std/Iptc4xmpExt/2008-02-29/"\n'
            '    xmlns:dc="http://purl.org/dc/elements/1.1/"\n'
            '    xmp:CreatorTool="%s"\n'
            '    Iptc4xmpExt:DigitalSourceType="%s">\n'
            '   <dc:description><rdf:Alt><rdf:li xml:lang="x-default">AI generated test image%s</rdf:li></rdf:Alt></dc:description>\n'
            '  </rdf:Description>\n'
            ' </rdf:RDF>\n'
            '</x:xmpmeta>\n'
            '<?xpacket end="w"?>' % (creator_tool, dst, extra)).encode('utf-8')


def exif_bytes(orientation=None, software=None, make=None, model=None, extra=None):
    ex = Image.Exif()
    if make:
        ex[0x010F] = make
    if model:
        ex[0x0110] = model
    if orientation is not None:
        ex[0x0112] = orientation
    if software:
        ex[0x0131] = software
    for k, v in (extra or {}).items():
        ex[k] = v
    return ex.tobytes()


def png_chunk(typ, data):
    return struct.pack('>I', len(data)) + typ + data + struct.pack('>I', zlib.crc32(typ + data) & 0xffffffff)


def png_insert_before(png, before_type, chunk_bytes):
    i = 8
    while i < len(png):
        ln, typ = struct.unpack('>I4s', png[i:i + 8])
        if typ == before_type:
            return png[:i] + chunk_bytes + png[i:]
        i += 12 + ln
    raise ValueError('chunk %r not found' % before_type)


def png_insert_after(png, after_type, chunk_bytes, last=False):
    i, pos = 8, None
    while i < len(png):
        ln, typ = struct.unpack('>I4s', png[i:i + 8])
        i += 12 + ln
        if typ == after_type:
            pos = i
            if not last:
                break
    return png[:pos] + chunk_bytes + png[pos:]


# --------------------------------------------------------------------------- JPEG
def jpeg_insert_after_app(jpg, segment):
    """Insert a raw marker segment after the last APPn segment preceding DQT/SOF."""
    i = 2
    last = 2
    while i < len(jpg):
        m = jpg[i + 1]
        if 0xE0 <= m <= 0xEF or m == 0xFE:
            ln = struct.unpack('>H', jpg[i + 2:i + 4])[0]
            i += 2 + ln
            if 0xE0 <= m <= 0xEF:
                last = i
        else:
            break
    return jpg[:last] + segment + jpg[last:]


def app11_jumbf(store):
    payload = b'JP' + struct.pack('>HI', 1, 1) + store
    assert len(payload) + 2 < 0xFFFF
    return b'\xff\xeb' + struct.pack('>H', len(payload) + 2) + payload


def make_jpegs(out):
    base = photo_like(800, 600, 'TOP-LEFT', seed=1)
    thumb = io.BytesIO()
    base.resize((160, 120)).save(thumb, 'JPEG', quality=70)
    thumb_path = os.path.join(out, '_thumb.jpg')
    open(thumb_path, 'wb').write(thumb.getvalue())

    common = [
        '-XMP-iptcExt:DigitalSourceType=' + DST_TRAINED,
        '-XMP-xmp:CreatorTool=Google AI',
        '-XMP-photoshop:Credit=Made with Google AI',
        '-XMP-dc:Description=A mountain landscape generated by AI',
        '-IPTC:CodedCharacterSet=UTF8',
        '-IPTC:Caption-Abstract=AI-generated landscape (Gemini)',
        '-IPTC:Credit=Made with Google AI',
        '-EXIF:Software=Gemini',
        '-EXIF:Make=Google',
        '-EXIF:Model=Pixel 9 Pro',
        '-EXIF:DateTimeOriginal=2025:08:26 12:00:00',
        '-EXIF:GPSLatitude=59.9386', '-EXIF:GPSLatitudeRef=N',
        '-EXIF:GPSLongitude=30.3141', '-EXIF:GPSLongitudeRef=E',
        '-EXIF:GPSAltitude=12', '-EXIF:GPSAltitudeRef=0',
        '-ThumbnailImage<=' + thumb_path,
        '-Comment=Generated by Nano Banana',
    ]
    res = {}
    # a) ai_xmp_iptc.jpg -------------------------------------------------------
    p = os.path.join(out, 'ai_xmp_iptc.jpg')
    base.save(p, 'JPEG', quality=90, icc_profile=srgb_icc())
    exiftool(p, *common, '-EXIF:Orientation#=6')
    trailer = b'\x00\x00TRAILING-GARBAGE c2pa-trailer JUMB\x00'
    trailer += bytes(random.randrange(256) for _ in range(64 - len(trailer)))
    assert len(trailer) == 64
    open(p, 'ab').write(trailer)
    res['ai_xmp_iptc.jpg'] = p

    # b) ai_orient1.jpg ---------------------------------------------------------
    p = os.path.join(out, 'ai_orient1.jpg')
    base.save(p, 'JPEG', quality=90, icc_profile=srgb_icc())
    exiftool(p, *common, '-EXIF:Orientation#=1')
    data = open(p, 'rb').read()
    data = jpeg_insert_after_app(data, app11_jumbf(fake_jumbf_store(pad_to=1500, generator='Gemini')))
    open(p, 'wb').write(data)
    res['ai_orient1.jpg'] = p

    # c) progressive.jpg (progressive + DRI/RST + XMP) --------------------------
    p = os.path.join(out, 'progressive.jpg')
    photo_like(640, 480, 'PROGRESSIVE', seed=2).save(
        p, 'JPEG', quality=85, progressive=True, restart_marker_blocks=16)
    exiftool(p, '-XMP-xmp:CreatorTool=Adobe Firefly',
             '-XMP-iptcExt:DigitalSourceType=' + DST_COMPOSITE,
             '-XMP-dc:Description=Generative fill test')
    res['progressive.jpg'] = p
    os.remove(thumb_path)
    return res


# --------------------------------------------------------------------------- PNG
A1111_PARAMS = ('a photo of an astronaut cat on the moon, highly detailed, 8k\n'
                'Negative prompt: blurry, lowres\n'
                'Steps: 20, Sampler: Euler a, CFG scale: 7, Seed: 1234567890, Size: 512x512, '
                'Model hash: 31e35c80fc, Model: sdxl, Version: v1.10.1')

COMFY_PROMPT = json.dumps({
    '3': {'class_type': 'KSampler', 'inputs': {'seed': 42, 'steps': 20, 'cfg': 7.0,
                                               'sampler_name': 'euler', 'model': ['4', 0]}},
    '4': {'class_type': 'CheckpointLoaderSimple', 'inputs': {'ckpt_name': 'sd_xl_base_1.0.safetensors'}},
    '6': {'class_type': 'CLIPTextEncode', 'inputs': {'text': 'a lighthouse at dusk', 'clip': ['4', 1]}},
})
COMFY_WORKFLOW = json.dumps({'last_node_id': 9, 'last_link_id': 9, 'version': 0.4,
                             'nodes': [{'id': 3, 'type': 'KSampler', 'pos': [863, 186]},
                                       {'id': 9, 'type': 'SaveImage', 'pos': [1451, 189],
                                        'widgets_values': ['ComfyUI']}]})


def make_pngs(out):
    res = {}
    p = os.path.join(out, 'ai_a1111.png')
    img = photo_like(512, 512, 'A1111', seed=3, alpha=True)
    info = PngInfo()
    info.add_text('parameters', A1111_PARAMS)                                  # tEXt
    info.add_itxt('XML:com.adobe.xmp', xmp_packet('Stable Diffusion web UI').decode('utf-8'))  # iTXt
    info.add(b'tIME', struct.pack('>HBBBBB', 2025, 8, 26, 12, 34, 56))        # tIME
    info.add(b'caBX', fake_jumbf_store(pad_to=1200, generator='OpenAI ChatGPT'))  # fake C2PA
    buf = io.BytesIO()
    img.save(buf, 'PNG', pnginfo=info, icc_profile=srgb_icc(),
             exif=exif_bytes(orientation=1, software='ComfyUI'))
    # zTXt Comment placed AFTER the last IDAT (legal for text chunks; Pillow only honours
    # after_idat for private chunks, so insert it by hand before IEND)
    ztxt = b'Comment\x00\x00' + zlib.compress(b'Generated with Stable Diffusion (AUTOMATIC1111)')
    data = png_insert_before(buf.getvalue(), b'IEND', png_chunk(b'zTXt', ztxt))
    trailer = b'\x00GARBAGE-AFTER-IEND parameters c2pa jumb\x00' + bytes(random.randrange(256) for _ in range(40))
    open(p, 'wb').write(data + trailer)
    res['ai_a1111.png'] = p

    p = os.path.join(out, 'ai_comfy_orient.png')
    img = photo_like(256, 192, 'COMFY', seed=4)
    info = PngInfo()
    info.add_text('prompt', COMFY_PROMPT)
    info.add_text('workflow', COMFY_WORKFLOW)
    img.save(p, 'PNG', pnginfo=info, exif=exif_bytes(orientation=6, software='ComfyUI'))
    res['ai_comfy_orient.png'] = p
    return res


# --------------------------------------------------------------------------- WebP
def riff_chunks(d):
    i, out = 12, []
    while i + 8 <= len(d):
        typ, ln = struct.unpack('<4sI', d[i:i + 8])
        out.append((typ, d[i + 8:i + 8 + ln]))
        i += 8 + ln + (ln & 1)
    return out


def riff_build(chunks):
    body = b'WEBP'
    for typ, data in chunks:
        body += typ + struct.pack('<I', len(data)) + data + (b'\x00' if len(data) & 1 else b'')
    return b'RIFF' + struct.pack('<I', len(body)) + body


def make_webps(out):
    res = {}
    p = os.path.join(out, 'ai_meta.webp')
    img = photo_like(640, 480, 'WEBP', seed=5)
    buf = io.BytesIO()
    img.save(buf, 'WEBP', quality=80, icc_profile=srgb_icc(),
             exif=exif_bytes(orientation=1, software='OpenAI', make='OpenAI', model='gpt-image-1'),
             xmp=xmp_packet('OpenAI ChatGPT'))
    chunks = riff_chunks(buf.getvalue())
    names = [c[0] for c in chunks]
    assert names[0] == b'VP8X', names
    # c2pa-rs appends the C2PA chunk at the end of the RIFF; do the same.
    c2pa_payload = fake_jumbf_store(pad_to=1800, generator='OpenAI ChatGPT', odd=True)
    assert len(c2pa_payload) & 1  # odd-sized chunk -> exercises RIFF pad byte handling
    chunks.append((b'C2PA', c2pa_payload))
    data = riff_build(chunks)
    open(p, 'wb').write(data)
    res['ai_meta.webp'] = p

    p = os.path.join(out, 'ai_lossless_alpha.webp')
    photo_like(320, 240, 'ALPHA', seed=6, alpha=True).save(
        p, 'WEBP', lossless=True, xmp=xmp_packet('Midjourney'))
    res['ai_lossless_alpha.webp'] = p

    p = os.path.join(out, 'ai_anim.webp')
    frames = [photo_like(160, 120, 'F%d' % k, seed=10 + k) for k in range(3)]
    frames[0].save(p, 'WEBP', save_all=True, append_images=frames[1:], duration=200, loop=0,
                   quality=70, exif=exif_bytes(orientation=1, software='Runway Gen-3'),
                   xmp=xmp_packet('Runway'))
    res['ai_anim.webp'] = p
    return res


# --------------------------------------------------------------------------- GIF
def make_gif(out):
    p = os.path.join(out, 'ai_anim.gif')
    frames = [photo_like(160, 120, 'G%d' % k, seed=20 + k).convert('P', palette=Image.Palette.ADAPTIVE)
              for k in range(3)]
    frames[0].save(p, 'GIF', save_all=True, append_images=frames[1:], duration=200, loop=0,
                   comment=b'Made with Midjourney')
    exiftool(p, '-XMP-xmp:CreatorTool=Midjourney v7',
             '-XMP-iptcExt:DigitalSourceType=' + DST_TRAINED)
    return {'ai_anim.gif': p}


# --------------------------------------------------------------------------- HEIC / AVIF
def make_heif(out):
    res = {}
    try:
        import pillow_heif
        pillow_heif.register_heif_opener()
        p = os.path.join(out, 'ai_heic.heic')
        photo_like(640, 480, 'HEIC', seed=30).save(
            p, 'HEIF', quality=60,
            exif=exif_bytes(orientation=1, software='Gemini', make='Google', model='Pixel 9 Pro'),
            xmp=xmp_packet('Google AI'))
        exiftool(p, '-XMP-photoshop:Credit=Made with Google AI',
                 '-EXIF:GPSLatitude=59.9386', '-EXIF:GPSLatitudeRef=N',
                 '-EXIF:GPSLongitude=30.3141', '-EXIF:GPSLongitudeRef=E')
        res['ai_heic.heic'] = p
    except Exception as e:  # noqa
        print('HEIC skipped:', e, file=sys.stderr)
    try:
        p = os.path.join(out, 'ai.avif')
        photo_like(640, 480, 'AVIF', seed=31).save(
            p, 'AVIF', quality=60, icc_profile=srgb_icc(),
            exif=exif_bytes(orientation=1, software='Adobe Firefly'),
            xmp=xmp_packet('Adobe Firefly', DST_COMPOSITE))
        res['ai.avif'] = p
    except Exception as e:  # noqa
        print('AVIF skipped:', e, file=sys.stderr)
    return res


# --------------------------------------------------------------------------- MP4 / MOV
def bmff_top(d):
    i, out = 0, []
    while i + 8 <= len(d):
        sz, typ = struct.unpack('>I4s', d[i:i + 8])
        hdr = 8
        if sz == 1:
            sz = struct.unpack('>Q', d[i + 8:i + 16])[0]
            hdr = 16
        elif sz == 0:
            sz = len(d) - i
        out.append((typ, i, sz, hdr))
        i += sz
    return out


def shift_chunk_offsets(d, moov_off, moov_size, delta, min_offset):
    """Add `delta` to every stco/co64 entry >= min_offset inside moov (in place)."""
    d = bytearray(d)
    count = 0
    containers = {b'moov', b'trak', b'mdia', b'minf', b'stbl'}

    def walk(start, end):
        nonlocal count
        i = start
        while i + 8 <= end:
            sz, typ = struct.unpack('>I4s', d[i:i + 8])
            hdr = 8
            if sz == 1:
                sz = struct.unpack('>Q', d[i + 8:i + 16])[0]
                hdr = 16
            if typ in containers:
                walk(i + hdr, i + sz)
            elif typ == b'stco':
                n = struct.unpack('>I', d[i + hdr + 4:i + hdr + 8])[0]
                for k in range(n):
                    o = i + hdr + 8 + 4 * k
                    v = struct.unpack('>I', d[o:o + 4])[0]
                    if v >= min_offset:
                        nv = v + delta
                        if nv > 0xFFFFFFFF:
                            raise OverflowError('stco overflow; would need co64')
                        d[o:o + 4] = struct.pack('>I', nv)
                        count += 1
            elif typ == b'co64':
                n = struct.unpack('>I', d[i + hdr + 4:i + hdr + 8])[0]
                for k in range(n):
                    o = i + hdr + 8 + 8 * k
                    v = struct.unpack('>Q', d[o:o + 8])[0]
                    if v >= min_offset:
                        d[o:o + 8] = struct.pack('>Q', v + delta)
                        count += 1
            i += sz
    walk(moov_off + 8, moov_off + moov_size)
    return bytes(d), count


def c2pa_uuid_box(payload_size=2048, generator='OpenAI Sora'):
    store = fake_jumbf_store(pad_to=payload_size, generator=generator)
    body = C2PA_BMFF_UUID + b'\x00\x00\x00\x00' + b'manifest\x00' + struct.pack('>Q', 0) + store
    return box(b'uuid', body)


def insert_after_ftyp_fix_offsets(path, new_box):
    d = open(path, 'rb').read()
    top = bmff_top(d)
    assert top[0][0] == b'ftyp', top
    ins = top[0][1] + top[0][2]
    moov = [t for t in top if t[0] == b'moov'][0]
    # every chunk offset >= insertion point moves by len(new_box)
    d2, n = shift_chunk_offsets(d, moov[1], moov[2], len(new_box), ins)
    d2 = d2[:ins] + new_box + d2[ins:]
    open(path, 'wb').write(d2)
    return n


def set_ilst_string_moov_last(path, tag4, value):
    """Replace the string of an iTunes-style ilst item (e.g. b'\\xa9too') in a file whose moov is the
    LAST top-level box (so resizing moov shifts no chunk offsets). Fixes ancestor box sizes."""
    d = bytearray(open(path, 'rb').read())
    top = bmff_top(bytes(d))
    assert top[-1][0] == b'moov', 'moov must be the last top-level box'
    i = d.find(tag4)
    assert i > 0 and d[i + 8:i + 12] == b'data'
    item_off = i - 4
    old_size = struct.unpack('>I', d[item_off:item_off + 4])[0]
    data_box = struct.pack('>I4sI4s', 16 + len(value), b'data', 1, b'\x00' * 4) + value
    new_item = struct.pack('>I', 8 + len(data_box)) + tag4 + data_box
    delta = len(new_item) - old_size
    # ancestors: every box whose range contains item_off (walk from moov down)
    def fix(start, end):
        j = start
        while j + 8 <= end:
            sz, typ = struct.unpack('>I4s', d[j:j + 8])
            if j < item_off < j + sz and j != item_off:
                d[j:j + 4] = struct.pack('>I', sz + delta)
                cs = j + 8 + (4 if typ == b'meta' and d[j + 12:j + 16] != b'hdlr' else 0)
                fix(cs, j + sz)
                return
            j += sz
    fix(0, len(d))
    d[item_off:item_off + old_size] = new_item
    open(path, 'wb').write(bytes(d))


def ffprobe_ok(path):
    j = json.loads(run([FFPROBE, '-v', 'error', '-show_streams', '-show_format', '-of', 'json', path]))
    r = subprocess.run([FFMPEG, '-v', 'error', '-i', path, '-f', 'null', '-'], capture_output=True, text=True)
    return len(j.get('streams', [])), (r.returncode == 0 and not r.stderr.strip()), r.stderr.strip()[:300]


def make_videos(out):
    res = {}
    src = [FFMPEG, '-y', '-v', 'error',
           '-f', 'lavfi', '-i', 'testsrc2=size=320x240:rate=25:duration=2',
           '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=44100:duration=2',
           '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-preset', 'veryfast', '-g', '25',
           '-c:a', 'aac', '-b:a', '64k', '-shortest']

    # faststart + mdta keys + XMP + C2PA uuid after ftyp (forces stco shift)
    p = os.path.join(out, 'ai_video_faststart.mp4')
    run(src + ['-movflags', '+faststart+use_metadata_tags',
               '-metadata', 'title=Sora sample clip',
               '-metadata', 'comment=Generated by OpenAI Sora',
               '-metadata', 'encoder=Sora',
               '-metadata', 'AIGC={"Label":"1","ContentProducer":"test"}',
               '-metadata', 'location=+59.9386+030.3141/',
               p])
    exiftool(p, '-XMP-iptcExt:DigitalSourceType=' + DST_TRAINED, '-XMP-xmp:CreatorTool=OpenAI Sora',
             '-Keys:Encoder=Sora')  # ffmpeg CLI always overwrites 'encoder' with Lavf
    top = [t[0] for t in bmff_top(open(p, 'rb').read())]
    n = insert_after_ftyp_fix_offsets(p, c2pa_uuid_box(2048, 'OpenAI Sora'))
    res['ai_video_faststart.mp4'] = p
    print('faststart: top before insert', top, 'shifted', n, 'chunk offsets', ffprobe_ok(p))

    # moov at end, classic udta/meta/ilst metadata, trailing uuid C2PA + free
    p = os.path.join(out, 'ai_video_moovend.mp4')
    run(src + ['-metadata', 'title=Sora sample clip',
               '-metadata', 'comment=Generated by OpenAI Sora',
               '-metadata', 'artist=OpenAI Sora',
               '-metadata', 'encoder=Sora',
               '-metadata', 'location=+59.9386+030.3141/',
               p])
    # NOTE: ExifTool would relocate moov in front of mdat when rewriting -> edit ©too ourselves
    set_ilst_string_moov_last(p, b'\xa9too', b'Sora')
    with open(p, 'ab') as f:
        f.write(c2pa_uuid_box(2048, 'OpenAI Sora'))
        f.write(box(b'free', b'\x00' * 24))
    res['ai_video_moovend.mp4'] = p
    print('moovend:', ffprobe_ok(p))

    # fragmented
    p = os.path.join(out, 'ai_video_frag.mp4')
    run(src[:-1] + ['-g', '12', '-shortest', '-movflags', 'frag_keyframe+empty_moov',
                    '-metadata', 'title=Kling sample clip',
                    '-metadata', 'comment=AI generated (Kling)',
                    '-metadata', 'encoder=Kling AI',
                    '-metadata', 'location=+59.9386+030.3141/',
                    p])
    # ExifTool can't write fragmented MP4 -> patch the ©too value in place (same length)
    d = bytearray(open(p, 'rb').read())
    i = d.find(b'\xa9too')
    if i > 0 and d[i + 8:i + 12] == b'data':
        dsz = struct.unpack('>I', d[i + 4:i + 8])[0]
        vstart, vend = i + 20, i - 4 + struct.unpack('>I', d[i - 4:i])[0]
        new = b'Kling AI'.ljust(vend - vstart)[:vend - vstart]
        d[vstart:vend] = new
        open(p, 'wb').write(bytes(d))
    res['ai_video_frag.mp4'] = p
    print('frag:', ffprobe_ok(p))

    # QuickTime .mov with com.apple.quicktime.* keys + XMP (udta/XMP_)
    # (ExifTool rewrites it with moov in front of mdat -- fine for this fixture)
    p = os.path.join(out, 'ai_video.mov')
    run(src + ['-f', 'mov', '-movflags', 'use_metadata_tags',
               '-metadata', 'com.apple.quicktime.location.ISO6709=+59.9386+030.3141+012.000/',
               '-metadata', 'com.apple.quicktime.software=Veo',
               '-metadata', 'com.apple.quicktime.make=Google',
               '-metadata', 'com.apple.quicktime.model=Veo 3',
               '-metadata', 'com.apple.quicktime.creationdate=2025-06-01T12:00:00+0300',
               '-metadata', 'com.apple.quicktime.description=Generated with Google Veo',
               '-metadata', 'title=Veo sample',
               p])
    exiftool(p, '-XMP-iptcExt:DigitalSourceType=' + DST_TRAINED, '-XMP-xmp:CreatorTool=Google Veo',
             '-UserData:Encoder=Veo', '-UserData:GPSCoordinates=59.9386, 30.3141, 12')
    res['ai_video.mov'] = p
    print('mov:', ffprobe_ok(p))
    return res


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    allres = {}
    for fn in (make_jpegs, make_pngs, make_webps, make_gif, make_heif, make_videos):
        try:
            allres.update(fn(out))
        except Exception as e:  # noqa
            import traceback
            traceback.print_exc()
            print('FAILED step', fn.__name__, e, file=sys.stderr)
    for k, v in sorted(allres.items()):
        print('%-28s %8d' % (k, os.path.getsize(v)))


if __name__ == '__main__':
    main()
