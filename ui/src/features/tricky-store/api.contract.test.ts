// @vitest-environment node
import { lstatSync, mkdirSync, readFileSync, readlinkSync, writeFileSync } from "node:fs"
import path from "node:path"

import { afterEach, describe, expect, it } from "vitest"

import type { Device, Host, Server } from "@/core/testing/host"
import { CONTRACT, hasDuckd, installHost, serve } from "@/core/testing/host"

import { trickyStoreApi } from "./api"
import { draftFromStatus, select, toSaveRequest } from "./selection"

const AOSP_KEYBOX = readFileSync(
  path.resolve(import.meta.dirname, "../../../../crates/duck-tricky-store/assets/aosp-keybox.xml"),
  "utf8",
)

const moduleProp = (id: string, versionCode: number) =>
  `id=${id}\nname=${id}\nversion=v${versionCode}\nversionCode=${versionCode}\n`

const packages = {
  userPackages: ["com.google.android.apps.walletnfcrel", "io.github.vvb2060.keyattestation"],
  systemPackages: ["com.google.android.gms", "com.android.vending"],
}

const backends: Record<string, Device> = {
  "tricky-store": {
    files: {
      "/data/adb/modules/tricky_store/module.prop": moduleProp("tricky_store", 246),
      "/data/adb/tricky_store/config.ini": "[target]\nio.github.vvb2060.keyattestation!\n",
    },
  },
  "tricky-store-legacy": {
    files: {
      "/data/adb/modules/tricky_store/module.prop": moduleProp("tricky_store", 200),
      "/data/adb/tricky_store/target.txt": "io.github.vvb2060.keyattestation\n",
      "/data/adb/tricky_store/security_patch.txt": "system=prop\n",
    },
  },
  "tee-simulator": {
    files: {
      "/data/adb/modules/teesim/module.prop": moduleProp("teesim", 31),
      "/data/adb/teesim/keybox.xml": AOSP_KEYBOX,
      "/data/adb/teesim/config.json": JSON.stringify({
        version: 1,
        profiles: { default: { keybox: "keybox.xml", apps: ["io.github.vvb2060.keyattestation"] } },
      }),
    },
  },
  "oh-my-keymint": {
    files: {
      "/data/adb/modules/oh_my_keymint/module.prop": moduleProp("oh_my_keymint", 90),
      // Released OhMyKeymint (v1.2.0) has no version key here and refuses unknown keys.
      "/data/misc/keystore/omk/injector.toml": 'scoop = ["io.github.vvb2060.keyattestation"]\n',
      "/data/misc/keystore/omk/config.toml": 'version = 2\n[trust]\nsecurity_patch = "auto"\n',
    },
  },
}

let host: Host | undefined
let server: Server | undefined
afterEach(async () => {
  host?.dispose()
  await server?.close()
  server = undefined
})

