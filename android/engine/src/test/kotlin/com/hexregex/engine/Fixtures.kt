package com.hexregex.engine

/** Shared helpers for the CLI-generated fixtures under `android/fixtures`. */
object Fixtures {
    val names: List<String> = listOf(
        "rect_easy_1000",
        "hex_medium_1000",
        "hex_hard_1000",
    )

    fun text(name: String): String {
        val stream = Fixtures::class.java.getResourceAsStream("/$name.json")
            ?: error("missing fixture $name.json")
        return stream.bufferedReader().readText()
    }

    fun alternate(name: String): String {
        val stream = Fixtures::class.java.getResourceAsStream("/$name.alt.json")
            ?: error("missing fixture $name.alt.json")
        return stream.bufferedReader().readText()
    }

    fun puzzle(name: String): Puzzle = Puzzle.fromJson(text(name))
}
