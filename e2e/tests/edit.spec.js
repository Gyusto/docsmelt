const fs = require("fs");
const { test, expect, openTool, upload, run, textOf, serverInfo, fixture } = require("./helpers");

/** Drags across the editor page between two points given as fractions of the page. */
async function dragOnPage(page, view, from, to) {
  const box = await view.locator(".editor-stage").boundingBox();
  await page.mouse.move(box.x + box.width * from[0], box.y + box.height * from[1]);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width * to[0], box.y + box.height * to[1], { steps: 6 });
  await page.mouse.up();
}

test.describe("edit tools", () => {
  test("compress makes a scan much smaller", async ({ page }, testInfo) => {
    const view = await openTool(page, "compress");
    await upload(page, "compress", "scanned.pdf");
    await view.locator("label.level", { hasText: "Strong" }).click();
    const result = await run(page, "compress", testInfo);
    expect(result.size).toBeLessThan(fs.statSync(fixture("scanned.pdf")).size / 2);
    await expect(view.locator(".status.done")).toContainText("smaller");
    await expect(view.locator(".pv-switch")).toBeVisible();
  });

  test("watermark with text, then with an image", async ({ page, request }, testInfo) => {
    const view = await openTool(page, "watermark");
    await upload(page, "watermark", "report.pdf");
    await view.locator("[name=wmText]").fill("DRAFT");
    await page.selectOption("#view-watermark [name=angle]", "0"); // horizontal text can be read back
    const textResult = await run(page, "watermark", testInfo);
    expect(await textOf(request, textResult.file)).toContain("DRAFT");

    await view.locator(".segmented label:has(input[value=image])").click();
    await expect(view.locator("[name=wmText]")).toBeHidden(); // really in image mode
    await view.locator("[name=wmFile]").setInputFiles(fixture("signature.png"));
    expect((await run(page, "watermark", testInfo)).name).toBe("report-watermarked.pdf");
  });

  test("page numbers: Page N of M, skipping the cover", async ({ page, request }, testInfo) => {
    const view = await openTool(page, "number");
    await upload(page, "number", "handbook.pdf");
    await view.locator("input[value=bottom-right]").check();
    await page.selectOption("#view-number [name=format]", { label: "Page 1 of 10" });
    await view.locator("[name=skipFirst]").check();
    const text = await textOf(request, (await run(page, "number", testInfo)).file);
    expect(text).toContain("Page 2 of 4");
    expect(text).toContain("Page 4 of 4");
    expect(text).not.toContain("Page 1 of 4");
  });

  test("add text, a highlight and an arrow; undo and redo", async ({ page, request }, testInfo) => {
    const view = await openTool(page, "annotate");
    await upload(page, "annotate", "report.pdf");
    await expect(view.locator(".editor-page")).toBeVisible();

    // Text tool is the default: click to place, type, Enter to finish.
    const stage = await view.locator(".editor-stage").boundingBox();
    await page.mouse.click(stage.x + stage.width * 0.1, stage.y + stage.height * 0.3);
    await page.keyboard.type("Approved by Juma");
    await page.keyboard.press("Enter");
    await expect(view.locator(".ed-text")).toHaveText("Approved by Juma");

    await view.getByRole("button", { name: "Highlight" }).click();
    await dragOnPage(page, view, [0.1, 0.05], [0.5, 0.1]);
    await view.getByRole("button", { name: "Arrow" }).click();
    await dragOnPage(page, view, [0.6, 0.6], [0.3, 0.35]);
    await expect(view.locator(".ed-item, .editor-svg g")).toHaveCount(3);
    await expect(view.locator(".item-count")).toHaveText("3 items");

    await view.locator("[data-undo]").click();
    await expect(view.locator(".item-count")).toHaveText("2 items");
    await view.locator("[data-redo]").click();
    await expect(view.locator(".item-count")).toHaveText("3 items");

    // Select the highlight and delete it with the keyboard.
    await view.getByRole("button", { name: "Select and move" }).click();
    await view.locator(".ed-highlight").click();
    await page.keyboard.press("Delete");
    await expect(view.locator(".item-count")).toHaveText("2 items");

    const result = await run(page, "annotate", testInfo);
    expect(result.name).toBe("report-edited.pdf");
    expect(await textOf(request, result.file)).toContain("Approved by Juma");
  });

  test("fill a PDF form and lock the answers", async ({ page, request }, testInfo) => {
    const view = await openTool(page, "forms");
    await upload(page, "forms", "form.pdf");
    await expect(view.locator(".form-fields .form-row")).toHaveCount(2);
    await view.getByLabel("Full name").fill("Asha Mwakyusa");
    await view.locator(".form-fields select").selectOption("Dodoma");
    await view.locator("[name=flatten]").check();
    const result = await run(page, "forms", testInfo);
    expect(result.name).toBe("form-filled.pdf");
    const text = await textOf(request, result.file);
    expect(text).toContain("Asha Mwakyusa");
    expect(text).toContain("Dodoma");
  });

  test("a PDF without fields says so", async ({ page }) => {
    page.allowConsoleErrors(/status of 400/);
    await openTool(page, "forms");
    await upload(page, "forms", "report.pdf");
    await expect(page.locator("#view-forms .status.error")).toHaveText("This PDF has no fillable fields");
  });

  test("PDF/A conversion", async ({ page, request }, testInfo) => {
    const info = await serverInfo(request);
    test.skip(!info.pdfa, "Ghostscript is not installed on this server");
    await openTool(page, "pdfa");
    await upload(page, "pdfa", "report.pdf");
    const result = await run(page, "pdfa", testInfo);
    expect(result.name).toBe("report-pdfa.pdf");
    expect(fs.readFileSync(result.file, "latin1")).toMatch(/pdfaid:part/);
  });
});
