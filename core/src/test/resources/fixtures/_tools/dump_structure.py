#!/usr/bin/env python3
"""Dump container structure of media files (JPEG segments, PNG chunks, RIFF/WebP
chunks, ISO-BMFF boxes, GIF blocks).  usage: dump_structure.py [--json] FILE..."""
import json
import struct
import sys

C2PA_UUID = bytes.fromhex('d8fec3d61b0e483c92975828877ec481')
XMP_UUID = bytes.fromhex('be7acfcb97a942e89c71999491e3afac')
BMFF_CONTAINERS = {b'moov', b'trak', b'mdia', b'minf', b'stbl', b'udta', b'edts',
                   b'dinf', b'moof', b'traf', b'mvex', b'meta', b'ilst', b'iprp',
                   b'ipco', b'sinf', b'schi', b'mfra', b'tref', b'grpl'}
JPEG_NAMES = {0xD8: 'SOI', 0xD9: 'EOI', 0xDA: 'SOS', 0xDB: 'DQT', 0xC4: 'DHT',
              0xDD: 'DRI', 0xFE: 'COM', 0xC0: 'SOF0', 0xC1: 'SOF1', 0xC2: 'SOF2'}


def _p(b):
    return ''.join(chr(c) if 32 <= c < 127 else '.' for c in b)


def jpeg(d):
    out, i = [], 2
    out.append({'off': 0, 'name': 'SOI'})
    while i < len(d) - 1:
        if d[i] != 0xFF:
            out.append({'off': i, 'name': 'GARBAGE?', 'len': len(d) - i})
            break
        m = d[i + 1]
        if m == 0xFF:
            i += 1
            continue
        name = JPEG_NAMES.get(m, 'APP%d' % (m - 0xE0) if 0xE0 <= m <= 0xEF else 'M%02X' % m)
        if m == 0xD9:
            out.append({'off': i, 'name': 'EOI'})
            i += 2
            if i < len(d):
                out.append({'off': i, 'name': 'TRAILER', 'len': len(d) - i, 'head': _p(d[i:i + 24])})
            break
        if m in (0x01,) or 0xD0 <= m <= 0xD7:
            i += 2
            continue
        ln = struct.unpack('>H', d[i + 2:i + 4])[0]
        ent = {'off': i, 'name': name, 'len': ln, 'head': _p(d[i + 4:i + 4 + min(ln - 2, 20)])}
        if m == 0xDD:
            ent['restart_interval'] = struct.unpack('>H', d[i + 4:i + 6])[0]
        out.append(ent)
        i += 2 + ln
        if m == 0xDA:  # scan data: skip to next non-RST marker
            j = i
            rst = 0
            while j < len(d) - 1:
                if d[j] == 0xFF and d[j + 1] != 0 and not (0xD0 <= d[j + 1] <= 0xD7) and d[j + 1] != 0xFF:
                    break
                if d[j] == 0xFF and 0xD0 <= d[j + 1] <= 0xD7:
                    rst += 1
                j += 1
            out.append({'off': i, 'name': 'ECS', 'len': j - i, 'rst_markers': rst})
            i = j
    return out


def png(d):
    out, i = [], 8
    while i + 8 <= len(d):
        ln, typ = struct.unpack('>I4s', d[i:i + 8])
        ent = {'off': i, 'name': typ.decode('latin1'), 'len': ln}
        if typ in (b'tEXt', b'zTXt', b'iTXt'):
            ent['key'] = d[i + 8:i + 8 + min(ln, 80)].split(b'\0')[0].decode('latin1')
        if typ == b'iCCP':
            ent['key'] = d[i + 8:i + 8 + 80].split(b'\0')[0].decode('latin1')
        out.append(ent)
        i += 12 + ln
        if typ == b'IEND':
            if i < len(d):
                out.append({'off': i, 'name': 'TRAILER', 'len': len(d) - i, 'head': _p(d[i:i + 24])})
            break
    return out


def riff(d):
    out = [{'off': 0, 'name': 'RIFF', 'size_field': struct.unpack('<I', d[4:8])[0],
            'expected': len(d) - 8, 'form': d[8:12].decode('latin1')}]
    i = 12
    while i + 8 <= len(d):
        typ, ln = struct.unpack('<4sI', d[i:i + 8])
        ent = {'off': i, 'name': typ.decode('latin1'), 'len': ln}
        if typ == b'VP8X':
            f = d[i + 8]
            ent['flags'] = {'ICC': bool(f & 0x20), 'ALPHA': bool(f & 0x10), 'EXIF': bool(f & 0x08),
                            'XMP': bool(f & 0x04), 'ANIM': bool(f & 0x02), 'raw': f}
        out.append(ent)
        i += 8 + ln + (ln & 1)
    if i < len(d):
        out.append({'off': i, 'name': 'TRAILER', 'len': len(d) - i})
    return out


