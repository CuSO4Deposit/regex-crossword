package com.hexregex.engine

/**
 * A tiny, dependency-free JSON reader.
 *
 * The engine deliberately avoids third-party JSON libraries so it stays as
 * portable as the Python original (which is standard-library only). The parser
 * is enough for puzzle files: objects, arrays, strings, numbers, booleans and
 * null.
 */
object MiniJson {
    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> {
        val value = parse(text)
        require(value is Map<*, *>) { "expected a JSON object" }
        return value as Map<String, Any?>
    }

    fun parse(text: String): Any? {
        val parser = Parser(text)
        parser.skipWhitespace()
        val value = parser.parseValue()
        parser.skipWhitespace()
        require(parser.atEnd()) { "trailing content at offset ${parser.offset}" }
        return value
    }

    private class Parser(private val text: String) {
        var offset: Int = 0
            private set

        fun atEnd(): Boolean = offset >= text.length

        fun skipWhitespace() {
            while (offset < text.length && text[offset].isWhitespace()) offset++
        }

        fun parseValue(): Any? {
            if (atEnd()) fail("unexpected end of input")
            return when (val ch = text[offset]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                else -> if (ch == '-' || ch.isDigit()) parseNumber() else fail("unexpected '$ch'")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                offset++
                return map
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                map[key] = parseValue()
                skipWhitespace()
                when (val ch = next()) {
                    ',' -> continue
                    '}' -> break
                    else -> fail("expected ',' or '}' but found '$ch'")
                }
            }
            return map
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                offset++
                return list
            }
            while (true) {
                skipWhitespace()
                list.add(parseValue())
                skipWhitespace()
                when (val ch = next()) {
                    ',' -> continue
                    ']' -> break
                    else -> fail("expected ',' or ']' but found '$ch'")
                }
            }
            return list
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) fail("unterminated string")
                when (val ch = text[offset++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) fail("dangling escape")
                        when (val esc = text[offset++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (offset + 4 > text.length) fail("bad \\u escape")
                                val hex = text.substring(offset, offset + 4)
                                offset += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> fail("invalid escape '\\$esc'")
                        }
                    }
                    else -> sb.append(ch)
                }
            }
        }

        private fun parseNumber(): Any {
            val start = offset
            if (peek() == '-') offset++
            while (!atEnd() && text[offset].isDigit()) offset++
            var isInteger = true
            if (!atEnd() && text[offset] == '.') {
                isInteger = false
                offset++
                while (!atEnd() && text[offset].isDigit()) offset++
            }
            if (!atEnd() && (text[offset] == 'e' || text[offset] == 'E')) {
                isInteger = false
                offset++
                if (!atEnd() && (text[offset] == '+' || text[offset] == '-')) offset++
                while (!atEnd() && text[offset].isDigit()) offset++
            }
            val raw = text.substring(start, offset)
            return if (isInteger) raw.toLong() else raw.toDouble()
        }

        private fun parseBoolean(): Boolean {
            if (text.startsWith("true", offset)) {
                offset += 4
                return true
            }
            if (text.startsWith("false", offset)) {
                offset += 5
                return false
            }
            fail("invalid literal")
        }

        private fun parseNull(): Any? {
            if (!text.startsWith("null", offset)) fail("invalid literal")
            offset += 4
            return null
        }

        private fun peek(): Char? = if (atEnd()) null else text[offset]

        private fun next(): Char = if (atEnd()) fail("unexpected end of input") else text[offset++]

        private fun expect(ch: Char) {
            if (atEnd() || text[offset] != ch) fail("expected '$ch'")
            offset++
        }

        private fun fail(message: String): Nothing =
            throw IllegalArgumentException("JSON error at offset $offset: $message")
    }
}
