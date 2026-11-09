const { test, expect, openTool, upload, run, textOf, pageCount } = require("./helpers");

test.describe("organize tools", () => {
  test("merge mixes formats, shows thumbnails and keeps the dragged order", async ({ page, request }, testInfo) => {
    const view = await openTool(page, "merge");
    await upload(page, "merge", "report.pdf", "letter.docx", "photo.png");
    await expect(view.locator(".files .thumb img")).toHaveCount(3);
    // Drag the image to the top, then use the keyboard-friendly arrow to move the Word file up.
    await view.locator(".files li").nth(2).dragTo(view.locator(".files li").nth(0), { targetPosition: { x: 20, y: 2 } });
    await expect(view.locator(".files .file-name")).toHaveText(["photo.png", "report.pdf", "letter.docx"]);
    await view.getByRole("button", { name: "Move letter.docx up" }).click();
    await expect(view.locator(".files .file-name")).toHaveText(["photo.png", "letter.docx", "report.pdf"]);
    const merged = await run(page, "merge", testInfo);
    expect(await pageCount(request, merged.file)).toBe(3);
  });

  test("split by ranges gives a zip", async ({ page }, testInfo) => {
    await openTool(page, "split");
    await upload(page, "split", "handbook.pdf");
    await page.fill("#view-split [name=ranges]", "1-2,3-");
    const result = await run(page, "split", testInfo);
    expect(result.name).toBe("handbook-split.zip");
  });

  test("organize: rotate, delete, reorder, undo and redo", async ({ page, request }, testInfo) => {
    const view = await openTool(page, "organize");
    await upload(page, "organize", "handbook.pdf");
    const cards = view.locator(".page-card:not(.loading)");
    await expect(cards).toHaveCount(4);
    await view.getByRole("button", { name: "Delete page 2" }).click();
    await expect(cards).toHaveCount(3);
    await expect(view.locator(".page-count")).toHaveText("3 pages (1 removed)");
    await view.locator("[data-undo]").click();
    await expect(cards).toHaveCount(4);
    await view.locator("[data-redo]").click();
    await expect(cards).toHaveCount(3);
    await page.keyboard.press("Control+z");
    await expect(cards).toHaveCount(4);
    await view.getByRole("button", { name: "Rotate page 3 right" }).click();
    await view.getByRole("button", { name: "Move page 4 earlier" }).click();
    await expect(view.locator(".page-num")).toHaveText(["1", "2", "4", "3"]);
    await view.getByRole("button", { name: "Delete page 1" }).click();
    const result = await run(page, "organize", testInfo);
    expect(result.name).toBe("handbook-organized.pdf");
    expect(await pageCount(request, result.file)).toBe(3);
  });

  test("crop: trim margins automatically and resize to Letter", async ({ page }, testInfo) => {
    const view = await openTool(page, "crop");
    await upload(page, "crop", "report.pdf");
    expect((await run(page, "crop", testInfo)).name).toBe("report-cropped.pdf");
    await view.locator(".segmented label:has(input[value=resize])").click();
    await page.selectOption("#view-crop [name=paper]", "letter");
    expect((await run(page, "crop", testInfo)).name).toBe("report-resized.pdf");
  });

  test("extract images from a PDF", async ({ page }, testInfo) => {
    await openTool(page, "extract");
    await upload(page, "extract", "scanned.pdf");
    const result = await run(page, "extract", testInfo);
    expect(result.name).toMatch(/scanned.*\.(zip|png)$/);
  });

  test("extracting from a PDF without images explains why", async ({ page }) => {
    page.allowConsoleErrors(/status of 400/);
    await openTool(page, "extract");
    await upload(page, "extract", "report.pdf");
    await page.click("#view-extract [data-action]");
    await expect(page.locator("#view-extract .status.error")).toContainText("No images found");
  });
});
