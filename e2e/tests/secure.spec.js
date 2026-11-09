const { test, expect, openTool, upload, run, textOf, fixture } = require("./helpers");

test.describe("protect, sign, redact", () => {
  test("protect with a password, refuse a wrong one, then unlock", async ({ page, request }, testInfo) => {
    page.allowConsoleErrors(/status of 400/);
    const view = await openTool(page, "protect");
    await upload(page, "protect", "report.pdf");
    await view.locator("[name=password]").fill("siri1234");
    await view.locator("[name=confirm]").fill("different");
    await page.click("#view-protect [data-action]");
    await expect(view.locator(".status.error")).toHaveText("The passwords don't match");

    await view.locator("[name=confirm]").fill("siri1234");
    await view.locator("[name=allowCopy]").uncheck();
    const locked = await run(page, "protect", testInfo);
    await expect(view.locator("[name=password]")).toHaveValue(""); // not left on screen
    const blocked = await request.post("/api/convert", {
      multipart: { files: { name: "x.pdf", mimeType: "application/pdf", buffer: require("fs").readFileSync(locked.file) }, to: "txt" },
    });
    expect(await blocked.text()).toContain("password-protected");

    await view.locator(".segmented label:has(input[value=unlock])").click();
    await expect(view.locator("[name=confirm]")).toBeHidden();
    await view.locator(".files .remove").click();
    await page.setInputFiles("#view-protect .dropzone input", locked.file);
    await view.locator("[name=password]").fill("wrong-one");
    await page.click("#view-protect [data-action]");
    await expect(view.locator(".status.error")).toHaveText("Wrong password");
    await view.locator("[name=password]").fill("siri1234");
    const open = await run(page, "protect", testInfo);
    expect(await textOf(request, open.file)).toContain("Hello");
  });

  test("sign: draw, move with undo, type, and upload a signature", async ({ page }, testInfo) => {
    const view = await openTool(page, "sign");
    await upload(page, "sign", "report.pdf");
    await expect(view.locator(".sign-place")).toBeVisible();

    const pad = await view.locator(".sig-canvas").boundingBox();
    await page.mouse.move(pad.x + 30, pad.y + 100);
    await page.mouse.down();
    for (let i = 0; i < 30; i++) await page.mouse.move(pad.x + 30 + i * 10, pad.y + 90 + Math.sin(i / 3) * 30);
    await page.mouse.up();
    await expect(view.locator(".sig-box")).toHaveClass(/has-sig/);

    // Positions relative to the page preview: clicking buttons may scroll the window.
    const relY = async () => (await view.locator(".sig-box").boundingBox()).y - (await view.locator(".sign-stage").boundingBox()).y;
    const before = await view.locator(".sig-box").boundingBox();
    const stage = await view.locator(".sign-stage").boundingBox();
    const startY = await relY();
    await page.mouse.move(before.x + 8, before.y + 8);
    await page.mouse.down();
    await page.mouse.move(stage.x + 30, stage.y + 30, { steps: 5 });
    await page.mouse.up();
    expect(await relY()).toBeLessThan(startY - 50);
    await view.locator(".sign-tools [data-undo]").click();
    await expect.poll(async () => Math.round(await relY())).toBe(Math.round(startY));

    expect((await run(page, "sign", testInfo)).name).toBe("report-signed.pdf");

    await view.locator(".segmented label:has(input[value=type])").click();
    await view.locator("[name=sigText]").fill("George Yusto");
    await expect(view.locator(".sig-box")).toHaveClass(/has-sig/);
    await view.locator(".segmented label:has(input[value=upload])").click();
    await view.locator("[name=sigFile]").setInputFiles(fixture("signature.png"));
    await expect(view.locator(".sig-preview")).toHaveAttribute("src", /^data:image\/png/);
    expect((await run(page, "sign", testInfo)).name).toBe("report-signed.pdf");
  });

  test("redact: find text and draw a box; the text is really gone", async ({ page, request }, testInfo) => {
    const view = await openTool(page, "redact");
    await upload(page, "redact", "report.pdf");
    await expect(view.locator(".editor-page")).toBeVisible();

    await view.locator("[name=find]").fill("Sample text");
    await view.getByRole("button", { name: "Find" }).click();
    await expect(view.locator(".find-result")).toHaveText("1 found and marked.");
    await expect(view.locator(".ed-box")).toHaveCount(1);

    // Draw another box, undo it, redo it.
    const stage = await view.locator(".editor-stage").boundingBox();
    await page.mouse.move(stage.x + stage.width * 0.5, stage.y + stage.height * 0.5);
    await page.mouse.down();
    await page.mouse.move(stage.x + stage.width * 0.8, stage.y + stage.height * 0.6, { steps: 5 });
    await page.mouse.up();
    await expect(view.locator(".ed-box")).toHaveCount(2);
    await page.keyboard.press("Control+z");
    await expect(view.locator(".ed-box")).toHaveCount(1);
    await page.keyboard.press("Control+Shift+z");
    await expect(view.locator(".ed-box")).toHaveCount(2);

    const result = await run(page, "redact", testInfo);
    expect(result.name).toBe("report-redacted.pdf");
    const text = await textOf(request, result.file);
    expect(text).not.toContain("Sample text");
  });

  test("redact needs at least one box", async ({ page }) => {
    await openTool(page, "redact");
    await upload(page, "redact", "report.pdf");
    await expect(page.locator("#view-redact .editor-page")).toBeVisible();
    await expect(page.locator("#view-redact [data-action]")).toBeDisabled();
  });
});
