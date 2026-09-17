#!/usr/bin/env python3
"""Independent verifier for Clear Content outputs / fixtures.

usage: verify_media.py [--pretty] [--no-decode] [--lenient-exif] FILE [FILE...]

  --lenient-exif  also accept benign baseline EXIF tags next to Orientation
                  (X/YResolution, ResolutionUnit, YCbCrPositioning, ExifVersion, ColorSpace,
                  pixel dimensions, Interop index). Default is strict: Orientation only.

Prints one JSON object per file (JSON Lines) with:
  format, size, decodable, c2pa, exiftool (flagged groups/tags, allowed ICC/orientation,
  warnings), containers (own structural scan), marker_hits, clean (+ clean_reasons), pass.

Run it with the tools venv python so the `c2pa` module (c2pa-python) is importable;
otherwise it falls back to the c2patool binary.
Tools are looked up in: $EXIFTOOL/$C2PATOOL/$FFMPEG/$FFPROBE env vars, this script's directory,
$CLEAR_CONTENT_TOOLS (default ~/.cache/clear-content-tools, see setup_tools.sh), then PATH.
Never raises for a single bad file: errors are reported in the JSON.
"""
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import traceback

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

TOOL_DIRS = [HERE, os.environ.get('CLEAR_CONTENT_TOOLS') or os.path.expanduser('~/.cache/clear-content-tools')]


def _find(env, rel, which):
    if os.environ.get(env):
        return os.environ[env]
    for d in TOOL_DIRS:
        if os.path.exists(os.path.join(d, rel)):
            return os.path.join(d, rel)
    return shutil.which(which)


EXIFTOOL = _find('EXIFTOOL', 'exiftool-master/exiftool', 'exiftool')
C2PATOOL = _find('C2PATOOL', 'c2patool/c2patool/c2patool', 'c2patool')
FFPROBE = _find('FFPROBE', 'ffmpeg/ffprobe', 'ffprobe') if not shutil.which('ffprobe') else shutil.which('ffprobe')
FFMPEG = _find('FFMPEG', 'ffmpeg/ffmpeg', 'ffmpeg') if not shutil.which('ffmpeg') else shutil.which('ffmpeg')
if os.environ.get('FFPROBE'):
    FFPROBE = os.environ['FFPROBE']
if os.environ.get('FFMPEG'):
    FFMPEG = os.environ['FFMPEG']

MARKERS = ['c2pa', 'jumb', 'trainedAlgorithmicMedia', 'compositeWithTrainedAlgorithmicMedia',
           'digitalsourcetype', 'openai', 'chatgpt', 'dall-e', 'gemini', 'imagen', 'synthid',
           'midjourney', 'stable diffusion', 'comfyui', 'parameters', 'firefly', 'AIGC',
           'xmpmeta', 'Exif\x00\x00']
SHORT_MARKERS = {'c2pa', 'jumb', 'aigc'}  # 4-byte markers: random-match probability non-zero

C2PA_UUID = bytes.fromhex('d8fec3d61b0e483c92975828877ec481')
XMP_UUID = bytes.fromhex('be7acfcb97a942e89c71999491e3afac')

# ---------------------------------------------------------------- ExifTool classification
ICC_GROUP_RE = re.compile(r'^ICC(-|_|$)')
STRUCTURAL_GROUPS = {'ExifTool', 'System', 'File', 'Composite', 'JFIF', 'Adobe', 'PNG-pHYs',
                     'PNG-cICP', 'QuickTime', 'Meta', 'GIF', 'RIFF', 'Track', 'AVI1'}
FILE_ALLOWED = {'FileType', 'FileTypeExtension', 'MIMEType', 'ImageWidth', 'ImageHeight',
                'EncodingProcess', 'BitsPerSample', 'ColorComponents', 'YCbCrSubSampling',
                'ExifByteOrder', 'CurrentIPTCDigest', 'Error', 'Warning'}
PNG_ALLOWED = {'ImageWidth', 'ImageHeight', 'BitDepth', 'ColorType', 'Compression', 'Filter',
               'Interlace', 'Gamma', 'SRGBRendering', 'WhitePointX', 'WhitePointY', 'RedX', 'RedY',
               'GreenX', 'GreenY', 'BlueX', 'BlueY', 'BackgroundColor', 'Transparency',
               'SignificantBits', 'Palette', 'PaletteColors', 'ProfileName', 'AnimationFrames',
               'AnimationPlays', 'ColorPrimaries', 'TransferCharacteristics', 'MatrixCoefficients',
               'VideoFullRangeFlag'}
