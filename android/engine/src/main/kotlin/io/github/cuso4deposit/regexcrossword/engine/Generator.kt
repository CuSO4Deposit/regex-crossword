package io.github.cuso4deposit.regexcrossword.engine

import java.util.regex.Pattern
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min

typealias LineKey = Pair<String, Int>

/** One clue fragment; `lo`/`hi` record the true-text span it covers. */
data class Frag(
    val body: String,
    val lo: Int,
    val hi: Int,
    val single: Boolean,
    val kind: String = "plain",
    val suffix: String = "",
)

/** Python 3.7+ `re.escape` (escapes `()[]{}?*+-|^$\.&~#` and whitespace). */
object PyRe {
    private const val SPECIAL = "()[]{}?*+-|^\$\\.&~# \t\n\r\u000B\u000C"

    fun escape(s: String): String = buildString {
        for (c in s) {
            if (SPECIAL.indexOf(c) >= 0) append('\\')
            append(c)
        }
    }
}

data class GenConfig(
    val edge: Int = 5,
    val difficulty: String = "medium",
    val kind: String = "auto",
    val seed: Int = 0,
    val alphabet: String = "ABCDEFGHIJKLMNOPQRSTUVWXYZ",
    val maxChunkAlts: Int = 2,
    val maxLiteralFraction: Double? = null,
    //: aim for this score (overrides the difficulty band default)
    val targetScore: Double? = null,
    val requireAllRelaxed: Boolean = true,
    val maxAttempts: Int = 8,
    val author: String = "generated",
    val name: String = "generated",
)

/**
 * Port of `hexregex.generator._generate_constructive`, driven by [PyRandom] so
 * the output is byte-identical to `hexregex gen --seed N --no-unique`.
 */
object Generator {
    private val shaperNames = listOf(
        "shape_class_word",
        "shape_class_star",
        "shape_literal_skel",
        "shape_dot_skeleton",
        "shape_negclass_word",
        "shape_alt_star",
        "shape_repeat_block",
    )

    fun constructive(cfg: GenConfig): Puzzle {
        val rng = PyRandom(cfg.seed.toLong())
        val resolvedKind = if (cfg.kind == "auto") {
            if (cfg.difficulty == "easy") "rect" else "hex"
        } else {
            cfg.kind
        }
        val geo: Geometry = if (resolvedKind == "rect") RectGeometry(cfg.edge, cfg.edge) else HexGeometry(cfg.edge)
        geo.validate()
        val families = geo.families

        val target = cfg.targetScore ?: when (cfg.difficulty) {
            "hard" -> 70.0 + 0.1 * (100.0 - 70.0)
            "medium" -> 55.0 + 0.5 * (70.0 - 55.0)
            else -> 0.6 * 55.0
        }
        val literalCap = cfg.maxLiteralFraction ?: if (cfg.difficulty == "medium") 0.6 else null
        val alphabetCount = cfg.alphabet.toSortedSet().size
        val dimension = families.size

        val floor = staticScore(geo.numRows, alphabetCount, dimension, 0.0)
        val opacityNeeded = min(0.95, max(0.05, (target - floor) / 55.0))
        var desiredLiteral = 1.0 - opacityNeeded
        if (literalCap != null) desiredLiteral = min(desiredLiteral, literalCap)

        repeat(cfg.maxAttempts) {
            val solution = randomSolution(geo, cfg.alphabet, rng)
            val texts = lineTexts(geo, solution)
            val tokens = LinkedHashMap<LineKey, MutableList<Frag>>()
            for ((key, text) in texts) tokens[key] = literalTokens(text).toMutableList()

            val keys = tokens.keys.toMutableList()
            rng.shuffle(keys)
            val nShapers = rng.randint(0, min(3, cfg.maxChunkAlts + 1))
            for (key in keys.take(nShapers)) {
                val op = rng.choice(shaperNames)
                val newTokens = applyShaper(op, texts.getValue(key), cfg.alphabet, rng)
                if (newTokens != null && fullmatch(renderTokens(newTokens), texts.getValue(key))) {
                    tokens[key] = newTokens.toMutableList()
                }
            }

            fun isLiteralFrag(tok: Frag, text: String): Boolean =
                tok.single && tok.hi - tok.lo == 1 && tok.body == PyRe.escape(text[tok.lo].toString())

            fun literalFraction(): Double {
                var total = 0
                var nonLiteral = 0
                for ((key, toks) in tokens) {
                    val text = texts.getValue(key)
                    for (tok in toks) {
                        total++
                        if (!isLiteralFrag(tok, text)) nonLiteral++
                    }
                }
                return if (total == 0) 1.0 else 1.0 - nonLiteral.toDouble() / total
            }

            fun convertRandomLiteral(): Boolean {
                val candidates = ArrayList<Pair<LineKey, Int>>()
                for ((key, toks) in tokens) {
                    val text = texts.getValue(key)
                    for (i in toks.indices) if (isLiteralFrag(toks[i], text)) candidates.add(key to i)
                }
                if (candidates.isEmpty()) return false
                val (key, i) = rng.choice(candidates)
                val tok = tokens.getValue(key)[i]
                tokens.getValue(key)[i] = loosenToken(tok, texts.getValue(key)[tok.lo], cfg.alphabet, rng)
                return true
            }

            for (key in tokens.keys.toList()) {
                if (lineIsLiteral(tokens.getValue(key), texts.getValue(key)) && !convertRandomLiteral()) {
                    // no literal token left anywhere; nothing to do
                }
            }

            var guard = 0
            while (literalFraction() > desiredLiteral && guard < 100000) {
                if (!convertRandomLiteral()) break
                guard++
            }

            val clues = cluesFromTokens(tokens, families)
            var allOk = true
            for (line in geo.lines) {
                if (!fullmatch(clues.getValue(line.family)[line.index], texts.getValue(line.family to line.index))) {
                    allOk = false
                    break
                }
            }
            if (allOk) return buildPuzzle(geo, cfg, clues, solution)
        }
        error("constructive generation failed for difficulty ${cfg.difficulty}")
    }

