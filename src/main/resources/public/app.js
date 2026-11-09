"use strict";

/* ---------- API client -------------------------------------------------- */

class Api {
  async info() {
    const res = await fetch("api/formats");
    if (!res.ok) throw new Error("Could not reach the server");
    return res.json();
  }

  /**
   * POSTs a multipart form with upload progress (fetch can't report that),
   * resolving to { blob, filename }.
   */
  send(endpoint, form, onUploadProgress = () => {}) {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open("POST", endpoint);
      xhr.responseType = "blob";
      xhr.upload.onprogress = (e) => e.lengthComputable && onUploadProgress(e.loaded / e.total);
      xhr.upload.onload = () => onUploadProgress(1);
      xhr.onerror = () => reject(new Error("Network error — is the server still running?"));
      xhr.onload = async () => {
        if (xhr.status === 200) {
          resolve({ blob: xhr.response, filename: Api.filename(xhr.getResponseHeader("Content-Disposition")) });
          return;
        }
        let message = `Request failed (${xhr.status})`;
        try { message = JSON.parse(await xhr.response.text()).error || message; } catch { /* not JSON */ }
        reject(new Error(message));
      };
      xhr.send(form);
    });
  }

  static filename(disposition) {
    if (!disposition) return "download";
    const star = /filename\*=UTF-8''([^;]+)/i.exec(disposition);
    if (star) return decodeURIComponent(star[1]);
    const plain = /filename="([^"]+)"/i.exec(disposition);
    return plain ? plain[1] : "download";
  }
}

/* ---------- Helpers ----------------------------------------------------- */

const el = (tag, props = {}, ...children) => {
  const node = Object.assign(document.createElement(tag), props);
  node.append(...children);
  return node;
};

const ext = (name) => (name.includes(".") ? name.split(".").pop().toLowerCase() : "");
const IMAGE_EXTS = ["png", "jpg", "jpeg", "bmp", "gif"];

const icon = (name, extra = "") => {
  const i = el("i", { className: `fa-solid fa-${name} ${extra}`.trim() });
  i.setAttribute("aria-hidden", "true");
  return i;
};

const FILE_ICONS = {
  pdf: ["file-pdf", "pdf"], docx: ["file-word", "word"], txt: ["file-lines", "text"],
  png: ["file-image", "image"], jpg: ["file-image", "image"], jpeg: ["file-image", "image"],
  bmp: ["file-image", "image"], gif: ["file-image", "image"],
  xlsx: ["file-excel", "excel"], xls: ["file-excel", "excel"], pptx: ["file-powerpoint", "powerpoint"],
};
const fileIcon = (name) => {
  const [glyph, kind] = FILE_ICONS[ext(name)] ?? ["file", "other"];
  return icon(glyph, `file-icon ${kind}`);
};

const iconButton = (glyph, label, onclick, { disabled = false, extra = "" } = {}) => {
  const b = el("button", { className: `icon-btn ${extra}`.trim(), type: "button", title: label, disabled, onclick },
    icon(glyph));
  b.setAttribute("aria-label", label);
  return b;
};

const formatSize = (bytes) =>
  bytes < 1024 ? `${bytes} B`
    : bytes < 1024 ** 2 ? `${(bytes / 1024).toFixed(0)} KB`
      : `${(bytes / 1024 ** 2).toFixed(1)} MB`;

/* ---------- File list --------------------------------------------------- */

class FileList {
  constructor(listEl, { single = false, sortable = false, onChange }) {
    this.listEl = listEl;
    this.single = single;
    this.sortable = sortable;
    this.onChange = onChange;
    this.files = [];
  }

  add(newFiles) {
    this.files = this.single ? newFiles.slice(0, 1) : [...this.files, ...newFiles];
    this.render();
  }

  remove(index) {
    this.files.splice(index, 1);
    this.render();
  }

  move(index, delta) {
    const [file] = this.files.splice(index, 1);
    this.files.splice(index + delta, 0, file);
    this.render();
    this.listEl.children[index + delta]?.querySelector(delta < 0 ? "[data-up]" : "[data-down]")?.focus();
  }

  render() {
    this.listEl.replaceChildren(...this.files.map((file, i) => {
      const buttons = [];
      if (this.sortable) {
        const up = iconButton("arrow-up", `Move ${file.name} up`, () => this.move(i, -1), { disabled: i === 0 });
        const down = iconButton("arrow-down", `Move ${file.name} down`, () => this.move(i, 1),
          { disabled: i === this.files.length - 1 });
        up.dataset.up = "";
        down.dataset.down = "";
        buttons.push(up, down);
      }
      buttons.push(iconButton("xmark", `Remove ${file.name}`, () => this.remove(i), { extra: "remove" }));
      return el("li", {},
        fileIcon(file.name),
        el("span", { className: "name", textContent: file.name, title: file.name }),
        el("span", { className: "size", textContent: formatSize(file.size) }),
        ...buttons);
    }));
    this.onChange();
  }
}

