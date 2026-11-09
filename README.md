<h1 align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/images/logo-dark.png">
    <img src="docs/images/logo-light.png" alt="DocSmelt" width="380">
  </picture>
</h1>

<p align="center">
  Every PDF and document tool in one place: convert, merge, split, organize, crop, compress,<br>
  watermark, number, protect, sign and OCR PDF, Word, Excel, PowerPoint and image files.<br>
  Self-hosted (files never leave your server), in English and Kiswahili.
</p>

<p align="center">
  <img src="docs/images/home.png" alt="DocSmelt home screen with tool groups and search" width="760">
</p>

## Features

**Convert**
- **Convert** between `pdf`, `docx`, `xlsx`, `pptx`, `txt`, `png`, `jpg`, `bmp` and `gif` (plus reading legacy `xls`), one file or whole folders
- **Images to PDF**: photos and scans, one per page; phone photos are turned upright automatically
- **OCR** with Tesseract: scans and photos become searchable PDFs, Word or text (English, Swahili, French in Docker)

**Organize**
- **Merge** any mix of PDFs, Word, Excel, text and images into one PDF
- **Split** by page count or custom ranges
- **Organize pages** visually: rotate, delete, drag to reorder
- **Crop & resize**: trim white margins automatically, cut a fixed margin, or fit pages to A4/Letter/A3/A5/Legal
- **Extract images** from a PDF (JPEGs byte-for-byte, others as PNG)

**Edit & optimize**
- **Compress** PDFs by shrinking their images (a 13.5 MB scan became 621 KB in testing)
- **Watermark** with text or a logo: centred at any angle or tiled, with opacity and colour
- **Page numbers**: six positions, styles like "Page 1 of 10", skip the cover

**Protect & sign**
- **Protect** with an AES-256 password (optionally blocking printing or copying), or remove a password you know
- **Sign**: draw, type or upload a signature and drag it into place

**Web interface**
- Home screen with all tools grouped, plus search (in both languages; press <kbd>/</kbd>)
- **Live preview** on the right: the file you dropped and the result, page by page, with an Original/Result switch
- **Continue with this file**: send a result straight to the next tool (e.g. Merge → Compress → Protect) without re-uploading
- **English / Kiswahili**, **soft dark mode**, and **installable as an app** on phones and desktops (PWA)
- Drop files anywhere on the page

<table>
  <tr>
    <td align="center"><img src="docs/images/convert.png" alt="Converting an Excel file with the result previewed on the right" width="400"><br><b>Convert</b> with live preview</td>
    <td align="center"><img src="docs/images/watermark.png" alt="Watermarking a PDF with the result previewed" width="400"><br><b>Watermark</b> + continue with the result</td>
  </tr>
  <tr>
    <td align="center"><img src="docs/images/organize.png" alt="Organizing pages as thumbnails" width="400"><br><b>Organize pages</b>: rotate, delete, drag</td>
    <td align="center"><img src="docs/images/sign.png" alt="Placing a typed signature on a page" width="400"><br><b>Sign</b>: draw, type or upload</td>
  </tr>
  <tr>
    <td align="center"><img src="docs/images/ocr.png" alt="OCR of a scanned invoice into Word" width="400"><br><b>OCR</b>: scans to Word, text or searchable PDF</td>
    <td align="center"><img src="docs/images/dark-swahili.png" alt="Compress tool in Kiswahili with dark mode" width="400"><br><b>Kiswahili</b> and dark mode</td>
  </tr>
</table>

<p align="center"><img src="docs/images/mobile.png" alt="DocSmelt on a phone" width="220"><br>Works on phones too</p>

## Usage

