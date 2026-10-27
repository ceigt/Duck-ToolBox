<script setup lang="ts">
import { useMutation, useQuery, useQueryClient } from "@tanstack/vue-query"
import { computed, ref, watch } from "vue"

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Label } from "@/components/ui/label"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import ConfirmDialog from "@/core/components/ConfirmDialog.vue"
import KeyValueList from "@/core/components/KeyValueList.vue"
import QueryState from "@/core/components/QueryState.vue"
import SectionCard from "@/core/components/SectionCard.vue"

import { useRkpDeviceI18n } from "../i18n"
import type { RenewalProtocol, RenewalStatus } from "../renewal"
import { remainingHours, renewalApi } from "../renewal"

const { t, dynamic } = useRkpDeviceI18n()
const client = useQueryClient()
const queryKey = ["rkp-device.renewal"]
const status = useQuery({
  queryKey,
  queryFn: renewalApi.status,
  refetchInterval: (query) =>
    ["issuing", "installing", "pending"].includes(query.state.data?.phase ?? "") ? 10000 : 60000,
})
const protocol = ref<RenewalProtocol>()
const confirmation = ref<"enable" | "resume" | "discard" | "recover">("enable")
const confirmationLabel = computed(() => t(`renewal.${confirmation.value}`))
const confirmOpen = ref(false)
watch(
  status.data,
  (data, previous) => {
    const dirty = previous && protocol.value !== undefined && protocol.value !== previous.protocol
    if (data?.protocol_configured && !dirty) protocol.value = data.protocol
  },
  { immediate: true },
)
const protocolLocked = computed(
  () => status.data.value?.phase !== "idle" || status.data.value?.pending,
)
const protocolDirty = computed(
  () =>
    protocol.value !== undefined &&
    (!status.data.value?.protocol_configured || protocol.value !== status.data.value.protocol),
)
const change = useMutation({
  mutationFn: (
    action: "enable" | "protocol" | "disable" | "check" | "resume" | "discard" | "recover",
  ) => {
    if (action !== "enable" && action !== "protocol") return renewalApi[action]()
    if (!protocol.value) throw new Error(t("renewal.chooseProtocol"))
    return action === "enable"
      ? renewalApi.enable(protocol.value)
      : renewalApi.saveProtocol(protocol.value)
  },
  onSuccess: (data: RenewalStatus) => client.setQueryData(queryKey, data),
})
function confirm(action: "enable" | "resume" | "discard" | "recover") {
  confirmation.value = action
  confirmOpen.value = true
}
function date(value: number | null | undefined): string {
  return value ? new Date(value).toLocaleString() : "\u2014"
}
const rows = computed(() => {
  const data = status.data.value
  if (!data) return []
  return [
    { label: t("renewal.state"), value: data.enabled ? t("renewal.on") : t("renewal.off") },
    { label: t("renewal.expires"), value: date(data.expires_at) },
    {
      label: t("renewal.remaining"),
      value: t("renewal.hours", { hours: remainingHours(data.remaining_seconds) }),
    },
    { label: t("renewal.lastCheck"), value: date(data.last_check) },
    { label: t("renewal.lastSuccess"), value: date(data.last_success) },
    {
      label: t("renewal.result"),
      value: dynamic(`renewal.results.${data.result}`, data.result),
    },
    { label: t("renewal.retry"), value: date(data.retry_after) },
    ...(data.diagnostic
      ? [
          {
            label: t("renewal.diagnostic"),
            value: `${data.diagnostic_phase}: ${dynamic(`renewal.diagnostics.${data.diagnostic}`, data.diagnostic)}`,
          },
        ]
      : []),
  ]
})
</script>

