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
  send(endpoint, form, onUploadProgress) {
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

const icon = (name, extra = "") => {
  const i = el("i", { className: `fa-solid fa-${name} ${extra}`.trim() });
  i.setAttribute("aria-hidden", "true");
  return i;
};

const FILE_ICONS = {
  pdf: ["file-pdf", "pdf"], docx: ["file-word", "word"], txt: ["file-lines", "text"],
  png: ["file-image", "image"], jpg: ["file-image", "image"], jpeg: ["file-image", "image"],
  bmp: ["file-image", "image"], gif: ["file-image", "image"],
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

const ext = (name) => (name.includes(".") ? name.split(".").pop().toLowerCase() : "");

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

  clear() {
    this.files = [];
    this.render();
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

  progress(fraction) {
    if (fraction >= 1) {
      this.label.textContent = "Processing…";
      this.track.classList.add("indeterminate");
      this.bar.style.width = "";
    } else {
      this.bar.style.width = `${Math.round(fraction * 100)}%`;
    }
  }

  done({ blob, filename }) {
    if (this.url) URL.revokeObjectURL(this.url);
    this.url = URL.createObjectURL(blob);
    const link = el("a", { href: this.url, download: filename }, icon("download"), ` ${filename}`);
    this.set("done", "circle-check", "", el("span", {}, `Done (${formatSize(blob.size)}). Download: `, link));
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
  constructor(section, api, limits, { single = false, sortable = false, minFiles = 1 } = {}) {
    this.section = section;
    this.api = api;
    this.limits = limits;
    this.minFiles = minFiles;
    this.button = section.querySelector("[data-action]");
    this.status = new Status(section.querySelector(".status"));
    this.files = new FileList(section.querySelector(".files"), { single, sortable, onChange: () => this.refresh() });
    this.wireDropzone(section.querySelector(".dropzone"));
    this.button.addEventListener("click", () => this.submit());
    this.refresh();
  }

  /* Subclasses override these. */
  endpoint() { throw new Error("abstract"); }
  busyText() { return "Uploading…"; }
  fillForm(_form) {}
  validate() { return null; }

  wireDropzone(zone) {
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
    const max = this.limits.maxUploadMb * 1024 * 1024;
    const tooBig = files.filter((f) => f.size > max);
    const ok = files.filter((f) => f.size <= max);
    if (tooBig.length) this.status.error(`${tooBig.map((f) => f.name).join(", ")}: larger than ${this.limits.maxUploadMb} MB`);
    else this.status.clear();
    if (ok.length) this.files.add(ok);
  }

  refresh() {
    this.button.disabled = this.files.files.length < this.minFiles || this.busy;
  }

  async submit() {
    const problem = this.validate()
      ?? (this.files.files.length > this.limits.maxFiles ? `At most ${this.limits.maxFiles} files at once` : null);
    if (problem) { this.status.error(problem); return; }

    const form = new FormData();
    this.files.files.forEach((f) => form.append("files", f, f.name));
    this.fillForm(form);

    this.busy = true;
    this.refresh();
    this.status.busy(this.busyText());
    try {
      const result = await this.api.send(this.endpoint(), form, (p) => this.status.progress(p));
      this.status.done(result);
    } catch (err) {
      this.status.error(err.message);
    } finally {
      this.busy = false;
      this.refresh();
    }
  }
}

class ConvertPanel extends ToolPanel {
  constructor(section, api, limits) {
    super(section, api, limits);
    this.select = section.querySelector("select[name=to]");
    const names = { pdf: "PDF", docx: "Word (.docx)", txt: "Text (.txt)", png: "PNG image", jpg: "JPEG image", bmp: "BMP image", gif: "GIF image" };
    limits.write.filter((f) => f !== "jpeg").forEach((f) =>
      this.select.append(el("option", { value: f, textContent: names[f] ?? f.toUpperCase() })));
    this.userPicked = false;
    this.select.addEventListener("change", () => { this.userPicked = true; });
    section.querySelector("input[type=file]").accept = limits.read.map((e) => `.${e}`).join(",");
  }

  /** Pick a sensible target until the user chooses one themselves. */
  refresh() {
    super.refresh();
    if (!this.select || this.userPicked || !this.files.files.length) return;
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
      const value = this.section.querySelector(`[name=${name}]`).value.trim();
      if (value) form.append(name, value);
    });
  }
}

class MergePanel extends ToolPanel {
  constructor(section, api, limits) {
    super(section, api, limits, { sortable: true, minFiles: 2 });
    section.querySelector("input[type=file]").accept = limits.read.map((e) => `.${e}`).join(",");
  }
  endpoint() { return "api/merge"; }
  busyText() { return `Uploading ${this.files.files.length} files…`; }
}

class SplitPanel extends ToolPanel {
  constructor(section, api, limits) {
    super(section, api, limits, { single: true });
    // Typing in a field selects its radio button.
    section.querySelector("[name=every]").addEventListener("focus", () => { section.querySelector("[value=every]").checked = true; });
    section.querySelector("[name=ranges]").addEventListener("focus", () => { section.querySelector("[value=ranges]").checked = true; });
  }
  endpoint() { return "api/split"; }

  mode() { return this.section.querySelector("[name=mode]:checked").value; }

  validate() {
    if (ext(this.files.files[0].name) !== "pdf") return "Please choose a PDF file";
    if (this.mode() === "ranges" && !this.section.querySelector("[name=ranges]").value.trim()) {
      return "Enter page ranges, e.g. 1-3,4-9,10-";
    }
    return null;
  }

  fillForm(form) {
    const field = this.mode();
    form.append(field, this.section.querySelector(`[name=${field}]`).value.trim());
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
    const limits = await api.info();
    new ConvertPanel(document.getElementById("panel-convert"), api, limits);
    new MergePanel(document.getElementById("panel-merge"), api, limits);
    new SplitPanel(document.getElementById("panel-split"), api, limits);
    document.getElementById("limits").textContent =
      `Up to ${limits.maxFiles} files, ${limits.maxUploadMb} MB each.`;
  } catch (err) {
    document.querySelectorAll(".status").forEach((s) => new Status(s).error(err.message));
  }
})();
