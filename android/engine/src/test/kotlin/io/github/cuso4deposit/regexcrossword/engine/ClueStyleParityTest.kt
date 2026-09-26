package io.github.cuso4deposit.regexcrossword.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * `clueStyle` must count the same tokens as Python's `hexregex.difficulty`
 * for the same clue set; a mismatch changes the generator's score and makes
 * the unique generator diverge from the reference.
 */
class ClueStyleParityTest {
    private val x = listOf(
        "Y(?:(?:[BEHQ]W)?[EFRX])?(?:O)?",
        "[JPQR][UWXZ](?:[^W]P(?:B.)?)?",
        "[ADEFKMNPRZ]*M[ADEFKMNPRZ]*",
        ".[^P][EJMP][BHJV].NY(?:[^N])?",
        "[^A](?:N[EFIM][^X])?[EHJV](?:OYUD)?",
        "(?:(?:Q)?K[^G])?(?:[^C][GLNP]IZ(?:T|Q|U|H)*)?",
        "(?:.(?:.*J.*Y)?[^N][^P])?",
        "(?:Y(?:X|N|M|F)*)?[^K](?:W.*)?[ESTV]",
        "(?:CS)?(?:.I)?Y",
    )
    private val y = listOf(
        ".*S.*K.*K.*",
        "[ABFHRTUY]*T[ABFHRTUY]*",
        "(?:.*N.*.*B.*)?",
        "[AEMT]T[^A][ADHQ].*K.*",
        "[^F]*L[^F]*",
        "[^U]*N[^U]*",
        ".*M.*X.*",
        ".*W.*N.*X.*S.*",
        "(?:[FLYZ])?.(?:.)?[^H](?:[ACDM])?",
    )
    private val z = listOf(
        "(?:.[GIOR]M)?(?:WZ)?",
        ".*Z.*N.*X.*",
        ".(?:[^E])?(?:D)?(?:[AHMT]E[^G]F)?",
        "(?:ANY|FBG|RP)*",
        "(?:O(?:.)?)?(?:(?:RHV)?(?:BJX)?(?:[CLPY])?)?",
        "(?:S(?:F.)?O.JY)?(?:.)?",
        "[^U]*N[^U]*",
        "(?:K)?[^Y].[^W](?:Y(?:I|J|R|O)*)?",
        "[ABDHKPQTWY]*D[ABDHKPQTWY]*",
    )

    @Test
    fun `token counts match the Python reference`() {
        val style = clueStyle(mapOf("x" to x, "y" to y, "z" to z))
        assertEquals(240.0, style["tokens"], "tokens")
        assertEquals(74.0, style["literals"], "literals")
        assertEquals(78.0, style["repeats"], "repeats")
        assertEquals(48.0, style["classes"], "classes")
        assertEquals(39.0, style["anys"], "anys")
        assertEquals(1.0, style["alternations"], "alternations")
    }
}
