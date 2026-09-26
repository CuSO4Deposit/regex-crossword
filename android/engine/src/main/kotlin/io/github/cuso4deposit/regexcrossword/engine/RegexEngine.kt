package io.github.cuso4deposit.regexcrossword.engine

/**
 * A small regular-expression engine that matches against *letter domains*
 * instead of fixed strings.
 *
 * Direct port of `hexregex/regex_engine.py`. During search each grid position
 * carries a set of candidate letters; this engine answers whether a pattern
 * admits *some* assignment (with whole-string / `fullmatch` semantics).
 *
 * Supported syntax (the Python/JavaScript intersection the generator emits):
 * literals, `.`, character classes `[...]` (ranges and negation), alternation
 * `|`, groups `(...)` / `(?:...)`, `* + ? {m,n}` and backreferences `\1`..`\9`.
 * Lookarounds and other Python-only constructs are rejected.
 */
class RegexSyntaxError(message: String) : IllegalArgumentException(message)

sealed interface RegexNode

data object AnyNode : RegexNode
data object AnchorNode : RegexNode
data class LitNode(val ch: Char) : RegexNode
data class ClassNode(val negate: Boolean, val specs: List<ClassSpec>) : RegexNode
data class RepNode(val min: Int, val max: Int, val body: List<RegexNode>, val greedy: Boolean) : RegexNode
data class GroupNode(val gid: Int, val body: List<RegexNode>) : RegexNode
data class RefNode(val gid: Int) : RegexNode
data class AltNode(val branches: List<List<RegexNode>>) : RegexNode

sealed interface ClassSpec
data class LitSpec(val ch: Char) : ClassSpec
data class RangeSpec(val lo: Int, val hi: Int) : ClassSpec
data class CategorySpec(val name: String) : ClassSpec

class Compiled(
    val pattern: String,
    val ast: List<RegexNode>,
    val refs: Set<Int>,
    val ngroups: Int,
)

private const val INF_REPEAT = 1 shl 30

object RegexEngine {
    private val compileCache = HashMap<String, Compiled>()

