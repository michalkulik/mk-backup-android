package com.michalkulik.mkbackup.backup

/**
 * Minimal glob matcher used for the per-set exclude list.
 *
 * Supports `*` (any characters except `/`), `**` (any characters including `/`) and `?` (a single
 * character). A pattern without any wildcard matches when it appears anywhere in the path, which is
 * what people expect from a simple "ignore names containing …" box.
 *
 * Each pattern is tried against the whole relative path *and* against the plain file name, so
 * `*.tmp` excludes `DCIM/raw/x.tmp` as well.
 */
class GlobMatcher(patterns: List<String>) {

    private val regexes: List<Regex> = patterns
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { pattern ->
            val hasWildcard = pattern.any { it == '*' || it == '?' }
            val body = buildString {
                var i = 0
                while (i < pattern.length) {
                    val ch = pattern[i]
                    when {
                        ch == '*' && i + 1 < pattern.length && pattern[i + 1] == '*' -> {
                            append(".*")
                            i++
                        }

                        ch == '*' -> append("[^/]*")
                        ch == '?' -> append("[^/]")
                        else -> append(Regex.escape(ch.toString()))
                    }
                    i++
                }
            }
            Regex(if (hasWildcard) "^$body$" else Regex.escape(pattern))
        }

    val isEmpty: Boolean get() = regexes.isEmpty()

    fun matches(path: String): Boolean {
        if (regexes.isEmpty()) return false
        val name = path.substringAfterLast('/')
        return regexes.any { regex -> regex.containsMatchIn(path) || regex.containsMatchIn(name) }
    }
}
