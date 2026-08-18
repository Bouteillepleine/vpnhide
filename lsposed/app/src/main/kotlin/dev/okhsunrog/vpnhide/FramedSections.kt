package dev.okhsunrog.vpnhide

internal data class FramedSectionParsePolicy(
    val preserveIncomplete: Boolean,
    val discardOnMismatchedEnd: Boolean,
    val trimSectionEnd: Boolean,
)

internal data class IncompleteFramedSection(
    val name: String,
    val body: String,
)

internal data class FramedSections(
    val complete: Map<String, String>,
    val incomplete: IncompleteFramedSection?,
)

internal fun parseFramedSections(
    raw: String,
    beginPrefix: String,
    endPrefix: String,
    policy: FramedSectionParsePolicy,
    consumeLine: (String) -> Boolean = { false },
): FramedSections {
    val sections = linkedMapOf<String, String>()
    var currentName: String? = null
    val currentBody = StringBuilder()

    fun body(): String = currentBody.toString().let { if (policy.trimSectionEnd) it.trimEnd() else it }

    fun clearCurrent() {
        currentBody.clear()
        currentName = null
    }

    raw.lineSequence().forEach { line ->
        when {
            consumeLine(line) -> {}

            line.startsWith(beginPrefix) -> {
                currentName = line.removePrefix(beginPrefix)
                currentBody.clear()
            }

            line.contains(endPrefix) -> {
                val markerStart = line.indexOf(endPrefix)
                // Recover content glued onto the marker: a file emitted without a
                // trailing newline (e.g. `cat`'d) puts its last line and the END
                // marker on the same line (`...}__..._END__:name`). Without this
                // the marker wouldn't be recognised and the section would be
                // dropped as unterminated. The prefixes are unique sentinels, so a
                // mid-line occurrence is always a glued marker, never real content.
                if (markerStart > 0 && currentName != null) {
                    if (currentBody.isNotEmpty()) currentBody.append('\n')
                    currentBody.append(line, 0, markerStart)
                }
                val endName = line.substring(markerStart + endPrefix.length)
                if (currentName == endName) {
                    sections[endName] = body()
                    clearCurrent()
                } else if (policy.discardOnMismatchedEnd) {
                    clearCurrent()
                }
            }

            currentName != null -> {
                if (currentBody.isNotEmpty()) currentBody.append('\n')
                currentBody.append(line)
            }
        }
    }

    val incomplete =
        currentName?.takeIf { policy.preserveIncomplete }?.let { name ->
            IncompleteFramedSection(name, body())
        }
    return FramedSections(sections, incomplete)
}
