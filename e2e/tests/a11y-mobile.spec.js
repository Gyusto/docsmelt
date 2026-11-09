// Accessibility (axe-core, WCAG 2.1 AA) and phone-size layout checks across every page.
const path = require("path");
const { test, expect } = require("./helpers");

const AXE = path.join(require.resolve("axe-core"), "..", "axe.min.js");

// The site's Content-Security-Policy (rightly) blocks injected scripts; let the checker in for these tests only.
test.use({ bypassCSP: true });
const TOOLS = ["convert", "images", "ocr", "pdfa", "merge", "split", "organize", "crop", "extract",
  "compress", "annotate", "forms", "watermark", "number", "protect", "redact", "sign"];

async function axe(page) {
  await page.addScriptTag({ path: AXE });
  return page.evaluate(async () => {
    const results = await window.axe.run(document, {
      runOnly: { type: "tag", values: ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"] },
    });
    return results.violations
      .filter((v) => v.impact === "serious" || v.impact === "critical")
      .map((v) => `${v.id}: ${v.help} — ${v.nodes.slice(0, 3).map((n) => n.target.join(" ")).join(" | ")}`);
  });
}

for (const setup of [
  { name: "light, English", theme: "light", lang: "en" },
  { name: "dark, English", theme: "dark", lang: "en" },
  { name: "light, Arabic (right-to-left)", theme: "light", lang: "ar" },
]) {
  test(`no serious accessibility issues — ${setup.name}`, async ({ page }) => {
    await page.addInitScript(({ theme, lang }) => {
      localStorage.setItem("docsmelt.theme", theme);
      localStorage.setItem("docsmelt.lang", lang);
    }, setup);
    const problems = [];
    for (const hash of ["", ...TOOLS.map((t) => `#${t}`)]) {
      await page.goto(`/${hash}`);
      await page.waitForSelector(".tool-card", { state: "attached" });
      for (const v of await axe(page)) problems.push(`${hash || "home"} → ${v}`);
    }
    expect(problems).toEqual([]);
  });
}

test("phone width: nothing scrolls sideways, on any page", async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 780 });
  const overflowing = [];
  for (const hash of ["", ...TOOLS.map((t) => `#${t}`)]) {
    await page.goto(`/${hash}`);
    await page.waitForSelector(".tool-card", { state: "attached" });
    if (await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1)) overflowing.push(hash || "home");
  }
  expect(overflowing).toEqual([]);
});
