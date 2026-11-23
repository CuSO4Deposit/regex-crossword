package io.github.cuso4deposit.regexcrossword.app

import io.github.cuso4deposit.regexcrossword.engine.Cell
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

const val BACKUP_FORMAT = "regex-crossword-backup"
const val BACKUP_SCHEMA_VERSION = 1
const val BACKUP_MAX_BYTES = 32 * 1024 * 1024

data class BackupProgress(
    val puzzle: PuzzleId,
    val grid: Map<Cell, Char>,
    val notes: Map<Cell, Set<Char>>,
    val selected: Cell?,
    val completed: Boolean,
    val solution: Map<Cell, Char>?,
    val elapsedSeconds: Long,
)

data class BackupPayload(
    val progress: List<BackupProgress>,
    val lastPosition: PuzzleId?,
    val keyboardLayout: KeyboardLayout,
)

data class DecodedBackup(
    val payload: BackupPayload,
    val hash: String,
)

class BackupFormatException(message: String) : IllegalArgumentException(message)

private fun fail(message: String): Nothing = throw BackupFormatException(message)

internal object BackupValidator {
    fun validate(payload: BackupPayload) {
        val identities = HashSet<PuzzleId>()
        for (progress in payload.progress) {
            validateIdentity(progress.puzzle, "progress puzzle")
            if (!identities.add(progress.puzzle)) {
                fail("duplicate puzzle identity ${progress.puzzle}")
            }
            validateGrid(progress.puzzle.difficulty, progress.grid, "grid")
            for ((cell, letters) in progress.notes) {
                validateCell(progress.puzzle.difficulty, cell, "notes cell")
                if (letters.isEmpty()) fail("notes at $cell must not be empty")
                for (letter in letters) validateLetter(letter, "notes")
            }
            progress.selected?.let {
                validateCell(progress.puzzle.difficulty, it, "selected cell")
            }
            if (progress.elapsedSeconds < 0) fail("elapsedSeconds must be non-negative")
            if (progress.completed && progress.solution == null) {
                fail("completed progress must include its solution")
            }
            if (progress.completed && progress.solution?.isEmpty() == true) {
                fail("completed progress must include a non-empty solution")
            }
            if (!progress.completed && progress.solution != null) {
                fail("an unfinished puzzle must have a null solution")
            }
            progress.solution?.let {
                validateGrid(progress.puzzle.difficulty, it, "solution")
            }
        }
        payload.lastPosition?.let { validateIdentity(it, "lastPosition") }
    }

    private fun validateIdentity(id: PuzzleId, label: String) {
        if (id.version !in 0..Int.MAX_VALUE) fail("$label version is out of range")
        if (id.seed !in 0..Int.MAX_VALUE) fail("$label seed is out of range")
    }

    private fun validateGrid(
        difficulty: Difficulty,
        grid: Map<Cell, Char>,
        label: String,
    ) {
        for ((cell, letter) in grid) {
            validateCell(difficulty, cell, "$label cell")
            validateLetter(letter, label)
        }
    }

    private fun validateCell(difficulty: Difficulty, cell: Cell, label: String) {
        if (!isBoardCell(difficulty, cell)) fail("$label ${cell.r},${cell.c} is out of range")
    }

    private fun validateLetter(letter: Char, label: String) {
        if (letter !in 'A'..'Z') fail("$label contains an invalid letter")
    }
}

internal fun isBoardCell(difficulty: Difficulty, cell: Cell): Boolean =
    when (difficulty) {
        Difficulty.EASY -> cell.r in 0..4 && cell.c in 0..4
        Difficulty.MEDIUM, Difficulty.HARD -> {
            if (cell.r !in 0..8) {
                false
            } else {
                cell.c in 0 until 5 + minOf(cell.r, 8 - cell.r)
            }
        }
    }

internal fun Difficulty.schemaName(): String = name.lowercase(Locale.ROOT)

internal fun difficultyFromSchema(value: String): Difficulty =
    Difficulty.entries.firstOrNull { it.schemaName() == value }
        ?: fail("unknown difficulty '$value'")

internal fun KeyboardLayout.schemaName(): String = name.lowercase(Locale.ROOT)

internal fun keyboardLayoutFromSchema(value: String): KeyboardLayout =
    KeyboardLayout.entries.firstOrNull { it.schemaName() == value }
        ?: fail("unknown keyboardLayout '$value'")

internal class EncodedBackup(
    val text: String,
    val hash: String,
)

