import { describe, expect, it, vi } from "vitest"

import { duckd } from "@/core/duckd"

import { remainingHours, renewalApi, renewalSchema } from "./renewal"

vi.mock("@/core/duckd", () => ({ duckd: vi.fn() }))

const status = {
  enabled: false,
  protocol: "v2",
  protocol_configured: false,
  phase: "idle",
  result: "disabled",
  blocked: false,
  pending: false,
  recovery_required: false,
  target_supported: true,
  certificate_valid: true,
  expires_at: 1800000000000,
  remaining_seconds: 12 * 3600,
  last_check: 0,
  last_success: 0,
  retry_after: 0,
  check_hours: 6,
  lead_hours: 24,
}

describe("renewal UI contract", () => {
  it("accepts a read-only disabled status and removes unadvertised private fields", () => {
    const parsed = renewalSchema.parse({ ...status, keybox_xml: "PRIVATE" })
    expect(parsed.enabled).toBe(false)
    expect(parsed).not.toHaveProperty("keybox_xml")
  })

  it("shows unknown or expired time without a misleading negative hour count", () => {
    expect(remainingHours(null)).toBe("—")
    expect(remainingHours(-60)).toBe("0")
    expect(remainingHours(24 * 3600)).toBe("24")
  })

  it("never enables unsupported auto fallback or accepts different renewal intervals", () => {
    expect(renewalSchema.safeParse({ ...status, protocol: "auto" }).success).toBe(false)
    expect(renewalSchema.safeParse({ ...status, check_hours: 1 }).success).toBe(false)
    expect(renewalSchema.safeParse({ ...status, lead_hours: 48 }).success).toBe(false)
  })

  it("separates local status from explicit enable and bounded check commands", async () => {
    await renewalApi.status()
    await renewalApi.enable("v1-native")
    await renewalApi.saveProtocol("v1")
    await renewalApi.check()
    await renewalApi.disable()
    await renewalApi.resume()
    expect(vi.mocked(duckd).mock.calls.map(([args]) => args)).toEqual([
      ["renewal", "status"],
      ["renewal", "enable", "v1-native"],
      ["renewal", "protocol", "v1"],
      ["renewal", "check"],
      ["renewal", "disable"],
      ["renewal", "resume"],
    ])
  })
})
