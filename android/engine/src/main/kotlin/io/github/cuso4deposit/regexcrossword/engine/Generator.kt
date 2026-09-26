package io.github.cuso4deposit.regexcrossword.engine

import java.util.regex.Pattern
import kotlin.math.abs
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
    // unique-generator knobs
    val loosen: Int = 250,
    val literalRatio: Double? = null,
    val minScore: Double? = null,
    val maxScore: Double? = null,
    val templates: Boolean = false,
    val allowBackref: Boolean = false,
    val fullUnique: Boolean = false,
    val opTries: Int = 12,
    val easyMaxSteps: Int = 40,
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

    private val TEMPLATES = listOf(
        ".*H.*H.*",
        "(DI|NS|TH|OM)*",
        "[^C]*[^R]*III.*",
        "F.*[AO].*[AO].*",
        "(...?)\\1*",
        "[CHMNOR]*I[CHMNOR]*",
        "P+(..)\\1.*",
        ".*MCC.*DD.*",
        "(.)(.)(.)(.)\\4\\3\\2\\1",
        "(.)C\\1X\\1",
        "[^M]*M[^M]*",
        "[RC]*",
        "(S|MM|HHH)*",
        ".*X.*RCHX.*",
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

            // Whole-line structural clues. Easy keeps the light garnish; medium
            // and hard lean on position-free MIT-style clues (.*c.* spans,
            // [SET]*c[SET]*, class/alternation stars, backref repeats) so letters
            // are not pinned to cells. shape_dot_skeleton pins a position per
            // character and is deliberately excluded here.
            val structural = shaperNames.filter { it != "shape_dot_skeleton" }
            val keys = tokens.keys.toMutableList()
            rng.shuffle(keys)
            val nStructural: Int
            val candidates: List<String>
            when (cfg.difficulty) {
                "easy" -> {
                    nStructural = rng.randint(0, min(3, cfg.maxChunkAlts + 1))
                    candidates = shaperNames
                }
                "medium" -> {
                    nStructural = rng.randint(0, maxOf(2, keys.size / 5))
                    candidates = structural + listOf("contains", "contains", "shape_literal_skel")
                }
                else -> {
                    nStructural = rng.randint((keys.size * 3) / 4, keys.size)
                    candidates = List(4) { "contains" } +
                        List(3) { "shape_literal_skel" } +
                        List(2) { "shape_alt_star" } +
                        List(2) { "shape_class_star" } +
                        listOf(
                            "shape_class_word",
                            "shape_negclass_word",
                            "shape_repeat_block",
                            "dotstar",
                        )
                }
            }

            fun acceptable(key: LineKey, newTokens: List<Frag>): Boolean {
                val rendered = renderTokens(newTokens)
                if (rendered == renderTokens(tokens.getValue(key))) return false
                if (!rendered.any { it in 'A'..'Z' || it in 'a'..'z' }) return false
                return fullmatch(rendered, texts.getValue(key))
            }

            for (key in keys.take(nStructural)) {
                val op = rng.choice(candidates)
                val newTokens = when {
                    op in shaperNames -> applyShaper(op, texts.getValue(key), cfg.alphabet, rng)
                    op == "contains" -> opContains(tokens.getValue(key), texts.getValue(key), rng)
                    else -> opDotstar(tokens.getValue(key), texts.getValue(key), rng)
                }
                if (newTokens == null) continue
                if (acceptable(key, newTokens)) tokens[key] = newTokens.toMutableList()
            }

            if (cfg.difficulty == "hard") {
                // No cell may be pinned by a single line: replace any line that
                // is still literal with a position-free whole-line clue.
                val depot = listOf(
                    "dotstar_word",
                    "dotstar_word",
                    "shape_class_star",
                    "shape_class_star",
                    "shape_alt_star",
                    "shape_class_word",
                    "shape_negclass_word",
                )
                for (key in tokens.keys.toList()) {
                    val text = texts.getValue(key)
                    val toks = tokens.getValue(key)
                    if (toks.none { it.single && it.hi - it.lo == 1 }) continue
                    for (attempt in 0 until 4) {
                        val op = rng.choice(depot)
                        val newTokens = if (op == "dotstar_word") {
                            listOf(
                                Frag(
                                    ".*" + PyRe.escape(rng.choice(text.toList()).toString()) + ".*",
                                    0,
                                    text.length,
                                    false,
                                ),
                            )
                        } else {
                            applyShaper(op, text, cfg.alphabet, rng)
                        }
                        if (newTokens != null && acceptable(key, newTokens)) {
                            tokens[key] = newTokens.toMutableList()
                            break
                        }
                    }
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

    private fun randomRun(tokens: List<Frag>, rng: PyRandom, allowBackref: Boolean): Triple<Int, Int, List<Frag>>? {
        val n = tokens.size
        if (n == 0) return null
        val i = rng.randrange(0, n)
        val j = rng.randrange(i, n)
        val run = tokens.subList(i, j + 1)
        if (!allowBackref && run.any { it.kind == "backref" }) return null
        return Triple(i, j, run)
    }

    /** Replace a random span with `.*` (position-free). */
    private fun opDotstar(tokens: List<Frag>, text: String, rng: PyRandom): List<Frag>? {
        val run = randomRun(tokens, rng, true) ?: return null
        val i = run.first
        val j = run.second
        val out = tokens.toMutableList()
        val lo = tokens[i].lo
        val hi = tokens[j].hi
        repeat(j - i + 1) { out.removeAt(i) }
        out.add(i, Frag(".*", lo, hi, false))
        return out
    }

    /** Replace a random span with `.*c.*` for a character it contains. */
    private fun opContains(tokens: List<Frag>, text: String, rng: PyRandom): List<Frag>? {
        val run = randomRun(tokens, rng, true) ?: return null
        val i = run.first
        val j = run.second
        val trueText = text.substring(tokens[i].lo, tokens[j].hi)
        if (trueText.isEmpty()) return null
        val c = rng.choice(trueText.toList())
        val body = ".*" + PyRe.escape(c.toString()) + ".*"
        val out = tokens.toMutableList()
        val lo = tokens[i].lo
        val hi = tokens[j].hi
        repeat(j - i + 1) { out.removeAt(i) }
        out.add(i, Frag(body, lo, hi, false))
        return out
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

    // ==================================================================
    // unique generation (solver feedback) — port of generator.generate
    // with --unique. Total: for any seed it returns a puzzle with a unique
    // solution (the most-relaxed unique puzzle the greedy relaxation
    // reaches); it never raises.
    // ==================================================================
    private data class Candidate(
        val score: Double,
        val tokens: List<Frag>,
        val solver: Solver,
        val solutions: List<Map<Cell, Char>>,
        val stats: SolveStats,
        val unique: Boolean,
        val op: String,
    )

    private val operatorNames = listOf(
        "wildcard", "class", "negclass", "dotstar", "optional",
        "repeat", "altstar", "contains", "repeat_backref",
    )
    private val operatorWeightMap = mapOf(
        "wildcard" to 2.0, "class" to 2.0, "negclass" to 1.5, "dotstar" to 1.5,
        "optional" to 1.5, "repeat" to 1.5, "altstar" to 1.5, "contains" to 1.0,
        "repeat_backref" to 2.0,
    )
    private val shaperWeightMap = mapOf(
        "shape_class_word" to 4.0, "shape_class_star" to 2.5, "shape_literal_skel" to 3.5,
        "shape_dot_skeleton" to 3.5, "shape_negclass_word" to 2.5,
        "shape_alt_star" to 2.5, "shape_repeat_block" to 3.0,
    )

    private fun bandBounds(difficulty: String): Pair<Double, Double> = when (difficulty) {
        "easy" -> 0.0 to DEFAULT_WEIGHTS.easyMax
        "medium" -> DEFAULT_WEIGHTS.easyMax to DEFAULT_WEIGHTS.mediumMax
        else -> DEFAULT_WEIGHTS.mediumMax to 100.0
    }

    private fun operatorWeight(name: String): Double =
        shaperWeightMap[name] ?: operatorWeightMap.getValue(name)

    private fun chooseOperator(
        rng: PyRandom,
        cfg: GenConfig,
        usage: Map<String, Int>,
        altQuota: Int?,
        lightSet: Boolean,
    ): String {
        if (lightSet) {
            val names = listOf("wildcard", "class", "negclass", "optional", "repeat")
            return rng.choicesOne(names, names.map { 1.0 / (1.0 + (usage[it] ?: 0)) })
        }
        val names = ArrayList<String>()
        for (n in operatorNames) if (n != "repeat_backref") names.add(n)
        if (cfg.allowBackref) names.add("repeat_backref")
        names.addAll(shaperNames)
        val quota = altQuota ?: cfg.maxChunkAlts
        val filtered = names.filter { !(it == "shape_alt_star" && (usage[it] ?: 0) >= quota) }
        return rng.choicesOne(filtered, filtered.map { operatorWeight(it) / (1.0 + (usage[it] ?: 0)) })
    }

    private fun isLiteralFrag(tok: Frag, text: String): Boolean =
        tok.single && tok.hi - tok.lo == 1 && tok.body == PyRe.escape(text[tok.lo].toString())

    private fun literalFraction(tokens: Map<LineKey, List<Frag>>, texts: Map<LineKey, String>): Double {
        var total = 0
        var literals = 0
        for ((key, toks) in tokens) {
            val text = texts.getValue(key)
            for (tok in toks) {
                total++
                if (isLiteralFrag(tok, text)) literals++
            }
        }
        return if (total == 0) 0.0 else literals.toDouble() / total
    }

    private fun replaceRun(tokens: List<Frag>, i: Int, j: Int, frag: Frag): List<Frag> {
        val out = tokens.toMutableList()
        repeat(j - i + 1) { out.removeAt(i) }
        out.add(i, frag)
        return out
    }

    private fun renderPlain(tokens: List<Frag>): String =
        tokens.filter { it.kind == "plain" }.joinToString("") { it.body }

    private fun singleIndices(tokens: List<Frag>): List<Int> =
        tokens.indices.filter { tokens[it].single && tokens[it].hi - tokens[it].lo == 1 }

    private fun smallestPeriod(text: String): Int? {
        for (p in 1 until text.length) {
            if (text.length % p == 0 && text == text.substring(0, p).repeat(text.length / p)) return p
        }
        return null
    }

    private fun opWildcard(tokens: List<Frag>, text: String, rng: PyRandom): List<Frag>? {
        val singles = singleIndices(tokens)
        if (singles.isEmpty()) return null
        val i = rng.choice(singles)
        return replaceRun(tokens, i, i, Frag(".", tokens[i].lo, tokens[i].hi, true))
    }

    private fun opClass(tokens: List<Frag>, text: String, rng: PyRandom, alphabet: String): List<Frag>? {
        val singles = singleIndices(tokens)
        if (singles.isEmpty()) return null
        val i = rng.choice(singles)
        val trueCh = text[tokens[i].lo]
        val extras = alphabet.filter { it != trueCh }
        val extra = rng.sample(extras.toList(), min(3, alphabet.length - 1))
        val items = (listOf(trueCh.toString()) + extra.map { it.toString() }).toSortedSet()
        val body = "[" + items.joinToString("") { PyRe.escape(it) } + "]"
        return replaceRun(tokens, i, i, Frag(body, tokens[i].lo, tokens[i].hi, true))
    }

    private fun opNegclass(tokens: List<Frag>, text: String, rng: PyRandom, alphabet: String): List<Frag>? {
        val singles = singleIndices(tokens)
        if (singles.isEmpty()) return null
        val others = alphabet.filter { it != text[tokens[singles[0]].lo] }
        if (others.isEmpty()) return null
        val i = rng.choice(singles)
        val x = rng.choice(alphabet.filter { it != text[tokens[i].lo] }.toList())
        val body = "[^" + PyRe.escape(x.toString()) + "]"
        return replaceRun(tokens, i, i, Frag(body, tokens[i].lo, tokens[i].hi, true))
    }

    private fun opOptional(tokens: List<Frag>, text: String, rng: PyRandom): List<Frag>? {
        val run = randomRun(tokens, rng, false) ?: return null
        val i = run.first
        val j = run.second
        val group = run.third
        if (group.size == 1 && !group[0].single) return null
        val body = "(?:" + renderPlain(group) + ")?"
        return replaceRun(tokens, i, j, Frag(body, tokens[i].lo, tokens[j].hi, false))
    }

    private fun opRepeat(tokens: List<Frag>, text: String, rng: PyRandom): List<Frag>? {
        val run = randomRun(tokens, rng, false) ?: return null
        val i = run.first
        val j = run.second
        val trueText = text.substring(tokens[i].lo, tokens[j].hi)
        if (trueText.length < 2) return null
        val p = smallestPeriod(trueText) ?: return null
        val body = "(?:" + PyRe.escape(trueText.substring(0, p)) + ")+"
        return replaceRun(tokens, i, j, Frag(body, tokens[i].lo, tokens[j].hi, false))
    }

    private fun opRepeatBackref(tokens: List<Frag>, text: String, rng: PyRandom): List<Frag>? {
        val run = randomRun(tokens, rng, false) ?: return null
        val i = run.first
        val j = run.second
        val trueText = text.substring(tokens[i].lo, tokens[j].hi)
        if (trueText.length < 2) return null
        val p = smallestPeriod(trueText) ?: return null
        return replaceRun(
            tokens, i, j,
            Frag(PyRe.escape(trueText.substring(0, p)), tokens[i].lo, tokens[j].hi, false, "backref", "\\#*"),
        )
    }

    private fun opAltstar(tokens: List<Frag>, text: String, rng: PyRandom, alphabet: String): List<Frag>? {
        val singles = singleIndices(tokens)
        if (singles.isEmpty()) return null
        val i = rng.choice(singles)
        val trueCh = text[tokens[i].lo]
        val extra = rng.sample(alphabet.toList(), min(3, alphabet.length))
        val body = "(?:" +
            (listOf(trueCh.toString()) + extra.map { it.toString() }).joinToString("|") { PyRe.escape(it) } +
            ")*"
        return replaceRun(tokens, i, i, Frag(body, tokens[i].lo, tokens[i].hi, false))
    }

    private fun buildOperator(
        op: String,
        tokens: List<Frag>,
        text: String,
        rng: PyRandom,
        cfg: GenConfig,
    ): List<Frag>? = when (op) {
        "wildcard" -> opWildcard(tokens, text, rng)
        "class" -> opClass(tokens, text, rng, cfg.alphabet)
        "negclass" -> opNegclass(tokens, text, rng, cfg.alphabet)
        "dotstar" -> opDotstar(tokens, text, rng)
        "optional" -> opOptional(tokens, text, rng)
        "repeat" -> opRepeat(tokens, text, rng)
        "altstar" -> opAltstar(tokens, text, rng, cfg.alphabet)
        "contains" -> opContains(tokens, text, rng)
        else -> opRepeatBackref(tokens, text, rng)
    }

    fun unique(cfg: GenConfig): Puzzle {
        RegexEngine.clearCaches()
        val rng = PyRandom(cfg.seed.toLong())
        val resolvedKind =
            if (cfg.kind == "auto") (if (cfg.difficulty == "easy") "rect" else "hex") else cfg.kind
        val geo: Geometry =
            if (resolvedKind == "rect") RectGeometry(cfg.edge, cfg.edge) else HexGeometry(cfg.edge)
        geo.validate()
        val families = geo.families
        val (baseLo, baseHi) = bandBounds(cfg.difficulty)
        val customWindow = cfg.minScore != null || cfg.maxScore != null
        val lo = cfg.minScore ?: baseLo
        val hi = cfg.maxScore ?: baseHi
        val target: Double? = cfg.targetScore ?: when (cfg.difficulty) {
            "hard" -> lo + 0.1 * (hi - lo)
            "medium" -> lo + 0.5 * (hi - lo)
            else -> 0.6 * hi
        }
        fun reached(s: SolveStats): Boolean =
            if (target == null) (lo <= s.score && s.score <= hi) else s.score >= target
        val defaultCap = when (cfg.difficulty) {
            "medium" -> 0.6
            else -> null
        }
        val literalCap = cfg.maxLiteralFraction ?: defaultCap

        repeat(cfg.maxAttempts) {
            val solution = randomSolution(geo, cfg.alphabet, rng)
            val texts = lineTexts(geo, solution)
            var tokensByLine = LinkedHashMap<LineKey, MutableList<Frag>>()
            for ((k, t) in texts) tokensByLine[k] = literalTokens(t).toMutableList()

            fun styleOk(): Boolean =
                literalCap == null || literalFraction(tokensByLine, texts) <= literalCap

            fun evaluate(tokens: Map<LineKey, List<Frag>>): Triple<Solver, List<Map<Cell, Char>>, SolveStats> {
                val clues = cluesFromTokens(tokens, families)
                val probe = buildPuzzle(geo, cfg, clues, solution)
                val s = Solver(probe, cfg.alphabet.toList())
                val solutions: List<Map<Cell, Char>>
                val stats: SolveStats
                if (cfg.fullUnique) {
                    solutions = s.solveAll(2)
                    stats = s.solvePropagationOnly().second
                } else {
                    val r = s.solvePropagationOnly()
                    solutions = r.first
                    stats = r.second
                }
                return Triple(s, solutions, stats)
            }

            var solver: Solver
            var solutions: List<Map<Cell, Char>>
            var stats: SolveStats
            var isUnique: Boolean
            val initial = evaluate(tokensByLine)
            solver = initial.first
            solutions = initial.second
            stats = initial.third
            isUnique = solutions.size == 1

            if (cfg.templates) {
                val keys = tokensByLine.keys.toMutableList()
                rng.shuffle(keys)
                for (key in keys) {
                    val text = texts.getValue(key)
                    val candidates = TEMPLATES.filter { fullmatch(it, text) }.toMutableList()
                    rng.shuffle(candidates)
                    for (cand in candidates) {
                        val trial = LinkedHashMap(tokensByLine)
                        trial[key] = mutableListOf(Frag(cand, 0, text.length, false))
                        val result = evaluate(trial)
                        if (result.second.size == 1 && reached(result.third)) {
                            tokensByLine = trial
                            solver = result.first
                            solutions = result.second
                            stats = result.third
                            isUnique = true
                            break
                        }
                    }
                }
            }

            var accepted = 0
            val usage = HashMap<String, Int>()
            val altQuota = rng.randint(0, cfg.maxChunkAlts)

            fun build(op: String, key: LineKey): List<Frag>? =
                if (op in shaperNames) {
                    applyShaper(op, texts.getValue(key), cfg.alphabet, rng)
                } else {
                    buildOperator(op, tokensByLine.getValue(key), texts.getValue(key), rng, cfg)
                }

            fun tryApply(key: LineKey, light: Boolean): Boolean {
                val current = renderTokens(tokensByLine.getValue(key))
                val baseScore = stats.score
                val close = target != null && (target - baseScore) <= 12.0
                var best: Candidate? = null
                var bestLight = Double.MAX_VALUE
                var bestPair: Pair<Int, Double>? = null
                for (t in 0 until cfg.opTries) {
                    val op = if (light) {
                        rng.choice(listOf("wildcard", "class", "negclass"))
                    } else {
                        chooseOperator(rng, cfg, usage, altQuota, lightSet = close)
                    }
                    val newTokens = build(op, key) ?: continue
                    val rendered = renderTokens(newTokens)
                    if (rendered == current) continue
                    if (!fullmatch(rendered, texts.getValue(key))) continue
                    val trial = LinkedHashMap(tokensByLine)
                    trial[key] = newTokens.toMutableList()
                    val result = evaluate(trial)
                    val tSolutions = result.second
                    val tStats = result.third
                    val tUnique = tSolutions.size == 1
                    if (!tUnique) continue
                    if (tStats.score > hi) continue
                    if (!light && tStats.score < baseScore) continue
                    val candidate = Candidate(tStats.score, newTokens, result.first, tSolutions, tStats, tUnique, op)
                    if (light) {
                        if (candidate.score < bestLight) {
                            bestLight = candidate.score
                            best = candidate
                        }
                    } else {
                        val goal = target ?: hi
                        val over = if (candidate.score <= goal) 0 else 1
                        val keyDist = over to abs(candidate.score - goal)
                        val previous = bestPair
                        val better = previous == null ||
                            keyDist.first < previous.first ||
                            (keyDist.first == previous.first && keyDist.second < previous.second)
                        if (better) {
                            bestPair = keyDist
                            best = candidate
                        }
                        if (abs(candidate.score - goal) <= 1.0) break
                    }
                }
                val chosen = best ?: return false
                tokensByLine[key] = chosen.tokens.toMutableList()
                solver = chosen.solver
                solutions = chosen.solutions
                stats = chosen.stats
                isUnique = chosen.unique
                usage[chosen.op] = (usage[chosen.op] ?: 0) + 1
                accepted++
                return true
            }

            val requireRelax = cfg.requireAllRelaxed && cfg.difficulty != "easy"
            var allRelaxed = true
            if (requireRelax) {
                val keys = tokensByLine.keys.toMutableList()
                rng.shuffle(keys)
                for (key in keys) {
                    if (lineIsLiteral(tokensByLine.getValue(key), texts.getValue(key))) {
                        tryApply(key, light = true)
                    }
                }
                allRelaxed = tokensByLine.all { !lineIsLiteral(it.value, texts.getValue(it.key)) }
            }

            if (allRelaxed) {
                for (i in 0 until cfg.loosen) {
                    val key = rng.choice(tokensByLine.keys.toList())
                    var applied = tryApply(key, light = false)
                    if (!applied) {
                        val others = tokensByLine.keys.toMutableList()
                        rng.shuffle(others)
                        applied = others.any { tryApply(it, light = false) }
                        if (!applied) break
                    }
                    if (reached(stats) && styleOk()) break
                    if (!customWindow && cfg.difficulty == "easy" && accepted >= cfg.easyMaxSteps) break
                    if (cfg.literalRatio != null && literalFraction(tokensByLine, texts) <= cfg.literalRatio!!) break
                }
            }

            val final = evaluate(tokensByLine)
            solutions = final.second
            stats = final.third
            isUnique = solutions.size == 1
            if (isUnique) {
                return buildPuzzle(geo, cfg, cluesFromTokens(tokensByLine, families), solution)
            }
        }
        error("unique generation failed for difficulty ${cfg.difficulty}")
    }
}

class GenerationError(message: String) : RuntimeException(message)
