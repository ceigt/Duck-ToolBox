import { QueryClient, VueQueryPlugin } from "@tanstack/vue-query"
import { flushPromises, mount } from "@vue/test-utils"
import { afterEach, describe, expect, it, vi } from "vitest"
import { createI18n } from "vue-i18n"

import { Select } from "@/components/ui/select"

import type { RenewalStatus } from "../renewal"
import { renewalApi } from "../renewal"
import RenewalPanel from "./RenewalPanel.vue"

vi.mock("../renewal", () => ({
  remainingHours: () => "12",
  renewalApi: {
    status: vi.fn(),
    enable: vi.fn(),
    saveProtocol: vi.fn(),
    disable: vi.fn(),
    check: vi.fn(),
    resume: vi.fn(),
    recover: vi.fn(),
    discard: vi.fn(),
  },
}))
vi.mock("../i18n", () => ({
  useRkpDeviceI18n: () => ({ t: (key: string) => key, dynamic: (key: string) => key }),
}))

const initial: RenewalStatus = {
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
const clients: QueryClient[] = []
function panel(
  supported = true,
  override: Partial<RenewalStatus> = {},
  cachedClient?: QueryClient,
) {
  vi.mocked(renewalApi.status).mockResolvedValue({
    ...initial,
    target_supported: supported,
    ...override,
  })
  vi.mocked(renewalApi.enable).mockResolvedValue({ ...initial, enabled: true })
  const client =
    cachedClient ??
    new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 15000 } } })
  clients.push(client)
  return mount(RenewalPanel, {
    global: {
      plugins: [
        [VueQueryPlugin, { queryClient: client }],
        createI18n({ legacy: false, missingWarn: false, fallbackWarn: false }),
      ],
      stubs: {
        QueryState: { template: "<div><slot /></div>" },
        KeyValueList: { name: "KeyValueList", props: ["rows"], template: "<div />" },
        ConfirmDialog: {
          name: "ConfirmDialog",
          props: ["open"],
          emits: ["confirm"],
          template: "<div />",
        },
      },
    },
  })
}
afterEach(() => {
  for (const client of clients.splice(0)) client.clear()
  vi.clearAllMocks()
})

describe("renewal opt-in", () => {
  it("recovers an interrupted import while renewal is disabled", async () => {
    const wrapper = panel(true, { enabled: false, phase: "installing", recovery_required: true })
    vi.mocked(renewalApi.recover).mockResolvedValue({ ...initial, result: "rolled_back" })
    await flushPromises()
    const recover = wrapper.findAll("button").find((button) => button.text() === "renewal.recover")!
    expect(recover.attributes("disabled")).toBeUndefined()
    await recover.trigger("click")
    wrapper.findComponent({ name: "ConfirmDialog" }).vm.$emit("confirm")
    await flushPromises()
    expect(renewalApi.recover).toHaveBeenCalledOnce()
    expect(renewalApi.enable).not.toHaveBeenCalled()
    expect(renewalApi.check).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it("initializes the selector when navigating back to a fresh cached status", async () => {
    const saved = { ...initial, protocol: "v1" as const, protocol_configured: true }
    const first = panel(true, saved)
    await flushPromises()
    const client = clients[0]!
    first.unmount()
    const second = panel(true, saved, client)
    await flushPromises()
    expect(second.findComponent(Select).props("modelValue")).toBe("v1")
    expect(renewalApi.status).toHaveBeenCalledOnce()
    second.unmount()
  })

  it("requires confirmation before discarding a pending certificate", async () => {
    const wrapper = panel(true, { pending: true, phase: "pending", blocked: true })
    await flushPromises()
    vi.mocked(renewalApi.discard).mockResolvedValue(initial)
    await wrapper
      .findAll("button")
      .find((button) => button.text() === "renewal.discard")!
      .trigger("click")
    expect(renewalApi.discard).not.toHaveBeenCalled()
    wrapper.findComponent({ name: "ConfirmDialog" }).vm.$emit("confirm")
    await flushPromises()
    expect(renewalApi.discard).toHaveBeenCalledOnce()
    expect(renewalApi.resume).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it("allows saving an interface while enabled and preserves unsaved choices across polls", async () => {
    const saved = { ...initial, enabled: true, protocol: "v1" as const, protocol_configured: true }
    const wrapper = panel(true, saved)
    await flushPromises()
    expect(wrapper.findComponent(Select).props("disabled")).toBe(false)
    expect(wrapper.findComponent(Select).props("modelValue")).toBe("v1")
    wrapper.findComponent(Select).vm.$emit("update:modelValue", "v1-native")
    await flushPromises()
    await clients[0]!.refetchQueries({ queryKey: ["rkp-device.renewal"] })
    await flushPromises()
    expect(wrapper.findComponent(Select).props("modelValue")).toBe("v1-native")
    vi.mocked(renewalApi.saveProtocol).mockResolvedValue({ ...saved, protocol: "v1-native" })
    await wrapper
      .findAll("button")
      .find((button) => button.text() === "renewal.saveProtocol")!
      .trigger("click")
    await flushPromises()
    expect(renewalApi.saveProtocol).toHaveBeenCalledWith("v1-native")
    expect(renewalApi.enable).not.toHaveBeenCalled()
    expect(renewalApi.check).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain("renewal.disable")
    wrapper.unmount()
  })

  it("locks the interface while a certificate is pending", async () => {
    const wrapper = panel(true, {
      enabled: true,
      pending: true,
      phase: "pending",
      protocol_configured: true,
    })
    await flushPromises()
    expect(wrapper.findComponent(Select).props("disabled")).toBe(true)
    expect(
      wrapper
        .findAll("button")
        .find((button) => button.text() === "renewal.saveProtocol")!
        .attributes("disabled"),
    ).toBeDefined()
    wrapper.unmount()
  })

  it("opening the page only reads status; enabling requires a separate confirmation", async () => {
    const wrapper = panel()
    await flushPromises()
    expect(renewalApi.status).toHaveBeenCalledOnce()
    expect(renewalApi.enable).not.toHaveBeenCalled()
    expect(renewalApi.check).not.toHaveBeenCalled()
    const rows = wrapper.findComponent({ name: "KeyValueList" }).props("rows") as {
      label: string
      value: string
    }[]
    expect(rows.find((row) => row.label === "renewal.lastSuccess")?.value).toBe("\u2014")
    const enable = wrapper.findAll("button").find((button) => button.text() === "renewal.enable")!
    expect(enable.attributes("disabled")).toBeDefined()
    wrapper.findComponent(Select).vm.$emit("update:modelValue", "v1-native")
    await flushPromises()
    await clients[0]!.refetchQueries({ queryKey: ["rkp-device.renewal"] })
    await flushPromises()
    await enable.trigger("click")
    expect(wrapper.findComponent({ name: "ConfirmDialog" }).props("open")).toBe(true)
    expect(renewalApi.enable).not.toHaveBeenCalled()
    wrapper.findComponent({ name: "ConfirmDialog" }).vm.$emit("confirm")
    await flushPromises()
    expect(renewalApi.enable).toHaveBeenCalledWith("v1-native")
    expect(wrapper.text()).toContain("renewal.disable")
    wrapper.unmount()
  })

  it("cannot enable an unsupported profile", async () => {
    const wrapper = panel(false)
    await flushPromises()
    wrapper.findComponent(Select).vm.$emit("update:modelValue", "v1-native")
    await flushPromises()
    const enable = wrapper.findAll("button").find((button) => button.text() === "renewal.enable")!
    expect(enable.attributes("disabled")).toBeDefined()
    expect(renewalApi.enable).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
