"use strict";

/* =========================================================================
 *  DocSmelt web UI
 *  - I18n: English / Kiswahili
 *  - App: home screen with search, hash routing (#tool), theme, install
 *  - ToolPanel: shared upload → options → run → result flow (template method)
 *  - PreviewPane: right-hand preview of the input and the result
 * ========================================================================= */

/* ---------- i18n ---------------------------------------------------------- */

class I18n {
  constructor(dicts) {
    this.dicts = dicts;
    this.listeners = [];
    let saved = null;
    try { saved = localStorage.getItem("docsmelt.lang"); } catch { /* storage blocked */ }
    this.lang = dicts[saved] ? saved
      : (navigator.language || "").toLowerCase().startsWith("sw") ? "sw" : "en";
  }

  t(key, vars = {}) {
    let s = this.dicts[this.lang][key] ?? this.dicts.en[key] ?? key;
    for (const [k, v] of Object.entries(vars)) s = s.replaceAll(`{${k}}`, String(v));
    return s;
  }

  set(lang) {
    if (!this.dicts[lang]) return;
    this.lang = lang;
    try { localStorage.setItem("docsmelt.lang", lang); } catch { /* storage blocked */ }
    this.apply(document);
    this.listeners.forEach((f) => f());
  }

  onChange(f) { this.listeners.push(f); }

  apply(root) {
    document.documentElement.lang = this.lang;
    root.querySelectorAll("[data-i18n]").forEach((e) => { e.textContent = this.t(e.dataset.i18n); });
    root.querySelectorAll("[data-i18n-placeholder]").forEach((e) => { e.placeholder = this.t(e.dataset.i18nPlaceholder); });
    root.querySelectorAll("[data-i18n-aria]").forEach((e) => {
      const text = this.t(e.dataset.i18nAria);
      e.setAttribute("aria-label", text);
      e.title = text;
    });
  }

  /** Server messages are English; translate the common ones. */
  serverError(message) {
    if (this.lang !== "sw") return message;
    for (const [re, sw] of window.DS_SERVER_ERRORS_SW ?? []) {
      const m = message.match(re);
      if (m) return sw.replace("$1", m[1] ?? "");
    }
    return message;
  }
}

const i18n = new I18n(window.DS_I18N);
const t = (key, vars) => i18n.t(key, vars);

/* ---------- API client ---------------------------------------------------- */

class Api {
  async info() {
    const res = await fetch("api/formats");
    if (!res.ok) throw new Error(t("st.server"));
    return res.json();
  }

  /** POSTs a multipart form with upload progress, resolving to { blob, filename }. */
  send(endpoint, form, onUploadProgress = () => {}) {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open("POST", endpoint);
      xhr.responseType = "blob";
      xhr.upload.onprogress = (e) => e.lengthComputable && onUploadProgress(e.loaded / e.total);
      xhr.upload.onload = () => onUploadProgress(1);
      xhr.onerror = () => reject(new Error(t("st.network")));
      xhr.onload = async () => {
        if (xhr.status === 200) {
          resolve({ blob: xhr.response, filename: Api.filename(xhr.getResponseHeader("Content-Disposition")) });
          return;
        }
        let message = t("st.failed", { code: xhr.status });
        try { message = JSON.parse(await xhr.response.text()).error || message; } catch { /* not JSON */ }
        reject(new Error(i18n.serverError(message)));
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

/* ---------- Helpers ------------------------------------------------------- */

const el = (tag, props = {}, ...children) => {
  const node = Object.assign(document.createElement(tag), props);
  node.append(...children);
  return node;
};

const ext = (name) => (name.includes(".") ? name.split(".").pop().toLowerCase() : "");
const IMAGE_EXTS = ["png", "jpg", "jpeg", "bmp", "gif"];
const OFFICE_EXTS = ["docx", "xlsx", "xls", "pptx", "txt"];
const PREVIEWABLE = ["pdf", ...OFFICE_EXTS];

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
  zip: ["file-zipper", "other"],
};
const fileIcon = (name) => {
  const [glyph, kind] = FILE_ICONS[ext(name)] ?? ["file", "other"];
  return icon(glyph, `file-icon ${kind}`);
};

const iconButton = (glyph, label, onclick, { disabled = false, extra = "" } = {}) => {
  const b = el("button", { className: `icon-btn ${extra}`.trim(), type: "button", title: label, disabled, onclick }, icon(glyph));
  b.setAttribute("aria-label", label);
  return b;
};

const formatSize = (bytes) =>
  bytes < 1024 ? `${bytes} B`
    : bytes < 1024 ** 2 ? `${(bytes / 1024).toFixed(0)} KB`
      : `${(bytes / 1024 ** 2).toFixed(1)} MB`;

const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));

/* ---------- File list ----------------------------------------------------- */

class FileList {
  constructor(listEl, { single = false, sortable = false, onChange, onPreview }) {
    this.listEl = listEl;
    this.single = single;
    this.sortable = sortable;
    this.onChange = onChange;
    this.onPreview = onPreview;
    this.files = [];
    this.previewing = null;
    i18n.onChange(() => this.render(false));
  }

  add(newFiles) {
    this.files = this.single ? newFiles.slice(0, 1) : [...this.files, ...newFiles];
    this.render();
  }

  clear() {
    this.files = [];
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

  markPreview(file) {
    this.previewing = file;
    [...this.listEl.children].forEach((li, i) => li.classList.toggle("previewing", this.files[i] === file));
  }

  render(notify = true) {
    this.listEl.replaceChildren(...this.files.map((file, i) => {
      const buttons = [];
      if (this.sortable) {
        const up = iconButton("arrow-up", t("file.up", { name: file.name }), () => this.move(i, -1), { disabled: i === 0 });
        const down = iconButton("arrow-down", t("file.down", { name: file.name }), () => this.move(i, 1),
          { disabled: i === this.files.length - 1 });
        up.dataset.up = "";
        down.dataset.down = "";
        buttons.push(up, down);
      }
      buttons.push(iconButton("xmark", t("file.remove", { name: file.name }), () => this.remove(i), { extra: "remove" }));
      const name = el("button", { type: "button", className: "file-name", textContent: file.name, title: file.name,
        onclick: () => this.onPreview?.(file) });
      name.setAttribute("aria-label", t("file.preview", { name: file.name }));
      const li = el("li", {}, fileIcon(file.name), name,
        el("span", { className: "size", textContent: formatSize(file.size) }), ...buttons);
      li.classList.toggle("previewing", file === this.previewing && this.files.length > 1);
      return li;
    }));
    if (notify) this.onChange();
  }
}

/* ---------- Status box ---------------------------------------------------- */

class Status {
  constructor(node) { this.node = node; this.url = null; }