/* ---------- Status box -------------------------------------------------- */

class Status {
  constructor(node) { this.node = node; this.url = null; }

  busy(text) {
    this.bar = el("span");
    this.track = el("div", { className: "bar" }, this.bar);
    this.label = el("span", { textContent: text });
    this.set("busy", "spinner", "fa-spin", this.label, this.track);
  }

  progress(fraction, processingText = "Processing…") {
    if (fraction >= 1) {
      this.label.textContent = processingText;
      this.track.classList.add("indeterminate");
      this.bar.style.width = "";
    } else {
      this.bar.style.width = `${Math.round(fraction * 100)}%`;
    }
  }

  done({ blob, filename }, summary) {
    if (this.url) URL.revokeObjectURL(this.url);
    this.url = URL.createObjectURL(blob);
    const link = el("a", { href: this.url, download: filename }, icon("download"), ` ${filename}`);
    this.set("done", "circle-check", "",
      el("span", {}, `${summary ?? `Done (${formatSize(blob.size)}).`} Download: `, link));
    link.click(); // start the download automatically
  }

  error(message) { this.set("error", "circle-exclamation", "", el("span", { textContent: message })); }

  clear() {
    this.node.className = "status";
    this.node.replaceChildren();
  }

  set(kind, glyph, extra, ...children) {
    this.node.className = `status ${kind}`;
    this.node.replaceChildren(icon(glyph, extra), el("div", { className: "body" }, ...children));
  }
}

/* ---------- Tool panels (template method) ------------------------------- */

class ToolPanel {
  constructor(section, api, info, { single = false, sortable = false, minFiles = 1, accept } = {}) {
    this.section = section;
    this.api = api;
    this.info = info;
    this.minFiles = minFiles;
    this.button = section.querySelector("[data-action]");
    this.status = new Status(section.querySelector(".status"));
    this.files = new FileList(section.querySelector(".files"), { single, sortable, onChange: () => this.filesChanged() });
    if (accept) section.querySelector("input[type=file]").accept = accept;
    this.wireDropzone(section.querySelector(".dropzone"));
    this.button.addEventListener("click", () => this.submit());
    this.refresh();
  }

  /* Subclasses override these. */
  endpoint() { throw new Error("abstract"); }
  busyText() { return "Uploading…"; }
  processingText() { return "Processing…"; }
  fillForm(_form) {}
  async prepare() {}
  validate() { return null; }
  summary(_result) { return null; }
  filesChanged() { this.refresh(); }
  ready() { return this.files.files.length >= this.minFiles; }

  field(name) { return this.section.querySelector(`[name=${name}]`); }

  requireExt(allowed, message) {
    return this.files.files.every((f) => allowed.includes(ext(f.name))) ? null : message;
  }

  wireDropzone(zone) {
    this.zone = zone;
    const input = zone.querySelector("input[type=file]");
    input.addEventListener("change", () => { this.accept([...input.files]); input.value = ""; });
    ["dragenter", "dragover"].forEach((t) => zone.addEventListener(t, (e) => {
      e.preventDefault(); zone.classList.add("dragging");
    }));
    ["dragleave", "drop"].forEach((t) => zone.addEventListener(t, (e) => {
      e.preventDefault(); zone.classList.remove("dragging");
    }));
    zone.addEventListener("drop", (e) => this.accept([...e.dataTransfer.files]));
  }

  accept(files) {
    const max = this.info.maxUploadMb * 1024 * 1024;
    const tooBig = files.filter((f) => f.size > max);
    const ok = files.filter((f) => f.size <= max);
    if (tooBig.length) this.status.error(`${tooBig.map((f) => f.name).join(", ")}: larger than ${this.info.maxUploadMb} MB`);
    else this.status.clear();
    if (ok.length) this.files.add(ok);
  }

  refresh() {
    this.button.disabled = !this.ready() || this.busy || this.disabled;
  }

  async submit() {
    const problem = this.validate()
      ?? (this.files.files.length > this.info.maxFiles ? `At most ${this.info.maxFiles} files at once` : null);
    if (problem) { this.status.error(problem); return; }

    this.busy = true;
    this.refresh();
    this.status.busy(this.busyText());
    try {
      await this.prepare();
      const form = new FormData();
      this.files.files.forEach((f) => form.append("files", f, f.name));
      this.fillForm(form);
      const result = await this.api.send(this.endpoint(), form, (p) => this.status.progress(p, this.processingText()));
      this.status.done(result, this.summary(result));
    } catch (err) {
      this.status.error(err.message);
    } finally {
      this.busy = false;
      this.refresh();
    }
  }
}

