package com.hexregex.engine

/**
 * Serialises primitive JSON trees exactly like
 * `json.dumps(value, ensure_ascii=False, indent=2)`.
 *
 * Byte-for-byte parity with the `hexregex` CLI matters: a shareable level is
 * defined by the bytes the CLI would have written. The CLI writes the puzzle
 * with `indent=2` and a trailing newline.
 */
object PyJson {
    fun dumps(value: Any?, indent: Int = 2): String = StringBuilder().also {
        writeValue(it, value, indent, 0)
    }.toString()

    /** The exact text the CLI writes to `-o` (dumps + "\n"). */
    fun dumpsFile(value: Any?, indent: Int = 2): String = dumps(value, indent) + "\n"

    private fun writeValue(sb: StringBuilder, value: Any?, indent: Int, level: Int) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (value) "true" else "false")
            is Int, is Long -> sb.append(value.toString())
            is Double -> sb.append(formatDouble(value))
            is Float -> sb.append(formatDouble(value.toDouble()))
            is String -> writeString(sb, value)
            is Map<*, *> -> writeObject(sb, value, indent, level)
            is Iterable<*> -> writeArray(sb, value, indent, level)
            else -> error("cannot serialise ${value::class}")
        }
    }

    private fun writeObject(sb: StringBuilder, map: Map<*, *>, indent: Int, level: Int) {
        if (map.isEmpty()) {
            sb.append("{}")
            return
        }
        sb.append('{')
        sb.append('\n')
        val pad = " ".repeat(indent * (level + 1))
        var first = true
        for ((key, item) in map) {
            if (!first) {
                sb.append(',')
                sb.append('\n')
            }
            first = false
            sb.append(pad)
            writeString(sb, key.toString())
            sb.append(": ")
            writeValue(sb, item, indent, level + 1)
        }
        sb.append('\n')
        sb.append(" ".repeat(indent * level))
        sb.append('}')
    }

    private fun writeArray(sb: StringBuilder, items: Iterable<*>, indent: Int, level: Int) {
        val list = items.toList()
        if (list.isEmpty()) {
            sb.append("[]")
            return
        }
        sb.append('[')
        sb.append('\n')
        val pad = " ".repeat(indent * (level + 1))
        for ((i, item) in list.withIndex()) {
            if (i > 0) {
                sb.append(',')
                sb.append('\n')
            }
            sb.append(pad)
            writeValue(sb, item, indent, level + 1)
        }
        sb.append('\n')
        sb.append(" ".repeat(indent * level))
        sb.append(']')
    }

    private fun writeString(sb: StringBuilder, value: String) {
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') {
                    sb.append("\\u")
                    sb.append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
    }

    private fun formatDouble(value: Double): String {
        if (value == value.toLong().toDouble()) return "${value.toLong()}.0"
        return value.toString()
    }
}
