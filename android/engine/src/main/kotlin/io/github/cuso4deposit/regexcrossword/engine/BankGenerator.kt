package io.github.cuso4deposit.regexcrossword.engine

import java.io.File

/**
 * Offline generator for the bundled HARD bank.
 *
 * Writes `{"version":..,"base":..,"puzzles":[puzzle,..]}` where `puzzles[i]`
 * is the unique HARD puzzle for `seed = base + i`. Run through the Gradle
 * `generateHardBank` task; the app loads levels below the bank size from this
 * asset and generates later levels on device.
 */
fun main(args: Array<String>) {
    val out = args.getOrNull(0) ?: error("usage: <output.json> [count] [base]")
    val count = args.getOrNull(1)?.toIntOrNull() ?: 50
    val base = args.getOrNull(2)?.toIntOrNull() ?: 3_000_000

    val puzzles = ArrayList<Any?>()
    for (i in 0 until count) {
        val puzzle = Generator.unique(
            GenConfig(edge = 5, difficulty = "hard", seed = base + i, allowBackref = true, targetScore = 76.0),
        )
        puzzles.add(puzzle.toOrderedMap())
        System.err.println("bank: $i/$count seed=${base + i}")
    }
    val root = LinkedHashMap<String, Any?>()
    root["version"] = 6
    root["base"] = base
    root["puzzles"] = puzzles
    File(out).writeText(PyJson.dumpsFile(root))
    System.err.println("wrote $out")
}