class ConvertPanel extends ToolPanel {
  constructor(section, api, info) {
    super(section, api, info, { accept: info.read.map((e) => `.${e}`).join(",") });
    this.select = this.field("to");
    const names = { pdf: "PDF", docx: "Word (.docx)", xlsx: "Excel (.xlsx)", pptx: "PowerPoint (.pptx)",
      txt: "Text (.txt)", png: "PNG image", jpg: "JPEG image", bmp: "BMP image", gif: "GIF image" };
    info.write.filter((f) => f !== "jpeg").forEach((f) =>
      this.select.append(el("option", { value: f, textContent: names[f] ?? f.toUpperCase() })));
    this.userPicked = false;
    this.select.addEventListener("change", () => { this.userPicked = true; });
  }

  /** Pick a sensible target until the user chooses one themselves. */
  filesChanged() {
    super.filesChanged();
    if (this.userPicked || !this.files.files.length) return;
    const kinds = new Set(this.files.files.map((f) => ext(f.name)));
    this.select.value = kinds.size === 1 && kinds.has("pdf") ? "docx" : "pdf";
  }

  endpoint() { return "api/convert"; }
  busyText() { return `Uploading ${this.files.files.length} file(s)…`; }

  validate() {
    const target = this.select.value;
    const same = this.files.files.filter((f) => ext(f.name) === target || (target === "jpg" && ext(f.name) === "jpeg"));
    return same.length === this.files.files.length ? `Your file(s) are already ${target.toUpperCase()}` : null;
  }

  fillForm(form) {
    form.append("to", this.select.value);
    ["pages", "dpi", "quality"].forEach((name) => {
      const value = this.field(name).value.trim();
      if (value) form.append(name, value);
    });
  }
}

class MergePanel extends ToolPanel {
  constructor(section, api, info) {
    super(section, api, info, { sortable: true, minFiles: 2, accept: info.read.map((e) => `.${e}`).join(",") });
  }
  endpoint() { return "api/merge"; }
  busyText() { return `Uploading ${this.files.files.length} files…`; }
}

class SplitPanel extends ToolPanel {
  constructor(section, api, info) {
    super(section, api, info, { single: true });
    // Typing in a field selects its radio button.
    this.field("every").addEventListener("focus", () => { section.querySelector("[value=every]").checked = true; });
    this.field("ranges").addEventListener("focus", () => { section.querySelector("[value=ranges]").checked = true; });
  }
  endpoint() { return "api/split"; }

  mode() { return this.section.querySelector("[name=mode]:checked").value; }

  validate() {
    if (ext(this.files.files[0].name) !== "pdf") return "Please choose a PDF file";
    if (this.mode() === "ranges" && !this.field("ranges").value.trim()) {
      return "Enter page ranges, e.g. 1-3,4-9,10-";
    }
    return null;
  }

  fillForm(form) {
    const name = this.mode();
    form.append(name, this.field(name).value.trim());
  }
}

class CompressPanel extends ToolPanel {
  constructor(section, api, info) { super(section, api, info); }
  endpoint() { return "api/compress"; }
  busyText() { return `Uploading ${this.files.files.length} PDF(s)…`; }
  processingText() { return "Compressing…"; }
  validate() { return this.requireExt(["pdf"], "Only PDF files can be compressed"); }

  fillForm(form) {
    form.append("level", this.section.querySelector("[name=level]:checked").value);
  }

  summary({ blob }) {
    const before = this.files.files.reduce((sum, f) => sum + f.size, 0);
    if (this.files.files.length > 1) return `Compressed ${this.files.files.length} files.`;
    const saved = Math.round((1 - blob.size / before) * 100);
    return saved > 0
      ? `${formatSize(before)} → ${formatSize(blob.size)} (${saved}% smaller).`
      : `This PDF was already compact (${formatSize(before)}).`;
  }
}

class ImagesPanel extends ToolPanel {
  constructor(section, api, info) { super(section, api, info, { sortable: true }); }
  endpoint() { return "api/images-to-pdf"; }
  busyText() { return `Uploading ${this.files.files.length} image(s)…`; }
  validate() { return this.requireExt(IMAGE_EXTS, "Only images can be added (JPG, PNG, BMP, GIF)"); }

  fillForm(form) {
    form.append("pageSize", this.field("pageSize").value);
    form.append("margin", this.field("margin").value);
  }
}

