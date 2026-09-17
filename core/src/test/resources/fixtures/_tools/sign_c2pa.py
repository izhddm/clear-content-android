#!/usr/bin/env python3
"""Sign an asset with a real C2PA manifest (CAI ES256 *test* certificate, publicly
available in contentauth/c2pa-python tests/fixtures -> validates as signingCredential.untrusted).
Adds a c2pa.actions.v2 'c2pa.created' action with digitalSourceType=trainedAlgorithmicMedia.
usage: sign_c2pa.py SRC DST [mime]      (run with the tools venv python)"""
import sys, json, os, c2pa
HERE = os.path.dirname(os.path.abspath(__file__))
CERTS = os.environ.get('C2PA_CERTS_DIR') or next(
    (os.path.join(d, 'certs') for d in (HERE, os.environ.get('CLEAR_CONTENT_TOOLS') or
                                        os.path.expanduser('~/.cache/clear-content-tools'))
     if os.path.exists(os.path.join(d, 'certs', 'es256_certs.pem'))), os.path.join(HERE, 'certs'))
src, dst = sys.argv[1], sys.argv[2]
mime = sys.argv[3] if len(sys.argv) > 3 else None
manifest = {
    "claim_generator_info": [{"name": "ClearContent-FixtureGen", "version": "1.0"}],
    "title": os.path.basename(dst),
    "assertions": [
        {"label": "c2pa.actions.v2",
         "data": {"actions": [{
             "action": "c2pa.created",
             "digitalSourceType": "http://cv.iptc.org/newscodes/digitalsourcetype/trainedAlgorithmicMedia",
             "softwareAgent": {"name": "Fake AI Image Generator", "version": "0.1"}}]}},
    ],
}
info = c2pa.C2paSignerInfo(alg=b"es256",
                           sign_cert=open(os.path.join(CERTS, 'es256_certs.pem'), 'rb').read(),
                           private_key=open(os.path.join(CERTS, 'es256_private.key'), 'rb').read(),
                           ta_url=None)
signer = c2pa.Signer.from_info(info)
b = c2pa.Builder(json.dumps(manifest))
if mime:
    with open(src, 'rb') as s, open(dst, 'w+b') as d:
        b.sign(signer, mime, s, d)
else:
    b.sign_file(src, dst, signer)
print('signed', dst, os.path.getsize(dst))