RIFF_ALLOWED = {'ImageWidth', 'ImageHeight', 'VP8Version', 'HorizontalScale', 'VerticalScale',
                'WebP_Flags', 'AlphaIsUsed', 'BackgroundColor', 'AnimationLoopCount', 'Duration',
                'FrameCount', 'AlphaPreprocessing', 'AlphaFiltering', 'AlphaCompression'}
# tag names inside otherwise-structural BMFF groups that ARE user metadata
QT_METADATA_NAMES = re.compile(
    r'^(Title|Comment|Encoder|Software|Artist|Author|Description|GPS.*|Location.*|Make|Model|'
    r'Keywords|Copyright|Creator|ContentCreateDate|CreationDate|Album|Genre|Information|'
    r'Unknown_(uuid|udta|meta|keys|ilst|XMP_|free_meta).*|XMP.*|UserComment|Producer|'
    r'Publisher|Director|Rating|Category|Year|Date.*Original)$')
GIF_FLAGGED_NAMES = {'Comment', 'XMPToolkit', 'Text'}


def classify_exiftool(pairs):
    """pairs: list of (key, value) incl. duplicates. Returns dict."""
    groups, flagged, warnings, icc, errors = {}, {}, [], [], []
    orientation = None
    ifd0_tags = []
    for k, v in pairs:
        if k == 'SourceFile' or ':' not in k:
            continue
        g, t = k.split(':', 1)
        groups.setdefault(g, [])
        if t not in groups[g]:
            groups[g].append(t)
        if g == 'ExifTool':
            if t == 'Warning':
                warnings.append(str(v))
            elif t == 'Error':
                errors.append(str(v))
            continue
        if ICC_GROUP_RE.match(g):
            if t not in icc:
                icc.append(t)
            continue
        if g == 'IFD0':
            ifd0_tags.append(t)
            if t == 'Orientation':
                orientation = v
                continue
        base_g = re.sub(r'\d+$', '', g)
        is_flag = False
        if g == 'File':
            is_flag = t not in FILE_ALLOWED
        elif g == 'PNG':
            is_flag = t not in PNG_ALLOWED
        elif g == 'RIFF':
            is_flag = t not in RIFF_ALLOWED
        elif g == 'GIF':
            is_flag = t in GIF_FLAGGED_NAMES
        elif base_g in ('QuickTime', 'Track', 'Meta'):
            is_flag = bool(QT_METADATA_NAMES.match(t))
        elif base_g in STRUCTURAL_GROUPS:
            is_flag = False
        else:
            is_flag = True
        if is_flag:
            flagged.setdefault(g, [])
            if t not in flagged[g]:
                flagged[g].append(t)
    return {'groups_found': sorted(groups), 'flagged': flagged, 'icc_profile_tags': len(icc),
            'icc_profile_present': bool(icc), 'ifd0_orientation': orientation,
            'warnings': warnings, 'errors': errors}


def run_exiftool(path):
    if not EXIFTOOL:
        return {'error': 'exiftool not found'}
    # the perl distribution is run through perl; a system/packaged exiftool is run directly
    cmd = ['perl', EXIFTOOL] if 'exiftool-master' in EXIFTOOL or not os.access(EXIFTOOL, os.X_OK) else [EXIFTOOL]
    try:
        r = subprocess.run(cmd + ['-j', '-G1', '-a', '-u', '-s', '-api', 'LargeFileSupport=1', path],
                           capture_output=True, timeout=120)
        txt = r.stdout.decode('utf-8', 'replace')
        if not txt.strip():
            return {'error': 'no exiftool output: ' + r.stderr.decode('utf-8', 'replace')[:300]}
        arr = json.loads(txt, strict=False, object_pairs_hook=lambda p: p)
        pairs = arr[0] if arr else []
        res = classify_exiftool(pairs)
        # numeric orientation
        r2 = subprocess.run(cmd + ['-j', '-n', '-G1', '-a', '-IFD0:Orientation', '-XMP-tiff:Orientation',
                                   '-Composite:Rotation', '-QuickTime:Rotation', path],
                            capture_output=True, timeout=60)
        try:
            o = json.loads(r2.stdout.decode('utf-8', 'replace'), strict=False)[0]
            res['orientation_numeric'] = {k: v for k, v in o.items() if k != 'SourceFile'}
        except Exception:  # noqa
            pass
        return res
    except Exception as e:  # noqa
        return {'error': '%s: %s' % (type(e).__name__, e)}


# ---------------------------------------------------------------- own container scan
# Baseline TIFF/EXIF tags that carry no identifying or provenance information.
BENIGN_IFD0 = {0x011A, 0x011B, 0x0128, 0x0213}           # X/YResolution, ResolutionUnit, YCbCrPositioning
BENIGN_EXIFIFD = {0x9000, 0x9101, 0xA000, 0xA001, 0xA002, 0xA003, 0xA005}  # versions, ColorSpace, dims, Interop ptr
BENIGN_INTEROP = {0x0001, 0x0002}
EXIF_PTR, GPS_PTR, INTEROP_PTR = 0x8769, 0x8825, 0xA005
LENIENT_EXIF = False