class OcrPanel extends ToolPanel {
  constructor(section, api, info) {
    super(section, api, info, { single: true });
    const ocr = info.ocr ?? { available: false, languages: [] };
    const names = { eng: "English", swa: "Swahili", fra: "French", deu: "German", spa: "Spanish",
      por: "Portuguese", ara: "Arabic", ita: "Italian", nld: "Dutch", hin: "Hindi" };
    const select = this.field("lang");
    ocr.languages.forEach((l) => select.append(el("option", { value: l, textContent: names[l] ?? l })));
    if (ocr.languages.includes("eng") && ocr.languages.includes("swa")) {
      select.append(el("option", { value: "eng+swa", textContent: "English + Swahili" }));
    }
    select.value = ocr.languages.includes("eng") ? "eng" : ocr.languages[0] ?? "";
    this.maxPages = ocr.maxPages;
    if (!ocr.available) {
      this.disabled = true;
      section.querySelector(".notice").hidden = false;
      this.zone.hidden = true;
      this.refresh();
    }
  }

  endpoint() { return "api/ocr"; }
  busyText() { return "Uploading…"; }
  processingText() { return "Recognizing text… this takes a few seconds per page"; }
  validate() { return this.requireExt(["pdf", ...IMAGE_EXTS], "OCR works on PDFs and images"); }

  fillForm(form) {
    form.append("lang", this.field("lang").value);
    form.append("output", this.field("output").value);
    const pages = this.field("pages").value.trim();
    if (pages) form.append("pages", pages);
  }
}

/**
 * Organize: loads page thumbnails for one PDF, lets the user rotate, delete
 * and reorder them, then sends the resulting order + rotations.
 */
class OrganizePanel extends ToolPanel {
  constructor(section, api, info) {
    super(section, api, info, { single: true, accept: ".pdf,application/pdf" });
    this.grid = section.querySelector(".page-grid");
    this.toolbar = section.querySelector(".pages-toolbar");
    this.hint = section.querySelector(".pages-hint");
    this.countEl = section.querySelector(".page-count");
    this.pages = [];
    this.original = [];
    this.loadToken = 0;
    section.querySelectorAll("[data-rotate-all]").forEach((b) =>
      b.addEventListener("click", () => this.rotateAll(Number(b.dataset.rotateAll))));
    section.querySelector("[data-reset]").addEventListener("click", () => this.reset());
  }

  endpoint() { return "api/organize"; }
  processingText() { return "Saving…"; }
  ready() { return (this.pages?.length ?? 0) > 0; } // runs once before the constructor finishes

  validate() {
    if (!this.pages.length) return "Every page was removed. Use Reset to start over.";
    return null;
  }

  fillForm(form) {
    form.append("order", this.pages.map((p) => p.orig).join(","));
    form.append("rotate", this.pages.filter((p) => p.rotation % 360).map((p) => `${p.orig}:${p.rotation % 360}`).join(","));
  }

  async filesChanged() {
    const token = ++this.loadToken;
    this.pages = [];
    this.original = [];
    this.render();
    const file = this.files.files[0];
    if (!file) return;
    if (ext(file.name) !== "pdf") { this.status.error("Please choose a PDF file"); return; }

    const form = new FormData();
    form.append("files", file, file.name);
    this.status.busy("Loading pages…");
    this.grid.replaceChildren(...Array.from({ length: 4 }, () => this.skeletonCard()));
    try {
      const { blob } = await this.api.send("api/preview", form, (p) => this.status.progress(p, "Rendering pages…"));
      if (token !== this.loadToken) return; // a newer file was chosen meanwhile
      const data = JSON.parse(await blob.text());
      this.original = data.pages.map((p, i) => ({ orig: i + 1, src: p.src, base: p.rotation, rotation: 0 }));
      this.reset();
      this.status.clear();
    } catch (err) {
      if (token !== this.loadToken) return;
      this.grid.replaceChildren();
      this.status.error(err.message);
    }
  }

  reset() {
    this.pages = this.original.map((p) => ({ ...p, rotation: 0 }));
    this.render();
  }

  rotateAll(delta) {
    this.pages.forEach((p) => { p.rotation = (p.rotation + delta + 360) % 360; });
    this.render();
  }

  move(from, to) {
    if (to < 0 || to >= this.pages.length || from === to) return;
    const [page] = this.pages.splice(from, 1);
    this.pages.splice(to, 0, page);
    this.render();
  }

  skeletonCard() {
    return el("li", { className: "page-card loading" },
      el("div", { className: "page-thumb" }, el("div", { className: "skeleton" })),
      el("div", { className: "page-meta" }, el("span", { className: "page-num", textContent: "…" })));
  }

  render() {
    const removed = this.original.length - this.pages.length;
    this.toolbar.hidden = this.hint.hidden = !this.original.length;
    this.countEl.textContent = this.original.length
      ? `${this.pages.length} page${this.pages.length === 1 ? "" : "s"}${removed ? ` (${removed} removed)` : ""}`
      : "";
    this.grid.replaceChildren(...this.pages.map((p, i) => this.card(p, i)));
    this.refresh();
  }