    // ------------------------------------------------------------------
    // solution / tokens
    // ------------------------------------------------------------------
    private fun randomSolution(geo: Geometry, alphabet: String, rng: PyRandom): LinkedHashMap<Cell, Char> {
        val chars = alphabet.toList()
        val solution = LinkedHashMap<Cell, Char>()
        for (cell in geo.cells()) solution[cell] = rng.choice(chars)
        return solution
    }

    private fun lineTexts(geo: Geometry, solution: Map<Cell, Char>): LinkedHashMap<LineKey, String> {
        val texts = LinkedHashMap<LineKey, String>()
        for (line in geo.lines) {
            texts[line.family to line.index] = buildString {
                for (cell in line.cells) append(solution.getValue(cell))
            }
        }
        return texts
    }

    private fun literalTokens(text: String): List<Frag> =
        (0 until text.length).map { i -> Frag(PyRe.escape(text[i].toString()), i, i + 1, true) }

    private fun renderTokens(tokens: List<Frag>): String {
        val out = StringBuilder()
        var group = 0
        for (tok in tokens) {
            if (tok.kind == "backref") {
                group++
                out.append('(').append(tok.body).append(')')
                out.append(tok.suffix.replace("\\#", "\\$group"))
            } else {
                out.append(tok.body)
            }
        }
        return out.toString()
    }