  busy(text) {
    this.bar = el("span");
    this.track = el("div", { className: "bar" }, this.bar);
    this.label = el("span", { textContent: text });
    this.set("busy", "spinner", "fa-spin", this.label, this.track);
  }

  progress(fraction, processingText = t("st.processing")) {
    if (!this.label) return;
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
      el("span", {}, `${summary ?? t("st.done", { size: formatSize(blob.size) })} ${t("st.download")} `, link));
    link.click(); // start the download automatically
  }

  error(message) { this.set("error", "circle-exclamation", "", el("span", { textContent: message })); }

  clear() {
    this.label = null;
    this.node.className = "status";
    this.node.replaceChildren();
  }

  set(kind, glyph, extra, ...children) {
    this.node.className = `status ${kind}`;
    this.node.replaceChildren(icon(glyph, extra), el("div", { className: "body" }, ...children));
  }
}

/* ---------- Preview pane -------------------------------------------------- */

/**
 * Shows the chosen file (and, after a run, the result) page by page.
 * Images display directly; PDFs and Office files are rendered by the server.
 */
class PreviewPane {
  constructor(root, api) {
    this.root = root;
    this.api = api;
    this.img = root.querySelector(".pv-img");
    this.msg = root.querySelector(".pv-msg");
    this.empty = root.querySelector(".pv-empty");
    this.loading = root.querySelector(".pv-loading");
    this.pager = root.querySelector(".pv-pager");
    this.countEl = root.querySelector(".pv-count");
    this.nameEl = root.querySelector(".pv-name");
    this.switchEl = root.querySelector(".pv-switch");
    this.items = { before: null, after: null };
    this.which = "before";
    this.page = 1;
    this.total = 1;
    this.token = 0;
    this.cache = new WeakMap();   // file → Map(page → {src, total})
    this.urls = new WeakMap();    // image file → object URL
    this.switchEl.querySelectorAll("button").forEach((b) => b.addEventListener("click", () => {
      this.which = b.dataset.which;
      this.page = 1;
      this.render();
    }));
    root.querySelectorAll("[data-step]").forEach((b) => b.addEventListener("click", () => {
      this.page = clamp(this.page + Number(b.dataset.step), 1, this.total);
      this.render();
    }));
    i18n.onChange(() => this.render());
  }

  show(file) {
    this.items = { before: file, after: null };
    this.which = "before";
    this.page = 1;
    this.render();
  }

  showResult(file) {
    this.items.after = file;
    this.which = "after";
    this.page = 1;
    this.render();
  }

  width() {
    return clamp(Math.round((this.root.clientWidth || 500) * (window.devicePixelRatio || 1)), 300, 1000);
  }

  async render() {
    const item = this.items[this.which];
    const both = !!(this.items.before && this.items.after);
    this.switchEl.hidden = !both;
    this.switchEl.querySelectorAll("button").forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.which === this.which)));
    this.img.hidden = this.msg.hidden = this.pager.hidden = this.loading.hidden = true;
    this.nameEl.textContent = item?.name ?? "";
    this.empty.hidden = !!item;
    if (!item) return;

    const e = ext(item.name);
    if (e === "zip") return this.message(t("pv.zip"));
    if (IMAGE_EXTS.includes(e)) {
      if (!this.urls.has(item)) this.urls.set(item, URL.createObjectURL(item));
      this.total = 1;
      this.img.src = this.urls.get(item);
      this.img.hidden = false;
      return;
    }
    if (!PREVIEWABLE.includes(e)) return this.message(t("pv.none"));

    const token = ++this.token;
    if (!this.cache.has(item)) this.cache.set(item, new Map());
    const pages = this.cache.get(item);
    let entry = pages.get(this.page);
    if (!entry) {
      this.loading.hidden = false;
      try {
        const form = new FormData();
        form.append("files", item, item.name);
        form.append("page", String(this.page));
        form.append("width", String(this.width()));
        const { blob } = await this.api.send("api/preview", form);
        const data = JSON.parse(await blob.text());
        entry = { src: data.pages[0].src, total: data.count };
        pages.set(this.page, entry);
      } catch (err) {
        if (token === this.token) {
          this.loading.hidden = true;
          this.message(err.message, true);
        }
        return;
      }
      if (token !== this.token) return; // the user moved on meanwhile
      this.loading.hidden = true;
    }
    this.total = entry.total;
    this.img.src = entry.src;
    this.img.hidden = false;
    this.pager.hidden = this.total <= 1;
    this.countEl.textContent = t("pv.page", { n: this.page, total: this.total });
    const [prev, next] = this.pager.querySelectorAll("[data-step]");
    prev.disabled = this.page <= 1;
    next.disabled = this.page >= this.total;
  }

  message(text, isError = false) {
    this.msg.textContent = text;
    this.msg.classList.toggle("error", isError);
    this.msg.hidden = false;
  }
}

/* ---------- What can follow what ------------------------------------------ */

const NEXT_STEPS = {
  pdf: ["compress", "sign", "protect", "number", "watermark", "organize", "split", "crop", "merge", "extract", "ocr", "convert"],
  image: ["images", "ocr", "convert"],
  office: ["convert", "merge"],
};

/* ---------- Tool panels (template method) --------------------------------- */

class ToolPanel {
  constructor(tool, section, ctx, { single = false, sortable = false, minFiles = 1 } = {}) {
    this.tool = tool;
    this.id = tool.id;
    this.section = section;
    this.api = ctx.api;
    this.info = ctx.info;
    this.app = ctx.app;
    this.minFiles = minFiles;
    this.button = section.querySelector("[data-action]");
    this.status = new Status(section.querySelector(".status"));
    this.nextEl = section.querySelector(".next-steps");
    this.files = new FileList(section.querySelector(".files"), {
      single, sortable, onChange: () => this.filesChanged(), onPreview: (f) => this.previewFile(f),
    });
    const pane = section.querySelector(".pv-body") ? section.querySelector(".preview-pane") : null;
    this.preview = pane ? new PreviewPane(pane, this.api) : null;
    this.wireDropzone(section.querySelector(".dropzone"));
    this.button.addEventListener("click", () => this.submit());
    i18n.onChange(() => this.renderNext());
    this.refresh();
  }