    // Bounded LRU. Keys are large (a set per position), so the on-device budget
    // is much smaller than CPython's 1<<16; clearCaches() resets it between
    // generations.
    private val feasibleCache =
        object : java.util.LinkedHashMap<Pair<String, List<Set<Char>>>, Boolean>(512, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<Pair<String, List<Set<Char>>>, Boolean>,
            ): Boolean = size > (1 shl 13)
        }

    /** Drop the memoised feasibility results (frees memory between searches). */
    fun clearCaches() {
        feasibleCache.clear()
    }

    fun compile(pattern: String): Compiled =
        compileCache.getOrPut(pattern) { RegexParser(pattern).parse() }

    /** Whether some assignment of `allowed` matches the pattern. */
    fun feasible(allowed: List<Iterable<Char>>, compiled: Compiled): Boolean =
        Feasibility(compiled, allowed.map { it.toSet() }).run()

    /** Like [feasible] but memoised globally across solver runs. */
    fun feasibleCached(allowed: List<Iterable<Char>>, compiled: Compiled): Boolean {
        val key = allowed.map { it.toSet() }
        return feasibleCache.getOrPut(compiled.pattern to key) { feasible(key, compiled) }
    }

    internal fun classContains(negate: Boolean, specs: List<ClassSpec>, ch: Char): Boolean {
        var matched = false
        for (spec in specs) {
            when (spec) {
                is LitSpec -> if (ch == spec.ch) matched = true
                is RangeSpec -> if (spec.lo <= ch.code && ch.code <= spec.hi) matched = true
                is CategorySpec -> if (categoryMatch(spec.name, ch)) matched = true
            }
            if (matched) break
        }
        return matched != negate
    }

    private fun categoryMatch(name: String, ch: Char): Boolean {
        val negate = name.contains("NOT_")
        val base = name.removePrefix("NOT_")
        val value = when {
            base.endsWith("DIGIT") -> ch.isDigit()
            base.endsWith("WORD") -> ch.isLetterOrDigit() || ch == '_'
            base.endsWith("SPACE") -> ch in " \t\n\r\u000C\u000B"
            base.endsWith("LINEBREAK") -> ch == '\n' || ch == '\r'
            else -> false
        }
        return value != negate
    }

    private data class State(
        val pos: Int,
        val groups: List<List<Int>?>,
        val parent: List<Int>,
        val dom: List<Set<Char>>,
    )

    private data class StateKey(
        val seq: List<RegexNode>,
        val pos: Int,
        val groups: List<List<Int>?>,
        val parent: List<Int>,
        val dom: List<Set<Char>>,
    )

    private class Feasibility(private val compiled: Compiled, private val dom0: List<Set<Char>>) {
        private val length = dom0.size
        private val refs = compiled.refs
        private val track = refs.isNotEmpty()
        private val groups0: List<List<Int>?> = List(compiled.ngroups + 1) { null }
        private val parent0: List<Int> = List(length) { it }
        private val memo = HashMap<StateKey, Set<State>>()

        fun run(): Boolean = sequence(compiled.ast, 0, groups0, parent0, dom0).any { it.pos == length }

        private fun find(parent: List<Int>, x0: Int): Int {
            var x = x0
            while (parent[x] != x) x = parent[x]
            return x
        }

        private fun sequence(
            seq: List<RegexNode>,
            pos: Int,
            groups: List<List<Int>?>,
            parent: List<Int>,
            dom: List<Set<Char>>,
        ): Set<State> {
            val key = StateKey(seq, pos, groups, parent, dom)
            memo[key]?.let { return it }
            val result: Set<State> = if (seq.isEmpty()) {
                setOf(State(pos, groups, parent, dom))
            } else {
                val acc = HashSet<State>()
                for (state in node(seq.first(), pos, groups, parent, dom)) {
                    acc.addAll(
                        sequence(seq.subList(1, seq.size), state.pos, state.groups, state.parent, state.dom),
                    )
                }
                acc
            }
            memo[key] = result
            return result
        }

        private fun constrain(
            parent: List<Int>,
            dom: List<Set<Char>>,
            pos: Int,
            accept: Set<Char>?,
        ): List<Set<Char>>? {
            val r = if (track) find(parent, pos) else pos
            val cur = dom[r]
            if (accept == null) return if (cur.isNotEmpty()) dom else null
            val inter = cur intersect accept
            if (inter.isEmpty()) return null
            if (!track) return dom
            val nd = dom.toMutableList()
            nd[r] = inter
            return nd
        }

        private fun node(
            n: RegexNode,
            pos: Int,
            groups: List<List<Int>?>,
            parent: List<Int>,
            dom: List<Set<Char>>,
        ): List<State> {
            when (n) {
                is LitNode -> {
                    if (pos >= length) return emptyList()
                    val nd = constrain(parent, dom, pos, setOf(n.ch)) ?: return emptyList()
                    return listOf(State(pos + 1, groups, parent, nd))
                }
                is AnyNode -> {
                    if (pos >= length) return emptyList()
                    val r = if (track) find(parent, pos) else pos
                    if (dom[r].isEmpty()) return emptyList()
                    return listOf(State(pos + 1, groups, parent, dom))
                }
                is ClassNode -> {
                    if (pos >= length) return emptyList()
                    val r = if (track) find(parent, pos) else pos
                    val accept = dom[r].filterTo(HashSet()) { classContains(n.negate, n.specs, it) }
                    val nd = constrain(parent, dom, pos, accept) ?: return emptyList()
                    return listOf(State(pos + 1, groups, parent, nd))
                }
                is AnchorNode -> return listOf(State(pos, groups, parent, dom))
                is RefNode -> {
                    val gid = n.gid
                    if (gid >= groups.size) return emptyList()
                    val value = groups[gid] ?: return emptyList()
                    val k = value.size
                    if (pos + k > length) return emptyList()
                    var p = parent
                    var d = dom
                    if (track) {
                        for (i in 0 until k) {
                            val r1 = find(p, value[i])
                            val r2 = find(p, pos + i)
                            if (r1 == r2) continue
                            val inter = d[r1] intersect d[r2]
                            if (inter.isEmpty()) return emptyList()
                            val np = p.toMutableList()
                            np[r2] = r1
                            val nd = d.toMutableList()
                            nd[r1] = inter
                            p = np
                            d = nd
                        }
                    } else {
                        for (i in 0 until k) {
                            if ((d[value[i]] intersect d[pos + i]).isEmpty()) return emptyList()
                        }
                    }
                    return listOf(State(pos + k, groups, p, d))
                }
                is GroupNode -> {
                    val gid = n.gid
                    val out = ArrayList<State>()
                    for (st in sequence(n.body, pos, groups, parent, dom)) {
                        if (gid in refs) {
                            val captured = (pos until st.pos).toList()
                            val ng = st.groups.toMutableList()
                            ng[gid] = captured
                            out.add(State(st.pos, ng, st.parent, st.dom))
                        } else {
                            out.add(st)
                        }
                    }
                    return out
                }
                is AltNode -> {
                    val acc = HashSet<State>()
                    for (branch in n.branches) acc.addAll(sequence(branch, pos, groups, parent, dom))
                    return acc.toList()
                }
                is RepNode -> return repStates(n.min, n.max, n.body, pos, groups, parent, dom)
            }
        }

        private fun repStates(
            mn: Int,
            mx: Int,
            body: List<RegexNode>,
            pos: Int,
            groups: List<List<Int>?>,
            parent: List<Int>,
            dom: List<Set<Char>>,
        ): List<State> {
            val results = ArrayList<State>()

            fun rec(
                count: Int,
                p: Int,
                g: List<List<Int>?>,
                pa: List<Int>,
                d: List<Set<Char>>,
                lastZero: Boolean,
            ) {
                if (count >= mn) results.add(State(p, g, pa, d))
                if (count >= mx) return
                for (st in sequence(body, p, g, pa, d)) {
                    val zero = st.pos == p && st.groups == g && st.parent == pa && st.dom == d
                    if (zero) {
                        if (lastZero) continue
                        if (count + 1 < mn) results.add(st)
                        rec(count + 1, st.pos, st.groups, st.parent, st.dom, true)
                    } else {
                        rec(count + 1, st.pos, st.groups, st.parent, st.dom, false)
                    }
                }
            }

            rec(0, pos, groups, parent, dom, false)
            return results
        }
    }
}