internal object BackupCodec {
    fun encode(payload: BackupPayload): EncodedBackup {
        BackupValidator.validate(payload)
        val payloadJson = payloadToJson(payload)
        val hashSource = JsonObject(
            linkedMapOf(
                "format" to JsonString(BACKUP_FORMAT),
                "schemaVersion" to JsonNumber(BACKUP_SCHEMA_VERSION.toLong()),
                "payload" to payloadJson,
            ),
        )
        val hash = CanonicalJson.sha256(hashSource)
        val root = JsonObject(
            linkedMapOf(
                "format" to JsonString(BACKUP_FORMAT),
                "schemaVersion" to JsonNumber(BACKUP_SCHEMA_VERSION.toLong()),
                "hash" to JsonObject(
                    linkedMapOf(
                        "algorithm" to JsonString("sha256"),
                        "value" to JsonString(hash),
                    ),
                ),
                "payload" to payloadJson,
            ),
        )
        return EncodedBackup(JsonWriter.pretty(root), hash)
    }

    fun decode(text: String): DecodedBackup {
        if (text.toByteArray(StandardCharsets.UTF_8).size > BACKUP_MAX_BYTES) {
            fail("backup exceeds the 32 MiB limit")
        }
        val root = StrictJson.parse(text) as? JsonObject
            ?: fail("backup root must be an object")
        requireExactKeys(root, "backup", "format", "schemaVersion", "hash", "payload")

        val format = requireString(root.required("format"), "format")
        if (format != BACKUP_FORMAT) fail("unrecognized backup format")

        val hashObject = root.required("hash") as? JsonObject
            ?: fail("hash must be an object")
        requireExactKeys(hashObject, "hash", "algorithm", "value")
        val algorithm = requireString(hashObject.required("algorithm"), "hash.algorithm")
        if (algorithm != "sha256") fail("unsupported hash algorithm '$algorithm'")
        val claimedHash = requireString(hashObject.required("value"), "hash.value")
        if (!HASH_PATTERN.matches(claimedHash)) fail("hash.value must be 64 lowercase hex digits")

        val hashSource = JsonObject(
            linkedMapOf(
                "format" to root.required("format"),
                "schemaVersion" to root.required("schemaVersion"),
                "payload" to root.required("payload"),
            ),
        )
        val actualHash = CanonicalJson.sha256(hashSource)
        if (!MessageDigest.isEqual(
                actualHash.toByteArray(StandardCharsets.US_ASCII),
                claimedHash.toByteArray(StandardCharsets.US_ASCII),
            )
        ) {
            fail("backup hash does not match its contents")
        }

        val schemaVersion = requireInt(
            root.required("schemaVersion"),
            "schemaVersion",
            Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong(),
        )
        if (schemaVersion != BACKUP_SCHEMA_VERSION.toLong()) {
            fail("unsupported schemaVersion $schemaVersion")
        }

        val payload = payloadFromJson(root.required("payload"))
        BackupValidator.validate(payload)
        return DecodedBackup(payload, claimedHash)
    }