  /* Subclasses override these. */
  endpoint() { throw new Error("abstract"); }
  busyText() { return this.files.files.length > 1 ? t("st.uploadingN", { n: this.files.files.length }) : t("st.uploading"); }
  processingText() { return t("st.processing"); }
  fillForm(_form) {}
  async prepare() {}
  validate() { return null; }
  summary(_result) { return null; }
  ready() { return this.files.files.length >= this.minFiles; }
  nextTools(file) {
    const e = ext(file.name);
    const kind = e === "pdf" ? "pdf" : IMAGE_EXTS.includes(e) ? "image" : OFFICE_EXTS.includes(e) ? "office" : null;
    return (NEXT_STEPS[kind] ?? []).filter((id) => id !== this.id);
  }

  field(name) { return this.section.querySelector(`[name="${name}"]`); }

  requireExt(allowed, key) {
    return this.files.files.every((f) => allowed.includes(ext(f.name))) ? null : t(key);
  }

  filesChanged() {
    this.hideNext();
    const first = this.files.files[0] ?? null;
    this.preview?.show(first);
    this.files.markPreview(first);
    this.refresh();
  }

  previewFile(file) {
    this.preview?.show(file);
    this.files.markPreview(file);
  }

  /** Files handed over from another tool ("continue with this file"). */
  receive(files) {
    this.status.clear();
    this.files.files = [];
    this.accept(files);
  }

  wireDropzone(zone) {
    this.zone = zone;
    const input = zone.querySelector("input[type=file]");
    input.addEventListener("change", () => { this.accept([...input.files]); input.value = ""; });
    ["dragenter", "dragover"].forEach((type) => zone.addEventListener(type, (e) => {
      e.preventDefault(); zone.classList.add("dragging");
    }));
    ["dragleave", "drop"].forEach((type) => zone.addEventListener(type, (e) => {
      e.preventDefault(); zone.classList.remove("dragging");
    }));
    zone.addEventListener("drop", (e) => {
      e.stopPropagation(); // the page-wide drop handler would add them again
      this.accept([...e.dataTransfer.files]);
    });
  }

  accept(files) {
    const max = this.info.maxUploadMb * 1024 * 1024;
    const tooBig = files.filter((f) => f.size > max);
    const ok = files.filter((f) => f.size <= max);
    if (tooBig.length) this.status.error(t("st.tooBig", { names: tooBig.map((f) => f.name).join(", "), mb: this.info.maxUploadMb }));
    else this.status.clear();
    if (ok.length) this.files.add(ok);
  }

  refresh() {
    this.button.disabled = !this.ready() || this.busy || this.disabled;
  }

  async submit() {
    const problem = this.validate()
      ?? (this.files.files.length > this.info.maxFiles ? t("st.tooMany", { n: this.info.maxFiles }) : null);
    if (problem) { this.status.error(problem); return; }

    this.busy = true;
    this.refresh();
    this.hideNext();
    this.status.busy(this.busyText());
    try {
      await this.prepare();
      const form = new FormData();
      this.files.files.forEach((f) => form.append("files", f, f.name));
      this.fillForm(form);
      const result = await this.api.send(this.endpoint(), form, (p) => this.status.progress(p, this.processingText()));
      this.status.done(result, this.summary(result));
      this.onResult(result);
    } catch (err) {
      this.status.error(err.message);
    } finally {
      this.busy = false;
      this.refresh();
    }
  }

  onResult({ blob, filename }) {
    const file = new File([blob], filename, { type: blob.type });
    this.preview?.showResult(file);
    this.nextFile = file;
    this.renderNext();
  }

  renderNext() {
    const file = this.nextFile;
    const ids = file ? this.nextTools(file).filter((id) => this.app.panels[id]) : [];
    this.nextEl.hidden = !ids.length;
    if (!ids.length) return;
    const tools = this.app.tools;
    this.nextEl.replaceChildren(
      el("p", { textContent: t("next.title") }),
      el("div", {}, ...ids.slice(0, 8).map((id) => el("button", {
        type: "button", className: "chip", onclick: () => this.app.open(id, [file]),
      }, icon(tools[id].icon), t(`tool.${id}.name`)))));
  }

  hideNext() {
    this.nextFile = null;
    this.nextEl.hidden = true;
  }
}

class ConvertPanel extends ToolPanel {
  constructor(tool, section, ctx) {
    super(tool, section, ctx);
    this.select = this.field("to");
    this.formats = ctx.info.write.filter((f) => f !== "jpeg");
    this.userPicked = false;
    this.select.addEventListener("change", () => { this.userPicked = true; });
    this.renderOptions();
    i18n.onChange(() => this.renderOptions());
  }

  renderOptions() {
    const value = this.select.value;
    this.select.replaceChildren(...this.formats.map((f) => el("option", { value: f, textContent: t(`fmt.${f}`) })));
    if (value) this.select.value = value;
  }

  /** Pick a sensible target until the user chooses one themselves. */
  filesChanged() {
    super.filesChanged();
    if (!this.select || this.userPicked || !this.files.files.length) return;
    const kinds = new Set(this.files.files.map((f) => ext(f.name)));
    this.select.value = kinds.size === 1 && kinds.has("pdf") ? "docx" : "pdf";
  }

  endpoint() { return "api/convert"; }

  validate() {
    const target = this.select.value;
    const same = this.files.files.filter((f) => ext(f.name) === target || (target === "jpg" && ext(f.name) === "jpeg"));
    return same.length === this.files.files.length ? t("v.already", { fmt: target.toUpperCase() }) : null;
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
  constructor(tool, section, ctx) { super(tool, section, ctx, { sortable: true, minFiles: 2 }); }
  endpoint() { return "api/merge"; }
}

class SplitPanel extends ToolPanel {
  constructor(tool, section, ctx) {
    super(tool, section, ctx, { single: true });
    this.field("every").addEventListener("focus", () => { section.querySelector("[value=every]").checked = true; });
    this.field("ranges").addEventListener("focus", () => { section.querySelector("[value=ranges]").checked = true; });
  }
  endpoint() { return "api/split"; }
  mode() { return this.section.querySelector("[name=mode]:checked").value; }