def _read_ifd(tiff, e, off):
    if off <= 0 or off + 2 > len(tiff):
        return None
    n = struct.unpack(e + 'H', tiff[off:off + 2])[0]
    entries = {}
    for k in range(n):
        p = off + 2 + 12 * k
        if p + 12 > len(tiff):
            break
        tag, typ = struct.unpack(e + 'HH', tiff[p:p + 4])
        if typ == 3:
            val = struct.unpack(e + 'H', tiff[p + 8:p + 10])[0]
        else:
            val = struct.unpack(e + 'I', tiff[p + 8:p + 12])[0]
        entries[tag] = val
    nxt_p = off + 2 + 12 * n
    nxt = struct.unpack(e + 'I', tiff[nxt_p:nxt_p + 4])[0] if nxt_p + 4 <= len(tiff) else 0
    return entries, nxt


def analyze_exif(tiff):
    """Returns dict(orientation, extra_tags=[...], benign_tags=[...], gps, thumbnail, parse_ok)."""
    res = {'orientation': None, 'extra_tags': [], 'benign_tags': [], 'gps': False, 'ifd1': False,
           'parse_ok': False}
    if len(tiff) < 8 or tiff[:2] not in (b'II', b'MM'):
        return res
    e = '<' if tiff[:2] == b'II' else '>'
    r = _read_ifd(tiff, e, struct.unpack(e + 'I', tiff[4:8])[0])
    if r is None:
        return res
    ifd0, nxt = r
    res['parse_ok'] = True
    res['ifd1'] = bool(nxt)
    for tag, val in ifd0.items():
        if tag == 0x0112:
            res['orientation'] = val
        elif tag in BENIGN_IFD0:
            res['benign_tags'].append('IFD0:0x%04X' % tag)
        elif tag == GPS_PTR:
            res['gps'] = True
            res['extra_tags'].append('IFD0:GPSInfo')
        elif tag == EXIF_PTR:
            sub = _read_ifd(tiff, e, val)
            for t2, v2 in (sub[0] if sub else {}).items():
                if t2 in BENIGN_EXIFIFD:
                    res['benign_tags'].append('ExifIFD:0x%04X' % t2)
                    if t2 == INTEROP_PTR:
                        sub2 = _read_ifd(tiff, e, v2)
                        for t3 in (sub2[0] if sub2 else {}):
                            (res['benign_tags'] if t3 in BENIGN_INTEROP else res['extra_tags']).append(
                                'InteropIFD:0x%04X' % t3)
                else:
                    res['extra_tags'].append('ExifIFD:0x%04X' % t2)
        else:
            res['extra_tags'].append('IFD0:0x%04X' % tag)
    if nxt:
        res['extra_tags'].append('IFD1(thumbnail)')
    return res


def exif_allowed(tiff):
    a = analyze_exif(tiff)
    ok = a['parse_ok'] and not a['extra_tags'] and (LENIENT_EXIF or not a['benign_tags'])
    return ok, a


