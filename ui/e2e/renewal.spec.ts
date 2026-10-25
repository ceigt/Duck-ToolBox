import { expect, test } from "./fixtures"

test("renewal saves the selected interface without enabling and checks locally", async ({
  page,
  open,
}) => {
  await page.addInitScript(() =>
    localStorage.setItem("duck-toolbox/acknowledged", JSON.stringify(["rkp-device"])),
  )
  await open("/rkp-device")
  const enable = page.getByRole("button", { name: "Enable automatic renewal", exact: true })
  await expect(enable).toBeDisabled()
  await page
    .getByRole("combobox", {
      name: "Use the protocol that already issued successfully on this phone",
    })
    .click()
  await page.getByRole("option", { name: "V1 AIDL", exact: true }).click()
  const save = page.getByRole("button", { name: "Save interface", exact: true })
  await save.click()
  await expect(save).toBeDisabled()
  await expect(enable).toBeEnabled()
  await expect(page.getByRole("button", { name: "Disable renewal", exact: true })).toBeHidden()
  await enable.click()
  await page
    .getByRole("alertdialog")
    .getByRole("button", { name: "Enable automatic renewal", exact: true })
    .click()
  await page.getByRole("button", { name: "Check now (respect expiry window)", exact: true }).click()
  await expect(page.getByText("Not yet due; local check only", { exact: true })).toBeVisible()
  await page.getByRole("button", { name: "Disable renewal", exact: true }).click()
  await expect(enable).toBeEnabled()
})