  validate() {
    if (ext(this.files.files[0].name) !== "pdf") return t("v.pdfOnly");
    if (this.mode() === "ranges" && !this.field("ranges").value.trim()) return t("v.ranges");
    return null;
  }

  fillForm(form) {
    const name = this.mode();
    form.append(name, this.field(name).value.trim());
  }
}

class CompressPanel extends ToolPanel {
  endpoint() { return "api/compress"; }
  processingText() { return t("b.compress"); }
  validate() { return this.requireExt(["pdf"], "v.pdfOnly"); }
  fillForm(form) { form.append("level", this.section.querySelector("[name=level]:checked").value); }

  summary({ blob }) {
    const before = this.files.files.reduce((sum, f) => sum + f.size, 0);
    if (this.files.files.length > 1) return t("r.compressedN", { n: this.files.files.length });
    const pct = Math.round((1 - blob.size / before) * 100);
    return pct > 0
      ? t("r.compressed", { before: formatSize(before), after: formatSize(blob.size), pct })
      : t("r.compact", { size: formatSize(before) });
  }
}

class ImagesPanel extends ToolPanel {
  constructor(tool, section, ctx) { super(tool, section, ctx, { sortable: true }); }
  endpoint() { return "api/images-to-pdf"; }
  validate() { return this.requireExt(IMAGE_EXTS, "v.imagesOnly"); }
  fillForm(form) {
    form.append("pageSize", this.field("pageSize").value);
    form.append("margin", this.field("margin").value);
  }
}

class OcrPanel extends ToolPanel {
  constructor(tool, section, ctx) {
    super(tool, section, ctx, { single: true });
    this.ocr = ctx.info.ocr ?? { available: false, languages: [] };
    this.renderLangs();
    i18n.onChange(() => this.renderLangs());
    if (!this.ocr.available) {
      this.disabled = true;
      section.querySelector(".notice").hidden = false;
      this.zone.hidden = true;
      this.refresh();
    }
  }

  renderLangs() {
    const select = this.field("lang");
    const value = select.value;
    const langs = [...this.ocr.languages];
    const options = langs.map((l) => el("option", { value: l, textContent: t(`ln.${l}`) === `ln.${l}` ? l : t(`ln.${l}`) }));
    if (langs.includes("eng") && langs.includes("swa")) options.push(el("option", { value: "eng+swa", textContent: t("ln.engswa") }));
    select.replaceChildren(...options);
    select.value = value || (i18n.lang === "sw" && langs.includes("swa") ? "swa" : langs.includes("eng") ? "eng" : langs[0] ?? "");
  }

  endpoint() { return "api/ocr"; }
  processingText() { return t("b.ocr"); }
  validate() { return this.requireExt(["pdf", ...IMAGE_EXTS], "v.ocrTypes"); }

  fillForm(form) {
    form.append("lang", this.field("lang").value);
    form.append("output", this.field("output").value);
    const pages = this.field("pages").value.trim();
    if (pages) form.append("pages", pages);
  }
}

class CropPanel extends ToolPanel {
  constructor(tool, section, ctx) {
    super(tool, section, ctx, { single: true });
    section.querySelectorAll("[name=cropMode]").forEach((r) => r.addEventListener("change", () => this.applyMode()));
    this.applyMode();
  }
  mode() { return this.section.querySelector("[name=cropMode]:checked").value; }
  applyMode() {
    this.section.querySelectorAll("[data-crop]").forEach((e) => { e.hidden = e.dataset.crop !== this.mode(); });
  }
  endpoint() { return "api/crop"; }
  validate() { return this.requireExt(["pdf"], "v.pdfOnly"); }
  fillForm(form) {
    form.append("mode", this.mode());
    form.append("margin", this.field("margin").value);
    form.append("paper", this.field("paper").value);
  }
}

class ExtractPanel extends ToolPanel {
  constructor(tool, section, ctx) { super(tool, section, ctx, { single: true }); }
  endpoint() { return "api/extract-images"; }
  validate() { return this.requireExt(["pdf"], "v.pdfOnly"); }
  fillForm(form) {
    const pages = this.field("pages").value.trim();
    if (pages) form.append("pages", pages);
  }
}

class WatermarkPanel extends ToolPanel {
  constructor(tool, section, ctx) {
    super(tool, section, ctx, { single: true });
    this.textInput = this.field("wmText");
    this.textInput.value = t("wm.textDefault");
    this.defaultText = this.textInput.value;
    i18n.onChange(() => {
      if (this.textInput.value === this.defaultText) this.textInput.value = t("wm.textDefault");
      this.defaultText = t("wm.textDefault");
    });
    section.querySelectorAll("[name=wmKind]").forEach((r) => r.addEventListener("change", () => this.applyKind()));
    this.field("wmFile").addEventListener("change", (e) => { this.image = e.target.files[0] ?? null; });
    const range = this.field("opacity");
    const out = range.parentElement.querySelector("output");
    range.addEventListener("input", () => { out.textContent = `${range.value}%`; });
    this.applyKind();
  }
  kind() { return this.section.querySelector("[name=wmKind]:checked").value; }
  applyKind() {
    this.section.querySelectorAll("[data-wm]").forEach((e) => { e.hidden = e.dataset.wm !== this.kind(); });
  }
  endpoint() { return "api/watermark"; }
  validate() {
    const pdf = this.requireExt(["pdf"], "v.pdfOnly");
    if (pdf) return pdf;
    if (this.kind() === "text" && !this.textInput.value.trim()) return t("v.wmText");
    if (this.kind() === "image" && !this.image) return t("v.wmImage");
    return null;
  }
  fillForm(form) {
    if (this.kind() === "image") {
      form.append("files", this.image, this.image.name);
    } else {
      form.append("text", this.textInput.value.trim());
      form.append("color", this.field("color").value);
    }
    form.append("opacity", String(Number(this.field("opacity").value) / 100));
    form.append("angle", this.field("angle").value);
    form.append("layout", this.field("layout").value);
    const pages = this.field("wmPages").value;
    if (pages) form.append("pages", pages);
  }
}

class NumberPanel extends ToolPanel {
  constructor(tool, section, ctx) {
    super(tool, section, ctx, { single: true });
    this.renderFormats();
    i18n.onChange(() => this.renderFormats());
  }

