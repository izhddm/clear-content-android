# Clear Content: test fixtures

> **Licensing.** The `c2pa_*` files come from, or were signed with the public test credentials of, the Content Authenticity Initiative repositories [c2pa-rs](https://github.com/contentauth/c2pa-rs), [c2pa-node](https://github.com/contentauth/c2pa-node) and [c2pa-python](https://github.com/contentauth/c2pa-python) (MIT OR Apache-2.0); see §1 for the per-file origin. All other files were generated for this project and are covered by the repository's Apache-2.0 licence. The test signing keys are **not** stored here; `_tools/setup_tools.sh` downloads them.

These are media files that carry provenance or AI-generator metadata: C2PA/JUMBF, XMP, IPTC, EXIF, PNG text chunks, RIFF chunks, GIF extensions and MP4/MOV `udta`/`meta`/`uuid` boxes. They are inputs for the stripper tests. Every file is under 3 MB, and the directory is about 6.8 MB in total.

* `c2pa_*`: files with a **real, cryptographically valid C2PA manifest** (see §1).
* `ai_*` and `progressive.jpg`: **synthetic** files that mimic what AI generators and tools write (see §2). The C2PA data in them is *fake*: it has JUMBF structure but no valid signature.
* `control_*`: negative controls that are already "clean" (see §3).
* `verify_baseline.jsonl`: output of `_tools/verify_media.py` on every fixture, one JSON line per file.
* `_tools/`: the scripts that generated and verified these files (see §5).

## What "clean" means (expected stripper output)

`verify_media.py` reports `clean: true` only when **all** of the following hold:

1. **C2PA reader:** c2pa-python (or c2patool) finds no manifest store. That covers embedded stores and remote references via `dcterms:provenance`.
2. **ExifTool** (`-j -G1 -a -u -s`) reports no metadata groups apart from these:
   - **Structural groups:** `File` (except `File:Comment`, which is the JPEG COM segment), `JFIF`, `Adobe` (APP14), `Composite`, basic `PNG` IHDR/gAMA/cHRM/sRGB/pHYs tags, basic `RIFF` (VP8/VP8X/ANIM) tags, `GIF` (except Comment), and `QuickTime`/`Track*`/`Meta` stream info (except user-metadata tag names).
   - **Allowed:** `ICC_Profile` / `ICC-*` (reported as `allowed.icc_profile`), and `IFD0:Orientation`.
   - **Everything else is flagged.** That includes `XMP-*`, `IPTC`, `Photoshop`, `ExifIFD`, `GPS`, `IFD1` (thumbnail), other `IFD0` tags, `JUMBF`, `CBOR`, `JSON`, `Jpeg2000` (C2PA boxes as ExifTool sees them), PNG text keys, `ItemList`, `Keys`, `UserData` and `VideoKeys`.
3. **Own container scan** (independent of ExifTool) finds none of the following:
   - **JPEG:** APP1-XMP, APP11, APP13, COM, MPF, unknown APPn, or data after EOI.
   - **PNG:** `tEXt`/`zTXt`/`iTXt`/`tIME`/`caBX`/unknown chunks, or data after IEND. CRCs are also checked.
   - **WebP:** `XMP `/`C2PA`/unknown chunks, or data after the RIFF. The RIFF size and VP8X flag consistency are also checked.
   - **GIF:** comment/plain-text extensions, or application extensions other than NETSCAPE2.0/ANIMEXTS1.0/ICCRGBG1.
   - **BMFF:** top-level `uuid`; any payload under `moov/udta` or `moov|trak/meta`, or a non-empty `keys` box (an empty `udta/meta/hdlr/ilst` shell is fine); HEIF `mime`/XMP items.
   - **EXIF:** an EXIF block (JPEG APP1, PNG `eXIf`, WebP `EXIF`) is allowed only if IFD0 contains **nothing but Orientation**. It must have no sub-IFDs, no GPS and no IFD1 thumbnail.
     - `--lenient-exif` also accepts benign baseline tags: X/YResolution, ResolutionUnit, YCbCrPositioning, ExifVersion, ColorSpace and pixel dimensions.
     - The flag exists because ExifTool itself adds YCbCrPositioning when it copies Orientation.
4. **Raw byte search:** no case-insensitive hits for `c2pa`, `jumb`, `trainedAlgorithmicMedia`, `compositeWithTrainedAlgorithmicMedia`, `digitalsourcetype`, `openai`, `chatgpt`, `dall-e`, `gemini`, `imagen`, `synthid`, `midjourney`, `stable diffusion`, `comfyui`, `parameters`, `firefly`, `AIGC`, `xmpmeta` or `Exif\0\0`. Two kinds of hit are ignored:
   - **Likely random matches:** 4-byte markers (`c2pa`/`jumb`/`AIGC`) sitting in compressed data rather than in a box header, a JUMBF description box (`jumd`) type/label, or text. `c2pa_signed.gif` has one such hit inside its LZW data.
   - **`Exif\0\0` for allowed EXIF:** the header of orientation-only EXIF.

   Note that `trainedAlgorithmicMedia` also matches inside `compositeWithTrainedAlgorithmicMedia`.

`pass` = `clean` **and** decodable **and** no structural errors. Decodable means Pillow (plus pillow-heif) loads every frame; for video it means `ffprobe` finds streams and `ffmpeg -v error -i f -map 0 -f null -` prints no errors.

For **every** fixture except `control_*`, the stripper output should reach `pass: true`, with these conditions:
- Pixels and samples are unchanged, or re-encoded only where the stripper chooses to.
- Orientation is preserved: either keep EXIF Orientation only, or bake the rotation into the pixels and drop the tag.

## 1. Real C2PA fixtures

The readers are c2pa-python 0.37.10 (c2pa-rs SDK 0.90.19) and c2patool 0.27.22.

- **Remote fetching:** the verifier turns `verify.remote_manifest_fetch` off.
- **`Valid` state:** the manifest is structurally and cryptographically valid. All of these are signed with **test** certificates, so every one also reports the failure code `signingCredential.untrusted`. That is expected.

| File | Bytes | Source | Where the manifest lives | Reader result | Other metadata |
|---|---:|---|---|---|---|
| `c2pa_CA.jpg` | 166 864 | c2pa-rs `sdk/tests/fixtures/CA.jpg` | **One** 117 241 B JUMBF store **split across 2 APP11 segments** (same En=529, Z=1 and Z=2; the 2nd packet repeats the LBox/TBox header). Contains 1 manifest with an ingredient (incl. ingredient thumbnail) | Valid; `make_test_images/0.33.1 c2pa-rs/0.33.1`; no DST | APP0 JFIF only |
| `c2pa_C.jpg` | 132 518 | c2pa-rs `C.jpg` | 1 × APP11 (En=529, Z=1) | Valid; `digitalSourceType=…/algorithmicMedia` | JFIF |
| `c2pa_C_cawg.jpg` | 139 636 | c2pa-rs `C_with_CAWG_data.jpg` | 1 × APP11 (also includes CAWG identity + `cawg.training-mining` assertions) | Valid (2 × `signingCredential.untrusted`); DST `" http://…/digitalCapture"` (**leading space in the real data**) | JFIF |
| `c2pa_remote_firefly.jpg` | 690 069 | c2pa-node `tests/fixtures/cloud-only-firefly.jpg` (Photoshop 24.5 + Firefly) | **No embedded store.** `XMP-dcterms:provenance = https://cai-manifests.adobe.com/manifests/adobe-urn-uuid-381760d4-…` (APP1 XMP) | c2pa-python with remote fetch off → `Remote: must fetch remote manifests from url …` (status `remote`). c2patool fetches it → Valid, 4 manifests | APP1 XMP (dcterms:provenance), APP13 Photoshop IRB (IPTCDigest only), APP2 ICC (sRGB IEC61966-2.1), APP14 Adobe; progressive |
| `c2pa_video1.mp4` | 828 571 | c2pa-rs `video1.mp4` | top-level `uuid` d8fec3d6-… (**right after `ftyp`, before `moov`**), BMFF hash v2 | Valid; `TestApp c2patool/0.6.2 c2pa-rs/0.28.2` | `moov/udta` ©TIM/©TSC/©TSZ (timecode), top-level `uuid` XMP (be7acfcb-…, xmpDM/xmpMM/dcterms:provenance), `free` 54 KB; 720x1280 H.264 + AAC |
| `c2pa_dashinit.mp4` | 4 765 | c2pa-rs `dashinit.mp4` (DASH **init segment only**) | `uuid` C2PA after `ftyp`/`mfra`/`free`, before `moov`; Merkle BMFF hash | **Invalid** (`assertion.bmffHash.mismatch` because the media segments are missing), still counted as present | `moov/udta/meta/ilst/©too` (Lavf). **Not decodable** (no samples), which is expected. Use it for "strip without decoding" tests only |
| `c2pa_signed.png` | 362 353 | c2pa-rs `sample1.png`, signed by us | `caBX` chunk (63 084 B) **directly after IHDR** | Valid; `ClearContent-FixtureGen/1.0`; DST `trainedAlgorithmicMedia` | `eXIf` **after IDAT** (Canon EOS, Lightroom), 49 × `tEXt` after IDAT (`date:*`, `exif:*`, `icc:*`, from ImageMagick), gAMA, sRGB, bKGD, pHYs |
| `c2pa_signed.webp` | 499 334 | c2pa-rs `sample1.webp`, signed by us | `C2PA` RIFF chunk (448 918 B, includes a large thumbnail) **appended to a *simple* lossy WebP (VP8, no VP8X)** | Valid; DST trainedAlgorithmicMedia | none |
| `c2pa_signed.gif` | 1 025 747 | c2pa-rs `sample1.gif`, signed by us | Application Extension `C2PA_GIF` (285 KB of sub-blocks) placed before NETSCAPE2.0 | Valid; DST trainedAlgorithmicMedia | Comment extension "GIF converted with https://ezgif.com/webp-to-gif"; 90 frames, loop |
| `c2pa_signed.heic` | 307 252 | c2pa-python `files-for-signing-tests/sample1.heic`, signed by us | top-level `uuid` C2PA **between `ftyp` and `meta`**. c2pa-rs shifted the `iloc` offsets accordingly | Valid; DST trainedAlgorithmicMedia | none (brand `mif1`; 1440x960) |
| `c2pa_signed.avif` | 111 080 | c2pa-rs `sample1.avif`, signed by us | top-level `uuid` C2PA between `ftyp` and `meta` (`iloc` shifted) | Valid; DST trainedAlgorithmicMedia | `hdlr` name "cavif - https://github.com/link-u/cavif" (not flagged); 10-bit 4:4:4 |
| `c2pa_signed.mov` | 987 474 | c2pa-python `C-recorded-as-mov.mov` (macOS screen recording), signed by us | top-level `uuid` C2PA after `ftyp`, before `wide`/`mdat`/`moov` (moov at end) | Valid; DST trainedAlgorithmicMedia | `moov/meta` keys `com.apple.quicktime.make/model/software/creationdate` (Apple / Mac15,9 / macOS 14.6.1), `trak/meta` key `com.apple.quicktime.pixeldensity`; 2750x1834 H.264, no audio |
| `c2pa_signed_ai.jpg` | 112 440 | synthetic 640x480 Pillow JPEG with no metadata, signed by us | 71 358 B store split across 2 × APP11 (En=529, Z=1/2) | Valid; DST trainedAlgorithmicMedia | JFIF only, which isolates the "C2PA only" case |
| `c2pa_signed_ai.mp4` | 98 754 | synthetic 2 s 320x240 H.264+AAC, faststart, `-fflags +bitexact`, signed by us | `uuid` C2PA after `ftyp`, before `moov` (c2pa-rs shifted `stco`) | Valid; DST trainedAlgorithmicMedia | empty `udta/meta/hdlr/ilst` shell only (not flagged) |

**Signing**: `_tools/sign_c2pa.py` signs with c2pa-python `Builder` and the public CAI test ES256 certificate (`contentauth/c2pa-python/tests/fixtures/es256_certs.pem` and `es256_private.key`).
- **Manifest content:** `claim_generator_info = ClearContent-FixtureGen/1.0` plus one `c2pa.actions.v2` action: `c2pa.created`, `digitalSourceType = http://cv.iptc.org/newscodes/digitalsourcetype/trainedAlgorithmicMedia`, `softwareAgent = "Fake AI Image Generator"`.
- **Thumbnail:** the SDK adds a claim thumbnail automatically, which is why the signed WebP and GIF are larger.

**Real signed samples not found**: the CAI repos only have *unsigned* `sample1.*` inputs for PNG, WebP, GIF, HEIC and AVIF. That is why these were signed locally with the real SDK. The embedding code is therefore the reference c2pa-rs implementation, and only the certificate is a test one.

## 2. Synthetic "AI generator" fixtures

`_tools/make_fixtures.py` builds these with Pillow 12.3 (+AVIF), pillow-heif 1.7, ExifTool 13.59, ffmpeg 8.0.1 and some Python byte patching.
- **Picture content:** a landscape with a red "label" box in the **top-left** corner and a yellow arrow pointing **up**, so orientation handling can be checked visually.
- **Fake C2PA store** (`fake_jumbf_store`): `jumb{jumd(c2pa, label "c2pa")}` containing `jumb{jumd(c2ma, "urn:c2pa:…")}`, which in turn holds:
  - `c2as` → `c2pa.actions.v2` (json box: `c2pa.created`, `digitalSourceType = trainedAlgorithmicMedia`, `softwareAgent.name = <generator>`)
  - `c2cl` (json claim, `claim_generator = "<generator> c2pa-fake/0.0"`)
  - `c2cs` (cbor stub)
  - `free` padding

  c2pa-python/c2patool report these as **`Other: required JUMBF box not found`** (status `error`, counted as *present*). ExifTool parses them as JUMBF/JSON/CBOR.

| File | Bytes | Produced by | Exactly what it contains (in file order) | EXIF Orientation | Notes / expected clean output |
|---|---:|---|---|:---:|---|
| `ai_xmp_iptc.jpg` | 73 751 | Pillow (q90, ICC) → ExifTool → append trailer | **APP0** JFIF · **APP1 Exif** (IFD0: Make=Google, Model=Pixel 9 Pro, Software=**Gemini**, Orientation=**6**, X/YResolution, ResolutionUnit, YCbCrPositioning; ExifIFD: DateTimeOriginal, ExifVersion, ComponentsConfiguration, ColorSpace; **GPS** 59.9386 N / 30.3141 E / alt 12 m; **IFD1 thumbnail** 160x120 JPEG) · **APP13** Photoshop IRB with **IPTC** (Caption-Abstract "AI-generated landscape (Gemini)", Credit "Made with Google AI", CodedCharacterSet UTF8) · **APP1 XMP** (`Iptc4xmpExt:DigitalSourceType=…/trainedAlgorithmicMedia`, `xmp:CreatorTool="Google AI"`, `photoshop:Credit="Made with Google AI"`, `dc:description`) · **APP2 ICC_PROFILE** (lcms sRGB) · **COM** "Generated by Nano Banana" · baseline DCT 800x600 4:2:0 · EOI · **64 B trailer** `\0\0TRAILING-GARBAGE c2pa-trailer JUMB\0`+random | **6** (Rotate 90 CW; displays as 600x800) | Clean output: only JFIF + ICC + orientation-only EXIF (Orientation=6), or rotated pixels with no EXIF. No trailer. The `c2pa`/`jumb` byte hits come from the trailer text only (no real C2PA; the reader reports none) |
| `ai_orient1.jpg` | 75 199 | same as above, Orientation=1, no trailer, + inserted APP11 | same segments as `ai_xmp_iptc.jpg` (Orientation=**1**, same GPS/thumbnail/IPTC/XMP/ICC/COM), plus **APP11** (1 510 B) inserted after APP2: `'JP' 0x0001 0x00000001` + fake JUMBF store (generator "Gemini") | **1** | Reader: `error` (present). Clean output: JFIF + ICC (+ optional Orientation=1 EXIF) |
| `progressive.jpg` | 39 633 | Pillow `progressive=True, restart_marker_blocks=16` → ExifTool | APP0 JFIF · **APP1 XMP** (`xmp:CreatorTool="Adobe Firefly"`, `DigitalSourceType=…/compositeWithTrainedAlgorithmicMedia`, `dc:description`) · SOF2 640x480 · **DRI** interval 16 · 10 scans with **RST0-7 markers** inside the entropy-coded data (74–299 per scan) · EOI | none | Exercises segment walking across progressive scans, DHT between scans, and RSTn. Clean output: JFIF + SOF2/DRI/scans untouched |
| `ai_a1111.png` | 318 669 | Pillow (RGBA 512x512) + manual chunk insertion | IHDR · **iCCP** ("ICC Profile", sRGB) · **tEXt `parameters`** (A1111: "…Steps: 20, Sampler: Euler a, CFG scale: 7, Seed: 1234567890, Size: 512x512, Model hash: 31e35c80fc, Model: sdxl, Version: v1.10.1") · **iTXt `XML:com.adobe.xmp`** (DigitalSourceType trainedAlgorithmicMedia, CreatorTool "Stable Diffusion web UI") · **tIME** 2025-08-26 12:34:56 · **caBX** (1 200 B fake store, generator "OpenAI ChatGPT", valid CRC) · **eXIf** (MM; Orientation=1, Software=**ComfyUI**) · IDAT × n · **zTXt `Comment`** *after IDAT* ("Generated with Stable Diffusion (AUTOMATIC1111)") · IEND · **81 B trailer** (`\0GARBAGE-AFTER-IEND parameters c2pa jumb\0`+random) | **1** | ExifTool 13.59 **does not report the eXIf here** (it only warns "IFD0 pointer references previous JUMBFBox directory"); the container scan does report it. Clean output: IHDR, iCCP, IDAT, IEND (+ optional eXIf with Orientation only), nothing after IEND |
| `ai_comfy_orient.png` | 56 318 | Pillow | IHDR (RGB 256x192) · **tEXt `prompt`** (ComfyUI API JSON: KSampler, CheckpointLoaderSimple `sd_xl_base_1.0.safetensors`, CLIPTextEncode) · **tEXt `workflow`** (ComfyUI graph JSON) · **eXIf** (MM; Orientation=**6**, Software=ComfyUI) · IDAT · IEND | **6** | Must keep orientation: either eXIf with only Orientation=6, or pixels rotated to 192x256 |
| `ai_meta.webp` | 10 100 | Pillow (lossy q80, icc/exif/xmp) + RIFF rebuild | RIFF (size correct) · **VP8X** flags 0x2C = ICC+EXIF+XMP (no alpha/anim) · **ICCP** (588 B) · VP8 640x480 · **EXIF** (no `Exif\0\0` prefix; Make=OpenAI, Model=gpt-image-1, Software=**OpenAI**, Orientation=1) · **XMP** (DigitalSourceType trainedAlgorithmicMedia, CreatorTool "OpenAI ChatGPT") · **`C2PA`** chunk (**odd size, 1 801 B, followed by a RIFF pad byte**; fake store, generator "OpenAI ChatGPT") at the end, as c2pa-rs places it | **1** | Clean output: VP8X with flags recomputed (ICC only, or ICC+EXIF if orientation-only EXIF is kept), ICCP, VP8, and the RIFF size fixed. A simple VP8 file is also fine if the ICC profile is dropped deliberately |
| `ai_lossless_alpha.webp` | 73 958 | Pillow lossless RGBA | RIFF · **VP8X** 0x14 = ALPHA+XMP · VP8L 320x240 · **XMP** (CreatorTool "Midjourney", DST trainedAlgorithmicMedia) | none | Clean output: VP8X with the ALPHA flag only + VP8L, or plain VP8L (VP8L carries alpha by itself) |
| `ai_anim.webp` | 6 032 | Pillow `save_all` 3 frames 160x120, loop 0 | RIFF · **VP8X** 0x1E = ALPHA+EXIF+XMP+ANIM · ANIM · 3 × ANMF · **EXIF** (**starts with an `Exif\0\0` prefix**, which Pillow writes for animated files; ExifTool warns "[minor] Improper EXIF header"; Orientation=1, Software="Runway Gen-3") · **XMP** (CreatorTool "Runway", DST trainedAlgorithmicMedia) | **1** | Clean output: VP8X (ALPHA+ANIM) + ANIM + 3 ANMF; the animation must still have 3 frames |
| `ai_anim.gif` | 43 813 | Pillow `save_all` 3 frames, loop=0, `comment=` → ExifTool | GIF89a · **NETSCAPE2.0** (loop) · **Comment Extension** "Made with Midjourney" · **Application Extension "XMP DataXMP"** (3 330 B, ExifTool; CreatorTool "Midjourney v7", DST trainedAlgorithmicMedia) · 3 frames with GCE · trailer 0x3B | n/a | Clean output: GIF89a + NETSCAPE2.0 + 3 frames (loop preserved) |
| `ai_heic.heic` | 59 837 | pillow-heif (x265, q60, exif/xmp) → ExifTool | `ftyp` (heic; mif1, heic, miaf) · `meta` (iinf items: hvc1 primary, **Exif**, **mime `application/rdf+xml`**) · `mdat` | **1** | EXIF item: Make=Google, Model=Pixel 9 Pro, Software=**Gemini**, Orientation=1, plus **GPS** added by ExifTool. XMP item: CreatorTool "Google AI", DST trainedAlgorithmicMedia, photoshop:Credit "Made with Google AI". Clean output: EXIF and XMP items (and their `iloc`/`iref`/`iinf` entries) removed, or EXIF reduced to Orientation, with `iloc` offsets still valid |
| `ai.avif` | 8 731 | Pillow AVIF (q60, icc/exif/xmp) | `ftyp` (avif) · `meta` (iinf: av01, **Exif**, **mime XMP**; `colr` prof = ICC + nclx) · `mdat` | none (Pillow drops Orientation=1 from AVIF EXIF) | EXIF item: Software="**Adobe Firefly**". XMP item: CreatorTool "Adobe Firefly", DST **compositeWithTrainedAlgorithmicMedia**. The ICC profile is in `ipco/colr` (allowed). `Exif\0\0` byte hits: one is the `infe` item type+name ("ExifExif\0"), one is the payload header |
| `ai_video_faststart.mp4` | 94 537 | ffmpeg (`+faststart+use_metadata_tags`) → ExifTool (XMP, encoder) → **Python inserts a C2PA `uuid` after `ftyp` and shifts all 98 `stco` entries by +2 093** | `ftyp` · **`uuid` C2PA** (d8fec3d6-1b0e-483c-9297-5828877ec481, FullBox v0, purpose "manifest", merkle_offset 0, 2 048 B fake store, generator "OpenAI Sora") · `moov` [mvhd, 2 × trak (stco), **udta**/{**meta**/{hdlr mdta, **keys**: `title`, `comment`, `location`, `AIGC`, `encoder`; **ilst** 5 items}, **loci** (3GPP location)}] · `free` · **`uuid` XMP** (be7acfcb-…, ExifTool: DST trainedAlgorithmicMedia, CreatorTool "OpenAI Sora") · `mdat` | n/a | Values: title "Sora sample clip", comment "Generated by OpenAI Sora", encoder "**Sora**", AIGC `{"Label":"1","ContentProducer":"test"}`, location `+59.9386+030.3141/`. Decodes cleanly; packet MD5s are identical to an ExifTool-stripped copy, which proves the offset fix. **Removing the leading uuid means shifting `stco` by −2 093** (and the XMP uuid removal shifts it too) |
| `ai_video_moovend.mp4` | 91 333 | ffmpeg default (moov after mdat) → Python rewrites `©too` → append boxes | `ftyp` · `free` · `mdat` · `moov` [**udta**/{**meta**/{hdlr mdir, **ilst**: `©nam` "Sora sample clip", `©ART` "OpenAI Sora", `©too` "Sora", `©cmt` "Generated by OpenAI Sora"}, **loci**}] · **`uuid` C2PA** (2 093 B, fake store, generator "OpenAI Sora") · **`free`** (32 B) | n/a | Trailing boxes after moov; no offset changes are needed when removing them. (ExifTool is not used on this file because it moves moov in front of mdat when rewriting) |
| `ai_video_frag.mp4` | 99 535 | ffmpeg `-movflags frag_keyframe+empty_moov -g 12`; `©too` patched in place (same length) | `ftyp` (iso6) · `moov` [mvhd, trak, **mvex**, **udta**/{**meta**/{hdlr mdir, **ilst**: `©nam` "Kling sample clip", `©too` "Kling AI" (space-padded), `©cmt` "AI generated (Kling)"}, **loci**}] · 5 × (`moof` + `mdat`) · `mfra` | n/a | No raw marker hits ("Kling" is not in the marker list); detected via ExifTool ItemList/UserData and the container scan. ExifTool **cannot write** fragmented MP4 ("Can't yet handle movie fragments"). Changing the moov size shifts every `moof`: check `tfhd` base_data_offset / `tfra` offsets if you resize moov |
| `ai_video.mov` | 92 816 | ffmpeg `-f mov -movflags use_metadata_tags` → ExifTool | `ftyp` (qt) · `moov` [mvhd, 2 × trak, **udta**/{**meta**/{hdlr mdta, **keys**: `com.apple.quicktime.location.ISO6709` (+59.9386+030.3141+012.000/), `…software` "**Veo**", `…make` "Google", `…model` "Veo 3", `…creationdate` 2025-06-01T12:00:00+0300, `…description` "Generated with Google Veo", `title` "Veo sample", `encoder` (Lavf); **ilst** 8 items}, **XMP_** (ExifTool XMP: DST trainedAlgorithmicMedia, CreatorTool "Google Veo"), **©too** "Veo", **©xyz** GPS}] · `mdat` | n/a | ExifTool's rewrite put moov **before** mdat. QuickTime `meta` is a plain box (no version/flags), unlike ISO `meta` |

## 3. Negative controls (already clean)

| File | Bytes | Content | Verifier |
|---|---:|---|---|
| `control_clean_orient6.jpg` | 40 989 | Pillow JPEG 640x480 with JFIF, **APP1 Exif containing only IFD0 Orientation=6** (1 entry, no next IFD) and APP2 ICC | `clean: true`, `allowed.exif_orientation: 6`, `Exif\0\0` hit explained |
| `control_clean.mp4` | 86 272 | ffmpeg `-map_metadata -1 -fflags +bitexact +faststart` | `clean: true`; it still has an empty `udta/meta/hdlr/ilst` shell, which is *not* flagged |

A stripper should leave these semantically unchanged, ideally byte-identical.

## 4. Gotchas found while building these

* **c2pa-rs placement:** it inserts the BMFF `uuid` C2PA box **right after `ftyp`** in MP4, MOV, HEIC and AVIF, and rewrites `stco`/`co64` or `iloc` offsets to match. Removing it means shifting those offsets back.
* **WebP:** c2pa-rs appends the `C2PA` chunk even to **simple (non-VP8X) WebP** files. A stripper must not assume that a `C2PA` chunk implies VP8X.
* **PNG:** c2pa-rs puts `caBX` **immediately after IHDR**. Real-world PNGs also carry `eXIf`/`tEXt` **after IDAT**.
* **ExifTool 13.59 misses PNG EXIF:** it silently skips an `eXIf` chunk that comes after a `caBX` (warning "IFD0 pointer references previous JUMBFBox directory"). So a single ExifTool pass can call such a file EXIF-free when it is not. `verify_media.py` therefore also runs its own chunk scan and keeps every duplicate `Warning` key from the JSON.
* **ExifTool rewrites move `moov`:** when rewriting MP4/MOV it moves `moov` in front of `mdat` (seen on `ai_video.mov`).
* **ExifTool and fragments:** it refuses to write fragmented MP4.
* **ffmpeg CLI encoder tag:** it always overwrites `-metadata encoder=…` with `Lavf…`. The encoder values here were set afterwards.
* **Pillow quirks:**
  - Animated WebP gets an EXIF chunk with an `Exif\0\0` prefix; still WebP gets none.
  - AVIF drops Orientation from EXIF.
  - `PngInfo.add(..., after_idat=True)` is honoured only for private chunks, so zTXt had to be inserted by hand.
* **c2pa-python reports:**
  - Remote-only manifests: with `verify.remote_manifest_fetch=false` → `Remote: must fetch remote manifests from url …`. With the default settings, and with **c2patool**, the reader **fetches over the network**.
  - Fake or incomplete JUMBF → `Other: required JUMBF box not found`.
  - A truncated/unparseable asset → `Other: asset could not be parsed: …`. The verifier treats that as *inconclusive*, not as "C2PA present".
* **`c2pa_C_cawg.jpg` source type:** its `digitalSourceType` string has a leading space, so trim it before comparing.
* **`c2pa_dashinit.mp4`:** an init segment (no samples). ffmpeg cannot decode it, and the C2PA state is `Invalid` because the segments are missing.
* **Short markers:** 4-byte markers do occur by chance in compressed data (e.g. one `c2pa` in `c2pa_signed.gif`'s LZW stream). The verifier classifies hits by context.

## 5. Tools (`_tools/`)

| Script | Purpose |
|---|---|
| `setup_tools.sh [DIR]` | Downloads the ExifTool perl distribution and c2patool 0.27.22, creates a venv with `c2pa-python` + Pillow + pillow-heif, and fetches the CAI test certificate. Default DIR is `$CLEAR_CONTENT_TOOLS` or `~/.cache/clear-content-tools`. Nothing is installed system-wide. |
| `verify_media.py [--pretty] [--no-decode] [--lenient-exif] FILE…` | Independent verifier; prints one JSON line per file (never crashes on a bad file). **Run it with the venv python** so c2pa-python is used; otherwise it falls back to c2patool. |
| `dump_structure.py [--json] FILE…` | Lists JPEG segments (+RST count), PNG chunks, RIFF chunks (+VP8X flags), BMFF box tree (+uuid kind, mdta keys) and GIF blocks. |
| `make_fixtures.py OUT_DIR` | Regenerates the synthetic `ai_*`/`progressive.jpg` files. The structure is reproducible; the bytes are not, because the Pillow noise is unseeded. |
| `sign_c2pa.py SRC DST [mime]` | Signs a file with a real C2PA manifest (test cert, DST trainedAlgorithmicMedia). |

Example:

```sh
_tools/setup_tools.sh                                   # once
T=~/.cache/clear-content-tools
$T/venv/bin/python _tools/verify_media.py stripped/*.jpg | jq -c '{file, clean, pass, clean_reasons}'
# compare against the input baseline
jq -c '{file, clean, c2pa: .c2pa.status, orient: .allowed.exif_orientation}' verify_baseline.jsonl
```

ExifTool is looked up in `$EXIFTOOL`, the script's own directory, `$CLEAR_CONTENT_TOOLS`, then `PATH`. c2patool is looked up the same way via `$C2PATOOL`. ffmpeg/ffprobe come from `PATH`, or from `$FFMPEG`/`$FFPROBE`.

Sources and licences:
- The `c2pa_*` inputs come from [contentauth/c2pa-rs](https://github.com/contentauth/c2pa-rs), [c2pa-python](https://github.com/contentauth/c2pa-python) and [c2pa-node](https://github.com/contentauth/c2pa-node) test fixtures (MIT/Apache-2.0 repositories).
- The synthetic files were generated from scratch.
