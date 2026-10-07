package dev.herdroid.herdr

/**
 * Builds the remote shell command lines used to talk to herdr.
 *
 * Non-interactive SSH sessions often lack the user's PATH additions, so we try the usual
 * install locations (the official installer uses ~/.local/bin) before giving up — the same
 * strategy herdr's own `--remote` client uses.
 */
object RemoteCommand {
    const val NOT_FOUND_EXIT = 127
    const val NOT_FOUND_MESSAGE = "herdroid: herdr executable not found"

    private val CANDIDATES = listOf(
        "\$(command -v herdr 2>/dev/null)",
        "\$HOME/.local/bin/herdr",
        "\$HOME/.cargo/bin/herdr",
        "\$HOME/.nix-profile/bin/herdr",
        "/opt/homebrew/bin/herdr",
        "/usr/local/bin/herdr",
        "/usr/bin/herdr",
    )

    fun herdr(session: String, herdrPath: String?, vararg args: String): String {
        val argv = (listOf("--session", session) + args).joinToString(" ") { shellQuote(it) }
        val candidates = buildList {
            herdrPath?.trim()?.takeIf { it.isNotEmpty() }?.let { add(shellQuote(expandHome(it))) }
            CANDIDATES.forEach { add("\"$it\"") }
        }
        val script = buildString {
            append("for c in ")
            append(candidates.joinToString(" "))
            append("; do if [ -n \"\$c\" ] && [ -x \"\$c\" ]; then exec \"\$c\" ")
            append(argv)
            append("; fi; done; echo ")
            append(shellQuote(NOT_FOUND_MESSAGE))
            append(" >&2; exit ")
            append(NOT_FOUND_EXIT)
        }
        // Run under POSIX sh regardless of the user's login shell (fish, nu, ...).
        return "/bin/sh -c " + shellQuote(script)
    }

    fun apiBridge(session: String, herdrPath: String?) =
        herdr(session, herdrPath, "remote-api-bridge")

    fun terminalControl(
        session: String,
        herdrPath: String?,
        target: String,
        cols: Int,
        rows: Int,
        takeover: Boolean,
    ): String {
        val args = mutableListOf(
            "terminal", "session", "control", target,
            "--cols", cols.toString(), "--rows", rows.toString(),
        )
        if (takeover) args += "--takeover"
        return herdr(session, herdrPath, *args.toTypedArray())
    }

    fun terminalObserve(session: String, herdrPath: String?, target: String, cols: Int, rows: Int) =
        herdr(
            session, herdrPath,
            "terminal", "session", "observe", target,
            "--cols", cols.toString(), "--rows", rows.toString(),
        )

    /**
     * Lists the subdirectories of [path] (empty means home) for the directory picker. Falls back
     * to the nearest existing ancestor, prints the resolved directory first, then one entry per
     * line: `g/name` for git checkouts, `d/name` for other directories. Parse with [DirListing.parse].
     */
    fun listDirectories(path: String): String {
        val script = """
            d=${'$'}1
            case "${'$'}d" in "" | "~") d=${'$'}HOME ;; "~/"*) d=${'$'}HOME/${'$'}{d#"~/"} ;; esac
            while [ ! -d "${'$'}d" ] && [ "${'$'}d" != / ] && [ "${'$'}d" != . ]; do d=${'$'}(dirname -- "${'$'}d"); done
            cd -- "${'$'}d" 2>/dev/null || cd || exit 2
            pwd
            for e in * .[!.]* ..?*; do
              [ -d "${'$'}e" ] || continue
              if [ -e "${'$'}e/.git" ]; then printf 'g/%s\n' "${'$'}e"; else printf 'd/%s\n' "${'$'}e"; fi
            done
        """.trimIndent()
        return "/bin/sh -c " + shellQuote(script) + " sh " + shellQuote(path)
    }

    private fun expandHome(path: String): String =
        if (path == "~" || path.startsWith("~/")) "\$HOME" + path.removePrefix("~") else path

    fun shellQuote(value: String): String {
        if (value.isNotEmpty() && value.all { it.isLetterOrDigit() || it in "@%_+=:,./-" }) {
            return value
        }
        // Keep $HOME expansions working for the herdr path we injected ourselves.
        if (value.startsWith("\$HOME/") && value.drop(6).all { it.isLetterOrDigit() || it in "_./-" }) {
            return "\"$value\""
        }
        return "'" + value.replace("'", "'\\''") + "'"
    }
}