def bmff(d, start=0, end=None, depth=0, out=None):
    out = [] if out is None else out
    end = len(d) if end is None else end
    i = start
    while i + 8 <= end:
        sz, typ = struct.unpack('>I4s', d[i:i + 8])
        hdr = 8
        if sz == 1:
            sz = struct.unpack('>Q', d[i + 8:i + 16])[0]
            hdr = 16
        elif sz == 0:
            sz = end - i
        if sz < hdr or i + sz > end:
            out.append({'off': i, 'depth': depth, 'name': 'BAD(%r)' % typ, 'len': sz})
            break
        if all(32 <= c < 127 or c == 0xA9 for c in typ):
            name = typ.decode('latin1')
        else:
            name = 'key#%d' % struct.unpack('>I', typ)[0]  # mdta-indexed ilst item
        ent = {'off': i, 'depth': depth, 'name': name, 'len': sz}
        if typ == b'uuid':
            u = d[i + hdr:i + hdr + 16]
            ent['uuid'] = u.hex()
            ent['kind'] = 'C2PA' if u == C2PA_UUID else ('XMP' if u == XMP_UUID else '?')
            if u == C2PA_UUID:
                ent['purpose'] = d[i + hdr + 20:i + hdr + 40].split(b'\0')[0].decode('latin1')
        out.append(ent)
        if typ in BMFF_CONTAINERS:
            cs = i + hdr
            if typ == b'meta':
                # full box in ISO; QuickTime meta is not
                if d[cs + 4:cs + 8] != b'hdlr':
                    cs += 4
            bmff(d, cs, i + sz, depth + 1, out)
        elif typ in (b'stsd',):
            pass
        elif typ == b'iinf':
            pass
        elif typ == b'keys':
            n = struct.unpack('>I', d[i + 12:i + 16])[0]
            j, ks = i + 16, []
            for _ in range(n):
                kl = struct.unpack('>I', d[j:j + 4])[0]
                ks.append(d[j + 8:j + kl].decode('utf8', 'replace'))
                j += kl
            ent['keys'] = ks
        i += sz
    return out


def gif(d):
    out = [{'off': 0, 'name': d[:6].decode('latin1')}]
    i = 6
    w, h, flags = struct.unpack('<HHB', d[i:i + 5])
    i += 7
    if flags & 0x80:
        i += 3 * (2 << (flags & 7))

    def subblocks(j):
        data = b''
        while d[j] != 0:
            data += d[j + 1:j + 1 + d[j]]
            j += 1 + d[j]
        return j + 1, data
    frames = 0
    while i < len(d):
        b = d[i]
        if b == 0x3B:
            out.append({'off': i, 'name': 'TRAILER(3B)'})
            i += 1
            if i < len(d):
                out.append({'off': i, 'name': 'GARBAGE', 'len': len(d) - i})
            break
        if b == 0x21:
            label = d[i + 1]
            j = i + 2
            if label == 0xFF:
                bl = d[j]
                ident = d[j + 1:j + 1 + bl]
                j2, data = subblocks(j + 1 + bl)
                out.append({'off': i, 'name': 'AppExt', 'id': _p(ident), 'len': j2 - i})
                i = j2
            else:
                j2, data = subblocks(j)
                nm = {0xFE: 'CommentExt', 0xF9: 'GCE', 0x01: 'PlainTextExt'}.get(label, 'Ext%02X' % label)
                ent = {'off': i, 'name': nm, 'len': j2 - i}
                if label == 0xFE:
                    ent['text'] = _p(data[:80])
                if label != 0xF9:
                    out.append(ent)
                i = j2
        elif b == 0x2C:
            flags = d[i + 9]
            j = i + 10
            if flags & 0x80:
                j += 3 * (2 << (flags & 7))
            j += 1
            j, _ = subblocks(j)
            frames += 1
            i = j
        else:
            out.append({'off': i, 'name': 'UNKNOWN', 'len': len(d) - i})
            break
    out.append({'name': 'frames', 'count': frames})
    return out


def detect(d):
    if d[:3] == b'\xff\xd8\xff':
        return 'jpeg'
    if d[:8] == b'\x89PNG\r\n\x1a\n':
        return 'png'
    if d[:4] == b'RIFF':
        return 'riff'
    if d[:6] in (b'GIF87a', b'GIF89a'):
        return 'gif'
    if d[4:8] in (b'ftyp', b'moov', b'mdat', b'free', b'wide', b'uuid', b'styp'):
        return 'bmff'
    return None


def dump(path):
    d = open(path, 'rb').read()
    k = detect(d)
    fn = {'jpeg': jpeg, 'png': png, 'riff': riff, 'gif': gif, 'bmff': bmff}.get(k)
    return k, (fn(d) if fn else [])


def main():
    args = sys.argv[1:]
    as_json = '--json' in args
    args = [a for a in args if a != '--json']
    for p in args:
        try:
            k, s = dump(p)
        except Exception as e:  # noqa
            print(p, 'ERROR', e)
            continue
        if as_json:
            print(json.dumps({'file': p, 'kind': k, 'structure': s}))
            continue
        print('==', p, k)
        for e in s:
            ind = '  ' * (e.get('depth', 0) + 1)
            rest = {x: y for x, y in e.items() if x not in ('depth', 'name', 'off')}
            print('%s%-6s @%s %s' % (ind, e['name'], e.get('off', ''), rest if rest else ''))


if __name__ == '__main__':
    main()