/**
 * Recursive-descent parser for the cross-platform regex subset.
 *
 * Python parses with `re._parser`; Kotlin has no public regex AST, so this
 * reimplements the small grammar the generator can emit.
 */
/**
 * Positions that a pattern forces to one exact literal character.
 *
 * A fixed-width scan: literals are pinned while the consumed length up to that
 * point is fixed; on the first variable-width construct (`*`, `+`, `?`, `{m,n}`,
 * a backreference or unequal-length alternation) the rest of the line is
 * considered ambiguous. This is exactly "a letter written in the clue at a
 * fixed position", and needs no feasibility search.
 */
fun pinnedLiterals(ast: List<RegexNode>): Map<Int, Char> {
    fun analyze(nodes: List<RegexNode>): Pair<Int?, Map<Int, Char>> {
        var width = 0
        val pinned = LinkedHashMap<Int, Char>()
        for (node in nodes) {
            when (node) {
                is LitNode -> {
                    pinned[width] = node.ch
                    width += 1
                }
                is AnyNode -> width += 1
                is ClassNode -> width += 1
                is AnchorNode -> {}
                is GroupNode -> {
                    val (bodyWidth, bodyPins) = analyze(node.body)
                    if (bodyWidth == null) return null to pinned
                    for ((offset, ch) in bodyPins) pinned[width + offset] = ch
                    width += bodyWidth
                }
                is RepNode -> {
                    if (node.min != node.max) return null to pinned
                    val (bodyWidth, bodyPins) = analyze(node.body)
                    if (bodyWidth == null) return null to pinned
                    for (repeat in 0 until node.min) {
                        for ((offset, ch) in bodyPins) pinned[width + repeat * bodyWidth + offset] = ch
                    }
                    width += node.min * bodyWidth
                }
                is AltNode -> {
                    val results = node.branches.map { analyze(it) }
                    val widths = results.map { it.first }
                    if (widths.any { it == null } || widths.distinct().size != 1) return null to pinned
                    val branchWidth = widths.first()!!
                    for (offset in 0 until branchWidth) {
                        val chars = results.mapNotNull { it.second[offset] }
                        if (chars.size == results.size && chars.distinct().size == 1) {
                            pinned[width + offset] = chars.first()
                        }
                    }
                    width += branchWidth
                }
                is RefNode -> return null to pinned
            }
        }
        return width to pinned
    }
    return analyze(ast).second
}

