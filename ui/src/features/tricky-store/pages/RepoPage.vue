<script setup lang="ts">
import { computed } from "vue"
import { useRouter } from "vue-router"
import { Button } from "@/components/ui/button"
import SectionCard from "@/core/components/SectionCard.vue"
import { openExternal } from "@/core/links"
import { useTrickyI18n } from "../i18n"
const { t, locale } = useTrickyI18n()
const router = useRouter()
const source = computed(() => `https://keybox.kowx712.cc/${locale.value}`)
</script>
<template>
  <div class="p-4">
    <SectionCard :title="t('keybox.repoTitle')" :description="t('keybox.repoExternal')">
      <div class="flex flex-wrap gap-2">
        <Button @click="openExternal(source)">{{ t("keybox.openBrowser") }}</Button>
        <Button variant="outline" @click="router.push('/tricky-store/keybox')">{{
          t("keybox.importDownloaded")
        }}</Button>
      </div>
    </SectionCard>
  </div>
</template>
