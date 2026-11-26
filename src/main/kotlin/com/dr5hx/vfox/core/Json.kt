package com.dr5hx.vfox.core

/**
 * Minimal, dependency-free JSON reader.
 *
 * The plugin only ever parses small documents emitted by the vfox CLI (`--json`). Pulling in a
 * JSON library would add a runtime dependency that can clash with the platform's own bundled
 * copies, so a compact recursive-descent parser is used instead.
 */
internal object Json {

    fun parse(text: String): Any? {
        val parser = Parser(text)
        parser.skipWhitespace()
        val value = parser.readValue()
        parser.skipWhitespace()
        return value
    }

    /**
     * Parses [text] and returns the root object, or an empty map when the document is not a
     * JSON object (vfox prints a plain-text error in that case).
     */
    fun parseObject(text: String): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return parse(text) as? Map<String, Any?> ?: emptyMap()
    }

    /** Reads a nested object at [key], returning an empty map when absent or of another type. */
    @Suppress("UNCHECKED_CAST")
    fun objectAt(map: Map<String, Any?>, key: String): Map<String, Any?> =
        map[key] as? Map<String, Any?> ?: emptyMap()

    /** Reads a nested array of strings at [key], skipping non-string elements. */
    fun stringListAt(map: Map<String, Any?>, key: String): List<String> =
        (map[key] as? List<*>)?.filterIsInstance<String>() ?: emptyList()

    /** Reads a nested object of string values at [key]. */
    fun stringMapAt(map: Map<String, Any?>, key: String): Map<String, String> =
        objectAt(map, key).mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    /** Reads a string value at [key], or null when absent. */
    fun stringAt(map: Map<String, Any?>, key: String): String? = map[key] as? String

    private class Parser(private val source: String) {
        private var index = 0

        fun skipWhitespace() {
            while (index < source.length && source[index].isWhitespace()) index++
        }

        fun readValue(): Any? {
            skipWhitespace()
            require(index < source.length) { "Unexpected end of JSON input" }
            return when (source[index]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't', 'f' -> readBoolean()
                'n' -> readNull()
                else -> readNumber()
            }
        }

        private fun readObject(): Map<String, Any?> {
            expect(OPEN_BRACE)
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return result
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                result[key] = readValue()
                skipWhitespace()
                when (val c = peek()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return result
                    }
                    else -> error("Expected ',' or '}' but found '$c' at index $index")
                }
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return result
            }
            while (true) {
                result.add(readValue())
                skipWhitespace()
                when (val c = peek()) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return result
                    }
                    else -> error("Expected ',' or ']' but found '$c' at index $index")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                require(index < source.length) { "Unterminated string" }
                when (val c = source[index++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        require(index < source.length) { "Unterminated escape sequence" }
                        when (val esc = source[index++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                require(index + 4 <= source.length) { "Truncated unicode escape" }
                                sb.append(source.substring(index, index + 4).toInt(16).toChar())
                                index += 4
                            }
                            else -> error("Unknown escape '\\$esc' at index $index")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readBoolean(): Boolean = when {
            source.startsWith("true", index) -> {
                index += 4
                true
            }
            source.startsWith("false", index) -> {
                index += 5
                false
            }
            else -> error("Invalid literal at index $index")
        }

        private fun readNull(): Any? {
            require(source.startsWith("null", index)) { "Invalid literal at index $index" }
            index += 4
            return null
        }

        private fun readNumber(): Any {
            val start = index
            while (index < source.length && (source[index].isDigit() || source[index] in "-+.eE")) index++
            val text = source.substring(start, index)
            return text.toLongOrNull() ?: text.toDoubleOrNull()
                ?: error("Invalid number '$text' at index $start")
        }

        private fun peek(): Char {
            require(index < source.length) { "Unexpected end of JSON input" }
            return source[index]
        }

        private fun expect(c: Char) {
            require(index < source.length && source[index] == c) {
                "Expected '$c' at index $index but found '${if (index < source.length) source[index] else "EOF"}'"
            }
            index++
        }
    }

    private const val OPEN_BRACE = '{'
}