    fun decodeUtf8(bytes: ByteArray): String {
        if (bytes.size > BACKUP_MAX_BYTES) fail("backup exceeds the 32 MiB limit")
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            fail("backup is not valid UTF-8")
        }
    }

    private fun payloadToJson(payload: BackupPayload): JsonObject {
        val progress = payload.progress.sortedWith(
            compareBy<BackupProgress>(
                { it.puzzle.version },
                { it.puzzle.difficulty.ordinal },
                { it.puzzle.seed },
            ),
        )
        return JsonObject(
            linkedMapOf(
                "progress" to JsonArray(progress.map(::progressToJson)),
                "lastPosition" to identityToJson(payload.lastPosition),
                "settings" to JsonObject(
                    linkedMapOf(
                        "keyboardLayout" to JsonString(payload.keyboardLayout.schemaName()),
                    ),
                ),
            ),
        )
    }

    private fun progressToJson(progress: BackupProgress): JsonObject =
        JsonObject(
            linkedMapOf(
                "puzzle" to identityToJson(progress.puzzle),
                "grid" to gridToJson(progress.grid),
                "notes" to JsonArray(
                    progress.notes.entries
                        .sortedWith(compareBy({ it.key.r }, { it.key.c }))
                        .map { (cell, letters) ->
                            JsonObject(
                                linkedMapOf(
                                    "r" to JsonNumber(cell.r.toLong()),
                                    "c" to JsonNumber(cell.c.toLong()),
                                    "letters" to JsonArray(
                                        letters.sorted().map { JsonString(it.toString()) },
                                    ),
                                ),
                            )
                        },
                ),
                "selected" to (progress.selected?.let { cellToJson(it) } ?: JsonNull),
                "completed" to JsonBoolean(progress.completed),
                "solution" to (progress.solution?.let { gridToJson(it) } ?: JsonNull),
                "elapsedSeconds" to JsonNumber(progress.elapsedSeconds),
            ),
        )

    private fun identityToJson(id: PuzzleId?): BackupJson =
        id?.let {
            JsonObject(
                linkedMapOf(
                    "version" to JsonNumber(it.version.toLong()),
                    "difficulty" to JsonString(it.difficulty.schemaName()),
                    "seed" to JsonNumber(it.seed.toLong()),
                ),
            )
        } ?: JsonNull

    private fun gridToJson(grid: Map<Cell, Char>): JsonArray =
        JsonArray(
            grid.entries
                .sortedWith(compareBy({ it.key.r }, { it.key.c }))
                .map { (cell, letter) ->
                    JsonObject(
                        linkedMapOf(
                            "r" to JsonNumber(cell.r.toLong()),
                            "c" to JsonNumber(cell.c.toLong()),
                            "value" to JsonString(letter.toString()),
                        ),
                    )
                },
        )

    private fun cellToJson(cell: Cell): JsonObject =
        JsonObject(
            linkedMapOf(
                "r" to JsonNumber(cell.r.toLong()),
                "c" to JsonNumber(cell.c.toLong()),
            ),
        )

    private fun payloadFromJson(node: BackupJson): BackupPayload {
        val obj = asObject(node, "payload")
        requireExactKeys(obj, "payload", "progress", "lastPosition", "settings")
        val progressNode = obj.required("progress") as? JsonArray
            ?: fail("payload.progress must be an array")
        val progress = progressNode.items.mapIndexed { index, item ->
            progressFromJson(item, "payload.progress[$index]")
        }
        val lastPosition = identityFromJson(obj.required("lastPosition"), "payload.lastPosition")
        val settings = asObject(obj.required("settings"), "payload.settings")
        requireExactKeys(settings, "payload.settings", "keyboardLayout")
        val keyboardLayout = keyboardLayoutFromSchema(
            requireString(settings.required("keyboardLayout"), "payload.settings.keyboardLayout"),
        )
        return BackupPayload(progress, lastPosition, keyboardLayout)
    }

    private fun progressFromJson(node: BackupJson, label: String): BackupProgress {
        val obj = asObject(node, label)
        requireExactKeys(
            obj,
            label,
            "puzzle",
            "grid",
            "notes",
            "selected",
            "completed",
            "solution",
            "elapsedSeconds",
        )
        val puzzle = identityFromJson(obj.required("puzzle"), "$label.puzzle")
            ?: fail("$label.puzzle must not be null")
        val grid = cellsFromJson(obj.required("grid"), "$label.grid")
        val notes = notesFromJson(obj.required("notes"), "$label.notes")
        val selected = cellFromJson(obj.required("selected"), "$label.selected")
        val completed = (obj.required("completed") as? JsonBoolean)?.value
            ?: fail("$label.completed must be a boolean")
        val solutionNode = obj.required("solution")
        val solution = if (solutionNode is JsonNull) {
            null
        } else {
            cellsFromJson(solutionNode, "$label.solution")
        }
        val elapsed = requireInt(
            obj.required("elapsedSeconds"),
            "$label.elapsedSeconds",
            0..Long.MAX_VALUE,
        )
        return BackupProgress(
            puzzle = puzzle,
            grid = grid,
            notes = notes,
            selected = selected,
            completed = completed,
            solution = solution,
            elapsedSeconds = elapsed,
        )
    }

    private fun identityFromJson(node: BackupJson, label: String): PuzzleId? {
        if (node is JsonNull) return null
        val obj = asObject(node, label)
        requireExactKeys(obj, label, "version", "difficulty", "seed")
        val version = requireInt(
            obj.required("version"),
            "$label.version",
            0..Int.MAX_VALUE.toLong(),
        ).toInt()
        val difficulty = difficultyFromSchema(
            requireString(obj.required("difficulty"), "$label.difficulty"),
        )
        val seed = requireInt(
            obj.required("seed"),
            "$label.seed",
            0..Int.MAX_VALUE.toLong(),
        ).toInt()
        return PuzzleId(version, difficulty, seed)
    }

    private fun cellsFromJson(node: BackupJson, label: String): Map<Cell, Char> {
        val array = node as? JsonArray ?: fail("$label must be an array")
        val cells = LinkedHashMap<Cell, Char>()
        array.items.forEachIndexed { index, item ->
            val itemLabel = "$label[$index]"
            val obj = asObject(item, itemLabel)
            requireExactKeys(obj, itemLabel, "r", "c", "value")
            val cell = Cell(
                requireInt(obj.required("r"), "$itemLabel.r", 0..Int.MAX_VALUE.toLong()).toInt(),
                requireInt(obj.required("c"), "$itemLabel.c", 0..Int.MAX_VALUE.toLong()).toInt(),
            )
            val value = requireString(obj.required("value"), "$itemLabel.value")
            if (value.length != 1 || value[0] !in 'A'..'Z') {
                fail("$itemLabel.value must be one uppercase letter")
            }
            if (cells.put(cell, value[0]) != null) fail("$label contains duplicate cell $cell")
        }
        return cells
    }

    private fun notesFromJson(node: BackupJson, label: String): Map<Cell, Set<Char>> {
        val array = node as? JsonArray ?: fail("$label must be an array")
        val notes = LinkedHashMap<Cell, Set<Char>>()
        array.items.forEachIndexed { index, item ->
            val itemLabel = "$label[$index]"
            val obj = asObject(item, itemLabel)
            requireExactKeys(obj, itemLabel, "r", "c", "letters")
            val cell = Cell(
                requireInt(obj.required("r"), "$itemLabel.r", 0..Int.MAX_VALUE.toLong()).toInt(),
                requireInt(obj.required("c"), "$itemLabel.c", 0..Int.MAX_VALUE.toLong()).toInt(),
            )
            val letterArray = obj.required("letters") as? JsonArray
                ?: fail("$itemLabel.letters must be an array")
            val letters = LinkedHashSet<Char>()
            letterArray.items.forEachIndexed { letterIndex, letterNode ->
                val letter = requireString(letterNode, "$itemLabel.letters[$letterIndex]")
                if (letter.length != 1 || letter[0] !in 'A'..'Z') {
                    fail("$itemLabel.letters[$letterIndex] must be one uppercase letter")
                }
                if (!letters.add(letter[0])) fail("$itemLabel.letters contains a duplicate")
            }
            if (letters.isEmpty()) fail("$itemLabel.letters must not be empty")
            if (notes.put(cell, letters) != null) fail("$label contains duplicate cell $cell")
        }
        return notes
    }

    private fun cellFromJson(node: BackupJson, label: String): Cell? {
        if (node is JsonNull) return null
        val obj = asObject(node, label)
        requireExactKeys(obj, label, "r", "c")
        return Cell(
            requireInt(obj.required("r"), "$label.r", 0..Int.MAX_VALUE.toLong()).toInt(),
            requireInt(obj.required("c"), "$label.c", 0..Int.MAX_VALUE.toLong()).toInt(),
        )
    }

    private fun asObject(node: BackupJson, label: String): JsonObject =
        node as? JsonObject ?: fail("$label must be an object")

    private fun requireExactKeys(obj: JsonObject, label: String, vararg expected: String) {
        if (obj.fields.keys != expected.toSet()) {
            val missing = expected.filterNot { it in obj.fields }
            val unknown = obj.fields.keys.filterNot { it in expected }
            fail("$label has missing keys $missing or unknown keys $unknown")
        }
    }

    private fun requireString(node: BackupJson, label: String): String =
        (node as? JsonString)?.value ?: fail("$label must be a string")

    private fun requireInt(node: BackupJson, label: String, range: LongRange): Long {
        val value = (node as? JsonNumber)?.value ?: fail("$label must be an integer")
        if (value !in range) fail("$label is out of range")
        return value
    }

    private fun JsonObject.required(key: String): BackupJson =
        fields[key] ?: fail("missing key '$key'")

    private fun fail(message: String): Nothing = throw BackupFormatException(message)

    private val HASH_PATTERN = Regex("[0-9a-f]{64}")
}

