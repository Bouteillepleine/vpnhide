package dev.okhsunrog.vpnhide

import dev.okhsunrog.vpnhide.generated.HookIds

internal enum class FilesystemHidingStatus {
    Unavailable,
    Disabled,
    Active,
    PendingEnable,
    PendingDisable,
    BootConfigError,
    HookSetupError,
}

internal data class FilesystemHidingState(
    val status: FilesystemHidingStatus,
    val errorDetail: String? = null,
) {
    val kernelBackendInstalled: Boolean
        get() = status != FilesystemHidingStatus.Unavailable
}

private data class FilesystemKernelBackend(
    val id: NativeBackendId,
    val statusRaw: String,
)

private fun filesystemKernelBackend(sections: Map<String, String>): FilesystemKernelBackend? {
    val kmodRaw = sections["kmod_state"].orEmpty()
    val kpmRaw = sections["kpm_state"].orEmpty()
    val kmodStatus = parseProtocolStatusBlock(kmodRaw)
    val kpmStatus = parseProtocolStatusBlock(kpmRaw)
    return when {
        kmodStatus?.backend ==
            HookIds.Backend.KMOD.id
                .toLong() -> FilesystemKernelBackend(NativeBackendId.Kmod, kmodRaw)

        kpmStatus?.backend ==
            HookIds.Backend.KPM.id
                .toLong() -> FilesystemKernelBackend(NativeBackendId.Kpm, kpmRaw)

        sections["kmod_module_dir"]?.trim() == "1" -> FilesystemKernelBackend(NativeBackendId.Kmod, kmodRaw)

        sections["kpm_module_dir"]?.trim() == "1" -> FilesystemKernelBackend(NativeBackendId.Kpm, kpmRaw)

        else -> null
    }
}

private fun currentKmodLoadStatus(
    backend: FilesystemKernelBackend,
    sections: Map<String, String>,
): KmodLoadStatus? =
    if (backend.id == NativeBackendId.Kmod) {
        readKmodLoadStatus(
            currentBootId = sections["current_boot_id"].orEmpty().trim(),
            raw = sections["kmod_load_status"].orEmpty(),
            dmesgRaw = "",
        )?.takeIf { it.freshForCurrentBoot }
    } else {
        null
    }

private fun filesystemHookSetupFailed(
    backend: FilesystemKernelBackend,
    load: KmodLoadStatus?,
    sections: Map<String, String>,
): Boolean =
    when (backend.id) {
        NativeBackendId.Kmod -> {
            load?.let { it.loaded == true && it.filesystemHiding == true } == true
        }

        NativeBackendId.Kpm -> {
            val kpmLoad = parseKpmLoadStatus(sections["kpm_load_status"].orEmpty())
            kpmLoad.loaded == true &&
                kpmLoad.filesystemHiding == true &&
                kpmLoad.isFreshFor(sections["current_boot_id"].orEmpty()) &&
                parseProtocolStatusBlock(backend.statusRaw)?.statusError == HookIds.StatusError.PARTIAL_HOOKS
        }

        NativeBackendId.Zygisk -> {
            false
        }
    }

/**
 * Compare the canonical next-boot choice with the hook set actually reported
 * by the running kernel backend. Boot diagnostics distinguish a newly changed
 * setting from a hook that was requested this boot but failed to install.
 */
internal fun resolveFilesystemHidingState(
    desiredEnabled: Boolean,
    sections: Map<String, String>,
): FilesystemHidingState {
    val backend =
        filesystemKernelBackend(sections)
            ?: return FilesystemHidingState(FilesystemHidingStatus.Unavailable)
    val hookInstalled =
        HookIds.Hook.FILESYSTEM_IFACE_PATHS in
            installedHooks(backend.statusRaw)
    val load = currentKmodLoadStatus(backend, sections)
    val configExit = load?.filesystemConfigExit
    if (configExit != null && configExit != 0 && configExit != 1) {
        return FilesystemHidingState(
            status = FilesystemHidingStatus.BootConfigError,
            errorDetail = load.filesystemConfigError ?: "exit=$configExit",
        )
    }

    return when {
        desiredEnabled && hookInstalled -> {
            FilesystemHidingState(FilesystemHidingStatus.Active)
        }

        !desiredEnabled && hookInstalled -> {
            FilesystemHidingState(FilesystemHidingStatus.PendingDisable)
        }

        desiredEnabled && filesystemHookSetupFailed(backend, load, sections) -> {
            FilesystemHidingState(FilesystemHidingStatus.HookSetupError)
        }

        desiredEnabled -> {
            FilesystemHidingState(FilesystemHidingStatus.PendingEnable)
        }

        else -> {
            FilesystemHidingState(FilesystemHidingStatus.Disabled)
        }
    }
}