def scan_containers(path, data):
    """Independent structural scan. Returns dict(kind, items=[{name, flagged, note}], errors)."""
    import dump_structure as ds
    kind = ds.detect(data)
    items, errors = [], []
    exif_orient = None

    def add(name, flagged, note=None):
        items.append({'name': name, 'flagged': flagged, **({'note': note} if note else {})})

    def exif_item(label, tiff):
        nonlocal exif_orient
        ok, a = exif_allowed(tiff)
        exif_orient = a['orientation']
        if ok:
            note = 'allowed: Orientation=%s%s' % (a['orientation'], ' + benign %s' % a['benign_tags'] if a['benign_tags'] else '')
        elif a['parse_ok'] and not a['extra_tags']:
            note = 'only benign baseline tags besides Orientation (%s); allowed with --lenient-exif' % a['benign_tags']
        else:
            note = 'EXIF beyond Orientation: %s' % (a['extra_tags'][:10] if a['parse_ok'] else 'unparseable')
        add(label, not ok, note)

    try:
        if kind == 'jpeg':
            for s in ds.jpeg(data):
                n = s['name']
                if n in ('SOI', 'EOI', 'SOS', 'DQT', 'DHT', 'DRI', 'ECS') or n.startswith('SOF') or n.startswith('M'):
                    continue
                off = s['off']
                seg = data[off + 4: off + 2 + s.get('len', 2)]
                if n == 'APP0':
                    add('APP0/' + ('JFIF' if seg.startswith(b'JFIF') else 'JFXX' if seg.startswith(b'JFXX') else '?'),
                        not seg.startswith(b'JFIF'))
                elif n == 'APP1' and seg.startswith(b'Exif\x00\x00'):
                    exif_item('APP1/Exif', seg[6:])
                elif n == 'APP1':
                    add('APP1/XMP' if b'ns.adobe.com/xap' in seg[:40] or b'ns.adobe.com/xmp/extension' in seg[:40]
                        else 'APP1/other', True)
                elif n == 'APP2' and seg.startswith(b'ICC_PROFILE\x00'):
                    add('APP2/ICC_PROFILE', False, 'allowed')
                elif n == 'APP2' and seg.startswith(b'MPF\x00'):
                    add('APP2/MPF', True, 'multi-picture index (secondary images/gain maps)')
                elif n == 'APP14' and seg.startswith(b'Adobe'):
                    add('APP14/Adobe', False, 'color transform (structural)')
                elif n == 'APP11':
                    add('APP11/' + ('JUMBF' if seg[:2] == b'JP' else '?'), True)
                elif n == 'APP13':
                    add('APP13/Photoshop-IRB(IPTC)', True)
                elif n == 'COM':
                    add('COM', True, seg[:40].decode('latin1'))
                elif n == 'TRAILER':
                    add('TRAILER after EOI (%d bytes)' % s['len'], True)
                elif n == 'GARBAGE?':
                    errors.append('unparseable data at %d' % off)
                else:
                    add(n, True, seg[:16].decode('latin1'))
        elif kind == 'png':
            allowed = {b'IHDR', b'PLTE', b'IDAT', b'IEND', b'tRNS', b'gAMA', b'cHRM', b'sRGB', b'iCCP',
                       b'sBIT', b'bKGD', b'pHYs', b'cICP', b'mDCv', b'cLLi', b'hIST', b'acTL', b'fcTL',
                       b'fdAT'}
            for s in ds.png(data):
                n = s['name']
                if n == 'TRAILER':
                    add('TRAILER after IEND (%d bytes)' % s['len'], True)
                    continue
                if n.encode('latin1') in allowed:
                    if n == 'iCCP':
                        add('iCCP', False, 'allowed')
                    continue
                if n == 'eXIf':
                    off = s['off']
                    exif_item('eXIf', data[off + 8: off + 8 + s['len']])
                    continue
                add(n + ('(%s)' % s['key'] if 'key' in s else ''), True)
            # CRC check
            i = 8
            while i + 12 <= len(data):
                ln, typ = struct.unpack('>I4s', data[i:i + 8])
                import zlib
                if i + 12 + ln > len(data):
                    errors.append('truncated chunk %r' % typ)
                    break
                crc = struct.unpack('>I', data[i + 8 + ln:i + 12 + ln])[0]
                if zlib.crc32(data[i + 4:i + 8 + ln]) & 0xffffffff != crc:
                    errors.append('bad CRC in %r@%d' % (typ, i))
                i += 12 + ln
                if typ == b'IEND':
                    break
        elif kind == 'riff':
            st = ds.riff(data)
            head = st[0]
            if head['size_field'] != head['expected']:
                errors.append('RIFF size field %d != actual %d' % (head['size_field'], head['expected']))
            names = [s['name'] for s in st[1:]]
            vp8x = next((s for s in st if s['name'] == 'VP8X'), None)
            if vp8x:
                f = vp8x['flags']
                for flag, ch in (('ICC', 'ICCP'), ('EXIF', 'EXIF'), ('XMP', 'XMP '), ('ANIM', 'ANIM')):
                    if f[flag] != (ch in names):
                        errors.append('VP8X %s flag=%s but chunk present=%s' % (flag, f[flag], ch in names))
            for s in st[1:]:
                n = s['name']
                if n in ('VP8 ', 'VP8L', 'VP8X', 'ALPH', 'ANIM', 'ANMF'):
                    continue
                if n == 'ICCP':
                    add('ICCP', False, 'allowed')
                elif n == 'EXIF':
                    raw = data[s['off'] + 8: s['off'] + 8 + s['len']]
                    if raw.startswith(b'Exif\x00\x00'):
                        raw = raw[6:]
                    exif_item('EXIF', raw)
                elif n == 'TRAILER':
                    add('TRAILER after RIFF (%d bytes)' % s['len'], True)
                else:
                    add(n.strip() or n, True)
        elif kind == 'gif':
            for s in ds.gif(data):
                n = s['name']
                if n == 'AppExt':
                    ident = s['id']
                    if ident.startswith('NETSCAPE2.0') or ident.startswith('ANIMEXTS1.0'):
                        continue
                    if ident.startswith('ICCRGBG1'):
                        add('AppExt(ICCRGBG1)', False, 'allowed')
                        continue
                    add('AppExt(%s)' % ident, True)
                elif n in ('CommentExt', 'PlainTextExt'):
                    add(n, True, s.get('text'))
                elif n in ('GARBAGE', 'UNKNOWN'):
                    add('%s after/inside GIF (%d bytes)' % (n, s.get('len', 0)), True)
        elif kind == 'bmff':
            st = ds.bmff(data)
            ok_top = {'ftyp', 'moov', 'mdat', 'free', 'skip', 'wide', 'moof', 'mfra', 'styp', 'sidx',
                      'meta', 'pdin', 'ssix', 'prft', 'emsg', 'idat', 'iloc'}
            parents = []
            for s in st:
                d_ = s.get('depth', 0)
                parents = parents[:d_]
                n = s['name']
                path_ = '/'.join(parents + [n])
                parents.append(n)
                if d_ == 0:
                    if n == 'uuid':
                        add('uuid(%s)' % s.get('kind'), True, s.get('uuid'))
                    elif n not in ok_top:
                        add(n, True, 'unexpected top-level box')
                    continue
                in_meta = parents[0] in ('moov', 'moof') and any(x in ('udta', 'meta') for x in parents[:-1])
                if n in ('udta', 'meta', 'ilst', 'hdlr', 'free', 'skip') and in_meta or \
                        (n in ('udta', 'meta') and parents[0] in ('moov', 'moof')):
                    continue  # empty shells are not metadata; their payload children are checked below
                if n == 'keys':
                    if s.get('keys'):
                        add(path_, True, 'keys: %s' % ', '.join(s['keys'][:8]))
                    continue
                if in_meta:
                    add(path_, True)
                elif n == 'uuid':
                    add(path_ + '(%s)' % s.get('kind'), True, s.get('uuid'))
            # HEIF items
            heif = scan_heif_items(data, st)
            for it in heif:
                if it['type'] == 'Exif':
                    add('heif item Exif(id=%s)' % it['id'], None, 'EXIF item; see exiftool for content')
                elif it['type'] == 'mime':
                    add('heif item mime(%s)' % it.get('content_type'), True)
                elif it['type'] in ('uri ', 'jumb', 'c2pa'):
                    add('heif item %s' % it['type'], True)
        else:
            errors.append('unknown container')
    except Exception as e:  # noqa
        errors.append('scan error: %s: %s' % (type(e).__name__, e))
    return {'kind': kind, 'items': items, 'errors': errors, 'exif_orientation': exif_orient}


