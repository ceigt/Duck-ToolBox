import { expect, test } from "./fixtures"

test("fetches Pixel 6a by default and saves a candidate without applying", async ({
  page,
  open,
}) => {
  await open("/play-integrity/generate")
  await expect(page.getByRole("combobox", { name: "Pixel device", exact: true })).toHaveText(
    "Pixel 6a",
  )
  await page.getByRole("button", { name: "Fetch Pixel builds", exact: true }).click()
  await expect(
    page.getByText("google/bluejay/bluejay:16/RELEASE.1/1234:user/release-keys", { exact: true }),
  ).toBeVisible()
  await page.getByRole("button", { name: "Preview selected fingerprint", exact: true }).click()
  await expect(page.getByText(/candidate leaves it unset/)).toBeVisible()
  await page.getByLabel("Configuration name", { exact: true }).fill("Pixel 6a release")
  await page.getByRole("button", { name: "Save draft as snapshot", exact: true }).click()
  await page.getByRole("button", { name: "Dismiss preview", exact: true }).click()
  await page.getByRole("link", { name: "Configuration", exact: true }).click()
  await expect(page.getByLabel("Device model · MODEL", { exact: true })).toHaveValue("Pixel 9 Pro")
  await page.getByRole("button", { name: "Saved configurations", exact: true }).click()
  await expect(page.getByText("Pixel 6a release", { exact: true })).toBeVisible()
})

test("prepares three overrides in a draft and shows pending process activation after apply", async ({
  page,
  open,
}) => {
  await open("/play-integrity/config")
  await page.getByRole("button", { name: "Prepare all three overrides", exact: true }).click()
  await page.getByRole("button", { name: "Preview changes", exact: true }).click()
  await expect(page.getByRole("cell", { name: "spoofVendingFinger", exact: true })).toBeVisible()
  await page.getByRole("button", { name: "Apply configuration", exact: true }).click()
  await expect(
    page
      .getByText("Configuration saved. Restart PI processes to load the new values.", {
        exact: true,
      })
      .first(),
  ).toBeVisible()
  await page.getByRole("link", { name: "Status", exact: true }).click()
  await expect(
    page
      .getByText("Configuration saved. Restart PI processes to load the new values.", {
        exact: true,
      })
      .first(),
  ).toBeVisible()
})

test("partition changes require another preview before synchronization", async ({ page, open }) => {
  await open("/play-integrity/config")
  await page.getByRole("button", { name: "Preview changes", exact: true }).click()
  const sync = page.getByRole("checkbox", { name: /Also apply this keystore patch policy/ })
  await page.getByRole("textbox", { name: "Vendor patch", exact: true }).fill("2026-09-05")
  await expect(sync).toBeDisabled()
  await page.getByRole("button", { name: "Update patch preview", exact: true }).click()
  await expect(sync).toBeEnabled()
  await sync.check()
  await page.getByRole("button", { name: "Apply configuration", exact: true }).click()
  await expect(page.getByText(/Reboot the device to finish activation/).first()).toBeVisible()
  await page.getByRole("link", { name: "Diagnostics", exact: true }).click()
  await page.getByRole("button", { name: "Engine logs and process restart", exact: true }).click()
  await page.getByRole("button", { name: "Restart PI processes", exact: true }).first().click()
  await expect(page.getByText(/Reboot the device to finish activation/).first()).toBeVisible()
})

test("imports decoded report without treating omitted labels as failed", async ({ page, open }) => {
  await open("/play-integrity/diagnostics")
  await page.getByRole("button", { name: "Test history", exact: true }).click()
  await page.getByRole("button", { name: "Import decoded report", exact: true }).click()
  await page.getByLabel("Decoded integrity report JSON", { exact: true }).fill(
    JSON.stringify({
      requestDetails: { requestPackageName: "example.checker" },
      deviceIntegrity: { deviceRecognitionVerdict: ["MEETS_DEVICE_INTEGRITY"] },
    }),
  )
  await page.getByRole("button", { name: "Import decoded report", exact: true }).last().click()
  await expect(page.getByText("example.checker", { exact: true })).toBeVisible()
  await expect(page.getByText(/Strong: Not returned · reason unknown/)).toBeVisible()
})

for (const [locale, fetch, preview] of [
  ["en", "Fetch Pixel builds", "Preview selected fingerprint"],
  ["zh-CN", "获取 Pixel 构建", "预览所选指纹"],
  ["ar", "جلب إصدارات Pixel", "معاينة البصمة المحددة"],
] as const) {
  test(`Pixel candidate fits a narrow viewport in ${locale}`, async ({ page, open }, testInfo) => {
    await page.setViewportSize({ width: 320, height: 800 })
    await open("/play-integrity/generate", { locale })
    await page.getByRole("button", { name: fetch, exact: true }).click()
    await page.getByRole("button", { name: preview, exact: true }).click()
    await expect(page.getByRole("cell", { name: "FINGERPRINT", exact: true })).toBeVisible()
    const size = await page.evaluate(() => ({
      width: innerWidth,
      scroll: document.documentElement.scrollWidth,
    }))
    expect(size.scroll).toBeLessThanOrEqual(size.width)
    await page.screenshot({ path: testInfo.outputPath("pixel-fingerprints.png"), fullPage: true })
  })
}

test("keeps editable SDK and custom fingerprint controls visible while clearing them", async ({
  page,
  open,
}) => {
  await open("/play-integrity/config")
  const mode = page.getByRole("combobox", { name: "Play Store override mode", exact: true })
  await mode.click()
  await page.getByRole("option", { name: "SDK compatibility ceiling", exact: true }).click()
  const sdk = page.getByRole("textbox", { name: "SDK compatibility ceiling", exact: true })
  await sdk.fill("")
  await expect(sdk).toBeVisible()
  await sdk.fill("35")
  await mode.click()
  await page.getByRole("option", { name: "Custom Play Store fingerprint", exact: true }).click()
  const fingerprint = page.getByRole("textbox", {
    name: "Custom Play Store fingerprint",
    exact: true,
  })
  await fingerprint.fill("")
  await expect(fingerprint).toBeVisible()
  await fingerprint.fill("google/custom/custom:16/RELEASE/123:user/release-keys")
  await page.getByRole("button", { name: "Preview changes", exact: true }).click()
  await expect(
    page.getByRole("cell", {
      name: "google/custom/custom:16/RELEASE/123:user/release-keys",
      exact: true,
    }),
  ).toBeVisible()
})
