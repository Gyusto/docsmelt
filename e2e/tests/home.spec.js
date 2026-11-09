const { test, expect, openTool, upload, run } = require("./helpers");

test.describe("home screen", () => {
  test("shows every tool in four groups", async ({ page }) => {
    await page.goto("/");
    await expect(page.locator(".tool-group")).toHaveCount(4);
    await expect(page.locator(".tool-card")).toHaveCount(17);
    await expect(page.locator(".tool-group h2")).toHaveText(["Convert", "Organize", "Edit & optimize", "Protect & sign"]);
  });

  test("search filters tools in any language, Enter opens the first match, / focuses it", async ({ page }) => {
    await page.goto("/");
    await page.keyboard.press("/");
    await expect(page.locator("#tool-search")).toBeFocused();
    await page.keyboard.type("punguza"); // Kiswahili for "reduce"
    await expect(page.locator(".tool-card:visible")).toHaveText([/Compress/]);
    await page.fill("#tool-search", "zzzz");
    await expect(page.locator("#search-empty")).toHaveText("No tool matches “zzzz”.");
    await page.fill("#tool-search", "black out");
    await page.press("#tool-search", "Enter");
    await expect(page).toHaveURL(/#redact$/);
    await expect(page).toHaveTitle("Redact · DocSmelt");
    await page.click("#view-redact .back");
    await expect(page.locator("#view-home")).toBeVisible();
  });

  test("language choice applies everywhere, persists, and Arabic is right-to-left", async ({ page }) => {
    await page.goto("/#sign");
    for (const [lang, title, dir] of [["sw", "Saini", "ltr"], ["fr", "Signer", "ltr"], ["ar", "توقيع", "rtl"]]) {
      await page.selectOption("#lang-select", lang);
      await expect(page.locator("#view-sign h1")).toHaveText(title);
      await expect(page.locator("html")).toHaveAttribute("dir", dir);
      await expect(page.locator("html")).toHaveAttribute("lang", lang);
    }
    await page.reload();
    await expect(page.locator("#view-sign h1")).toHaveText("توقيع");
    await page.selectOption("#lang-select", "en");
  });

  test("dark mode toggles and is remembered", async ({ page }) => {
    await page.goto("/");
    await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
    await page.click("#theme-btn");
    await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
    const bg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
    expect(bg).not.toBe("rgb(0, 0, 0)"); // soft slate, never black
    await page.reload();
    await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  });

  test("is installable: manifest, icons and service worker", async ({ page, request }) => {
    const manifest = await (await request.get("/manifest.json")).json();
    expect(manifest.name).toBe("DocSmelt");
    for (const icon of manifest.icons) {
      expect((await request.get(`/${icon.src}`)).status()).toBe(200);
    }
    await page.goto("/");
    await expect.poll(() => page.evaluate(async () => !!(await navigator.serviceWorker.getRegistration()))).toBe(true);
  });

  test("recent results list keeps results and hands them to other tools", async ({ page }, testInfo) => {
    await openTool(page, "compress");
    await upload(page, "compress", "scanned.pdf");
    const result = await run(page, "compress", testInfo);
    await page.click("#view-compress .back");
    const item = page.locator("#recent .recent-list li").first();
    await expect(item.locator(".recent-name")).toHaveText(result.name);
    await item.locator("summary").click();
    await item.locator(".chip", { hasText: "Protect" }).click();
    await expect(page).toHaveURL(/#protect$/);
    await expect(page.locator("#view-protect .files .file-name")).toHaveText(result.name);
  });

  test("keyboard: tool cards are reachable and open with Enter", async ({ page }) => {
    await page.goto("/");
    await page.locator(".tool-card").first().focus();
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(/#convert$/);
    await expect(page.locator("#view-convert h1")).toBeFocused();
  });
});