  card(page, index) {
    const angle = page.base + page.rotation;
    const sideways = angle % 180 !== 0;
    const img = el("img", { src: page.src, alt: `Page ${page.orig}`, draggable: false });
    img.style.transform = `rotate(${angle}deg)${sideways ? " scale(.72)" : ""}`;

    const label = `page ${page.orig}`;
    const li = el("li", { className: "page-card", draggable: true },
      el("div", { className: "page-thumb" }, img),
      el("div", { className: "page-meta" },
        el("span", { className: "page-num", textContent: String(page.orig) }),
        iconButton("trash-can", `Delete ${label}`, () => { this.pages.splice(index, 1); this.render(); }, { extra: "remove" })),
      el("div", { className: "page-meta" },
        iconButton("chevron-left", `Move ${label} earlier`, () => this.move(index, index - 1), { disabled: index === 0 }),
        el("span", { className: "page-actions" },
          iconButton("rotate-left", `Rotate ${label} left`, () => { page.rotation = (page.rotation + 270) % 360; this.render(); }),
          iconButton("rotate-right", `Rotate ${label} right`, () => { page.rotation = (page.rotation + 90) % 360; this.render(); })),
        iconButton("chevron-right", `Move ${label} later`, () => this.move(index, index + 1),
          { disabled: index === this.pages.length - 1 })));

    // Drag and drop reordering.
    li.addEventListener("dragstart", (e) => {
      this.dragFrom = index;
      e.dataTransfer.effectAllowed = "move";
      e.dataTransfer.setData("text/plain", String(index));
      li.classList.add("dragging");
    });
    li.addEventListener("dragend", () => li.classList.remove("dragging"));
    li.addEventListener("dragover", (e) => {
      if (this.dragFrom === undefined) return;
      e.preventDefault();
      const after = e.clientX > li.getBoundingClientRect().left + li.offsetWidth / 2;
      li.classList.toggle("drop-after", after);
      li.classList.toggle("drop-before", !after);
    });
    li.addEventListener("dragleave", () => li.classList.remove("drop-before", "drop-after"));
    li.addEventListener("drop", (e) => {
      e.preventDefault();
      e.stopPropagation(); // don't let the drop reach the file dropzone logic
      const after = li.classList.contains("drop-after");
      const from = this.dragFrom;
      this.dragFrom = undefined;
      if (from === undefined) return;
      let to = index + (after ? 1 : 0);
      if (from < to) to -= 1;
      this.move(from, to);
    });
    return li;
  }
}

class ProtectPanel extends ToolPanel {
  constructor(section, api, info) {
    super(section, api, info, { single: true, accept: ".pdf,application/pdf" });
    section.querySelectorAll("[name=secMode]").forEach((r) => r.addEventListener("change", () => this.applyMode()));
    const reveal = section.querySelector("[data-reveal]");
    reveal.addEventListener("click", () => {
      const show = this.field("password").type === "password";
      ["password", "confirm"].forEach((n) => { this.field(n).type = show ? "text" : "password"; });
      reveal.replaceChildren(icon(show ? "eye-slash" : "eye"));
      reveal.setAttribute("aria-label", show ? "Hide password" : "Show password");
    });
    this.applyMode();
  }

  mode() { return this.section.querySelector("[name=secMode]:checked").value; }

  applyMode() {
    const protect = this.mode() === "protect";
    this.section.querySelectorAll("[data-protect-only]").forEach((e) => { e.hidden = !protect; });
    this.section.querySelector("[data-label]").textContent = protect ? "Protect PDF" : "Remove password";
    this.field("password").autocomplete = protect ? "new-password" : "current-password";
    this.status.clear();
  }

  endpoint() { return this.mode() === "protect" ? "api/protect" : "api/unlock"; }
  processingText() { return this.mode() === "protect" ? "Encrypting…" : "Unlocking…"; }

  validate() {
    const pdf = this.requireExt(["pdf"], "Please choose a PDF file");
    if (pdf) return pdf;
    const pw = this.field("password").value;
    if (pw.length < 4) return "The password must be at least 4 characters";
    if (this.mode() === "protect" && pw !== this.field("confirm").value) return "The passwords don't match";
    return null;
  }

  fillForm(form) {
    form.append("password", this.field("password").value);
    if (this.mode() === "protect") {
      ["allowPrint", "allowCopy", "allowEdit"].forEach((n) => form.append(n, String(this.field(n).checked)));
    }
  }

  summary() {
    const protect = this.mode() === "protect";
    ["password", "confirm"].forEach((n) => { this.field(n).value = ""; }); // don't leave it on screen
    return protect ? "Protected with AES-256." : "Password removed.";
  }
}

class NumberPanel extends ToolPanel {
  constructor(section, api, info) { super(section, api, info, { single: true, accept: ".pdf,application/pdf" }); }
  endpoint() { return "api/number"; }
  validate() { return this.requireExt(["pdf"], "Please choose a PDF file"); }

