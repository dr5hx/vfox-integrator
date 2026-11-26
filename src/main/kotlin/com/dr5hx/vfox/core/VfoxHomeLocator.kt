package com.dr5hx.vfox.core

import java.io.File

/**
 * Locates the local vfox installation and resolves the directory SDKs are stored in.
 *
 * vfox is installed in many ways — scoop, brew, a manual download, or a custom `VFOX_HOME` — but the
 * home directory itself is always discoverable, so there is no setting for it. Resolution is layered,
 * most explicit first:
 *
 *  1. `VFOX_HOME`, if the IDE environment carries it.
 *  2. The conventional `~/.vfox`, then the legacy `~/.version-fox`.
 *
 * SDK storage can be relocated away from the home via `storage.sdkPath` in `config.yaml`, which is
 * common on Windows where the home lives on `C:` but the SDKs live on another drive.
 */
class VfoxHomeLocator(
    private val environment: (String) -> String? = { System.getenv(it) },
    private val userHome: String = System.getProperty("user.home"),
) {

    /** Resolves the vfox home directory, or null when vfox does not appear to be installed. */
    fun locateHome(): File? {
        val candidates = buildList {
            environment("VFOX_HOME")?.takeIf { it.isNotBlank() }?.let { add(File(it)) }
            add(File(userHome, ".vfox"))
            add(File(userHome, ".version-fox"))
        }
        return candidates.firstOrNull { it.isDirectory }
    }

    /**
     * Resolves the directory that holds per-SDK version folders.
     *
     * Reads `storage.sdkPath` from `<home>/config.yaml` when set, otherwise falls back to the
     * conventional `<home>/sdks`. The result is not required to exist — callers treat a missing
     * directory as "no SDKs found".
     */
    fun locateSdkRoot(home: File): File {
        val configured = readStorageSdkPath(File(home, "config.yaml"))
        return configured ?: File(home, "sdks")
    }

    /** Resolves the directory holding vfox plugins (one directory per supported SDK). */
    fun locatePluginRoot(home: File): File = File(home, "plugin")

    /**
     * Directories that may hold [sdk]'s version folders, most likely first.
     *
     * vfox does not keep every SDK in one place. Most live under a shared `cache` directory
     * (`<storage>/cache/nodejs/v-24.14.0/`), while some plugins install straight into the storage
     * root (`<storage>/golang/v-1.27.1/`). Both shapes are tried, so a scanner does not have to know
     * which SDKs take which shape.
     *
     * @param storageRoot the SDK storage root, i.e. `storage.sdkPath` from `config.yaml`.
     */
    fun sdkRootCandidates(storageRoot: File, sdk: String): List<File> = listOf(
        File(File(storageRoot, CACHE_DIR), sdk),
        File(storageRoot, sdk),
    )

    private fun readStorageSdkPath(configFile: File): File? {
        if (!configFile.isFile) return null
        val text = runCatching { configFile.readText() }.getOrNull() ?: return null
        return parseStorageSdkPath(text)?.let { File(it) }
    }

    companion object {
        /** The subdirectory most SDKs are installed under, inside the storage root. */
        const val CACHE_DIR = "cache"

        /**
         * Extracts `storage.sdkPath` from vfox's `config.yaml`.
         *
         * Only the `storage:` mapping is inspected, so a same-named key elsewhere in the file is
         * not mistaken for the storage path. Quoting is stripped when present.
         */
        fun parseStorageSdkPath(yaml: String): String? {
            var inStorage = false
            var storageIndent = -1
            for (raw in yaml.lineSequence()) {
                val line = raw.substringBefore('#')
                if (line.isBlank()) continue
                val indent = line.indexOfFirst { !it.isWhitespace() }
                val content = line.trim()

                if (!inStorage) {
                    if (content.startsWith("storage:")) {
                        inStorage = true
                        storageIndent = indent
                    }
                    continue
                }

                // A line at or left of `storage:`'s own indentation ends the block.
                if (indent <= storageIndent) break

                if (content.startsWith("sdkPath:")) {
                    val value = content.removePrefix("sdkPath:").trim().trim('"', '\'')
                    if (value.isNotEmpty()) return value
                }
            }
            return null
        }
    }
}
