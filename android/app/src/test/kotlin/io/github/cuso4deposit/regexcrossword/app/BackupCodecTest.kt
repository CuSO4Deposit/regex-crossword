package io.github.cuso4deposit.regexcrossword.app

import io.github.cuso4deposit.regexcrossword.engine.Cell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCodecTest {
    @Test
    fun emptyBackupHasFixedDigestAndRoundTrips() {
        val payload = BackupPayload(
            progress = emptyList(),
            lastPosition = null,
            keyboardLayout = KeyboardLayout.ALPHABETICAL,
        )

        val encoded = BackupCodec.encode(payload)

        assertEquals(
            "b7f631dea0b344eb9d0b1f5635f718f47eaf6de46d7dc7231cdb23eba8d1d12c",
            encoded.hash,
        )
        assertEquals(payload, BackupCodec.decode(encoded.text).payload)
    }

    @Test
    fun fullBackupRoundTripsAndSortsExternalRecords() {
        val easy = BackupProgress(
            puzzle = PuzzleId(6, Difficulty.EASY, 1_000_002),
            grid = linkedMapOf(
                Cell(2, 3) to 'D',
                Cell(0, 1) to 'B',
                Cell(0, 0) to 'A',
            ),
            notes = linkedMapOf(
                Cell(3, 2) to linkedSetOf('Z', 'A'),
                Cell(1, 4) to linkedSetOf('M'),
            ),
            selected = Cell(4, 4),
            completed = false,
            solution = null,
            elapsedSeconds = Long.MAX_VALUE,
        )
        val hard = BackupProgress(
            puzzle = PuzzleId(5, Difficulty.HARD, 3_000_001),
            grid = mapOf(
                Cell(0, 0) to 'A',
                Cell(1, 0) to 'B',
            ),
            notes = emptyMap(),
            selected = null,
            completed = true,
            solution = mapOf(
                Cell(0, 0) to 'A',
                Cell(1, 0) to 'B',
            ),
            elapsedSeconds = 125,
        )
        val payload = BackupPayload(
            progress = listOf(hard, easy),
            lastPosition = easy.puzzle,
            keyboardLayout = KeyboardLayout.COLEMAK,
        )

        val encoded = BackupCodec.encode(payload)
        val decoded = BackupCodec.decode(encoded.text)

        assertEquals(payload.progress, decoded.payload.progress)
        assertEquals(payload.lastPosition, decoded.payload.lastPosition)
        assertEquals(payload.keyboardLayout, decoded.payload.keyboardLayout)
        assertTrue(encoded.text.indexOf("\"difficulty\": \"easy\"") > 0)
        assertTrue(encoded.text.indexOf("\"difficulty\": \"hard\"") > 0)
        assertTrue(
            encoded.text.indexOf("\"difficulty\": \"hard\"") <
                encoded.text.indexOf("\"difficulty\": \"easy\""),
        )
        assertEquals(
            listOf(Cell(0, 0), Cell(0, 1), Cell(2, 3)),
            decoded.payload.progress.last().grid.keys.toList(),
        )
    }

    @Test
    fun canonicalDigestVectorCoversUnicodeEscapesAndLongIntegers() {
        val source = StrictJson.parse(
            """
            {
              "nested": { "z": 1, "a": 2 },
              "big": 9223372036854775807,
              "a": "\u4e2d\u6587\"\\\u0001\n"
            }
            """.trimIndent(),
        )
        val reordered = StrictJson.parse(
            """{"a":"中文\"\\\u0001\u000a","big":9223372036854775807,"nested":{"a":2,"z":1}}""",
        )
        val expected =
            """{"a":"中文\"\\\u0001\u000a","big":9223372036854775807,"nested":{"a":2,"z":1}}"""

        assertEquals(expected, CanonicalJson.canonical(source))
        assertEquals(expected, CanonicalJson.canonical(reordered))
        assertEquals(
            "709e715c1bc5ccb03fcedebf9753ed7b1e68b427f981a4c47b621d287c384f46",
            CanonicalJson.sha256(source),
        )

        val ordered = StrictJson.parse("""{"items":[1,2,3]}""")
        val reorderedArray = StrictJson.parse("""{"items":[3,2,1]}""")
        assertTrue(CanonicalJson.sha256(ordered) != CanonicalJson.sha256(reorderedArray))
    }

    @Test
    fun whitespaceAndObjectKeyOrderDoNotChangeArtifactValidation() {
        val artifact = """
            {
              "payload": {
                "settings": { "keyboardLayout": "alphabetical" },
                "progress": [],
                "lastPosition": null
              },
              "schemaVersion": 1,
              "hash": {
                "value": "b7f631dea0b344eb9d0b1f5635f718f47eaf6de46d7dc7231cdb23eba8d1d12c",
                "algorithm": "sha256"
              },
              "format": "regex-crossword-backup"
            }
        """.trimIndent()

        assertEquals(emptyList<BackupProgress>(), BackupCodec.decode(artifact).payload.progress)
    }

    @Test
    fun changedPayloadFailsBeforeDecoding() {
        val encoded = emptyArtifact()
        val changed = encoded.replace("\"alphabetical\"", "\"qwerty\"")

        assertThrows(BackupFormatException::class.java) {
            BackupCodec.decode(changed)
        }
    }

    @Test
    fun unsupportedSchemaIsRejectedAfterHashVerification() {
        val artifact = artifactWithSchema(2)

        val error = assertThrows(BackupFormatException::class.java) {
            BackupCodec.decode(artifact)
        }

        assertTrue(error.message!!.contains("unsupported schemaVersion 2"))
    }

    @Test
    fun malformedArtifactsAreRejectedAsAWhole() {
        val encoded = emptyArtifact()
        val badHash = encoded.replaceFirst(Regex("(?<=\"value\": \")[0-9a-f]"), "0")
        val truncated = encoded.dropLast(8)

        assertThrows(BackupFormatException::class.java) { BackupCodec.decode(badHash) }
        assertThrows(BackupFormatException::class.java) { BackupCodec.decode(truncated) }
        assertThrows(BackupFormatException::class.java) {
            BackupCodec.decode("""{"format":"x","format":"y"}""")
        }
        assertThrows(BackupFormatException::class.java) {
            StrictJson.parse("""{"text":"\uD800"}""")
        }
        assertThrows(BackupFormatException::class.java) {
            StrictJson.parse("""{"number":1.5}""")
        }
    }

    @Test
    fun invalidUtf8IsRejected() {
        assertThrows(BackupFormatException::class.java) {
            BackupCodec.decodeUtf8(byteArrayOf(0xC3.toByte()))
        }
        assertThrows(BackupFormatException::class.java) {
            BackupCodec.decodeUtf8(ByteArray(BACKUP_MAX_BYTES + 1))
        }
    }

    @Test
    fun duplicatePuzzlesCellsAndOutOfRangeCoordinatesAreRejected() {
        val progress = BackupProgress(
            puzzle = PuzzleId(6, Difficulty.EASY, 1_000_000),
            grid = mapOf(Cell(0, 0) to 'A'),
            notes = emptyMap(),
            selected = null,
            completed = false,
            solution = null,
            elapsedSeconds = 0,
        )

        assertThrows(BackupFormatException::class.java) {
            BackupValidator.validate(
                BackupPayload(
                    progress = listOf(progress, progress),
                    lastPosition = null,
                    keyboardLayout = KeyboardLayout.default,
                ),
            )
        }
        assertThrows(BackupFormatException::class.java) {
            BackupValidator.validate(
                BackupPayload(
                    progress = listOf(progress.copy(grid = mapOf(Cell(5, 0) to 'A'))),
                    lastPosition = null,
                    keyboardLayout = KeyboardLayout.default,
                ),
            )
        }
        assertThrows(BackupFormatException::class.java) {
            BackupCodec.decode(
                artifactWithPayload(
                    """
                    {
                      "progress": [{
                        "puzzle": {"version": 6, "difficulty": "easy", "seed": 1000000},
                        "grid": [
                          {"r": 0, "c": 0, "value": "A"},
                          {"r": 0, "c": 0, "value": "B"}
                        ],
                        "notes": [],
                        "selected": null,
                        "completed": false,
                        "solution": null,
                        "elapsedSeconds": 0
                      }],
                      "lastPosition": null,
                      "settings": {"keyboardLayout": "alphabetical"}
                    }
                    """.trimIndent(),
                ),
            )
        }
    }

    @Test
    fun unknownFieldsAndNegativeElapsedTimeAreRejected() {
        assertThrows(BackupFormatException::class.java) {
            BackupCodec.decode(
                artifactWithPayload(
                    """{"progress":[],"lastPosition":null,"settings":{"keyboardLayout":"alphabetical"}}""",
                    extraOuter = ""","extra":true""",
                ),
            )
        }
        assertThrows(BackupFormatException::class.java) {
            BackupCodec.decode(
                artifactWithPayload(
                    """
                    {
                      "progress": [{
                        "puzzle": {"version": 6, "difficulty": "easy", "seed": 1000000},
                        "grid": [],
                        "notes": [],
                        "selected": null,
                        "completed": false,
                        "solution": null,
                        "elapsedSeconds": -1
                      }],
                      "lastPosition": null,
                      "settings": {"keyboardLayout": "alphabetical"}
                    }
                    """.trimIndent(),
                ),
            )
        }
    }

    private fun emptyArtifact(): String =
        artifactWithPayload(
            """{"progress":[],"lastPosition":null,"settings":{"keyboardLayout":"alphabetical"}}""",
        )

    private fun artifactWithSchema(schemaVersion: Int): String =
        artifactWithPayload(
            """{"progress":[],"lastPosition":null,"settings":{"keyboardLayout":"alphabetical"}}""",
            schemaVersion = schemaVersion,
        )

    private fun artifactWithPayload(
        payload: String,
        schemaVersion: Int = 1,
        extraOuter: String = "",
    ): String {
        val source =
            """{"format":"$BACKUP_FORMAT","schemaVersion":$schemaVersion,"payload":$payload}"""
        val hash = CanonicalJson.sha256(StrictJson.parse(source))
        return """
            {
              "format": "$BACKUP_FORMAT",
              "schemaVersion": $schemaVersion,
              "hash": {"algorithm": "sha256", "value": "$hash"},
              "payload": $payload$extraOuter
            }
        """.trimIndent()
    }
}