def scan_heif_items(data, st):
    out = []
    for s in st:
        if s['name'] != 'iinf':
            continue
        o = s['off']
        ver = data[o + 8]
        p = o + 12 + (2 if ver == 0 else 4)
        end = o + s['len']
        while p + 8 <= end:
            sz, typ = struct.unpack('>I4s', data[p:p + 8])
            if typ == b'infe' and sz >= 20:
                v = data[p + 8]
                q = p + 12
                if v >= 2:
                    if v == 2:
                        iid = struct.unpack('>H', data[q:q + 2])[0]
                        q += 2
                    else:
                        iid = struct.unpack('>I', data[q:q + 4])[0]
                        q += 4
                    q += 2
                    itype = data[q:q + 4].decode('latin1')
                    q += 4
                    rest = data[q:p + sz].split(b'\x00')
                    it = {'id': iid, 'type': itype, 'name': rest[0].decode('latin1', 'replace')}
                    if itype == 'mime' and len(rest) > 1:
                        it['content_type'] = rest[1].decode('latin1', 'replace')
                    out.append(it)
            if sz < 8:
                break
            p += sz
    return out


# ---------------------------------------------------------------- markers
def _printable_run(data, i, j):
    a = i
    while a > 0 and 32 <= data[a - 1] < 127:
        a -= 1
    b = j
    while b < len(data) and 32 <= data[b] < 127:
        b += 1
    return b - a


