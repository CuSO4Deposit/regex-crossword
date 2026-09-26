package com.hexregex.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Cross-checks PyRandom against vectors produced by CPython 3.14. */
class PyRandomTest {
    private fun vectors(): Map<String, Any?> {
        val stream = Fixtures::class.java.getResourceAsStream("/pyrng_vectors.json")
            ?: error("missing pyrng_vectors.json")
        return MiniJson.parseObject(stream.bufferedReader().readText())
    }

    private fun longs(value: Any?): List<Long> = (value as List<*>).map { (it as Number).toLong() }

    private fun strings(value: Any?): List<String> = (value as List<*>).map { it.toString() }

    @Test
    fun `matches CPython for every recorded seed`() {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val extras = alphabet.filter { it != 'A' }.toList()
        for ((seedText, entryValue) in vectors()) {
            val seed = seedText.toLong()
            @Suppress("UNCHECKED_CAST")
            val entry = entryValue as Map<String, Any?>

            val random = PyRandom(seed)
            for ((i, expected) in (entry["random"] as List<*>).withIndex()) {
                assertEquals(
                    (expected as Number).toDouble(),
                    random.nextDouble(),
                    0.0,
                    "$seed random[$i]",
                )
            }

            val bits = PyRandom(seed)
            assertEquals(longs(entry["bits5"]), (0 until 10).map { bits.getrandbits(5).toLong() }, "$seed bits")

            val choice = PyRandom(seed)
            assertEquals(
                strings(entry["choice26"]),
                (0 until 61).map { choice.choice(alphabet.toList()).toString() },
                "$seed choice",
            )

            val shuffle = PyRandom(seed)
            val list = (0 until 27).toMutableList()
            shuffle.shuffle(list)
            assertEquals(longs(entry["shuffle27"]), list.map { it.toLong() }, "$seed shuffle")

            val randint = PyRandom(seed)
            assertEquals(longs(entry["randint03"]), (0 until 8).map { randint.randint(0, 3).toLong() }, "$seed randint")

            val sampleSmall = PyRandom(seed)
            val expectedSmall = (entry["sample9_4"] as List<*>).map { longs(it) }
            assertEquals(
                expectedSmall,
                (0 until 5).map { sampleSmall.sample((0 until 9).toList(), 4).map { it.toLong() } },
                "$seed sample9_4",
            )

            val sampleBig = PyRandom(seed)
            val expectedBig = (entry["sample25_2"] as List<*>).map { strings(it) }
            assertEquals(
                expectedBig,
                (0 until 5).map { sampleBig.sample(extras, 2).map { ch -> ch.toString() } },
                "$seed sample25_2",
            )
        }
    }
}