  fillForm(form) {
    form.append("position", this.section.querySelector("[name=position]:checked").value);
    ["format", "size", "start"].forEach((n) => form.append(n, this.field(n).value));
    if (this.field("skipFirst").checked) form.append("pages", "2-");
  }
}

/** Freehand drawing on a canvas; strokes are kept so the pad can be redrawn when resized. */
class SignaturePad {
  constructor(canvas, onChange) {
    this.canvas = canvas;
    this.ctx = canvas.getContext("2d", { willReadFrequently: true });
    this.strokes = [];
    this.onChange = onChange;
    canvas.addEventListener("pointerdown", (e) => {
      canvas.setPointerCapture(e.pointerId);
      this.current = [this.point(e)];
      this.strokes.push(this.current);
      this.redraw();
    });
    canvas.addEventListener("pointermove", (e) => {
      if (!this.current) return;
      this.current.push(this.point(e));
      this.redraw();
    });
    const end = () => { if (this.current) { this.current = null; this.onChange(); } };
    canvas.addEventListener("pointerup", end);
    canvas.addEventListener("pointercancel", end);
    new ResizeObserver(() => this.redraw()).observe(canvas);
  }

  point(e) {
    const r = this.canvas.getBoundingClientRect();
    return [e.clientX - r.left, e.clientY - r.top];
  }

  get empty() { return !this.strokes.some((s) => s.length); }

  clear() {
    this.strokes = [];
    this.redraw();
    this.onChange();
  }

  redraw() {
    const dpr = window.devicePixelRatio || 1;
    const w = this.canvas.clientWidth;
    const h = this.canvas.clientHeight;
    if (!w) return;
    if (this.canvas.width !== Math.round(w * dpr)) {
      this.canvas.width = Math.round(w * dpr);
      this.canvas.height = Math.round(h * dpr);
    }
    const c = this.ctx;
    c.setTransform(dpr, 0, 0, dpr, 0, 0);
    c.clearRect(0, 0, w, h);
    c.strokeStyle = "#1d2b4f";
    c.lineWidth = 2.6;
    c.lineCap = c.lineJoin = "round";
    for (const stroke of this.strokes) {
      c.beginPath();
      stroke.forEach(([x, y], i) => (i ? c.lineTo(x, y) : c.moveTo(x, y)));
      if (stroke.length === 1) c.lineTo(stroke[0][0] + 0.1, stroke[0][1]); // a dot
      c.stroke();
    }
    this.canvas.parentElement.classList.toggle("has-ink", !this.empty);
  }
}

/** Crops a canvas to its visible pixels (plus a little padding); null if it's blank. */
function trimCanvas(source) {
  const { width, height } = source;
  if (!width || !height) return null;
  const data = source.getContext("2d", { willReadFrequently: true }).getImageData(0, 0, width, height).data;
  let x0 = width, y0 = height, x1 = -1, y1 = -1;
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      if (data[(y * width + x) * 4 + 3] > 8) {
        if (x < x0) x0 = x;
        if (x > x1) x1 = x;
        if (y < y0) y0 = y;
        if (y > y1) y1 = y;
      }
    }
  }
  if (x1 < 0) return null;
  const pad = 6;
  x0 = Math.max(0, x0 - pad); y0 = Math.max(0, y0 - pad);
  x1 = Math.min(width - 1, x1 + pad); y1 = Math.min(height - 1, y1 + pad);
  const out = el("canvas", { width: x1 - x0 + 1, height: y1 - y0 + 1 });
  out.getContext("2d").drawImage(source, x0, y0, out.width, out.height, 0, 0, out.width, out.height);
  return out;
}

class SignPanel extends ToolPanel {
  constructor(section, api, info) {
    super(section, api, info, { single: true, accept: ".pdf,application/pdf" });
    this.place = section.querySelector(".sign-place");
    this.stage = section.querySelector(".sign-stage");
    this.pageImg = section.querySelector(".sign-page-img");
    this.box = section.querySelector(".sig-box");
    this.preview = section.querySelector(".sig-preview");
    this.pageSelect = this.field("signPage");
    this.pos = { x: 0.55, y: 0.8, w: 0.3 };
    this.aspect = 1 / 3;
    this.loadToken = 0;

    this.pad = new SignaturePad(section.querySelector(".sig-canvas"), () => this.updateSignature());
    section.querySelector(".sig-clear").addEventListener("click", () => this.pad.clear());
    section.querySelectorAll("[name=sigMode]").forEach((r) => r.addEventListener("change", () => {
      section.querySelectorAll("[data-sig]").forEach((d) => { d.hidden = d.dataset.sig !== this.sigMode(); });
      this.pad.redraw();
      this.updateSignature();
    }));
    this.field("sigText").addEventListener("input", () => this.updateSignature());
    this.field("sigFile").addEventListener("change", (e) => this.loadUploaded(e.target.files[0]));
    this.pageSelect.addEventListener("change", () => this.loadPage(Number(this.pageSelect.value)));
    this.wireBox();
    new ResizeObserver(() => this.layoutBox()).observe(this.stage);
  }