internal sealed interface BackupJson

internal data class JsonObject(val fields: LinkedHashMap<String, BackupJson>) : BackupJson

internal data class JsonArray(val items: List<BackupJson>) : BackupJson

internal data class JsonString(val value: String) : BackupJson

internal data class JsonNumber(val value: Long) : BackupJson

internal data class JsonBoolean(val value: Boolean) : BackupJson

internal data object JsonNull : BackupJson

internal object CanonicalJson {
    fun canonical(value: BackupJson): String = buildString {
        appendCanonical(value)
    }

    fun sha256(value: BackupJson): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical(value).toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun StringBuilder.appendCanonical(value: BackupJson) {
        when (value) {
            is JsonObject -> {
                append('{')
                value.fields.keys.sorted().forEachIndexed { index, key ->
                    if (index > 0) append(',')
                    appendString(key)
                    append(':')
                    appendCanonical(value.fields.getValue(key))
                }
                append('}')
            }
            is JsonArray -> {
                append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    appendCanonical(item)
                }
                append(']')
            }
            is JsonString -> appendString(value.value)
            is JsonNumber -> append(value.value)
            is JsonBoolean -> append(if (value.value) "true" else "false")
            JsonNull -> append("null")
        }
    }

    private fun StringBuilder.appendString(value: String) {
        append('"')
        for (ch in value) {
            when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch < '\u0020' -> {
                    append("\\u00")
                    append(HEX[(ch.code shr 4) and 0xF])
                    append(HEX[ch.code and 0xF])
                }
                else -> append(ch)
            }
        }
        append('"')
    }

    private const val HEX = "0123456789abcdef"
}

