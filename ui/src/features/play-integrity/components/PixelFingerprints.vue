<script setup lang="ts">
import { onScopeDispose, ref, watch } from "vue"
import { useQuery } from "@tanstack/vue-query"
import { Button } from "@/components/ui/button"
import { Field, FieldLabel } from "@/components/ui/field"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import SectionCard from "@/core/components/SectionCard.vue"
import QueryState from "@/core/components/QueryState.vue"
import { notifyError } from "@/core/notify"
import { pixelApi } from "../pixel-api"
import type { Preview } from "../api"
import { useIntegrityI18n } from "../i18n"
const emit = defineEmits<{ preview: [value: Preview] }>()
const { t } = useIntegrityI18n()
const devices = useQuery({ queryKey: ["play-integrity.pixel-devices"], queryFn: pixelApi.devices })
const device = ref("bluejay"),
  build = ref(""),
  busy = ref(false)
const catalog = ref<Awaited<ReturnType<typeof pixelApi.builds>> | null>(null)
let active = true
onScopeDispose(() => {
  active = false
})
watch(device, () => {
  catalog.value = null
  build.value = ""
})
async function work(preview = false) {
  if (busy.value) return
  busy.value = true
  try {
    if (preview) {
      const candidate = await pixelApi.preview(device.value, build.value)
      if (active) emit("preview", candidate)
    } else {
      const fetched = await pixelApi.builds(device.value)
      if (!active) return
      catalog.value = fetched
      build.value =
        (catalog.value.builds.find((b) => !b.notes) || catalog.value.builds[0])?.id ?? ""
    }
  } catch (error) {
    if (active) notifyError(error)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <SectionCard :title="t('pixel.title')" :description="t('pixel.help')">
    <QueryState
      :loading="devices.isPending.value"
      :error="devices.error.value"
      @retry="devices.refetch()"
    >
      <div class="flex flex-col gap-4">
        <Field
          ><FieldLabel for="pixel-device">{{ t("pixel.device") }}</FieldLabel>
          <Select v-model="device" :disabled="busy"
            ><SelectTrigger id="pixel-device" class="w-full"><SelectValue /></SelectTrigger>
            <SelectContent
              ><SelectItem
                v-for="item in devices.data.value?.devices"
                :key="item.id"
                :value="item.id"
                >{{ item.name }}</SelectItem
              ></SelectContent
            >
          </Select>
        </Field>
        <Button class="self-start" :disabled="busy" @click="work()">{{
          busy ? t("pixel.loading") : t("pixel.fetch")
        }}</Button>
        <template v-if="catalog">
          <Field
            ><FieldLabel for="pixel-build">{{ t("pixel.build") }}</FieldLabel>
            <Select v-model="build" :disabled="busy"
              ><SelectTrigger id="pixel-build" class="w-full"><SelectValue /></SelectTrigger>
              <SelectContent
                ><SelectItem v-for="item in catalog.builds" :key="item.id" :value="item.id"
                  >{{ item.id }} · Android {{ item.release }} {{ item.notes }}</SelectItem
                ></SelectContent
              >
            </Select>
          </Field>
          <p dir="ltr" class="text-muted-foreground break-all font-mono text-xs">
            {{ catalog.builds.find((b) => b.id === build)?.fingerprint }}
          </p>
          <p class="text-muted-foreground text-sm">{{ t("pixel.patchUnknown") }}</p>
          <Button class="self-start" :disabled="busy || !build" @click="work(true)">{{
            t("pixel.preview")
          }}</Button>
        </template>
      </div>
    </QueryState>
  </SectionCard>
</template>