def marker_hits(data):
    low = data.lower()
    out = {}
    for m in MARKERS:
        mb = m.lower().encode('latin1')
        offs, i = [], low.find(mb)
        while i != -1:
            offs.append(i)
            i = low.find(mb, i + 1)
        if not offs:
            continue
        real, suspect = [], []
        for o in offs:
            e = o + len(mb)
            if m.lower() not in SHORT_MARKERS:
                real.append(o)
                continue
            why = None
            if m.lower() == 'jumb':
                if o >= 4 and 8 <= struct.unpack('>I', data[o - 4:o])[0] <= len(data) + 16:
                    why = 'box header'
                elif data[e + 4:e + 8] == b'jumd':
                    why = 'box header'
            elif m.lower() == 'c2pa':
                if data[e:e + 4] == b'\x00\x11\x00\x10':
                    why = 'jumd type uuid'
                elif data[e:e + 1] == b'\x00' and o > 0 and data[o - 1] in (1, 3, 0x0b, 0x13, 0x1b):
                    why = 'jumd label'
                elif data[o:o + 4] == b'C2PA' and o >= 12 and len(data) >= e + 4 and \
                        8 <= struct.unpack('<I', data[e:e + 4])[0] <= len(data):
                    why = 'RIFF chunk'
            if why is None and _printable_run(data, o, e) >= 8:
                why = 'text context'
            (real if why else suspect).append(o)
        ctx = []
        for o in (real or suspect)[:3]:
            seg = data[max(0, o - 12): o + len(mb) + 12]
            ctx.append(''.join(chr(c) if 32 <= c < 127 else '.' for c in seg))
        out[m.replace('\x00', '\\0')] = {'count': len(offs), 'real': len(real), 'suspect_false_positive': len(suspect),
                                         'offsets': offs[:8], 'context': ctx}
    return out


# ---------------------------------------------------------------- C2PA
_c2pa = None
_c2pa_ctx = None


def _load_c2pa():
    global _c2pa, _c2pa_ctx
    if _c2pa is None:
        try:
            import c2pa  # noqa
            _c2pa = c2pa
            try:
                s = c2pa.Settings.from_dict({'verify': {'remote_manifest_fetch': False}})
                _c2pa_ctx = c2pa.Context(settings=s)
            except Exception:  # noqa
                _c2pa_ctx = None
        except Exception:  # noqa
            _c2pa = False
    return _c2pa


def _summarize_store(j, reader_name):
    am = j.get('active_manifest')
    mans = j.get('manifests', {}) or {}
    m = mans.get(am, {}) if am else {}
    gen = m.get('claim_generator')
    if not gen and m.get('claim_generator_info'):
        gen = ', '.join('%s/%s' % (x.get('name'), x.get('version', '')) for x in m['claim_generator_info'])
    dsts = []
    for a in m.get('assertions', []) or []:
        if str(a.get('label', '')).startswith('c2pa.actions'):
            for act in (a.get('data', {}) or {}).get('actions', []) or []:
                if act.get('digitalSourceType'):
                    dsts.append(act['digitalSourceType'])
    vr = (j.get('validation_results') or {}).get('activeManifest') or {}
    return {'present': True, 'status': (j.get('validation_state') or 'present').lower(), 'reader': reader_name,
            'manifest_count': len(mans), 'active_manifest': am, 'claim_generator': gen,
            'title': m.get('title'), 'digital_source_types': dsts,
            'validation_state': j.get('validation_state'),
            'failure_codes': sorted({x.get('code') for x in vr.get('failure', [])}) or
            sorted({x.get('code') for x in j.get('validation_status', []) or []})}


def c2pa_check(path):
    lib = _load_c2pa()
    if lib:
        name = 'c2pa-python %s' % getattr(lib, '__version__', '?')
        try:
            name += ' (sdk %s)' % lib.sdk_version()
        except Exception:  # noqa
            pass
        try:
            r = lib.Reader(path, context=_c2pa_ctx) if _c2pa_ctx is not None else lib.Reader(path)
            try:
                j = json.loads(r.json())
                res = _summarize_store(j, name)
                try:
                    res['embedded'] = r.is_embedded()
                    res['remote_url'] = r.get_remote_url()
                except Exception:  # noqa
                    pass
                return res
            finally:
                try:
                    r.close()
                except Exception:  # noqa
                    pass
        except Exception as e:  # noqa
            msg = '%s: %s' % (type(e).__name__, e)
            if 'ManifestNotFound' in msg or 'no JUMBF data found' in msg:
                return {'present': False, 'status': 'none', 'reader': name, 'error': msg[:300]}
            if 'NotSupported' in msg:
                return {'present': None, 'status': 'unsupported', 'reader': name, 'error': msg[:300]}
            if 'Remote' in msg and 'http' in msg:
                url = re.search(r'https?://\S+', msg)
                return {'present': True, 'status': 'remote', 'reader': name,
                        'remote_url': url.group(0) if url else None, 'error': msg[:300]}
            if re.search(r'jumbf|claim|manifest|assertion|signature|cbor|cose|hash', msg, re.I) and \
                    'asset could not be parsed' not in msg:
                # C2PA-like data was found but could not be parsed/validated
                return {'present': True, 'status': 'error', 'reader': name, 'error': msg[:300]}
            return {'present': None, 'status': 'reader-error', 'reader': name, 'error': msg[:300]}
    if C2PATOOL:
        try:
            r = subprocess.run([C2PATOOL, path], capture_output=True, timeout=120)
            out = r.stdout.decode('utf-8', 'replace')
            err = r.stderr.decode('utf-8', 'replace')
            if r.returncode == 0 and out.strip().startswith('{'):
                return _summarize_store(json.loads(out), 'c2patool')
            msg = (err or out).strip()[:300]
            if 'No claim found' in msg or 'ManifestNotFound' in msg or 'no JUMBF' in msg:
                return {'present': False, 'status': 'none', 'reader': 'c2patool', 'error': msg}
            return {'present': True, 'status': 'error', 'reader': 'c2patool', 'error': msg}
        except Exception as e:  # noqa
            return {'present': None, 'status': 'error', 'reader': 'c2patool', 'error': str(e)}
    return {'present': None, 'status': 'no-reader', 'error': 'no C2PA reader available'}


