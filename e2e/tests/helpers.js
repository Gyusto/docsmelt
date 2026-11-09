// Shared helpers for the DocSmelt end-to-end tests.
const base = require("@playwright/test");
const path = require("path");
const fs = require("fs");

const FIXTURES = path.join(__dirname, "..", "fixtures");
const fixture = (name) => path.join(FIXTURES, name);

/**
 * `test` with two extras:
 *  - every page starts in English and light mode, with storage cleared;
 *  - the test fails if the page logged errors, unless the test expected them
 *    (call `allowConsoleErrors(/pattern/)`).
 */
const test = base.test.extend({
  page: async ({ page }, use) => {
    const errors = [];
    const allowed = [];
    page.allowConsoleErrors = (re) => allowed.push(re);
    page.on("console", (m) => { if (m.type() === "error") errors.push(m.text()); });
    page.on("pageerror", (e) => errors.push(`pageerror: ${e.message}`));
    await page.addInitScript(() => {
      if (!sessionStorage.getItem("e2e-init")) {
        localStorage.clear();
        localStorage.setItem("docsmelt.lang", "en");
        sessionStorage.setItem("e2e-init", "1");
      }
    });
    await use(page);
    const unexpected = errors.filter((e) => !allowed.some((re) => re.test(e)));
    base.expect(unexpected, "console errors").toEqual([]);
  },
});

/** Opens a tool page and waits until it is shown. */
async function openTool(page, id) {
  await page.goto(`/#${id}`);
  await base.expect(page.locator(`#view-${id}`)).toBeVisible();
  return page.locator(`#view-${id}`);
}

async function upload(page, id, ...names) {
  await page.setInputFiles(`#view-${id} .dropzone input[type=file]`, names.map(fixture));
}

/** Clicks the tool's main button and returns the downloaded result. */
async function run(page, id, testInfo) {
  const [download] = await Promise.all([
    page.waitForEvent("download", { timeout: 110_000 }),
    page.click(`#view-${id} [data-action]`),
  ]);
  const name = download.suggestedFilename();
  const file = testInfo.outputPath(name);
  await download.saveAs(file);
  await base.expect(page.locator(`#view-${id} .status.done`)).toBeVisible();
  return { name, file, size: fs.statSync(file).size };
}

/** The text inside a PDF (or other document), read back through DocSmelt's own converter. */
async function textOf(request, file) {
  const res = await request.post("/api/convert", {
    multipart: {
      files: { name: path.basename(file), mimeType: "application/octet-stream", buffer: fs.readFileSync(file) },
      to: "txt",
    },
  });
  base.expect(res.status(), await res.text()).toBe(200);
  return (await res.text()).replace(/\s+/g, " ");
}

/** Number of pages in a PDF, via the preview endpoint. */
async function pageCount(request, file) {
  const res = await request.post("/api/preview", {
    multipart: {
      files: { name: path.basename(file), mimeType: "application/pdf", buffer: fs.readFileSync(file) },
      page: "1",
      width: "100",
    },
  });
  return (await res.json()).count;
}

async function serverInfo(request) {
  return (await request.get("/api/formats")).json();
}

module.exports = { test, expect: base.expect, fixture, openTool, upload, run, textOf, pageCount, serverInfo };
