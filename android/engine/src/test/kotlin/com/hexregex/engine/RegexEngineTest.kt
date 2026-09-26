package com.hexregex.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.regex.Pattern
import kotlin.random.Random

class RegexEngineTest {
    private fun singleton(pattern: String, word: String): Boolean =
        RegexEngine.feasible(word.map { setOf(it) }, RegexEngine.compile(pattern))

    private val backrefPatterns = listOf(
        "(..)\\1P+",
        "(...?)\\1*",
        "P+(..)\\1",
        "(.)C\\1X\\1",
        "(.)(.)(.)(.)\\4\\3\\2\\1",
        ".*(.)(.)(.)(.)\\4\\3\\2\\1.*",
        "(a?){2,}",
        "(a*)*",
        "(|a)*",
        "(.)\\1?",
        "(a|b)*abb",
    )

    /** The engine must agree with the platform regex on concrete words. */
    @Test
    fun `singleton feasibility matches the Java engine`() {
        val rng = Random(1234)
        val alphabet = "abCPX"
        for (pattern in backrefPatterns) {
            val compiled = RegexEngine.compile(pattern)
            val java = Pattern.compile(pattern)
            for (length in 0..6) {
                repeat(120) {
                    val word = buildString { repeat(length) { append(alphabet[rng.nextInt(alphabet.length)]) } }
                    assertEquals(
                        java.matcher(word).matches(),
                        RegexEngine.feasible(word.map { setOf(it) }, compiled),
                        "$pattern vs \"$word\"",
                    )
                }
            }
        }
    }

    @Test
    fun `required backreference examples`() {
        val cases = listOf(
            Triple("(...?)\\1*", "ABAB", true),
            Triple("(...?)\\1*", "AB", true),
            Triple("(...?)\\1*", "ABCABC", true),
            Triple("(.)C\\1X\\1", "ACAXA", true),
            Triple("(.)C\\1X\\1", "ACBXA", false),
            Triple("P+(..)\\1", "PPXYXY", true),
            Triple("P+(..)\\1", "PPXYZZ", false),
            Triple("(.)(.)(.)(.)\\4\\3\\2\\1", "ABCDDCBA", true),
            Triple("(.)(.)(.)(.)\\4\\3\\2\\1", "ABCDDBCA", false),
        )
        for ((pattern, word, expected) in cases) {
            assertEquals(expected, singleton(pattern, word), "$pattern / $word")
        }
    }

    @Test
    fun `unset group backreference fails`() {
        assertFalse(singleton("(a)|b\\1", "b"))
        assertTrue(singleton("(a)b\\1", "aba"))
    }

    /** Domain feasibility must equal brute force over the domain product. */
    @Test
    fun `non-singleton feasibility matches brute force`() {
        val patterns = listOf(
            "(.)(.)(.)(.)\\4\\3\\2\\1",
            "(..)\\1P+",
            "(a|b|c)*",
            "[abc]{2,3}",
            "a*b?c*",
            "(.)\\1",
            "(ab|a)+",
            "[^a]*a[^a]*",
            "(a?)(b?)\\1\\2",
        )
        val alphabet = "abc".toList()
        val rng = Random(99)
        for (pattern in patterns) {
            val compiled = RegexEngine.compile(pattern)
            val java = Pattern.compile(pattern)
            for (length in 0..4) {
                repeat(120) {
                    val domains = (0 until length).map {
                        alphabet.shuffled(rng).take(rng.nextInt(1, 4)).toSet()
                    }
                    var expected = false

                    fun rec(i: Int, prefix: String) {
                        if (expected) return
                        if (i == domains.size) {
                            if (java.matcher(prefix).matches()) expected = true
                            return
                        }
                        for (ch in domains[i]) rec(i + 1, prefix + ch)
                    }
                    rec(0, "")

                    assertEquals(expected, RegexEngine.feasible(domains, compiled), "$pattern $domains")
                }
            }
        }
    }

    @Test
    fun `empty string when the pattern allows it`() {
        assertTrue(RegexEngine.feasible(emptyList(), RegexEngine.compile("a*")))
        assertFalse(RegexEngine.feasible(emptyList(), RegexEngine.compile("a+")))
    }

    @Test
    fun `lookahead is rejected`() {
        assertThrows(RegexSyntaxError::class.java) { RegexEngine.compile("(?=a)") }
        assertThrows(RegexSyntaxError::class.java) { RegexEngine.compile("(?!a)") }
    }

    @Test
    fun `anchors are ignored`() {
        assertTrue(singleton("^abc$", "abc"))
    }
}