# ---------------------------------------------------------------- decoding
def sniff(data, path):
    if data[:3] == b'\xff\xd8\xff':
        return 'jpeg'
    if data[:8] == b'\x89PNG\r\n\x1a\n':
        return 'png'
    if data[:4] == b'RIFF' and data[8:12] == b'WEBP':
        return 'webp'
    if data[:4] == b'RIFF':
        return 'riff-' + data[8:12].decode('latin1').strip()
    if data[:6] in (b'GIF87a', b'GIF89a'):
        return 'gif'
    if data[4:8] == b'ftyp':
        brand = data[8:12].decode('latin1')
        compat = data[16:struct.unpack('>I', data[:4])[0]]
        if brand in ('avif', 'avis'):
            return 'avif'
        if brand in ('heic', 'heix', 'mif1', 'msf1', 'heim', 'heis', 'hevc', 'hevx'):
            return 'avif' if b'avif' in compat and b'heic' not in compat else 'heif'
        if brand == 'qt  ':
            return 'mov'
        return 'mp4(%s)' % brand.strip()
    if data[4:8] in (b'moov', b'mdat', b'wide', b'free'):
        return 'mov'
    return os.path.splitext(path)[1].lstrip('.').lower() or 'unknown'


_heif_registered = False


def decode_image(path):
    global _heif_registered
    try:
        from PIL import Image, ImageSequence
        if not _heif_registered:
            _heif_registered = True
            try:
                import pillow_heif
                pillow_heif.register_heif_opener()
            except Exception:  # noqa
                pass
        with Image.open(path) as im:
            fmt, size, mode = im.format, im.size, im.mode
            frames = 0
            for fr in ImageSequence.Iterator(im):
                fr.load()
                frames += 1
            try:
                ori = im.getexif().get(0x0112)
            except Exception:  # noqa
                ori = None
        return {'ok': True, 'via': 'Pillow', 'pil_format': fmt, 'size': list(size), 'mode': mode,
                'frames': frames, 'pil_exif_orientation': ori}
    except Exception as e:  # noqa
        return {'ok': False, 'via': 'Pillow', 'error': '%s: %s' % (type(e).__name__, e)}


def decode_video(path, full=True):
    res = {'via': 'ffprobe'}
    try:
        r = subprocess.run([FFPROBE, '-v', 'error', '-show_streams', '-show_format', '-of', 'json', path],
                           capture_output=True, timeout=120)
        j = json.loads(r.stdout.decode('utf-8', 'replace') or '{}')
        streams = j.get('streams', [])
        res['stream_count'] = len(streams)
        res['streams'] = ['%s:%s%s' % (s.get('codec_type'), s.get('codec_name'),
                                       ' %sx%s' % (s.get('width'), s.get('height')) if s.get('width') else '')
                          for s in streams]
        res['duration'] = (j.get('format') or {}).get('duration')
        res['format_tags'] = sorted(((j.get('format') or {}).get('tags') or {}).keys())
        res['probe_stderr'] = r.stderr.decode('utf-8', 'replace').strip()[:300]
        res['ok'] = r.returncode == 0 and len(streams) > 0
        if full and res['ok']:
            r2 = subprocess.run([FFMPEG, '-v', 'error', '-i', path, '-map', '0', '-f', 'null', '-'],
                                capture_output=True, timeout=600)
            e2 = r2.stderr.decode('utf-8', 'replace').strip()
            res['full_decode_ok'] = r2.returncode == 0 and not e2
            if e2:
                res['full_decode_errors'] = e2[:500]
            res['ok'] = res['ok'] and res['full_decode_ok']
    except Exception as e:  # noqa
        res.update({'ok': False, 'error': '%s: %s' % (type(e).__name__, e)})
    return res