  sigMode() { return this.section.querySelector("[name=sigMode]:checked").value; }
  endpoint() { return "api/sign"; }
  processingText() { return "Signing…"; }
  ready() { return (this.files?.files.length ?? 0) === 1 && !!this.pageLoaded && !!this.sigCanvas; }

  validate() {
    if (!this.sigCanvas) return "Add your signature first";
    if (!this.pageLoaded) return "Wait for the page to load";
    return null;
  }

  async prepare() {
    this.sigBlob = await new Promise((resolve) => this.sigCanvas.toBlob(resolve, "image/png"));
  }

  fillForm(form) {
    form.append("files", this.sigBlob, "signature.png");
    form.append("page", String(this.page));
    form.append("x", this.pos.x.toFixed(4));
    form.append("y", this.pos.y.toFixed(4));
    form.append("width", this.pos.w.toFixed(4));
  }

  /* --- signature sources --- */

  currentSignature() {
    switch (this.sigMode()) {
      case "draw": return this.pad.empty ? null : trimCanvas(this.pad.canvas);
      case "type": {
        const text = this.field("sigText").value.trim();
        if (!text) return null;
        const c = el("canvas", { width: 10, height: 10 });
        const g = c.getContext("2d", { willReadFrequently: true });
        const font = 'italic 96px "Segoe Script", "Brush Script MT", "Snell Roundhand", "URW Chancery L", cursive';
        g.font = font;
        c.width = Math.ceil(g.measureText(text).width) + 60;
        c.height = 160;
        g.font = font;
        g.fillStyle = "#1d2b4f";
        g.textBaseline = "middle";
        g.fillText(text, 30, 80);
        return trimCanvas(c);
      }
      default: return this.uploaded ?? null;
    }
  }

  async loadUploaded(file) {
    if (!file) return;
    try {
      const bitmap = await createImageBitmap(file);
      const scale = Math.min(1, 1500 / Math.max(bitmap.width, bitmap.height));
      const c = el("canvas", { width: Math.round(bitmap.width * scale), height: Math.round(bitmap.height * scale) });
      const g = c.getContext("2d", { willReadFrequently: true });
      g.drawImage(bitmap, 0, 0, c.width, c.height);
      // Make a white/paper background transparent, so photographed signatures sit on the page.
      const img = g.getImageData(0, 0, c.width, c.height);
      for (let i = 0; i < img.data.length; i += 4) {
        const [r, gr, b] = [img.data[i], img.data[i + 1], img.data[i + 2]];
        if (r > 225 && gr > 225 && b > 225) img.data[i + 3] = 0;
      }
      g.putImageData(img, 0, 0);
      this.uploaded = trimCanvas(c) ?? c;
    } catch {
      this.uploaded = null;
      this.status.error("Couldn't read that image");
    }
    this.updateSignature();
  }

  updateSignature() {
    const c = this.currentSignature();
    this.sigCanvas = c;
    if (c) {
      this.aspect = c.height / c.width;
      this.preview.src = c.toDataURL("image/png");
    } else {
      this.aspect = 1 / 3;
      this.preview.removeAttribute("src");
    }
    this.box.classList.toggle("has-sig", !!c);
    this.layoutBox();
    this.refresh();
  }

  /* --- page preview --- */

  async filesChanged() {
    this.pageLoaded = false;
    this.place.hidden = true;
    this.refresh();
    const file = this.files.files[0];
    if (!file) return;
    if (ext(file.name) !== "pdf") { this.status.error("Please choose a PDF file"); return; }
    this.pageSelect.replaceChildren();
    await this.loadPage(0); // last page: where signatures usually go
  }

  async loadPage(page) {
    const token = ++this.loadToken;
    const file = this.files.files[0];
    const form = new FormData();
    form.append("files", file, file.name);
    form.append("page", String(page));
    form.append("width", String(Math.min(1100, Math.round(520 * (window.devicePixelRatio || 1)))));
    this.status.busy("Loading page…");
    try {
      const { blob } = await this.api.send("api/preview", form, (p) => this.status.progress(p, "Rendering page…"));
      if (token !== this.loadToken) return;
      const data = JSON.parse(await blob.text());
      if (this.pageSelect.options.length !== data.count) {
        this.pageSelect.replaceChildren(...Array.from({ length: data.count }, (_, i) =>
          el("option", { value: String(i + 1), textContent: `Page ${i + 1} of ${data.count}` })));
      }
      this.pageSelect.value = String(data.page);
      this.page = data.page;
      this.pageImg.src = data.pages[0].src;
      await this.pageImg.decode().catch(() => {});
      this.place.hidden = false;
      this.pageLoaded = true;
      this.status.clear();
      this.layoutBox();
    } catch (err) {
      if (token === this.loadToken) this.status.error(err.message);
    }
    this.refresh();
  }