```bash
mvn package                                    # builds target/docsmelt.jar (runs tests)
alias docsmelt='java -jar target/docsmelt.jar'

# Convert
docsmelt report.docx report.pdf
docsmelt slides.pdf page.png --dpi 300          # multi-page → page-1.png, page-2.png, ...
docsmelt scan.pdf cover.jpg --pages 1 -q 80     # page selection, JPEG quality

# Batch (files and folders; folder inputs skip files of other types)
docsmelt --to pdf docs/ notes.txt -o out/ -r -j 4

# PDF tools
docsmelt merge cover.jpg report.docx appendix.pdf -o book.pdf   # non-PDFs converted automatically
docsmelt split book.pdf                         # one file per page
docsmelt split book.pdf --every 10 -o parts/
docsmelt split book.pdf --ranges 1-3,4-9,10-    # one file per range
docsmelt split book.pdf --ranges 5-7            # extract pages

# Pages: rotate, delete, reorder (page numbers refer to the original)
docsmelt pages scan.pdf --rotate 90 -o fixed.pdf          # every page
docsmelt pages scan.pdf --rotate 180:2,4 -o fixed.pdf     # pages 2 and 4
docsmelt pages report.pdf --delete 3,7-9 --order 5,1-4 -o edited.pdf

# Images to PDF (a4 | letter | fit)
docsmelt images scan1.jpg scan2.jpg scan3.jpg -o scans.pdf
docsmelt images photos/*.png -o album.pdf --page-size fit --margin 0

# Compress (low | medium | high)
docsmelt compress big.pdf -o small.pdf --level high

# OCR (needs Tesseract; output type follows the extension)
docsmelt ocr scan.pdf -o searchable.pdf
docsmelt ocr receipt.jpg -o receipt.txt
docsmelt ocr barua.pdf -o barua.docx --lang swa+eng
docsmelt ocr --list-langs

# Excel and PowerPoint are ordinary conversions
docsmelt sales.xlsx sales.pdf
docsmelt deck.pptx slide.png --dpi 200          # one image per slide
docsmelt report.docx report.pptx

# Password (leave out the value to be prompted, so it's not in your shell history)
docsmelt protect contract.pdf -o locked.pdf --password --no-copy
docsmelt unlock locked.pdf -o open.pdf --password

# Page numbers ({n} and {total})
docsmelt number thesis.pdf -o numbered.pdf --format "Page {n} of {total}" --position bottom-right --pages 2- --start 1

# Sign (visual signature; position as fractions of the page, default bottom right of the last page)
docsmelt sign contract.pdf --signature sig.png -o signed.pdf --page 2 --x 0.6 --y 0.8 --width 0.3

# Watermark (text or image; center or tile)
docsmelt watermark report.pdf -o draft.pdf --text DRAFT --layout tile --opacity 0.15
docsmelt watermark report.pdf -o branded.pdf --image logo.png --angle 0 --size 0.3

# Crop & resize
docsmelt crop scan.pdf -o trimmed.pdf                 # trim white margins automatically
docsmelt crop scan.pdf -o trimmed.pdf --margin 10     # cut 10 mm from every side
docsmelt crop letter.pdf -o a4.pdf --resize a4

# Extract images
docsmelt extract-images brochure.pdf -o images/

docsmelt --help          # and: docsmelt <command> --help
```

Exit codes: `0` ok, `1` conversion failed, `2` bad usage.

### Unicode / fonts

PDF output embeds DejaVu Sans (Latin, Greek, Cyrillic and many symbols). For
other scripts such as Chinese, Japanese or Korean, add a TrueType fallback font:

```bash
docsmelt notes.txt notes.pdf --font /usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf
# or once for all runs:
export DOCSMELT_FONTS=/path/a.ttf:/path/b.ttf
```