# ---------------------------------------------------------------- main
def verify(path, do_decode=True):
    out = {'file': path}
    try:
        data = open(path, 'rb').read()
    except Exception as e:  # noqa
        out.update({'error': str(e), 'clean': False, 'pass': False})
        return out
    out['size'] = len(data)
    fmt = sniff(data, path)
    out['format'] = fmt
    reasons, notes = [], []

    # decode
    if do_decode:
        try:
            if fmt in ('jpeg', 'png', 'webp', 'gif', 'heif', 'avif'):
                out['decodable'] = decode_image(path)
            else:
                out['decodable'] = decode_video(path)
        except Exception as e:  # noqa
            out['decodable'] = {'ok': False, 'error': str(e)}

    # C2PA
    try:
        out['c2pa'] = c2pa_check(path)
    except Exception as e:  # noqa
        out['c2pa'] = {'present': None, 'status': 'error', 'error': str(e)}
    if out['c2pa'].get('present'):
        reasons.append('C2PA: %s' % out['c2pa'].get('status'))

    # exiftool
    et = run_exiftool(path)
    out['exiftool'] = et
    for g, tags in (et.get('flagged') or {}).items():
        reasons.append('exiftool %s: %s' % (g, ','.join(tags[:12])))
    ori = et.get('ifd0_orientation')
    for w in et.get('warnings', []):
        if 'trailer' in w.lower() or 'JUMBF' in w:
            reasons.append('exiftool warning: ' + w)

    # container scan
    cs = scan_containers(path, data)
    out['containers'] = cs
    for it in cs['items']:
        if it['flagged']:
            reasons.append('container ' + it['name'])
    if cs['errors']:
        notes.append('structure errors: ' + '; '.join(cs['errors']))
    if cs['kind'] is None:
        reasons.append('unknown/unsupported container')
    if out['c2pa'].get('status') in ('reader-error', 'unsupported', 'no-reader'):
        notes.append('C2PA reader inconclusive: %s' % out['c2pa'].get('error'))
    exif_allowed_only = True
    for it in cs['items']:
        if it['name'].split('(')[0] in ('APP1/Exif', 'eXIf', 'EXIF') and it['flagged']:
            exif_allowed_only = False
    et_exif_extra = [g for g in (et.get('flagged') or {}) if re.match(r'^(IFD\d|ExifIFD|GPS|InteropIFD|MakerNotes)', g)]
    if LENIENT_EXIF:
        # drop ExifTool flags that are only benign baseline tags
        benign_names = {'IFD0': {'XResolution', 'YResolution', 'ResolutionUnit', 'YCbCrPositioning'},
                        'ExifIFD': {'ExifVersion', 'ColorSpace', 'ExifImageWidth', 'ExifImageHeight',
                                    'FlashpixVersion', 'ComponentsConfiguration'},
                        'InteropIFD': {'InteropIndex', 'InteropVersion'}}
        for g in list(et_exif_extra):
            rest = [t for t in et['flagged'][g] if t not in benign_names.get(g, set())]
            if not rest:
                et_exif_extra.remove(g)
                reasons[:] = [r for r in reasons if not r.startswith('exiftool %s:' % g)]
    if et_exif_extra:
        exif_allowed_only = False

    # markers
    hits = marker_hits(data)
    out['marker_hits'] = hits
    explained = {}
    for m, h in hits.items():
        if h['real'] == 0:
            explained[m] = 'all %d hit(s) look like random matches in compressed data' % h['count']
            continue
        if m == 'Exif\\0\\0' and exif_allowed_only:
            explained[m] = 'EXIF header of allowed orientation-only EXIF'
            continue
        reasons.append('marker %r x%d' % (m, h['real']))
    if explained:
        out['marker_hits_explained'] = explained

    on = (et.get('orientation_numeric') or {}).get('IFD0:Orientation')
    out['allowed'] = {'icc_profile': bool(et.get('icc_profile_present')) or any(
        (not it['flagged']) and 'IC' in it['name'] for it in cs['items']),
        'exif_orientation': on if on is not None else cs.get('exif_orientation'),
        'exif_orientation_text': ori}
    out['clean'] = not reasons
    out['clean_reasons'] = reasons
    if notes:
        out['notes'] = notes
    dec = out.get('decodable', {}).get('ok', True)
    out['pass'] = bool(out['clean'] and dec and not cs['errors'])
    return out


def main():
    args = sys.argv[1:]
    pretty = '--pretty' in args
    global LENIENT_EXIF
    LENIENT_EXIF = '--lenient-exif' in args
    do_decode = '--no-decode' not in args
    files = [a for a in args if not a.startswith('--')]
    if not files:
        print(__doc__)
        sys.exit(2)
    for f in files:
        try:
            res = verify(f, do_decode)
        except Exception as e:  # noqa
            res = {'file': f, 'error': 'verifier crashed: %s' % e, 'trace': traceback.format_exc()[-800:],
                   'clean': False, 'pass': False}
        print(json.dumps(res, indent=2 if pretty else None, ensure_ascii=False, default=str))
        sys.stdout.flush()


if __name__ == '__main__':
    main()
