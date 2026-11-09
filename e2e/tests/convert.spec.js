const { test, expect, openTool, upload, run, textOf, pageCount } = require("./helpers");

test.describe("convert", () => {
  test("Excel → PDF with live preview of the original and the result", async ({ page, request }, testInfo) => {
    const view = await openTool(page, "convert");
    await upload(page, "convert", "sales.xlsx");
    await expect(view.locator(".pv-img")).toBeVisible();
    await expect(view.locator(".pv-name")).toHaveText("sales.xlsx");
    await expect(view.locator("#convert-to")).toHaveValue("pdf");

    const result = await run(page, "convert", testInfo);
    expect(result.name).toBe("sales.pdf");
    await expect(view.locator(".pv-name")).toHaveText("sales.pdf");
    await view.locator(".pv-switch [data-which=before]").click();
    await expect(view.locator(".pv-name")).toHaveText("sales.xlsx");
    const text = await textOf(request, result.file);
    expect(text).toContain("Arusha");
    expect(text).toContain("Total");
  });

  test("PowerPoint → PNG gives one image per slide in a zip", async ({ page }, testInfo) => {
    await openTool(page, "convert");
    await upload(page, "convert", "review.pptx");
    await page.selectOption("#convert-to", "png");
    const result = await run(page, "convert", testInfo);
    expect(result.name).toBe("review.zip");
    await expect(page.locator("#view-convert .pv-msg")).toContainText("zip");
  });

  test("Word → Excel and PDF → Word", async ({ page, request }, testInfo) => {
    await openTool(page, "convert");
    await upload(page, "convert", "letter.docx");
    await page.selectOption("#convert-to", "xlsx");
    expect((await run(page, "convert", testInfo)).name).toBe("letter.xlsx");

    await page.click("#view-convert .files .remove");
    await upload(page, "convert", "report.pdf");
    await expect(page.locator("#convert-to")).toHaveValue("docx"); // sensible default for PDFs
    const docx = await run(page, "convert", testInfo);
    expect(await textOf(request, docx.file)).toContain("Hello");
  });

  test("converting to the same format is refused with a clear message", async ({ page }) => {
    await openTool(page, "convert");
    await upload(page, "convert", "report.pdf");
    await page.selectOption("#convert-to", "pdf");
    await page.click("#view-convert [data-action]");
    await expect(page.locator("#view-convert .status.error")).toHaveText("Your file(s) are already PDF");
  });

  test("continue with the result in another tool without re-uploading", async ({ page, request }, testInfo) => {
    await openTool(page, "convert");
    await upload(page, "convert", "letter.docx");
    await run(page, "convert", testInfo);
    await page.locator("#view-convert .next-steps .chip", { hasText: "Page numbers" }).click();
    await expect(page).toHaveURL(/#number$/);
    await expect(page.locator("#view-number .files .file-name")).toHaveText("letter.pdf");
    const numbered = await run(page, "number", testInfo);
    expect(await pageCount(request, numbered.file)).toBeGreaterThan(0);
  });

  test("images → one PDF, one page per image, reorderable by drag", async ({ page, request }, testInfo) => {
    const view = await openTool(page, "images");
    await upload(page, "images", "photo.png", "photo.jpg");
    await expect(view.locator(".files .thumb img")).toHaveCount(2);
    await view.locator(".files li").nth(1).dragTo(view.locator(".files li").nth(0), { targetPosition: { x: 20, y: 2 } });
    await expect(view.locator(".files .file-name")).toHaveText(["photo.jpg", "photo.png"]);
    const pdf = await run(page, "images", testInfo);
    expect(await pageCount(request, pdf.file)).toBe(2);
  });
});