  renderFormats() {
    const select = this.field("format");
    const index = Math.max(0, select.selectedIndex);
    const formats = ["{n}", t("nm.fmtPage"), t("nm.fmtPageOf"), "{n} / {total}", "- {n} -"];
    select.replaceChildren(...formats.map((f) => el("option", {
      value: f, textContent: f.replace("{n}", "1").replace("{total}", "10"),
    })));
    select.selectedIndex = index;
  }

  endpoint() { return "api/number"; }
  validate() { return this.requireExt(["pdf"], "v.pdfOnly"); }
  fillForm(form) {
    form.append("position", this.section.querySelector("[name=position]:checked").value);
    ["format", "size", "start"].forEach((n) => form.append(n, this.field(n).value));
    if (this.field("skipFirst").checked) form.append("pages", "2-");
  }
}

class ProtectPanel extends ToolPanel {
  constructor(tool, section, ctx) {
    super(tool, section, ctx, { single: true });
    section.querySelectorAll("[name=secMode]").forEach((r) => r.addEventListener("change", () => this.applyMode()));
    this.reveal = section.querySelector("[data-reveal]");
    this.reveal.addEventListener("click", () => {
      this.shown = !this.shown;
      ["password", "confirm"].forEach((n) => { this.field(n).type = this.shown ? "text" : "password"; });
      this.labelReveal();
    });
    i18n.onChange(() => { this.labelReveal(); this.applyMode(); });
    this.labelReveal();
    this.applyMode();
  }

  labelReveal() {
    this.reveal.replaceChildren(icon(this.shown ? "eye-slash" : "eye"));
    const label = t(this.shown ? "pr.hide" : "pr.show");
    this.reveal.setAttribute("aria-label", label);
    this.reveal.title = label;
  }

  mode() { return this.section.querySelector("[name=secMode]:checked").value; }

  applyMode() {
    const protect = this.mode() === "protect";
    this.section.querySelectorAll("[data-protect-only]").forEach((e) => { e.hidden = !protect; });
    this.section.querySelector("[data-label]").textContent = protect ? t("tool.protect.action") : t("pr.remove");
    this.field("password").autocomplete = protect ? "new-password" : "current-password";
  }

  endpoint() { return this.mode() === "protect" ? "api/protect" : "api/unlock"; }
  processingText() { return this.mode() === "protect" ? t("b.encrypt") : t("b.unlock"); }

  validate() {
    const pdf = this.requireExt(["pdf"], "v.pdfOnly");
    if (pdf) return pdf;
    const pw = this.field("password").value;
    if (pw.length < 4) return t("v.pwShort");
    if (this.mode() === "protect" && pw !== this.field("confirm").value) return t("v.pwMatch");
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
    return protect ? t("r.protected") : t("r.unlocked");
  }

  /** An encrypted result can't be processed further; an unlocked one can. */
  nextTools(file) { return this.mode() === "protect" ? [] : super.nextTools(file); }
}

/**
 * Organize: loads page thumbnails for one PDF into the right-hand panel,
 * lets the user rotate, delete and reorder them, then sends order + rotations.
 */
class OrganizePanel extends ToolPanel {
  constructor(tool, section, ctx) {
    super(tool, section, ctx, { single: true });
    this.grid = section.querySelector(".page-grid");
    this.sideEmpty = section.querySelector(".side-empty");
    this.toolbar = section.querySelector(".pages-toolbar");
    this.hint = section.querySelector(".pages-hint");
    this.countEl = section.querySelector(".page-count");
    this.pages = [];
    this.original = [];
    this.loadToken = 0;
    section.querySelectorAll("[data-rotate-all]").forEach((b) =>
      b.addEventListener("click", () => this.rotateAll(Number(b.dataset.rotateAll))));
    section.querySelector("[data-reset]").addEventListener("click", () => this.reset());
    i18n.onChange(() => this.render());
  }

  endpoint() { return "api/organize"; }
  processingText() { return t("b.save"); }
  ready() { return (this.pages?.length ?? 0) > 0; } // runs once before the constructor finishes
  validate() { return this.pages.length ? null : t("v.allRemoved"); }

  fillForm(form) {
    form.append("order", this.pages.map((p) => p.orig).join(","));
    form.append("rotate", this.pages.filter((p) => p.rotation % 360).map((p) => `${p.orig}:${p.rotation % 360}`).join(","));
  }

