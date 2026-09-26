package io.github.cuso4deposit.regexcrossword.engine

import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min

/** Port of `hexregex/difficulty.py` (human-oriented difficulty score). */
data class DifficultyWeights(
    val dimension: Double = 0.18,
    val opacity: Double = 0.55,
    val search: Double = 0.07,
    val alphabet: Double = 0.10,
    val size: Double = 0.10,
    val easyMax: Double = 55.0,
    val mediumMax: Double = 70.0,
)

val DEFAULT_WEIGHTS = DifficultyWeights()

/** Solver statistics used by [measure]; a subset of the Python SolveStats. */
data class SolveStats(
    val edge: Int = 0,
    val alphabetSize: Int = 0,
    var solvedByPropagation: Boolean = false,
    var backtracks: Int = 0,
    var nodes: Int = 0,
    var residualCells: Int = 0,
    var residualCandidates: Int = 0,
    var maxResidualDomain: Int = 0,
    var score: Double = 0.0,
    var band: String = "",
)

data class DifficultyReport(val score: Double, val band: String)

private fun walkRegex(nodes: List<RegexNode>): Sequence<RegexNode> = sequence {
    for (node in nodes) {
        yield(node)
        when (node) {
            is RepNode -> yieldAll(walkRegex(node.body))
            is GroupNode -> yieldAll(walkRegex(node.body))
            is AltNode -> for (branch in node.branches) yieldAll(walkRegex(branch))
            else -> {}
        }
    }
}

/** Static style measurements of a clue set (no solving). */
fun clueStyle(clues: Map<String, List<String>>): Map<String, Double> {
    val families = listOf("x", "y", "z").filter { it in clues }
    var literals = 0
    var anys = 0
    var classes = 0
    var repeats = 0
    var alternations = 0
    var backrefs = 0
    var groups = 0
    var lines = 0
    var tokens = 0
    for (family in families) {
        for (pattern in clues.getValue(family)) {
            lines++
            for (node in walkRegex(RegexEngine.compile(pattern).ast)) {
                tokens++
                when (node) {
                    is LitNode -> literals++
                    is AnyNode -> anys++
                    is ClassNode -> classes++
                    is RepNode -> repeats++
                    is AltNode -> alternations++
                    is RefNode -> backrefs++
                    is GroupNode -> groups++
                    is AnchorNode -> {}
                }
            }
        }
    }
    val literalRatio = if (tokens > 0) literals.toDouble() / tokens else 1.0
    return mapOf(
        "dimension" to families.size.toDouble(),
        "lines" to lines.toDouble(),
        "tokens" to tokens.toDouble(),
        "literals" to literals.toDouble(),
        "anys" to anys.toDouble(),
        "classes" to classes.toDouble(),
        "repeats" to repeats.toDouble(),
        "alternations" to alternations.toDouble(),
        "backrefs" to backrefs.toDouble(),
        "groups" to groups.toDouble(),
        "literal_ratio" to literalRatio,
        "wildcard_ratio" to if (tokens > 0) (tokens - literals).toDouble() / tokens else 0.0,
        "backref_ratio" to if (tokens > 0) backrefs.toDouble() / tokens else 0.0,
        "tokens_per_line" to if (lines > 0) tokens.toDouble() / lines else 0.0,
    )
}

fun measure(
    stats: SolveStats,
    style: Map<String, Double>,
    weights: DifficultyWeights = DEFAULT_WEIGHTS,
): DifficultyReport {
    val dimension = if ((style["dimension"] ?: 3.0) >= 3.0) 1.0 else 0.0
    val opacity = 1.0 - (style["literal_ratio"] ?: 1.0)
    val residual = log2(1.0 + max(0, stats.residualCandidates).toDouble())
    val searchRaw = log2(1.0 + stats.nodes) + 2.0 * log2(1.0 + stats.backtracks) + residual
    val search = min(1.0, searchRaw / 12.0)
    val alphabet = min(1.0, log2(maxOf(1.0, stats.alphabetSize.toDouble()) + 1.0) / log2(27.0))
    val edge = max(0.0, stats.edge.toDouble())
    val size = min(1.0, max(0.0, (edge - 2.0) / 5.0))
    val raw = weights.dimension * dimension +
        weights.opacity * opacity +
        weights.search * search +
        weights.alphabet * alphabet +
        weights.size * size
    val score = pyRound2(100.0 * raw)
    val band = when {
        score < weights.easyMax -> "easy"
        score < weights.mediumMax -> "medium"
        else -> "hard"
    }
    stats.score = score
    stats.band = band
    return DifficultyReport(score, band)
}

fun pyRound2(x: Double): Double =
    java.math.BigDecimal(x).setScale(2, java.math.RoundingMode.HALF_EVEN).toDouble()
