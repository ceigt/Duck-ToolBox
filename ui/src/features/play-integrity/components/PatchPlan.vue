<script setup lang="ts">
import { computed, reactive, watch } from "vue"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { Input } from "@/components/ui/input"
import type { SecurityPatchPreview } from "@/core/features"
import { useIntegrityI18n } from "../i18n"
const props = defineProps<{ preview: SecurityPatchPreview; selected: boolean; busy: boolean }>()
const emit = defineEmits<{
  select: [value: boolean]
  preview: [partitions: Record<string, string>]
}>()
const { t } = useIntegrityI18n()
const scopes = ["system", "vendor", "boot"] as const
const dates = reactive({
  system: props.preview.patch,
  vendor: props.preview.patch,
  boot: props.preview.patch,
})
const checked = reactive({ system: true, vendor: true, boot: true })
const plan = computed(() =>
  Object.fromEntries(scopes.filter((s) => checked[s]).map((s) => [s, dates[s]])),
)
const dirty = computed(() =>
  scopes.some((s) =>
    props.preview.partitions
      ? plan.value[s] !== props.preview.partitions[s]
      : plan.value[s] !== props.preview.patch,
  ),
)
watch(plan, () => {
  if (dirty.value) emit("select", false)
})
</script>
<template>
  <div class="mt-4 rounded-xl border bg-muted/20 p-4">
    <label class="flex min-h-11 items-center gap-3"
      ><Checkbox
        :disabled="busy || dirty"
        :model-value="selected"
        @update:model-value="emit('select', $event === true)"
      />{{ t("syncPatch") }} ({{ preview.backend }})</label
    >
    <p class="text-muted-foreground mb-3 text-sm">{{ t("patch.help") }}</p>
    <div v-for="scope in scopes" :key="scope" class="mb-2 flex flex-wrap items-center gap-3">
      <label class="flex min-h-11 items-center gap-2"
        ><Checkbox v-model="checked[scope]" :disabled="busy" />{{ t(`patch.${scope}`) }}</label
      >
      <Input
        v-model="dates[scope]"
        type="date"
        class="w-auto"
        :aria-label="t(`patch.${scope}`)"
        :disabled="busy || !checked[scope]"
      />
    </div>
    <Button
      variant="outline"
      :disabled="busy || !dirty || !Object.keys(plan).length"
      @click="emit('preview', plan)"
      >{{ t("patch.preview") }}</Button
    >
    <p v-if="dirty" role="status" class="mt-2 text-sm">{{ t("patch.draft") }}</p>
    <p
      v-for="change in preview.changes"
      :key="change.key"
      class="text-muted-foreground mt-2 break-all font-mono text-xs"
    >
      {{ change.key }}: {{ change.before ?? "—" }} → {{ change.after }}
    </p>
  </div>
</template>