    private fun cluesFromTokens(tokens: Map<LineKey, List<Frag>>, families: List<String>): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        for (family in families) out[family] = ArrayList()
        for ((key, toks) in tokens) out.getValue(key.first).add(renderTokens(toks))
        return out
    }

    private fun lineIsLiteral(tokens: List<Frag>, text: String): Boolean =
        tokens.all { it.single && it.hi - it.lo == 1 && it.body == PyRe.escape(text[it.lo].toString()) }

    private fun fullmatch(pattern: String, text: String): Boolean =
        Pattern.compile(pattern).matcher(text).matches()

    // ------------------------------------------------------------------
    // shapers (whole-line clue generators)
    // ------------------------------------------------------------------
    private fun applyShaper(name: String, text: String, alphabet: String, rng: PyRandom): List<Frag>? =
        when (name) {
            "shape_class_word" -> shapeClassWord(text, alphabet, rng)
            "shape_class_star" -> shapeClassStar(text, alphabet, rng)
            "shape_literal_skel" -> shapeLiteralSkeleton(text, alphabet, rng)
            "shape_dot_skeleton" -> shapeDotSkeleton(text, alphabet, rng)
            "shape_negclass_word" -> shapeNegclassWord(text, alphabet, rng)
            "shape_alt_star" -> shapeAltStar(text, alphabet, rng)
            else -> shapeRepeatBlock(text, alphabet, rng)
        }

    private fun classSet(text: String, alphabet: String, rng: PyRandom): String {
        val chars = text.toSet()
        val pool = alphabet.filter { it !in chars }.toMutableList()
        rng.shuffle(pool)
        val extra = if (pool.isNotEmpty()) pool.take(rng.randint(0, min(6, pool.size))) else emptyList()
        val items = (chars + extra.toSet()).sorted()
        return "[" + items.joinToString("") { PyRe.escape(it.toString()) } + "]"
    }

    private fun shapeClassWord(text: String, alphabet: String, rng: PyRandom): List<Frag> {
        val cls = classSet(text, alphabet, rng)
        val chosen = rng.choice(text.toList())
        return listOf(Frag(cls + "*" + PyRe.escape(chosen.toString()) + cls + "*", 0, text.length, false))
    }

    private fun shapeClassStar(text: String, alphabet: String, rng: PyRandom): List<Frag> {
        val cls = classSet(text, alphabet, rng)
        return listOf(Frag(cls + "*", 0, text.length, false))
    }

    private fun shapeLiteralSkeleton(text: String, alphabet: String, rng: PyRandom): List<Frag>? {
        val n = text.length
        if (n < 2) return null
        val k = rng.randint(2, min(4, n))
        val positions = rng.sample((0 until n).toList(), k).sorted()
        val body = positions.joinToString("") { ".*" + PyRe.escape(text[it].toString()) } + ".*"
        return listOf(Frag(body, 0, n, false))
    }

    private fun shapeDotSkeleton(text: String, alphabet: String, rng: PyRandom): List<Frag>? {
        val out = StringBuilder()
        for (ch in text) {
            val roll = rng.nextDouble()
            when {
                roll < 0.5 -> out.append(PyRe.escape(ch.toString()))
                roll < 0.85 -> out.append(".")
                else -> {
                    val others = alphabet.filter { it != ch }
                    val extra = if (others.isNotEmpty()) rng.sample(others.toList(), min(2, others.length)) else emptyList()
                    val items = (listOf(ch.toString()) + extra.map { it.toString() }).toSortedSet()
                    out.append('[').append(items.joinToString("") { PyRe.escape(it) }).append(']')
                }
            }
        }
        if (out.toString() == PyRe.escape(text)) return null
        return listOf(Frag(out.toString(), 0, text.length, false))
    }

    private fun shapeNegclassWord(text: String, alphabet: String, rng: PyRandom): List<Frag>? {
        val outside = alphabet.filter { it !in text.toSet() }
        if (outside.isEmpty()) return null
        val x = rng.choice(outside.toList())
        val c = rng.choice(text.toList())
        val body = "[^" + PyRe.escape(x.toString()) + "]*" + PyRe.escape(c.toString()) +
            "[^" + PyRe.escape(x.toString()) + "]*"
        return listOf(Frag(body, 0, text.length, false))
    }

    private fun shapeAltStar(text: String, alphabet: String, rng: PyRandom): List<Frag>? {
        val n = text.length
        if (n < 2) return null
        var bestKey: Pair<Int, Int>? = null
        var bestDistinct: Set<String>? = null
        repeat(160) {
            val pieces = ArrayList<String>()
            var i = 0
            while (i < n) {
                val length = min(rng.choice(listOf(2, 2, 3, 3, 1)), n - i)
                pieces.add(text.substring(i, i + length))
                i += length
            }
            val distinct = pieces.toSet()
            val multi = distinct.count { it.length >= 2 }
            val key = distinct.size to -multi
            val previous = bestKey
            val better = previous == null ||
                key.first < previous.first ||
                (key.first == previous.first && key.second < previous.second)
            if (better) {
                bestKey = key
                bestDistinct = distinct
            }
        }
        val distinct = bestDistinct ?: return null
        if (distinct.size > 6 || distinct.none { it.length >= 2 }) return null
        val ordered = distinct.sortedWith(compareBy({ -it.length }, { it }))
        val body = "(?:" + ordered.joinToString("|") { PyRe.escape(it) } + ")*"
        return listOf(Frag(body, 0, n, false))
    }

    private fun shapeRepeatBlock(text: String, alphabet: String, rng: PyRandom): List<Frag>? {
        val n = text.length
        for (i in 0 until n) {
            var bl = 1
            while (bl <= (n - i) / 2) {
                if ((n - i) % bl == 0) {
                    val block = text.substring(i, i + bl)
                    if (text.substring(i) == block.repeat((n - i) / bl)) {
                        val prefix = if (i > 0) PyRe.escape(text.substring(0, i)) else ""
                        val suffix = if (rng.nextDouble() < 0.5) "\\#*" else "\\#+"
                        val toks = ArrayList<Frag>()
                        if (prefix.isNotEmpty()) toks.add(Frag(prefix, 0, i, false))
                        toks.add(Frag(block, i, n, false, "backref", suffix))
                        return toks
                    }
                }
                bl++
            }
        }
        return null
    }

    private fun loosenToken(tok: Frag, trueChar: Char, alphabet: String, rng: PyRandom): Frag {
        val roll = rng.nextDouble()
        val body = when {
            roll < 0.7 -> "."
            roll < 0.9 -> {
                val extras = alphabet.filter { it != trueChar }
                val extra = if (extras.isNotEmpty()) rng.sample(extras.toList(), min(2, extras.length)) else emptyList()
                val items = (listOf(trueChar.toString()) + extra.map { it.toString() }).toSortedSet()
                "[" + items.joinToString("") { PyRe.escape(it) } + "]"
            }
            else -> {
                val outside = alphabet.filter { it != trueChar }
                if (outside.isNotEmpty()) "[^" + PyRe.escape(rng.choice(outside.toList()).toString()) + "]" else "."
            }
        }
        return Frag(body, tok.lo, tok.hi, true)
    }

    // ------------------------------------------------------------------
    // puzzle assembly / score
    // ------------------------------------------------------------------
    private fun buildPuzzle(
        geo: Geometry,
        cfg: GenConfig,
        clues: Map<String, List<String>>,
        solution: Map<Cell, Char>,
    ): Puzzle {
        val rows = (0 until geo.numRows).map { r ->
            (0 until geo.rowSize(r)).map { c -> solution.getValue(Cell(r, c)).toString() }
        }
        for (line in geo.lines) {
            val word = buildString { for (cell in line.cells) append(solution.getValue(cell)) }
            if (!fullmatch(clues.getValue(line.family)[line.index], word)) {
                error("generated clue ${line.family}[${line.index}] does not match its own answer")
            }
        }
        return when (geo) {
            is RectGeometry -> Puzzle(
                kind = "rect",
                edge = null,
                rows = geo.rows,
                cols = geo.cols,
                author = cfg.author,
                name = cfg.name,
                x = clues.getValue("x"),
                y = clues.getValue("y"),
                z = emptyList(),
                solution = rows,
            )
            else -> Puzzle(
                kind = "hex",
                edge = cfg.edge,
                rows = null,
                cols = null,
                author = cfg.author,
                name = cfg.name,
                x = clues.getValue("x"),
                y = clues.getValue("y"),
                z = clues.getValue("z"),
                solution = rows,
            )
        }
    }

    private fun staticScore(statsEdge: Int, alphabetCount: Int, dimension: Int, opacity: Double): Double {
        val alphaNorm = min(1.0, log2(alphabetCount + 1.0) / log2(27.0))
        val sizeNorm = min(1.0, max(0.0, (statsEdge - 2.0) / 5.0))
        val dim = if (dimension >= 3) 1.0 else 0.0
        val searchNorm = log2(2.0) / 12.0
        val raw = 0.18 * dim + 0.55 * opacity + 0.07 * searchNorm + 0.10 * alphaNorm + 0.10 * sizeNorm
        return pyRound2(100.0 * raw)
    }

    private fun pyRound2(x: Double): Double =
        java.math.BigDecimal(x).setScale(2, java.math.RoundingMode.HALF_EVEN).toDouble()
}