  /* --- placing the signature --- */

  layoutBox() {
    const W = this.stage.clientWidth;
    const H = this.stage.clientHeight;
    if (!W || !H) return;
    const p = this.pos;
    const maxW = Math.min(1, (H / W) / this.aspect); // the box must fit vertically too
    p.w = Math.min(Math.max(p.w, 0.05), maxW);
    const hFrac = (p.w * W * this.aspect) / H;
    p.x = Math.min(Math.max(p.x, 0), 1 - p.w);
    p.y = Math.min(Math.max(p.y, 0), 1 - hFrac);
    Object.assign(this.box.style, {
      left: `${p.x * W}px`, top: `${p.y * H}px`, width: `${p.w * W}px`, height: `${hFrac * H}px`,
    });
  }

  wireBox() {
    const handle = this.box.querySelector(".sig-handle");
    let drag = null;
    const start = (e, mode) => {
      e.preventDefault();
      e.stopPropagation();
      e.target.setPointerCapture(e.pointerId);
      drag = { mode, px: e.clientX, py: e.clientY, ...this.pos };
      this.box.focus({ preventScroll: true });
    };
    const move = (e) => {
      if (!drag) return;
      const W = this.stage.clientWidth;
      const H = this.stage.clientHeight;
      if (drag.mode === "move") {
        this.pos.x = drag.x + (e.clientX - drag.px) / W;
        this.pos.y = drag.y + (e.clientY - drag.py) / H;
      } else {
        this.pos.w = drag.w + (e.clientX - drag.px) / W;
      }
      this.layoutBox();
    };
    const end = () => { drag = null; };
    this.box.addEventListener("pointerdown", (e) => start(e, "move"));
    handle.addEventListener("pointerdown", (e) => start(e, "resize"));
    [this.box, handle].forEach((t) => {
      t.addEventListener("pointermove", move);
      t.addEventListener("pointerup", end);
      t.addEventListener("pointercancel", end);
    });
    this.box.addEventListener("keydown", (e) => {
      const step = e.shiftKey ? 0.05 : 0.01;
      const moves = { ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step] };
      if (moves[e.key]) {
        this.pos.x += moves[e.key][0];
        this.pos.y += moves[e.key][1];
      } else if (e.key === "+" || e.key === "=") {
        this.pos.w += 0.02;
      } else if (e.key === "-") {
        this.pos.w -= 0.02;
      } else {
        return;
      }
      e.preventDefault();
      this.layoutBox();
    });
  }
}

/* ---------- Tabs ------------------------------------------------------------ */

class Tabs {
  constructor(tablist) {
    this.tabs = [...tablist.querySelectorAll("[role=tab]")];
    this.tabs.forEach((tab, i) => {
      tab.addEventListener("click", () => this.select(i));
      tab.addEventListener("keydown", (e) => {
        const step = { ArrowRight: 1, ArrowLeft: -1 }[e.key];
        if (step) { e.preventDefault(); this.select((i + step + this.tabs.length) % this.tabs.length, true); }
      });
    });
    const fromHash = this.tabs.findIndex((t) => `#${t.id.replace("tab-", "")}` === location.hash);
    this.select(Math.max(0, fromHash));
  }

  select(index, focus = false) {
    this.tabs.forEach((tab, i) => {
      const on = i === index;
      tab.setAttribute("aria-selected", String(on));
      tab.tabIndex = on ? 0 : -1;
      document.getElementById(tab.getAttribute("aria-controls")).hidden = !on;
      if (on) tab.scrollIntoView({ block: "nearest", inline: "nearest" });
      if (on && focus) tab.focus();
    });
    history.replaceState(null, "", `#${this.tabs[index].id.replace("tab-", "")}`);
  }
}

/* ---------- Boot ------------------------------------------------------------ */

(async () => {
  new Tabs(document.querySelector("[role=tablist]"));
  const api = new Api();
  try {
    const info = await api.info();
    const panels = {
      convert: ConvertPanel, merge: MergePanel, split: SplitPanel, organize: OrganizePanel,
      compress: CompressPanel, images: ImagesPanel, ocr: OcrPanel,
      protect: ProtectPanel, number: NumberPanel, sign: SignPanel,
    };
    Object.entries(panels).forEach(([id, Panel]) => new Panel(document.getElementById(`panel-${id}`), api, info));
    document.getElementById("limits").textContent = `Up to ${info.maxFiles} files, ${info.maxUploadMb} MB each.`;
  } catch (err) {
    document.querySelectorAll(".status").forEach((s) => new Status(s).error(err.message));
  }
})();