<template>
  <SectionCard :title="t('renewal.title')" :description="t('renewal.description')">
    <QueryState
      :loading="status.isPending.value"
      :error="status.error.value"
      @retry="status.refetch()"
    >
      <div v-if="status.data.value" class="flex flex-col gap-4">
        <p class="text-muted-foreground text-sm break-all">
          {{ status.data.value.backend === "ohmykeymint" ? "OhMyKeymint" : "TEESimulator" }}
          · {{ status.data.value.target_path }}
        </p>
        <KeyValueList :rows="rows" />
        <Alert
          v-if="!status.data.value.target_supported || !status.data.value.expires_at"
          variant="destructive"
        >
          <AlertTitle>{{ t("renewal.unsupportedTitle") }}</AlertTitle>
          <AlertDescription>{{ t("renewal.unsupported") }}</AlertDescription>
        </Alert>
        <Alert v-if="status.data.value.blocked" variant="destructive">
          <AlertTitle>{{ t("renewal.paused") }}</AlertTitle>
          <AlertDescription>{{ t("renewal.pausedDescription") }}</AlertDescription>
        </Alert>
        <p class="text-muted-foreground text-sm">{{ t("renewal.privacy") }}</p>
        <div class="flex max-w-sm flex-col gap-2">
          <Label for="renewal-protocol">{{ t("renewal.protocol") }}</Label>
          <Select v-model="protocol" :disabled="protocolLocked || change.isPending.value">
            <SelectTrigger id="renewal-protocol"
              ><SelectValue :placeholder="t('renewal.chooseProtocol')"
            /></SelectTrigger>
            <SelectContent>
              <SelectItem value="v2">CSR_V2</SelectItem>
              <SelectItem value="v1">V1 AIDL</SelectItem>
              <SelectItem value="v1-native">V1 Native (0x311e)</SelectItem>
            </SelectContent>
          </Select>
          <p class="text-muted-foreground text-sm">{{ t("renewal.protocolHelp") }}</p>
          <Button
            variant="outline"
            :disabled="!protocolDirty || protocolLocked || change.isPending.value"
            @click="change.mutate('protocol')"
            >{{ t("renewal.saveProtocol") }}</Button
          >
        </div>
        <div class="flex flex-wrap gap-2">
          <Button
            v-if="status.data.value.recovery_required"
            variant="outline"
            :disabled="change.isPending.value"
            @click="confirm('recover')"
            >{{ t("renewal.recover") }}</Button
          >
          <Button
            v-if="status.data.value.pending && status.data.value.phase !== 'installing'"
            variant="outline"
            :disabled="change.isPending.value"
            @click="confirm('discard')"
          >
            {{ t("renewal.discard") }}
          </Button>
          <Button
            v-if="!status.data.value.enabled"
            :disabled="
              change.isPending.value ||
              status.data.value.pending ||
              status.data.value.recovery_required ||
              !protocol ||
              !status.data.value.target_supported ||
              !status.data.value.expires_at
            "
            @click="confirm('enable')"
            >{{ t("renewal.enable") }}</Button
          >
          <Button
            v-else
            variant="secondary"
            :disabled="change.isPending.value"
            @click="change.mutate('disable')"
          >
            {{ t("renewal.disable") }}
          </Button>
          <Button
            variant="outline"
            :disabled="change.isPending.value || !status.data.value.enabled"
            @click="change.mutate('check')"
          >
            {{ t("renewal.check") }}
          </Button>
          <Button
            v-if="status.data.value.blocked && !status.data.value.recovery_required"
            variant="outline"
            :disabled="change.isPending.value"
            @click="confirm('resume')"
          >
            {{ t("renewal.resume") }}
          </Button>
        </div>
        <Alert v-if="change.error.value" variant="destructive">
          <AlertDescription>{{ change.error.value.message }}</AlertDescription>
        </Alert>
      </div>
    </QueryState>
    <ConfirmDialog
      v-model:open="confirmOpen"
      :title="confirmationLabel"
      :confirm-label="confirmationLabel"
      :description="
        confirmation === 'recover'
          ? t('renewal.recoverConfirm')
          : confirmation === 'discard'
            ? t('renewal.discardConfirm')
            : confirmation === 'enable'
              ? t('renewal.consent') +
                (status.data.value?.backend === 'ohmykeymint'
                  ? '\n\n' + t('renewal.omkConsent')
                  : '')
              : t('renewal.resumeConsent')
      "
      @confirm="change.mutate(confirmation)"
    />
  </SectionCard>
</template>