Only `.ttf` fonts with TrueType outlines work (PDFBox limitation; `.ttc`/CFF `.otf` don't).
Characters no font covers (e.g. colour emoji) become `?`.

## Web UI

```bash
docsmelt serve                    # http://127.0.0.1:8080 (this computer only)
docsmelt serve --host 0.0.0.0     # reachable from your network
```

A home screen with 13 tools in four groups and search, a page per tool with a
live preview on the right, "continue with this file" between tools, English and
Kiswahili, light and soft dark themes, and install-as-app support (manifest +
service worker; the service worker caches only the interface, never files).
Passwords are never logged. Icons come from Font Awesome, served from the jar (no CDN).
Uploads are processed in a private temp folder that is deleted as soon as the
response is sent.

Limits (all configurable on `serve`): `--max-upload-mb 50`, `--max-files 20`,
`--rate-limit 30` requests/minute per IP. Concurrent conversions are capped at
the number of CPU cores.

API (multipart/form-data, field `files`):

| Endpoint | Fields | Returns |
|---|---|---|
| `POST /api/convert` | `files` (1+), `to`, `pages?`, `dpi?`, `quality?` | the file, or a `.zip` if several |
| `POST /api/merge` | `files` (2+, in order) | `merged.pdf` |
| `POST /api/split` | `files` (1 PDF), `every?` or `ranges?` | a `.zip` of the parts |
| `POST /api/preview` | `files` (1 file), `page?`, `width?` | JSON page thumbnails (PDF), or one page of any format with `page` |
| `POST /api/organize` | `files` (1 PDF), `order` (`3,1,2`), `rotate?` (`1:90,3:270`) | `<name>-organized.pdf` |
| `POST /api/compress` | `files` (PDFs), `level?` (`low`/`medium`/`high`) | `<name>-compressed.pdf`, or a `.zip` |
| `POST /api/images-to-pdf` | `files` (images, in order), `pageSize?`, `margin?` (`none`/`small`/`normal`) | one PDF |
| `POST /api/ocr` | `files` (1 PDF/image), `lang?`, `output?` (`pdf`/`txt`/`docx`), `pages?` | the result (max 30 pages) |
| `POST /api/protect` | `files` (1 PDF), `password`, `allowPrint?`, `allowCopy?`, `allowEdit?` | `<name>-protected.pdf` |
| `POST /api/unlock` | `files` (1 PDF), `password` | `<name>-unlocked.pdf` |
| `POST /api/number` | `files` (1 PDF), `position?`, `format?`, `start?`, `pages?`, `size?` | `<name>-numbered.pdf` |
| `POST /api/sign` | `files` (PDF + signature image), `page?` (0 = last), `x`, `y`, `width` (fractions) | `<name>-signed.pdf` |
| `POST /api/watermark` | `files` (PDF + optional image), `text?`, `color?`, `opacity?`, `angle?`, `size?`, `layout?` (`center`/`tile`), `pages?` | `<name>-watermarked.pdf` |
| `POST /api/crop` | `files` (1 PDF), `mode` (`auto`/`margin`/`resize`), `margin?` (mm), `paper?` | `<name>-cropped.pdf` / `-resized.pdf` |
| `POST /api/extract-images` | `files` (1 PDF), `pages?` | the image, or `<name>-images.zip` |
| `GET /api/formats` | | formats, limits and OCR languages as JSON |

Errors are JSON: `{"error": "..."}` with status 400, 429 or 503.

## Docker

```bash
docker compose up -d             # web UI on http://localhost:8080
DOCSMELT_PORT=8090 docker compose up -d   # if 8080 is taken

# or the CLI, without the web UI:
docker build -t docsmelt .
docker run --rm -u "$(id -u):$(id -g)" -v "$PWD:/work" docsmelt report.docx report.pdf
docker run --rm -u "$(id -u):$(id -g)" -v "$PWD:/work" docsmelt merge a.pdf b.pdf -o all.pdf
```

The image runs the test suite during build and ships with Tesseract OCR
(English, Swahili, French) and a CJK fallback font preconfigured. Choose other
OCR languages at build time: `docker build --build-arg OCR_LANGS="eng swa ara" .` With no arguments it starts the web UI; any arguments run the
CLI instead (paths relative to the mounted `/work`).

## Design

```
Reader ──► Document (pages → blocks) ──► Writer
```

| Package | Role |
|---|---|
| `model` | Format-neutral `Document` → `Page` → `Block` (sealed: `Paragraph`, `ImageBlock`, `Table`). A page may carry a raster *snapshot* of its original look, and a fixed size (slides). |
| `io` | `DocumentReader` / `DocumentWriter` strategy interfaces, `ReadOptions`, `WriteOptions`, `PageRange`. |
| `io.pdf`, `io.docx`, `io.office`, `io.image`, `io.text` | Adapters over Apache PDFBox, Apache POI (Word, Excel, PowerPoint) and ImageIO. `FontChain` does per-character font fallback; `TableLayout` sizes table columns for any renderer. |
| `core` | `FormatRegistry` (factory), `Converter` (single conversion), `ConversionOptions`. |
| `core.batch` | `JobPlanner` (expands folders), `BatchConverter` (parallel, failure-tolerant), `ConversionListener` (progress observer). |
| `pdf` | `PdfMerger`, `PdfSplitter`, `SplitStrategy` (sealed: `EveryNPages`, `ByRanges`), `PdfPageEditor` + `PageEditPlan`, `ImagesToPdf`, `PdfCompressor`, `PdfSecurity`, `PageNumberer`, `PdfSigner`, `PdfWatermarker`, `PdfImageExtractor`, `PdfPageSizer`, and `PageGeometry` (places content correctly on rotated pages). |
| `ocr` | `OcrEngine` interface, `TesseractEngine` (runs the `tesseract` CLI with a timeout), `OcrService` (renders pages, picks the output). |
| `web` | Javalin server; each endpoint is an `ApiHandler` subclass (template method: limits → workspace → `process` → reply). |
| `public/` | The web UI: a tool registry in `app.js` builds the home screen, search, tool pages and next-step suggestions; `i18n.js` holds English and Kiswahili text. |
| `cli` | picocli commands: convert (default), `merge`, `split`, `pages`, `images`, `compress`, `ocr`, `protect`, `unlock`, `number`, `sign`, `watermark`, `extract-images`, `crop`, `serve`. |

### Adding a format

1. Add it to `Format`.
2. Implement `DocumentReader` and/or `DocumentWriter`.
3. Register it in `FormatRegistry.withDefaults()`.

## Known limitations

- PDF → Word extracts text and images, not layout, fonts or exact positions.
- EMF/WMF images in Word files are skipped. Excel charts and images aren't converted (cell values are).
- PowerPoint → PDF draws each slide as an image, so the PDF text isn't selectable; use → Word or → text to get the text. Slide rendering uses Apache POI, so unusual effects may look simpler than in PowerPoint.
- "Sign" places a picture of a signature; it is not a certified (cryptographic) digital signature. Typed signatures use a handwriting font if the browser has one (Windows, macOS), otherwise italic serif.
- Password-protected Word/Excel/PowerPoint files can't be opened; unlock them in Office first.
- Messages generated by the server are in English; the common ones are translated in the Kiswahili interface.
- Crop & resize changes the visible page area; links and form fields keep their original positions.
- Word → image uses docsmelt's own simple layout, not Word's.
- Compression only shrinks images; PDFs that are mostly text or vector graphics barely change (they're never made bigger).
- OCR accuracy depends on scan quality; locally it needs `apt install tesseract-ocr` (the Docker image includes it).
