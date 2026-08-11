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
    val kmodInstalled: Boolean
        get() = status != FilesystemHidingStatus.Unavailable
}

/**
 * Compare the canonical next-boot choice with the hook set actually reported
 * by the running kmod. Boot diagnostics distinguish a newly changed setting
 * from a hook that was requested this boot but failed to install.
 */
internal fun resolveFilesystemHidingState(
    desiredEnabled: Boolean,
    sections: Map<String, String>,
): FilesystemHidingState {
    if (sections["kmod_module_dir"]?.trim() != "1") {
        return FilesystemHidingState(FilesystemHidingStatus.Unavailable)
    }

    val hookInstalled =
        HookIds.Hook.FILESYSTEM_IFACE_PATHS in
            installedHooks(sections["kmod_state"].orEmpty())
    val load =
        readKmodLoadStatus(
            currentBootId = sections["current_boot_id"].orEmpty().trim(),
            raw = sections["kmod_load_status"].orEmpty(),
            dmesgRaw = "",
        )?.takeIf { it.freshForCurrentBoot }
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

        desiredEnabled && load?.loaded == true && load.filesystemHiding == true -> {
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