class RegexParser(private val source: String) {
    private var index = 0
    private var groupCounter = 0
    private val refs = HashSet<Int>()

    fun parse(): Compiled {
        val ast = parseAlternation()
        if (index != source.length) {
            throw RegexSyntaxError("unexpected '${source[index]}' at $index in \"$source\"")
        }
        val ngroups = maxOf(groupCounter, refs.maxOrNull() ?: 0)
        return Compiled(source, ast, refs, ngroups)
    }

    private fun parseAlternation(): List<RegexNode> {
        val branches = ArrayList<List<RegexNode>>()
        branches.add(parseSequence())
        while (peek() == '|') {
            index++
            branches.add(parseSequence())
        }
        if (branches.size == 1) return branches[0]
        return listOf(AltNode(branches))
    }

    private fun parseSequence(): List<RegexNode> {
        val out = ArrayList<RegexNode>()
        while (true) {
            val c = peek() ?: break
            if (c == '|' || c == ')') break
            out.add(parseQuantified())
        }
        return out
    }

    private fun parseQuantified(): RegexNode {
        val atom = parseAtom()
        val c = peek() ?: return atom
        var min = 0
        var max = INF_REPEAT
        when (c) {
            '*' -> index++
            '+' -> {
                min = 1
                index++
            }
            '?' -> {
                max = 1
                index++
            }
            '{' -> {
                index++
                min = readInt() ?: throw RegexSyntaxError("bad repeat in \"$source\"")
                if (peek() == ',') {
                    index++
                    max = readInt() ?: INF_REPEAT
                } else {
                    max = min
                }
                if (peek() != '}') throw RegexSyntaxError("unterminated repeat in \"$source\"")
                index++
            }
            else -> return atom
        }
        var greedy = true
        if (peek() == '?') {
            greedy = false
            index++
        }
        return RepNode(min, max, listOf(atom), greedy)
    }

    private fun parseAtom(): RegexNode {
        val c = peek() ?: throw RegexSyntaxError("unexpected end of pattern \"$source\"")
        return when (c) {
            '(' -> {
                index++
                if (peek() == '?') {
                    index++
                    if (peek() == ':') {
                        index++
                        val body = parseAlternation()
                        expect(')')
                        GroupNode(0, body)
                    } else {
                        throw RegexSyntaxError("unsupported group construct '(?' in \"$source\"")
                    }
                } else {
                    val gid = ++groupCounter
                    val body = parseAlternation()
                    expect(')')
                    GroupNode(gid, body)
                }
            }
            '[' -> parseClass()
            '.' -> {
                index++
                AnyNode
            }
            '^', '$' -> {
                index++
                AnchorNode
            }
            ')' -> throw RegexSyntaxError("unmatched ')' in \"$source\"")
            '\\' -> parseEscape()
            else -> {
                index++
                LitNode(c)
            }
        }
    }

