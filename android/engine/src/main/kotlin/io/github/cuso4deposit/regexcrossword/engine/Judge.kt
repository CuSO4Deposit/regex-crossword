package io.github.cuso4deposit.regexcrossword.engine

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** Result of checking one line. */
data class LineResult(
    val family: String,
    val index: Int,
    val clue: String,
    val word: String,
    val complete: Boolean,
    val ok: Boolean,
    val error: String?,
)

/** Result of checking every line. */
data class JudgeResult(val lines: List<LineResult>) {
    val complete: Boolean get() = lines.all { it.complete }
    val ok: Boolean get() = lines.all { it.ok }
    val solved: Boolean get() = complete && ok
    val failures: List<LineResult> get() = lines.filter { !it.ok }
}

/**
 * Line-by-line judging: build each line's word in reading order and require a
 * **whole-string** regex match (`Matcher.matches()`, the Java equivalent of
 * Python's `re.fullmatch`).
 *
 * The stored solution is intentionally not consulted. Constructive puzzles are
 * not unique, so several grids are valid; comparing to one stored answer would
 * reject legitimate solutions.
 */
object Judge {
    private val cache = HashMap<String, Pattern>()

    fun judge(puzzle: Puzzle, grid: Map<Cell, Char>): JudgeResult {
        val geometry = puzzle.geometry
        val results = geometry.lines.map { line ->
            val builder = StringBuilder()
            var complete = true
            for (cell in line.cells) {
                val ch = grid[cell]
                if (ch == null) {
                    complete = false
                    builder.append('?')
                } else {
                    builder.append(ch)
                }
            }
            val clue = puzzle.clue(line.family, line.index)
            var ok = false
            var error: String? = null
            if (complete) {
                val pattern = try {
                    compile(clue)
                } catch (exc: PatternSyntaxException) {
                    error = exc.description
                    null
                }
                if (pattern != null) {
                    ok = pattern.matcher(builder.toString()).matches()
                }
            }
            LineResult(line.family, line.index, clue, builder.toString(), complete, ok, error)
        }
        return JudgeResult(results)
    }

    private fun compile(clue: String): Pattern =
        cache.getOrPut(clue) { Pattern.compile(clue) }
}
