const { test, expect, openTool, upload, run, textOf, serverInfo } = require("./helpers");

test.describe("OCR and background jobs", () => {
  test("OCR a scanned invoice into text", async ({ page }, testInfo) => {
    const info = await (await page.request.get("/api/formats")).json();
    test.skip(!info.ocr.available, "Tesseract is not installed on this server");
    const view = await openTool(page, "ocr");
    await upload(page, "ocr", "ocr-sample.pdf");
    await page.selectOption("#view-ocr [name=output]", "txt");
    const result = await run(page, "ocr", testInfo);
    const text = require("fs").readFileSync(result.file, "utf8");
    expect(text).toContain("Invoice");
    expect(text).toContain("Dar es Salaam");
    await expect(view.locator(".pv-switch")).toBeVisible();
  });

  test("a running job shows progress and can be cancelled", async ({ page, request }) => {
    const info = await serverInfo(request);
    test.skip(!info.ocr.available, "needs a slow job (OCR)");
    const view = await openTool(page, "ocr");
    await upload(page, "ocr", "scanned.pdf");
    await page.click("#view-ocr [data-action]");
    await expect(view.locator(".status.busy")).toBeVisible();
    await view.getByRole("button", { name: "Cancel" }).click();
    await expect(view.locator(".status.error")).toHaveText("Cancelled.");
    await expect(view.locator("[data-action]")).toBeEnabled();
  });

  test("without OCR installed the tool explains why it is unavailable", async ({ page, request }) => {
    const info = await serverInfo(request);
    test.skip(info.ocr.available, "OCR is installed here");
    const view = await openTool(page, "ocr");
    await expect(view.locator(".notice")).toBeVisible();
    await expect(view.locator("[data-action]")).toBeDisabled();
  });

  test("the API runs tools as background jobs with progress", async ({ request }) => {
    const fs = require("fs");
    const res = await request.post("/api/compress", {
      multipart: {
        files: { name: "scanned.pdf", mimeType: "application/pdf", buffer: fs.readFileSync(require("./helpers").fixture("scanned.pdf")) },
        async: "true",
      },
    });
    expect(res.status()).toBe(202);
    const { id } = await res.json();
    await expect.poll(async () => (await (await request.get(`/api/jobs/${id}`)).json()).state, { timeout: 60_000 }).toBe("done");
    const result = await request.get(`/api/jobs/${id}/result`);
    expect(result.status()).toBe(200);
    expect(result.headers()["content-disposition"]).toContain("scanned-compressed.pdf");
    expect((await request.get(`/api/jobs/${id}/result`)).status()).toBe(404); // handed over once, then deleted
  });
});
