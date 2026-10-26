import { useClipboard } from "@vueuse/core"
import { toast } from "vue-sonner"

import { bridge } from "@/core/bridge"
import { duckd } from "@/core/duckd"
import { i18n } from "@/core/i18n"

export const REPOSITORY_URL = "https://github.com/eltavine/Duck-ToolBox"

/**
 * Opens a link in the system browser. The manager's WebView cannot leave the app, so this
 * goes through `am start`. A root host must never fall back to WebView navigation.
 */
export async function openExternal(url: string): Promise<void> {
  if (bridge().available) {
    try {
      await duckd(["system", "open-url", url])
      return
    } catch {
      toast.error(i18n.global.t("core.errors.external_browser_unavailable"))
      return
    }
  }
  window.open(url, "_blank", "noopener")
}

export function useCopy() {
  // `legacy` falls back to execCommand when the WebView denies the async clipboard API.
  const clipboard = useClipboard({ legacy: true })
  return async (text: string) => {
    try {
      await clipboard.copy(text)
      toast.success(i18n.global.t("core.actions.copied"))
    } catch {
      toast.error(i18n.global.t("core.actions.copyFailed"))
    }
  }
}
