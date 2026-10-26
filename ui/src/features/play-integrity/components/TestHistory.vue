<script setup lang="ts">
import { reactive, ref } from "vue"
import { useQuery, useQueryClient } from "@tanstack/vue-query"
import { useI18n } from "vue-i18n"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import { Field, FieldLabel } from "@/components/ui/field"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import QueryState from "@/core/components/QueryState.vue"
import ConfirmDialog from "@/core/components/ConfirmDialog.vue"
import { notifyError, notifySuccess } from "@/core/notify"
import { libraryApi } from "../library-api"
import type { ReportedResult } from "../library-api"
import { useIntegrityI18n } from "../i18n"
import SettingDetails from "./SettingDetails.vue"
const props = defineProps<{ revision?: string }>()
const { t } = useIntegrityI18n()
const global = useI18n()
const client = useQueryClient()
const history = useQuery({ queryKey: ["play-integrity.records"], queryFn: libraryApi.records })
const form = reactive({
  app: "",
  version: "",
  note: "",
  installation_source: "",
  basic: "unknown" as ReportedResult,
  device: "unknown" as ReportedResult,
  strong: "unknown" as ReportedResult,
})
const busy = ref(false),
  confirm = ref(false)
const report = ref("")
async function importReport() {
  if (busy.value || !props.revision) return
  busy.value = true
  try {
    if (new TextEncoder().encode(report.value).length > 65536) throw new Error(t("fileLimit"))
    await libraryApi.importRecord(
      JSON.parse(report.value),
      props.revision,
      form.installation_source,
    )
    await client.invalidateQueries({ queryKey: ["play-integrity.records"] })
    report.value = ""
    notifySuccess(t("history.saved"))
  } catch (error) {
    notifyError(error)
  } finally {
    busy.value = false
  }
}
async function work(clear = false) {
  if (busy.value) return
  busy.value = true
  try {
    if (clear) await libraryApi.clearRecords()
    else await libraryApi.saveRecord({ ...form, revision: props.revision ?? "" })
    await client.invalidateQueries({ queryKey: ["play-integrity.records"] })
    notifySuccess(t("history.saved"))
  } catch (error) {
    notifyError(error)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <div class="flex flex-col gap-4">
    <p class="text-muted-foreground text-sm">{{ t("history.help") }}</p>
    <SettingDetails :title="t('history.import')">
      <p class="text-muted-foreground mb-3 text-sm">{{ t("history.importHelp") }}</p>
      <Textarea
        v-model="report"
        dir="ltr"
        spellcheck="false"
        autocapitalize="off"
        :aria-label="t('history.report')"
        class="min-h-36 font-mono text-xs"
      />
      <Button class="mt-3" :disabled="busy || !revision || !report.trim()" @click="importReport">{{
        t("history.import")
      }}</Button>
    </SettingDetails>
    <SettingDetails :title="t('history.add')">
      <div class="flex flex-col gap-4">
        <div class="grid gap-4 sm:grid-cols-2">
          <Field
            ><FieldLabel for="pif-test-app">{{ t("history.app") }}</FieldLabel
            ><Input id="pif-test-app" v-model="form.app" maxlength="80"
          /></Field>
          <Field
            ><FieldLabel for="pif-test-version">{{ t("history.version") }}</FieldLabel
            ><Input id="pif-test-version" v-model="form.version" maxlength="40"
          /></Field>
        </div>
        <div class="grid gap-4 sm:grid-cols-3">
          <Field v-for="key in ['basic', 'device', 'strong'] as const" :key="key">
            <FieldLabel :for="`pif-test-${key}`">{{
              { basic: "Basic", device: "Device", strong: "Strong" }[key]
            }}</FieldLabel>
            <Select v-model="form[key]"
              ><SelectTrigger :id="`pif-test-${key}`" class="w-full"><SelectValue /></SelectTrigger
              ><SelectContent
                ><SelectItem
                  v-for="value in ['unknown', 'pass', 'fail', 'omitted'] as const"
                  :key="value"
                  :value="value"
                  >{{ t(`history.${value}`) }}</SelectItem
                ></SelectContent
              ></Select
            >
          </Field>
        </div>
        <Field
          ><FieldLabel for="pif-test-source">{{ t("history.installationSource") }}</FieldLabel
          ><Input id="pif-test-source" v-model="form.installation_source" maxlength="80"
        /></Field>
        <Field
          ><FieldLabel for="pif-test-note">{{ t("history.note") }}</FieldLabel
          ><Input id="pif-test-note" v-model="form.note" maxlength="240"
        /></Field>
        <Button
          class="self-start"
          :disabled="busy || !form.app.trim() || !revision"
          @click="work()"
          >{{ t("history.save") }}</Button
        >
      </div>
    </SettingDetails>
    <QueryState
      :loading="history.isPending.value"
      :error="history.error.value"
      @retry="history.refetch()"
    >
      <p v-if="!history.data.value?.records.length" class="text-muted-foreground text-sm">
        {{ t("history.empty") }}
      </p>
      <article
        v-for="record in history.data.value?.records"
        :key="record.id"
        class="rounded-xl border p-3 text-sm"
      >
        <p class="font-medium break-words">{{ record.app }} {{ record.version }}</p>
        <p class="text-muted-foreground mt-1 text-xs">
          {{ new Date(record.created * 1000).toLocaleString(global.locale.value) }}
        </p>
        <p class="mt-2">
          {{
            ["Basic", "Device", "Strong"]
              .map(
                (label, index) => `${label}: ${t(`history.${record.results[index] ?? "unknown"}`)}`,
              )
              .join(" · ")
          }}
        </p>
        <p class="text-muted-foreground mt-2 break-words">
          {{ record.model }} · {{ t(record.enabled ? "on" : "off") }} ·
          {{ t(record.mode === "full" ? "full" : "scriptsOnly") }}
        </p>
        <SettingDetails :title="t('history.context')">
          <p class="font-mono text-xs break-all">{{ record.revision }}</p>
          <p class="mt-2 font-mono text-xs break-all">{{ record.fingerprint }}</p>
          <p v-if="record.note" class="mt-2 break-words">{{ record.note }}</p>
          <p v-if="record.environment" class="mt-2 break-words">
            SDK {{ record.environment.system_sdk ?? "—" }} · GMS
            {{ record.environment.gms_version ?? "—" }} · Play
            {{ record.environment.store_version ?? "—" }}
          </p>
          <p v-if="record.installation_source" class="mt-2 break-words">
            {{ t("history.installationSource") }}: {{ record.installation_source }}
          </p>
          <p v-if="record.report" class="mt-2 break-words">
            {{ record.report.licensing ?? "—" }} · {{ record.report.recognition ?? "—" }} · SDK
            {{ record.report.sdk ?? "—" }}
          </p>
          <p v-if="record.report" class="mt-2 text-sm">{{ t("history.unverified") }}</p>
        </SettingDetails>
      </article>
    </QueryState>
    <Button
      v-if="history.error.value || history.data.value?.records.length"
      variant="ghost"
      class="self-start"
      :disabled="busy"
      @click="confirm = true"
      >{{ t("history.clear") }}</Button
    >
    <ConfirmDialog
      v-model:open="confirm"
      :title="t('history.clear')"
      :description="t('history.clearHelp')"
      :confirm-label="global.t('core.actions.remove')"
      destructive
      @confirm="work(true)"
    />
  </div>
</template>