describe.skipIf(!hasDuckd)("Tricky Store API against the real duckd", CONTRACT, () => {
  describe.each(Object.entries(backends))("%s", (backend, device) => {
    it("reads status and saves a changed target list", async () => {
      host = installHost({ ...device, ...packages })
      const status = await trickyStoreApi.status()
      expect(status.active?.backend).toBe(backend)
      expect(status.schema).not.toBeNull()
      expect(status.config.targets.map((target) => target.package_name)).toContain(
        "io.github.vvb2060.keyattestation",
      )

      const draft = draftFromStatus(status)
      select(draft, ["com.google.android.apps.walletnfcrel"])
      await trickyStoreApi.save(toSaveRequest(draft))
      const saved = await trickyStoreApi.status()
      expect(saved.config.targets.map((target) => target.package_name)).toContain(
        "com.google.android.apps.walletnfcrel",
      )
    })

    it("rejects a policy value the backend would not accept", async () => {
      host = installHost({ ...device, ...packages })
      const status = await trickyStoreApi.status()
      const field = status.schema!.default_policy.find((entry) => entry.kind === "text")!
      const request = toSaveRequest(draftFromStatus(status))
      request.default_policy = { [field.key]: "definitely not valid!" }
      await expect(trickyStoreApi.save(request)).rejects.toMatchObject({ code: "invalid_policy" })
    })

    it("installs keyboxes from every source", async () => {
      host = installHost({ ...device, ...packages })
      await expect(trickyStoreApi.keyboxAosp()).rejects.toMatchObject({ code: "invalid_keybox" })
      const generated = await trickyStoreApi.keyboxGenerate()
      expect(generated.summary.keyboxes).toBe(1)
      const valid = readFileSync(host.path(generated.target_path), "utf8")
      const imported = await trickyStoreApi.keyboxImport(valid)
      expect(imported.backup_path).not.toBeNull()
      expect(imported.source).toBe("local")
      const local = await trickyStoreApi.keyboxInstall(generated.target_path)
      expect(local.target_path).toBe(generated.target_path)
      await expect(trickyStoreApi.keyboxImport("<not a keybox/>")).rejects.toMatchObject({
        code: "invalid_keybox",
      })
    })
  })

  it("tells when OhMyKeymint must restart and keeps its injector format", async () => {
    host = installHost({ ...backends["oh-my-keymint"], ...packages })
    const request = toSaveRequest(draftFromStatus(await trickyStoreApi.status()))
    const patchOnly = { ...request, default_policy: { security_patch: "2026-09-05" } }
    expect((await trickyStoreApi.save(patchOnly)).restart_required).toBe(false)
    const locked = { ...request, default_policy: { device_locked: "false" } }
    expect((await trickyStoreApi.save(locked)).restart_required).toBe(true)
    const injector = readFileSync(host.path("/data/misc/keystore/omk/injector.toml"), "utf8")
    expect(injector).not.toContain("version")
  })

  it("installs EC-only keyboxes for OMK from files, browser imports and downloads", async () => {
    host = installHost(backends["oh-my-keymint"])
    const generated = await trickyStoreApi.keyboxGenerate()
    const valid = readFileSync(host.path(generated.target_path), "utf8")
    const ecOnly = valid.replace(/\s*<Key algorithm="rsa">[\s\S]*?<\/Key>/, "")
    const source = host.path("/storage/emulated/0/ec-only.xml")
    mkdirSync(path.dirname(source), { recursive: true })
    writeFileSync(source, ecOnly)
    server = await serve({ "/ec-only.xml": ecOnly })

    for (const install of [
      () => trickyStoreApi.keyboxInstall("/storage/emulated/0/ec-only.xml"),
      () => trickyStoreApi.keyboxImport(ecOnly),
      () => trickyStoreApi.keyboxFetch(server!.url("/ec-only.xml")),
    ]) {
      const result = await install()
      expect(result.summary).toMatchObject({ has_ecdsa: true, has_rsa: false })
      expect(readFileSync(host.path(result.target_path), "utf8")).not.toContain('algorithm="rsa"')
    }

    const rsaOnly = valid.replace(/\s*<Key algorithm="ecdsa">[\s\S]*?<\/Key>/, "")
    await expect(trickyStoreApi.keyboxImport(rsaOnly)).rejects.toMatchObject({
      code: "backend_rule",
    })
    const keyboxPath = "/data/misc/keystore/omk/keybox.xml"
    expect(readFileSync(host.path(keyboxPath), "utf8")).toContain('algorithm="ecdsa"')
    expect(readFileSync(host.path(keyboxPath), "utf8")).not.toContain('algorithm="rsa"')
  })

  it("downloads keyboxes the way providers and the repository hand them over", async () => {
    host = installHost({ ...backends["tricky-store"], ...packages })
    const generated = await trickyStoreApi.keyboxGenerate()
    const valid = readFileSync(host.path(generated.target_path), "utf8")
    server = await serve({
      "/plain.xml": valid,
      "/encoded.txt": Buffer.from(valid).toString("base64"),
    })
    expect((await trickyStoreApi.keyboxFetch(server.url("/plain.xml"))).source).toBe("url")
    const decoded = await trickyStoreApi.keyboxFetch(server.url("/encoded.txt"), "base64 -d")
    expect(decoded.summary.keyboxes).toBe(1)
    await expect(trickyStoreApi.keyboxFetch(server.url("/missing"))).rejects.toMatchObject({
      code: "download_failed",
    })
  })

  it("manages custom keybox providers", async () => {
    host = installHost({ ...backends["tricky-store"], ...packages })
    const providers = await trickyStoreApi.providers()
    const next = [
      ...providers,
      { name: "Mine", url: "https://example.org/kb", decode: "base64 -d" },
    ]
    expect(await trickyStoreApi.saveProviders(next)).toHaveLength(next.length)
    await expect(
      trickyStoreApi.saveProviders([{ name: "Bad", url: "https://example.org", decode: "sh" }]),
    ).rejects.toMatchObject({ code: "unsupported_decoder" })

    const { path: exported } = await trickyStoreApi.exportProviders()
    const content = readFileSync(host.path(exported), "utf8")
    await trickyStoreApi.resetProviders()
    const restored = await trickyStoreApi.importProvidersContent(content)
    expect(restored.map((provider) => provider.name)).toContain("Mine")
  })

  it("lists helper app sets and saves prop settings", async () => {
    host = installHost({ ...backends["tricky-store"], ...packages })
    expect((await trickyStoreApi.denylist()).packages).toEqual([])
    expect(Array.isArray((await trickyStoreApi.xposed()).packages)).toBe(true)
    expect((await trickyStoreApi.unnecessary()).packages.length).toBeGreaterThan(0)

    const hash = "ab".repeat(32)
    const props = await trickyStoreApi.saveProps({ prop_handler_enabled: false, boot_hash: hash })
    expect(props.boot_hash).toBe(hash)
    expect(readFileSync(host.path("/data/adb/boot_hash"), "utf8").trim()).toBe(hash)
    expect(lstatSync(host.path("/data/adb/disable_prop_handler")).isFile()).toBe(true)

    await expect(
      trickyStoreApi.saveProps({ prop_handler_enabled: true, boot_hash: "invalid" }),
    ).rejects.toMatchObject({ code: "invalid_policy" })
    const unchanged = await trickyStoreApi.status()
    expect(unchanged.props).toEqual({ prop_handler_enabled: false, boot_hash: hash })
  })

  it("links and unlinks the entry on the keystore module", async () => {
    host = installHost({ ...backends["tricky-store"], ...packages })
    expect((await trickyStoreApi.entry()).module_id).toBe("tricky_store")
    const enabled = await trickyStoreApi.setEntry(true)
    expect(enabled.linked).toBe(true)
    expect(readlinkSync(host.path("/data/adb/modules/tricky_store/webroot"))).toMatch(/webroot$/)
    expect((await trickyStoreApi.setEntry(false)).linked).toBe(false)
  })
})