    private fun parseEscape(): RegexNode {
        index++ // backslash
        val c = peek() ?: throw RegexSyntaxError("dangling backslash in \"$source\"")
        if (c in '1'..'9') {
            var num = 0
            while (peek()?.isDigit() == true) {
                num = num * 10 + (source[index] - '0')
                index++
            }
            refs.add(num)
            return RefNode(num)
        }
        index++
        return escapeToNode(c)
    }

    private fun escapeToNode(c: Char): RegexNode = when (c) {
        'd', 'D', 'w', 'W', 's', 'S' -> {
            val negate = c.isUpperCase()
            val base = when (c.lowercaseChar()) {
                'd' -> "DIGIT"
                'w' -> "WORD"
                else -> "SPACE"
            }
            ClassNode(negate, listOf(CategorySpec(if (negate) "NOT_$base" else base)))
        }
        'b' -> AnchorNode
        'n' -> LitNode('\n')
        'r' -> LitNode('\r')
        't' -> LitNode('\t')
        'f' -> LitNode('\u000C')
        'v' -> LitNode('\u000B')
        'a' -> LitNode('\u0007')
        '0' -> LitNode('\u0000')
        else -> LitNode(c)
    }

    private fun parseClass(): RegexNode {
        index++ // '['
        var negate = false
        if (peek() == '^') {
            negate = true
            index++
        }
        val specs = ArrayList<ClassSpec>()
        var first = true
        while (true) {
            val c = peek() ?: throw RegexSyntaxError("unterminated class in \"$source\"")
            if (c == ']' && !first) {
                index++
                break
            }
            first = false
            val item = parseClassAtom()
            if (item is LitSpec && peek() == '-' && peekAt(1) != ']' && peekAt(1) != null) {
                index++ // '-'
                val hi = parseClassAtom()
                if (hi is LitSpec) {
                    specs.add(RangeSpec(item.ch.code, hi.ch.code))
                } else {
                    specs.add(item)
                    specs.add(LitSpec('-'))
                    specs.add(hi)
                }
            } else {
                specs.add(item)
            }
        }
        return ClassNode(negate, specs)
    }

    private fun parseClassAtom(): ClassSpec {
        val c = peek() ?: throw RegexSyntaxError("unterminated class in \"$source\"")
        if (c == '\\') {
            index++
            val e = peek() ?: throw RegexSyntaxError("dangling class escape in \"$source\"")
            return when (e) {
                'd', 'D', 'w', 'W', 's', 'S' -> {
                    index++
                    val negate = e.isUpperCase()
                    val base = when (e.lowercaseChar()) {
                        'd' -> "DIGIT"
                        'w' -> "WORD"
                        else -> "SPACE"
                    }
                    CategorySpec(if (negate) "NOT_$base" else base)
                }
                else -> {
                    index++
                    LitSpec(
                        when (e) {
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            'f' -> '\u000C'
                            'v' -> '\u000B'
                            'a' -> '\u0007'
                            '0' -> '\u0000'
                            else -> e
                        },
                    )
                }
            }
        }
        index++
        return LitSpec(c)
    }

    private fun readInt(): Int? {
        if (peek()?.isDigit() != true) return null
        var n = 0
        while (peek()?.isDigit() == true) {
            n = n * 10 + (source[index] - '0')
            index++
        }
        return n
    }

    private fun peek(): Char? = if (index < source.length) source[index] else null

    private fun peekAt(offset: Int): Char? {
        val i = index + offset
        return if (i < source.length) source[i] else null
    }

    private fun expect(ch: Char) {
        if (peek() != ch) throw RegexSyntaxError("expected '$ch' at $index in \"$source\"")
        index++
    }
}
