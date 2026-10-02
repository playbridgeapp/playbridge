package com.playbridge.sender.data.nuvio

import java.io.File

/**
 * On-disk script cache. Directory names are SHA-256 of the repo URL so short
 * hashCode collisions cannot merge repositories. Script names are constrained
 * path segments, never caller-supplied relative paths.
 */
internal class NuvioScriptStore(
    private val nuvioRoot: File,
) {
    fun readActive(repoUrl: String, scraperId: String, siblingRepoUrls: Collection<String> = emptyList()): String? {
        val id = NuvioDestinationPolicy.safeScraperId(scraperId) ?: return null
        val modern = activeFile(repoUrl, id)
        if (modern != null && modern.isFile) return readCapped(modern)
        if (legacyCollides(repoUrl, siblingRepoUrls)) return null
        val legacy = File(legacyDir(repoUrl), "$id.js")
        if (!legacy.isFile || !isInside(nuvioRoot, legacy)) return null
        return readCapped(legacy)
    }

    fun hashActive(repoUrl: String, scraperId: String, siblingRepoUrls: Collection<String> = emptyList()): String? {
        val text = readActive(repoUrl, scraperId, siblingRepoUrls) ?: return null
        return NuvioDestinationPolicy.sha256Hex(text)
    }

    fun writeActive(repoUrl: String, scraperId: String, code: String): Boolean {
        if (utf8Size(code) > NuvioLimits.MAX_CODE_BYTES) return false
        val file = activeFile(repoUrl, scraperId) ?: return false
        return writeAtomic(file, code).also { if (it) deleteLegacy(repoUrl, scraperId) }
    }

    fun writePending(repoUrl: String, scraperId: String, code: String): Boolean {
        if (utf8Size(code) > NuvioLimits.MAX_CODE_BYTES) return false
        val file = pendingFile(repoUrl, scraperId) ?: return false
        return writeAtomic(file, code)
    }

    fun readPending(repoUrl: String, scraperId: String): String? {
        val file = pendingFile(repoUrl, scraperId) ?: return null
        if (!file.isFile) return null
        return readCapped(file)
    }

    fun promotePending(repoUrl: String, scraperId: String, expectedSha256: String): Boolean {
        val pending = readPending(repoUrl, scraperId) ?: return false
        if (NuvioDestinationPolicy.sha256Hex(pending) != expectedSha256) return false
        return writeActive(repoUrl, scraperId, pending).also { if (it) deletePending(repoUrl, scraperId) }
    }

    fun deletePending(repoUrl: String, scraperId: String) {
        pendingFile(repoUrl, scraperId)?.delete()
    }

    fun deleteScraper(repoUrl: String, scraperId: String) {
        activeFile(repoUrl, scraperId)?.delete()
        deletePending(repoUrl, scraperId)
        deleteLegacy(repoUrl, scraperId)
    }

    fun deleteRepo(repoUrl: String) {
        repoDir(repoUrl)?.deleteRecursively()
        val legacy = legacyDir(repoUrl)
        if (isInside(nuvioRoot, legacy)) legacy.deleteRecursively()
    }

    fun migrateLegacy(repoUrl: String, siblingRepoUrls: Collection<String>) {
        if (legacyCollides(repoUrl, siblingRepoUrls)) return
        val legacy = legacyDir(repoUrl)
        if (!legacy.isDirectory || !isInside(nuvioRoot, legacy)) return
        val dest = repoDir(repoUrl) ?: return
        dest.mkdirs()
        legacy.listFiles()?.forEach { file ->
            val id = file.name.removeSuffix(".js")
            if (!file.isFile || file.name.contains(".pending.") || NuvioDestinationPolicy.safeScraperId(id) == null) return@forEach
            val target = File(File(dest, "active"), file.name)
            if (!target.exists() && file.length() <= NuvioLimits.MAX_CODE_BYTES && isInside(dest, target)) {
                target.parentFile?.mkdirs()
                file.copyTo(target, overwrite = false)
            }
        }
    }

    private fun activeFile(repoUrl: String, scraperId: String): File? {
        val id = NuvioDestinationPolicy.safeScraperId(scraperId) ?: return null
        val dir = repoDir(repoUrl) ?: return null
        return File(File(dir, "active"), "$id.js").takeIf { isInside(dir, it) }
    }

    private fun pendingFile(repoUrl: String, scraperId: String): File? {
        val id = NuvioDestinationPolicy.safeScraperId(scraperId) ?: return null
        val dir = repoDir(repoUrl) ?: return null
        return File(File(dir, "pending"), "$id.js").takeIf { isInside(dir, it) }
    }

    private fun repoDir(repoUrl: String): File? {
        val name = NuvioDestinationPolicy.repoDirectoryName(repoUrl)
        if (name.length != 64 || name.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        nuvioRoot.mkdirs()
        val dir = File(File(nuvioRoot, "repos"), name)
        return dir.takeIf { isInside(nuvioRoot, it) }
    }

    private fun legacyDir(repoUrl: String): File =
        File(nuvioRoot, NuvioDestinationPolicy.legacyDirectoryName(repoUrl))

    private fun legacyCollides(repoUrl: String, siblingRepoUrls: Collection<String>): Boolean {
        val name = NuvioDestinationPolicy.legacyDirectoryName(repoUrl)
        return siblingRepoUrls.any { it != repoUrl && NuvioDestinationPolicy.legacyDirectoryName(it) == name }
    }

    private fun deleteLegacy(repoUrl: String, scraperId: String) {
        val id = NuvioDestinationPolicy.safeScraperId(scraperId) ?: return
        val file = File(legacyDir(repoUrl), "$id.js")
        if (isInside(nuvioRoot, file)) file.delete()
    }

    private fun readCapped(file: File): String? {
        if (!file.isFile || file.length() > NuvioLimits.MAX_CODE_BYTES) return null
        return try {
            val text = file.readText()
            text.takeIf { utf8Size(it) <= NuvioLimits.MAX_CODE_BYTES }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeAtomic(file: File, code: String): Boolean {
        if (!isInside(nuvioRoot, file)) return false
        return try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(code)
            if (!tmp.renameTo(file)) {
                file.writeText(code)
                tmp.delete()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun isInside(root: File, target: File): Boolean {
        val rootPath = root.canonicalFile
        val targetPath = target.canonicalFile
        val prefix = rootPath.path + File.separator
        return targetPath.path == rootPath.path || targetPath.path.startsWith(prefix)
    }

    private fun utf8Size(text: String): Int = text.toByteArray(Charsets.UTF_8).size
}
