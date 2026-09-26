package io.github.cuso4deposit.regexcrossword.engine

/**
 * A parsed puzzle, matching the JSON the `hexregex` CLI emits.
 *
 * Hexagonal: `kind="hex"`, `edge`, `x`, `y`, `z`.
 * Rectangular: `kind="rect"`, `rows`, `cols`, `x`, `y`.
 * `solution` is one valid grid (rows of single-character strings); it is used
 * for hints/reveal only and must never be compared against a player's grid.
 */
class Puzzle(
    val kind: String,
    val edge: Int?,
    val rows: Int?,
    val cols: Int?,
    val author: String,
    val name: String,
    val x: List<String>,
    val y: List<String>,
    val z: List<String>,
    val solution: List<List<String>>?,
) {
    val geometry: Geometry by lazy {
        if (kind == "rect") {
            RectGeometry(requireNotNull(rows), requireNotNull(cols))
        } else {
            HexGeometry(requireNotNull(edge))
        }
    }

    init {
        geometry.validate()
        require(geometry.families.contains("x") && x.isNotEmpty()) { "missing x clues" }
        require(geometry.families.contains("y") && y.isNotEmpty()) { "missing y clues" }
        if (geometry.families.contains("z")) {
            require(z.isNotEmpty()) { "missing z clues" }
        }
    }

    fun clue(family: String, index: Int): String = when (family) {
        "x" -> x[index]
        "y" -> y[index]
        "z" -> z[index]
        else -> error("unknown family $family")
    }

    /** The stored solution as a cell -> letter map. */
    fun storedSolutionGrid(): Map<Cell, Char> {
        val rowsList = solution ?: return emptyMap()
        val out = HashMap<Cell, Char>()
        for ((r, row) in rowsList.withIndex()) {
            for ((c, ch) in row.withIndex()) {
                out[Cell(r, c)] = if (ch.isEmpty()) ' ' else ch[0]
            }
        }
        return out
    }

    /** Ordered map matching `hexregex.generator._puzzle_dict`. */
    fun toOrderedMap(): LinkedHashMap<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        map["kind"] = kind
        map["author"] = author
        map["name"] = name
        map["solution"] = solution
        if (kind == "rect") {
            map["rows"] = rows
            map["cols"] = cols
        } else {
            map["edge"] = edge
        }
        map["x"] = x
        map["y"] = y
        if (geometry.families.contains("z")) map["z"] = z
        return map
    }

    /** Exact bytes the CLI would write for this puzzle. */
    fun toJson(): String = PyJson.dumpsFile(toOrderedMap())

    companion object {
        fun fromJson(text: String): Puzzle = fromMap(MiniJson.parseObject(text))

        fun fromMap(map: Map<String, Any?>): Puzzle {
            val hasEdge = map["edge"] != null
            val kind = (map["kind"] as? String) ?: if (hasEdge) "hex" else "rect"
            fun strings(key: String): List<String> =
                (map[key] as? List<*>)?.map { it.toString() } ?: emptyList()

            val solution = (map["solution"] as? List<*>)?.map { row ->
                (row as List<*>).map { it?.toString() ?: "" }
            }
            return Puzzle(
                kind = kind,
                edge = (map["edge"] as? Number)?.toInt(),
                rows = (map["rows"] as? Number)?.toInt(),
                cols = (map["cols"] as? Number)?.toInt(),
                author = (map["author"] as? String) ?: "",
                name = (map["name"] as? String) ?: "",
                x = strings("x"),
                y = strings("y"),
                z = strings("z"),
                solution = solution,
            )
        }
    }
}