internal object JsonWriter {
    fun pretty(value: BackupJson): String = buildString {
        appendPretty(value, 0)
        append('\n')
    }

    private fun StringBuilder.appendPretty(value: BackupJson, depth: Int) {
        when (value) {
            is JsonObject -> {
                if (value.fields.isEmpty()) {
                    append("{}")
                    return
                }
                append("{\n")
                value.fields.entries.forEachIndexed { index, (key, child) ->
                    appendIndent(depth + 1)
                    appendQuoted(key)
                    append(": ")
                    appendPretty(child, depth + 1)
                    if (index != value.fields.size - 1) append(',')
                    append('\n')
                }
                appendIndent(depth)
                append('}')
            }
            is JsonArray -> {
                if (value.items.isEmpty()) {
                    append("[]")
                    return
                }
                append("[\n")
                value.items.forEachIndexed { index, child ->
                    appendIndent(depth + 1)
                    appendPretty(child, depth + 1)
                    if (index != value.items.size - 1) append(',')
                    append('\n')
                }
                appendIndent(depth)
                append(']')
            }
            is JsonString -> appendQuoted(value.value)
            is JsonNumber -> append(value.value)
            is JsonBoolean -> append(if (value.value) "true" else "false")
            JsonNull -> append("null")
        }
    }

    private fun StringBuilder.appendQuoted(value: String) {
        append('"')
        for (ch in value) {
            when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch < '\u0020' -> {
                    append("\\u00")
                    append("0123456789abcdef"[(ch.code shr 4) and 0xF])
                    append("0123456789abcdef"[ch.code and 0xF])
                }
                else -> append(ch)
            }
        }
        append('"')
    }

    private fun StringBuilder.appendIndent(depth: Int) {
        repeat(depth) { append("  ") }
    }
}

internal object StrictJson {
    private const val MAX_DEPTH = 64

    fun parse(text: String): BackupJson {
        val parser = Parser(text)
        parser.skipWhitespace()
        val value = parser.parseValue(0)
        parser.skipWhitespace()
        if (!parser.atEnd()) throw BackupFormatException("trailing JSON content")
        return value
    }

    private class Parser(private val text: String) {
        private var offset = 0

        fun atEnd(): Boolean = offset >= text.length

        fun skipWhitespace() {
            while (!atEnd() && text[offset] in JSON_WHITESPACE) offset++
        }

        fun parseValue(depth: Int): BackupJson {
            if (depth > MAX_DEPTH) fail("JSON nesting is too deep")
            if (atEnd()) fail("unexpected end of input")
            return when (text[offset]) {
                '{' -> parseObject(depth + 1)
                '[' -> parseArray(depth + 1)
                '"' -> JsonString(parseString())
                't' -> {
                    expectLiteral("true")
                    JsonBoolean(true)
                }
                'f' -> {
                    expectLiteral("false")
                    JsonBoolean(false)
                }
                'n' -> {
                    expectLiteral("null")
                    JsonNull
                }
                '-' -> JsonNumber(parseInteger())
                in '0'..'9' -> JsonNumber(parseInteger())
                else -> fail("unexpected character")
            }
        }