  async filesChanged() {
    this.hideNext();
    const token = ++this.loadToken;
    this.pages = [];
    this.original = [];
    this.render();
    const file = this.files.files[0];
    if (!file) return;
    if (ext(file.name) !== "pdf") { this.status.error(t("v.pdfOnly")); return; }

    const form = new FormData();
    form.append("files", file, file.name);
    this.status.busy(t("b.loadPages"));
    this.sideEmpty.hidden = true;
    this.grid.replaceChildren(...Array.from({ length: 6 }, () => this.skeletonCard()));
    try {
      const { blob } = await this.api.send("api/preview", form, (p) => this.status.progress(p, t("b.renderPages")));
      if (token !== this.loadToken) return; // a newer file was chosen meanwhile
      const data = JSON.parse(await blob.text());
      this.original = data.pages.map((p, i) => ({ orig: i + 1, src: p.src, base: p.rotation, rotation: 0 }));
      this.reset();
      this.status.clear();
    } catch (err) {
      if (token !== this.loadToken) return;
      this.grid.replaceChildren();
      this.render();
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
    if (!this.grid) return;
    const removed = this.original.length - this.pages.length;
    const loaded = this.original.length > 0;
    this.toolbar.hidden = this.hint.hidden = !loaded;
    this.sideEmpty.hidden = loaded || this.grid.querySelector(".loading") !== null;
    this.countEl.textContent = loaded ? t("org.count", { n: this.pages.length }) + (removed ? t("org.removed", { n: removed }) : "") : "";
    if (loaded || !this.grid.querySelector(".loading")) {
      this.grid.replaceChildren(...this.pages.map((p, i) => this.card(p, i)));
    }
    this.refresh();
  }

  card(page, index) {
    const angle = page.base + page.rotation;
    const sideways = angle % 180 !== 0;
    const img = el("img", { src: page.src, alt: `${page.orig}`, draggable: false });
    img.style.transform = `rotate(${angle}deg)${sideways ? " scale(.72)" : ""}`;
    const n = page.orig;
    const li = el("li", { className: "page-card", draggable: true },
      el("div", { className: "page-thumb" }, img),
      el("div", { className: "page-meta" },
        el("span", { className: "page-num", textContent: String(n) }),
        iconButton("trash-can", t("org.delete", { n }), () => { this.pages.splice(index, 1); this.render(); }, { extra: "remove" })),
      el("div", { className: "page-meta" },
        iconButton("chevron-left", t("org.earlier", { n }), () => this.move(index, index - 1), { disabled: index === 0 }),
        el("span", { className: "page-actions" },
          iconButton("rotate-left", t("org.rotL", { n }), () => { page.rotation = (page.rotation + 270) % 360; this.render(); }),
          iconButton("rotate-right", t("org.rotR", { n }), () => { page.rotation = (page.rotation + 90) % 360; this.render(); })),
        iconButton("chevron-right", t("org.later", { n }), () => this.move(index, index + 1),
          { disabled: index === this.pages.length - 1 })));

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
      e.stopPropagation();
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
  constructor(tool, section, ctx) {
    super(tool, section, ctx, { single: true });
    this.place = section.querySelector(".sign-place");
    this.sideEmpty = section.querySelector(".side-empty");
    this.stage = section.querySelector(".sign-stage");
    this.pageImg = section.querySelector(".sign-page-img");
    this.box = section.querySelector(".sig-box");
    this.sigPreview = section.querySelector(".sig-preview");
    this.pageRow = section.querySelector(".sign-page-row");
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
    i18n.onChange(() => this.renderPageOptions());
  }

  sigMode() { return this.section.querySelector("[name=sigMode]:checked").value; }
  endpoint() { return "api/sign"; }
  processingText() { return t("b.sign"); }
  ready() { return (this.files?.files.length ?? 0) === 1 && !!this.pageLoaded && !!this.sigCanvas; }

  validate() {
    if (!this.sigCanvas) return t("v.sigMissing");
    if (!this.pageLoaded) return t("v.pageLoading");
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
      const bitmap = await createImageBitmap(file, { imageOrientation: "from-image" });
      const scale = Math.min(1, 1500 / Math.max(bitmap.width, bitmap.height));
      const c = el("canvas", { width: Math.round(bitmap.width * scale), height: Math.round(bitmap.height * scale) });
      const g = c.getContext("2d", { willReadFrequently: true });
      g.drawImage(bitmap, 0, 0, c.width, c.height);
      // Make a white/paper background transparent, so photographed signatures sit on the page.
      const img = g.getImageData(0, 0, c.width, c.height);
      for (let i = 0; i < img.data.length; i += 4) {
        if (img.data[i] > 225 && img.data[i + 1] > 225 && img.data[i + 2] > 225) img.data[i + 3] = 0;
      }
      g.putImageData(img, 0, 0);
      this.uploaded = trimCanvas(c) ?? c;
    } catch {
      this.uploaded = null;
      this.status.error(t("sg.readErr"));
    }
    this.updateSignature();
  }

  updateSignature() {
    const c = this.currentSignature();
    this.sigCanvas = c;
    if (c) {
      this.aspect = c.height / c.width;
      this.sigPreview.src = c.toDataURL("image/png");
    } else {
      this.aspect = 1 / 3;
      this.sigPreview.removeAttribute("src");
    }
    this.box.classList.toggle("has-sig", !!c);
    this.layoutBox();
    this.refresh();
  }

  /* --- page preview --- */

  async filesChanged() {
    this.hideNext();
    this.pageLoaded = false;
    this.place.hidden = true;
    this.pageRow.hidden = true;
    this.sideEmpty.hidden = false;
    this.refresh();
    const file = this.files.files[0];
    if (!file) return;
    if (ext(file.name) !== "pdf") { this.status.error(t("v.pdfOnly")); return; }
    this.count = 0;
    await this.loadPage(0); // last page: where signatures usually go
  }

  renderPageOptions() {
    if (!this.count) return;
    const value = this.pageSelect.value;
    this.pageSelect.replaceChildren(...Array.from({ length: this.count }, (_, i) =>
      el("option", { value: String(i + 1), textContent: t("pv.page", { n: i + 1, total: this.count }) })));
    this.pageSelect.value = value || String(this.page);
  }

  async loadPage(page) {
    const token = ++this.loadToken;
    const file = this.files.files[0];
    const form = new FormData();
    form.append("files", file, file.name);
    form.append("page", String(page));
    form.append("width", String(Math.min(1100, Math.round(560 * (window.devicePixelRatio || 1)))));
    this.status.busy(t("b.loadPage"));
    try {
      const { blob } = await this.api.send("api/preview", form, (p) => this.status.progress(p, t("b.renderPages")));
      if (token !== this.loadToken) return;
      const data = JSON.parse(await blob.text());
      this.page = data.page;
      if (this.count !== data.count) {
        this.count = data.count;
        this.renderPageOptions();
      }
      this.pageSelect.value = String(data.page);
      this.pageImg.src = data.pages[0].src;
      await this.pageImg.decode().catch(() => {});
      this.place.hidden = false;
      this.pageRow.hidden = false;
      this.sideEmpty.hidden = true;
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
    p.w = clamp(p.w, 0.05, maxW);
    const hFrac = (p.w * W * this.aspect) / H;
    p.x = clamp(p.x, 0, 1 - p.w);
    p.y = clamp(p.y, 0, 1 - hFrac);
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
    [this.box, handle].forEach((target) => {
      target.addEventListener("pointermove", move);
      target.addEventListener("pointerup", end);
      target.addEventListener("pointercancel", end);
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

/* ---------- Tool registry ---------------------------------------------------- */

const PDF_ONLY = ".pdf,application/pdf";
const IMAGES = ".jpg,.jpeg,.png,.bmp,.gif,image/*";

/**
 * Every tool, once. The home screen, search, tool pages and "continue with
 * this file" suggestions are all built from this list.
 */
const TOOLS = [
  { id: "convert", group: "convert", icon: "right-left", color: "blue", Panel: ConvertPanel, multiple: true,
    accept: (info) => info.read.map((e) => `.${e}`).join(","), action: "wand-magic-sparkles",
    keywords: "convert change word excel powerpoint pdf to docx xlsx pptx jpg png txt badilisha geuza" },
  { id: "images", group: "convert", icon: "images", color: "blue", Panel: ImagesPanel, multiple: true, accept: () => IMAGES,
    action: "file-pdf", keywords: "jpg png photo picture scan image to pdf picha" },
  { id: "ocr", group: "convert", icon: "spell-check", color: "blue", Panel: OcrPanel, accept: () => `${PDF_ONLY},${IMAGES}`,
    action: "spell-check", keywords: "ocr text recognize scan searchable read soma maandishi tambua" },
  { id: "merge", group: "organize", icon: "layer-group", color: "teal", Panel: MergePanel, multiple: true,
    accept: (info) => info.read.map((e) => `.${e}`).join(","), action: "layer-group",
    keywords: "merge combine join append unganisha" },
  { id: "split", group: "organize", icon: "scissors", color: "teal", Panel: SplitPanel, accept: () => PDF_ONLY,
    action: "scissors", keywords: "split separate extract pages divide gawanya tenganisha" },
  { id: "organize", group: "organize", icon: "table-cells-large", color: "teal", Panel: OrganizePanel, accept: () => PDF_ONLY,
    action: "floppy-disk", side: "organize", keywords: "organize rotate delete reorder sort pages move panga zungusha futa" },
  { id: "crop", group: "organize", icon: "crop-simple", color: "teal", Panel: CropPanel, accept: () => PDF_ONLY,
    action: "crop-simple", keywords: "crop trim margin resize paper a4 letter size kata pambizo" },
  { id: "extract", group: "organize", icon: "file-image", color: "teal", Panel: ExtractPanel, accept: () => PDF_ONLY,
    action: "file-export", keywords: "extract images pictures photos save toa picha" },
  { id: "compress", group: "edit", icon: "file-zipper", color: "amber", Panel: CompressPanel, multiple: true, accept: () => PDF_ONLY,
    action: "file-zipper", keywords: "compress shrink reduce smaller size email optimize punguza ndogo" },
  { id: "watermark", group: "edit", icon: "stamp", color: "amber", Panel: WatermarkPanel, accept: () => PDF_ONLY,
    action: "stamp", keywords: "watermark stamp confidential draft logo brand alama maji nembo" },
  { id: "number", group: "edit", icon: "list-ol", color: "amber", Panel: NumberPanel, accept: () => PDF_ONLY,
    action: "list-ol", keywords: "page numbers numbering footer header namba kurasa" },
  { id: "protect", group: "secure", icon: "lock", color: "purple", Panel: ProtectPanel, accept: () => PDF_ONLY,
    action: "lock", keywords: "protect password encrypt lock unlock remove security linda nenosiri fungua" },
  { id: "sign", group: "secure", icon: "signature", color: "purple", Panel: SignPanel, accept: () => PDF_ONLY,
    action: "file-signature", side: "sign", keywords: "sign signature autograph esign saini sahihi" },
];
const GROUPS = ["convert", "organize", "edit", "secure"];

/* ---------- App: home, routing, theme, language, install --------------------- */

class App {
  constructor(api, info) {
    this.api = api;
    this.info = info;
    this.tools = Object.fromEntries(TOOLS.map((tool) => [tool.id, tool]));
    this.panels = {};
    this.main = document.getElementById("main");
    this.home = document.getElementById("view-home");
    this.search = document.getElementById("tool-search");

    for (const tool of TOOLS) {
      const section = this.buildSection(tool);
      this.main.append(section);
      i18n.apply(section);
      this.panels[tool.id] = new tool.Panel(tool, section, { api, info, app: this });
    }
    this.renderHome();
    this.wireSearch();
    this.wireTheme();
    this.wireLanguage();
    this.wireInstall();
    this.wireGlobalDrop();
    i18n.onChange(() => { this.renderHome(); this.updateTitle(); this.renderLimits(); });
    this.renderLimits();
    window.addEventListener("hashchange", () => this.route());
    this.route();
  }

  buildSection(tool) {
    const section = el("section", { className: "view tool", id: `view-${tool.id}`, hidden: true });
    section.dataset.tool = tool.id;
    section.innerHTML = `
      <div class="tool-head">
        <a class="back" href="#"><i class="fa-solid fa-arrow-left" aria-hidden="true"></i> <span data-i18n="nav.allTools"></span></a>
        <div class="tool-title">
          <span class="tool-icon c-${tool.color}"><i class="fa-solid fa-${tool.icon}" aria-hidden="true"></i></span>
          <div><h1 tabindex="-1" data-i18n="tool.${tool.id}.name"></h1><p data-i18n="tool.${tool.id}.desc"></p></div>
        </div>
      </div>
      <div class="workspace">
        <div class="card controls">
          <label class="dropzone">
            <input type="file" class="visually-hidden">
            <i class="fa-solid fa-cloud-arrow-up dz-icon" aria-hidden="true"></i>
            <span class="dz-title"><span data-i18n="tool.${tool.id}.drop"></span> <u data-i18n="dz.browse"></u></span>
            <span class="dz-hint" data-i18n="tool.${tool.id}.hint"></span>
          </label>
          <ul class="files" aria-live="polite"></ul>
          <div class="opts"></div>
          <button class="primary" data-action><i class="fa-solid fa-${tool.action}" aria-hidden="true"></i> <span data-label data-i18n="tool.${tool.id}.action"></span></button>
          <div class="status" role="status"></div>
          <div class="next-steps" hidden></div>
        </div>
        <aside class="card preview-pane"></aside>
      </div>`;
    const input = section.querySelector(".dropzone input");
    input.multiple = !!tool.multiple;
    input.accept = tool.accept(this.info);
    const opts = document.getElementById(`opts-${tool.id}`);
    if (opts) section.querySelector(".opts").append(opts.content.cloneNode(true));
    const side = document.getElementById(`side-${tool.side ?? "preview"}`);
    section.querySelector(".preview-pane").append(side.content.cloneNode(true));
    return section;
  }

  renderHome() {
    const container = document.getElementById("tool-groups");
    container.replaceChildren(...GROUPS.map((group) => el("section", { className: "tool-group" },
      el("h2", { textContent: t(`group.${group}`) }),
      el("div", { className: "tool-cards" }, ...TOOLS.filter((tool) => tool.group === group).map((tool) => {
        const card = el("a", { className: "tool-card", href: `#${tool.id}` },
          el("span", { className: `tool-icon c-${tool.color}` }, icon(tool.icon)),
          el("span", {}, el("b", { textContent: t(`tool.${tool.id}.name`) }), el("small", { textContent: t(`tool.${tool.id}.desc`) })));
        card.dataset.search = [t(`tool.${tool.id}.name`), t(`tool.${tool.id}.desc`), tool.keywords,
          i18n.dicts.en[`tool.${tool.id}.name`], i18n.dicts.sw[`tool.${tool.id}.name`]].join(" ").toLowerCase();
        return card;
      })))));
    this.filter();
  }

  wireSearch() {
    this.search.addEventListener("input", () => this.filter());
    this.search.addEventListener("keydown", (e) => {
      if (e.key === "Enter") {
        const first = this.home.querySelector(".tool-card:not([hidden])");
        if (first) location.hash = first.getAttribute("href");
      } else if (e.key === "Escape") {
        this.search.value = "";
        this.filter();
      }
    });
    document.addEventListener("keydown", (e) => {
      const typing = /^(INPUT|TEXTAREA|SELECT)$/.test(document.activeElement?.tagName);
      if (e.key === "/" && !typing && !this.home.hidden) {
        e.preventDefault();
        this.search.focus();
      }
    });
  }

  filter() {
    const words = this.search.value.toLowerCase().trim().split(/\s+/).filter(Boolean);
    let shown = 0;
    this.home.querySelectorAll(".tool-card").forEach((card) => {
      const match = words.every((w) => card.dataset.search.includes(w));
      card.hidden = !match;
      if (match) shown++;
    });
    this.home.querySelectorAll(".tool-group").forEach((g) => { g.hidden = !g.querySelector(".tool-card:not([hidden])"); });
    const empty = document.getElementById("search-empty");
    empty.hidden = shown > 0;
    empty.textContent = t("search.none", { q: this.search.value.trim() });
  }

  route() {
    const id = location.hash.slice(1);
    const current = this.panels[id] ? id : null;
    this.home.hidden = !!current;
    this.main.querySelectorAll(".view.tool").forEach((v) => { v.hidden = v.dataset.tool !== current; });
    this.current = current;
    this.updateTitle();
    window.scrollTo(0, 0);
    if (current) document.querySelector(`#view-${current} h1`).focus({ preventScroll: true });
  }

  updateTitle() {
    document.title = this.current ? `${t(`tool.${this.current}.name`)} · DocSmelt` : "DocSmelt";
  }

  /** Opens a tool, optionally with files already loaded (continue with result / drop on home). */
  open(id, files) {
    if (location.hash !== `#${id}`) location.hash = id;
    else this.route();
    if (files?.length) this.panels[id].receive(files);
  }

  renderLimits() {
    document.getElementById("limits").textContent = t("foot.limits", { files: this.info.maxFiles, mb: this.info.maxUploadMb });
  }

  wireTheme() {
    const button = document.getElementById("theme-btn");
    let theme = "light";
    try { theme = localStorage.getItem("docsmelt.theme") || "light"; } catch { /* storage blocked */ }
    const apply = () => {
      document.documentElement.dataset.theme = theme;
      button.replaceChildren(icon(theme === "dark" ? "sun" : "moon"));
      const label = t(theme === "dark" ? "theme.toLight" : "theme.toDark");
      button.setAttribute("aria-label", label);
      button.title = label;
      document.querySelector('meta[name="theme-color"]').content = theme === "dark" ? "#1c2230" : "#6aa8ff";
    };
    button.addEventListener("click", () => {
      theme = theme === "dark" ? "light" : "dark";
      try { localStorage.setItem("docsmelt.theme", theme); } catch { /* storage blocked */ }
      apply();
    });
    i18n.onChange(apply);
    apply();
  }

  wireLanguage() {
    const buttons = document.querySelectorAll("[data-lang]");
    const mark = () => buttons.forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.lang === i18n.lang)));
    buttons.forEach((b) => b.addEventListener("click", () => i18n.set(b.dataset.lang)));
    i18n.onChange(mark);
    mark();
  }

  wireInstall() {
    const button = document.getElementById("install-btn");
    let prompt = null;
    window.addEventListener("beforeinstallprompt", (e) => {
      e.preventDefault();
      prompt = e;
      button.hidden = false;
    });
    button.addEventListener("click", async () => {
      if (!prompt) return;
      prompt.prompt();
      await prompt.userChoice.catch(() => {});
      prompt = null;
      button.hidden = true;
    });
    window.addEventListener("appinstalled", () => { button.hidden = true; });
    if ("serviceWorker" in navigator && (location.protocol === "https:" || location.hostname === "localhost" || location.hostname === "127.0.0.1")) {
      navigator.serviceWorker.register("sw.js").catch(() => { /* offline support is optional */ });
    }
  }

  /** Files dropped anywhere: into the open tool, or into Convert from the home screen. */
  wireGlobalDrop() {
    let depth = 0;
    const isFiles = (e) => [...(e.dataTransfer?.types ?? [])].includes("Files");
    window.addEventListener("dragenter", (e) => { if (isFiles(e)) { depth++; document.body.classList.add("dragging-file"); } });
    window.addEventListener("dragleave", (e) => { if (isFiles(e) && --depth <= 0) { depth = 0; document.body.classList.remove("dragging-file"); } });
    window.addEventListener("dragover", (e) => { if (isFiles(e)) e.preventDefault(); });
    window.addEventListener("drop", (e) => {
      depth = 0;
      document.body.classList.remove("dragging-file");
      if (!isFiles(e)) return;
      e.preventDefault(); // never let the browser navigate away to the file
      const files = [...e.dataTransfer.files];
      if (!files.length) return;
      if (this.current) this.panels[this.current].accept(files);
      else this.open("convert", files);
    });
  }
}

/* ---------- Boot -------------------------------------------------------------- */

(async () => {
  i18n.apply(document);
  try {
    const info = await new Api().info();
    window.docsmelt = new App(new Api(), info);
  } catch (err) {
    document.getElementById("search-empty").hidden = false;
    document.getElementById("search-empty").textContent = err.message;
  }
})();
