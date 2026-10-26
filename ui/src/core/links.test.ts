import { afterEach, describe, expect, it, vi } from "vitest"
import { openExternal } from "./links"
import { duckd } from "@/core/duckd"
import { toast } from "vue-sonner"

const host = vi.hoisted(() => ({ available: true }))
vi.mock("@/core/bridge", () => ({ bridge: () => host }))
vi.mock("@/core/duckd", () => ({ duckd: vi.fn() }))
vi.mock("@/core/i18n", () => ({ i18n: { global: { t: (key: string) => key } } }))
vi.mock("vue-sonner", () => ({ toast: { error: vi.fn() } }))
afterEach(() => {
  vi.restoreAllMocks()
  vi.clearAllMocks()
  host.available = true
})
describe("external browser boundary", () => {
  it("never navigates the root WebView if launching the external browser fails", async () => {
    const open = vi.spyOn(window, "open").mockReturnValue(null)
    vi.mocked(duckd).mockRejectedValue(new Error("no browser"))
    await openExternal("https://example.org")
    expect(open).not.toHaveBeenCalled()
    expect(toast.error).toHaveBeenCalledWith("core.errors.external_browser_unavailable")
  })
  it("uses only the native external-browser command in a root host", async () => {
    const open = vi.spyOn(window, "open").mockReturnValue(null)
    vi.mocked(duckd).mockResolvedValue({})
    await openExternal("https://example.org")
    expect(duckd).toHaveBeenCalledWith(["system", "open-url", "https://example.org"])
    expect(open).not.toHaveBeenCalled()
  })
  it("allows a new window in an ordinary browser without a root bridge", async () => {
    host.available = false
    const open = vi.spyOn(window, "open").mockReturnValue(null)
    await openExternal("https://example.org")
    expect(open).toHaveBeenCalledWith("https://example.org", "_blank", "noopener")
    expect(duckd).not.toHaveBeenCalled()
  })
})
