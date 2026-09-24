package nu.staldal.mws

/** Inputs are already URL-resolved by discovery; the native client validates each fetch. */
data class IconCandidate(
    val url: String,
    val source: Source,
    val sizes: String? = null,
    val type: String? = null,
    val purpose: String? = null,
    val documentOrder: Int = 0,
) {
    enum class Source { MANIFEST, PAGE, FAVICON }

    val isMaskable: Boolean get() = source == Source.MANIFEST &&
        "any" !in purposeTokens && "maskable" in purposeTokens

    private val purposeTokens: Set<String> get() = purpose.orEmpty().lowercase()
        .split(Regex("\\s+")).filter(String::isNotBlank).toSet().ifEmpty { setOf("any") }

    internal val priority: Int get() = when (source) {
        Source.MANIFEST -> when {
            "any" in purposeTokens -> 0
            "maskable" in purposeTokens -> 1
            else -> 4 // monochrome-only is not supported
        }
        Source.PAGE -> 2
        Source.FAVICON -> 3
    }

    internal val sizeRank: Pair<Int, Int> get() {
        if (type?.substringBefore(';')?.trim()?.equals("image/svg+xml", true) == true ||
            url.substringBefore('?').substringBefore('#').endsWith(".svg", true) ||
            sizes.orEmpty().split(Regex("\\s+")).any { it.equals("any", true) }
        ) return 0 to 0
        val squares = sizes.orEmpty().split(Regex("\\s+")).mapNotNull { token ->
            val match = Regex("([1-9][0-9]*)[xX]([1-9][0-9]*)").matchEntire(token)
            val width = match?.groupValues?.get(1)?.toIntOrNull()
            val height = match?.groupValues?.get(2)?.toIntOrNull()
            width?.takeIf { it == height }
        }
        if (512 in squares) return 1 to 0
        squares.filter { it > 512 }.minOrNull()?.let { return 2 to it }
        squares.filter { it < 512 }.maxOrNull()?.let { return 3 to -it }
        return 4 to 0
    }
}

object IconSelection {
    fun ranked(candidates: List<IconCandidate>): List<IconCandidate> {
        var manifestCount = 0
        var pageCount = 0
        return candidates
        .asSequence().filter { it.priority < 4 }
        .filter {
            when (it.source) {
                IconCandidate.Source.MANIFEST -> ++manifestCount <= 128
                IconCandidate.Source.PAGE -> ++pageCount <= 128
                IconCandidate.Source.FAVICON -> true
            }
        }
        .sortedWith(compareBy<IconCandidate> { it.priority }
            .thenBy { it.sizeRank.first }
            .thenBy { it.sizeRank.second }
            .thenBy { it.documentOrder })
        .toList()
    }
}
