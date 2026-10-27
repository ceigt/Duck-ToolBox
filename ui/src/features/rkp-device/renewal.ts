import { z } from "zod"

import { duckd } from "@/core/duckd"

export const renewalSchema = z.object({
  enabled: z.boolean(),
  backend: z.enum(["teesimulator", "ohmykeymint"]).default("teesimulator"),
  target_path: z.string().default("/data/adb/teesim/keybox.xml"),
  protocol: z.enum(["v2", "v1", "v1-native"]),
  protocol_configured: z.boolean(),
  diagnostic: z.string().optional(),
  diagnostic_phase: z.string().optional(),
  phase: z.string(),
  result: z.string(),
  blocked: z.boolean(),
  pending: z.boolean(),
  recovery_required: z.boolean().default(false),
  target_supported: z.boolean(),
  certificate_valid: z.boolean(),
  expires_at: z.number().nullable(),
  remaining_seconds: z.number().nullable(),
  last_check: z.number(),
  last_success: z.number(),
  retry_after: z.number(),
  check_hours: z.literal(6),
  lead_hours: z.literal(24),
})

export type RenewalProtocol = "v2" | "v1" | "v1-native"
export type RenewalStatus = z.infer<typeof renewalSchema>

export const renewalApi = {
  status: () => duckd(["renewal", "status"], { schema: renewalSchema }),
  enable: (protocol: RenewalProtocol) =>
    duckd(["renewal", "enable", protocol], { schema: renewalSchema }),
  saveProtocol: (protocol: RenewalProtocol) =>
    duckd(["renewal", "protocol", protocol], { schema: renewalSchema }),
  disable: () => duckd(["renewal", "disable"], { schema: renewalSchema }),
  check: () => duckd(["renewal", "check"], { schema: renewalSchema }),
  discard: () => duckd(["renewal", "discard"], { schema: renewalSchema }),
  recover: () => duckd(["renewal", "recover"], { schema: renewalSchema }),
  resume: () => duckd(["renewal", "resume"], { schema: renewalSchema }),
}

export function remainingHours(seconds: number | null): string {
  return seconds === null ? "—" : Math.max(0, Math.floor(seconds / 3600)).toString()
}