        private fun parseObject(depth: Int): JsonObject {
            expect('{')
            val fields = LinkedHashMap<String, BackupJson>()
            skipWhitespace()
            if (peek() == '}') {
                offset++
                return JsonObject(fields)
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("object key must be a string")
                val key = parseString()
                if (key in fields) fail("duplicate object key '$key'")
                skipWhitespace()
                expect(':')
                skipWhitespace()
                fields[key] = parseValue(depth)
                skipWhitespace()
                when (next()) {
                    ',' -> continue
                    '}' -> return JsonObject(fields)
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun parseArray(depth: Int): JsonArray {
            expect('[')
            val items = ArrayList<BackupJson>()
            skipWhitespace()
            if (peek() == ']') {
                offset++
                return JsonArray(items)
            }
            while (true) {
                skipWhitespace()
                items += parseValue(depth)
                skipWhitespace()
                when (next()) {
                    ',' -> continue
                    ']' -> return JsonArray(items)
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val result = StringBuilder()
            while (!atEnd()) {
                val ch = text[offset++]
                when {
                    ch == '"' -> {
                        requireValidUnicode(result)
                        return result.toString()
                    }
                    ch == '\\' -> {
                        if (atEnd()) fail("dangling escape")
                        when (val escaped = text[offset++]) {
                            '"' -> result.append('"')
                            '\\' -> result.append('\\')
                            '/' -> result.append('/')
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000C')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> appendUnicodeEscape(result)
                            else -> fail("invalid escape '\\$escaped'")
                        }
                    }
                    ch < '\u0020' -> fail("unescaped control character in string")
                    else -> result.append(ch)
                }
            }
            fail("unterminated string")
        }

        private fun appendUnicodeEscape(result: StringBuilder) {
            val code = readHex4()
            when {
                Character.isHighSurrogate(code) -> {
                    if (!text.startsWith("\\u", offset)) {
                        fail("unpaired high surrogate")
                    }
                    offset += 2
                    val low = readHex4()
                    if (!Character.isLowSurrogate(low)) fail("unpaired high surrogate")
                    result.append(code)
                    result.append(low)
                }
                Character.isLowSurrogate(code) -> fail("unpaired low surrogate")
                else -> result.append(code)
            }
        }

        private fun readHex4(): Char {
            if (offset + 4 > text.length) fail("incomplete Unicode escape")
            var value = 0
            repeat(4) {
                val digit = text[offset++].digitToIntOrNull(16)
                    ?: fail("invalid Unicode escape")
                value = value * 16 + digit
            }
            return value.toChar()
        }

        private fun parseInteger(): Long {
            val start = offset
            if (peek() == '-') offset++
            if (atEnd()) fail("incomplete number")
            if (text[offset] == '0') {
                offset++
                if (!atEnd() && text[offset] in '0'..'9') fail("number has a leading zero")
            } else {
                if (text[offset] !in '1'..'9') fail("invalid number")
                while (!atEnd() && text[offset] in '0'..'9') offset++
            }
            if (!atEnd() && text[offset] in charArrayOf('.', 'e', 'E')) {
                fail("only integers are supported")
            }
            return text.substring(start, offset).toLongOrNull()
                ?: fail("integer is outside the signed 64-bit range")
        }

        private fun requireValidUnicode(value: CharSequence) {
            var index = 0
            while (index < value.length) {
                val ch = value[index]
                when {
                    Character.isHighSurrogate(ch) -> {
                        if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) {
                            fail("unpaired high surrogate")
                        }
                        index += 2
                    }
                    Character.isLowSurrogate(ch) -> fail("unpaired low surrogate")
                    else -> index++
                }
            }
        }

        private fun expectLiteral(literal: String) {
            if (!text.startsWith(literal, offset)) fail("invalid literal")
            offset += literal.length
        }

        private fun expect(ch: Char) {
            if (atEnd() || text[offset] != ch) fail("expected '$ch'")
            offset++
        }

        private fun peek(): Char? = if (atEnd()) null else text[offset]

        private fun next(): Char =
            if (atEnd()) fail("unexpected end of input") else text[offset++]

        private fun fail(message: String): Nothing =
            throw BackupFormatException("JSON error at offset $offset: $message")
    }

    private val JSON_WHITESPACE = charArrayOf(' ', '\t', '\n', '\r')
}
