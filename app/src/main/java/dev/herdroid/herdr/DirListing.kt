package dev.herdroid.herdr

/** One subdirectory on the remote host; [isRepo] when it holds a `.git`. */
data class RemoteDir(val name: String, val isRepo: Boolean) {
    val hidden: Boolean get() = name.startsWith(".")
}

/** A remote directory and its subdirectories, as printed by [RemoteCommand.listDirectories]. */
data class DirListing(val path: String, val entries: List<RemoteDir>) {
    val parent: String?
        get() = if (path == "/") null else path.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }

    val name: String get() = path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }

    fun child(name: String): String = path.trimEnd('/') + "/" + name

    companion object {
        fun parse(output: String): DirListing? {
            val lines = output.lineSequence().filter { it.isNotEmpty() }.toList()
            val path = lines.firstOrNull()?.takeIf { it.startsWith("/") } ?: return null
            val entries = lines.drop(1).mapNotNull { line ->
                val name = line.drop(2).takeIf { it.isNotEmpty() && it != "." && it != ".." }
                when {
                    name == null -> null
                    line.startsWith("g/") -> RemoteDir(name, isRepo = true)
                    line.startsWith("d/") -> RemoteDir(name, isRepo = false)
                    else -> null
                }
            }
            return DirListing(path, entries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }))
        }
    }
}
