package io.github.cuso4deposit.regexcrossword.engine

import kotlin.math.ceil
import kotlin.math.log
import kotlin.math.min
import kotlin.math.pow

/**
 * A faithful port of CPython's `random.Random` (Mersenne Twister) for integer
 * seeds, covering exactly the methods the generator uses: `nextDouble`,
 * `getrandbits`, `randint`, `choice`, `shuffle` and `sample`.
 *
 * Byte-for-byte parity with `hexregex gen` depends on this being identical to
 * CPython, so every method mirrors `_randommodule.c` / `random.py` directly.
 */
class PyRandom(seed: Long) {
    private val mt = IntArray(N)
    private var mti = N + 1

    init {
        seedFromLong(seed)
    }

    private fun seedFromLong(seed: Long) {
        val n = if (seed < 0) -seed else seed
        val bits = if (n == 0L) 0 else 64 - java.lang.Long.numberOfLeadingZeros(n)
        val keyMax = maxOf(1, (bits + 31) / 32)
        val key = IntArray(keyMax) { i -> ((n ushr (32 * i)) and 0xffffffffL).toInt() }
        initByArray(key)
    }

    private fun initGenrand(s: Int) {
        mt[0] = s
        for (i in 1 until N) {
            mt[i] = 1812433253 * (mt[i - 1] xor (mt[i - 1] ushr 30)) + i
        }
    }

    private fun initByArray(key: IntArray) {
        initGenrand(19650218)
        var i = 1
        var j = 0
        var k = maxOf(N, key.size)
        while (k > 0) {
            mt[i] = (mt[i] xor ((mt[i - 1] xor (mt[i - 1] ushr 30)) * 1664525)) + key[j] + j
            i++
            j++
            if (i >= N) {
                mt[0] = mt[N - 1]
                i = 1
            }
            if (j >= key.size) j = 0
            k--
        }
        k = N - 1
        while (k > 0) {
            mt[i] = (mt[i] xor ((mt[i - 1] xor (mt[i - 1] ushr 30)) * 1566083941)) - i
            i++
            if (i >= N) {
                mt[0] = mt[N - 1]
                i = 1
            }
            k--
        }
        mt[0] = 0x80000000.toInt()
    }

    private fun genrandUint32(): Int {
        if (mti >= N) {
            var kk = 0
            while (kk < N - M) {
                val y = (mt[kk] and UPPER_MASK) or (mt[kk + 1] and LOWER_MASK)
                mt[kk] = mt[kk + M] xor (y ushr 1) xor (if (y and 1 != 0) MATRIX_A else 0)
                kk++
            }
            while (kk < N - 1) {
                val y = (mt[kk] and UPPER_MASK) or (mt[kk + 1] and LOWER_MASK)
                mt[kk] = mt[kk + (M - N)] xor (y ushr 1) xor (if (y and 1 != 0) MATRIX_A else 0)
                kk++
            }
            val y = (mt[N - 1] and UPPER_MASK) or (mt[0] and LOWER_MASK)
            mt[N - 1] = mt[M - 1] xor (y ushr 1) xor (if (y and 1 != 0) MATRIX_A else 0)
            mti = 0
        }
        var y = mt[mti++]
        y = y xor (y ushr 11)
        y = y xor ((y shl 7) and 0x9d2c5680.toInt())
        y = y xor ((y shl 15) and 0xefc60000.toInt())
        y = y xor (y ushr 18)
        return y
    }

    /** CPython `random()`: 53-bit random in `[0, 1)`. */
    fun nextDouble(): Double {
        val a = genrandUint32() ushr 5
        val b = genrandUint32() ushr 6
        return (a * 67108864.0 + b) * (1.0 / 9007199254740992.0)
    }

    fun getrandbits(k: Int): Int {
        require(k >= 0) { "number of bits must be non-negative" }
        if (k == 0) return 0
        if (k <= 32) return genrandUint32() ushr (32 - k)
        throw UnsupportedOperationException("only k <= 32 is needed")
    }

    /** CPython `_randbelow_with_getrandbits`. */
    fun randbelow(n: Int): Int {
        if (n <= 0) return 0
        val k = 32 - Integer.numberOfLeadingZeros(n)
        var r = getrandbits(k)
        while (Integer.compareUnsigned(r, n) >= 0) r = getrandbits(k)
        return r
    }

    fun randint(min: Int, max: Int): Int {
        require(max >= min)
        return min + randbelow(max - min + 1)
    }

    /** CPython `randrange(start, stop)` for integer arguments. */
    fun randrange(start: Int, stop: Int): Int {
        require(stop > start) { "empty range for randrange" }
        return start + randbelow(stop - start)
    }

    fun <T> choice(seq: List<T>): T = seq[randbelow(seq.size)]

    /**
     * CPython `random.choices(population, weights=..., k=1)[0]` for float
     * weights: cumulative weights, a single `random()` draw and a right bisect.
     */
    fun <T> choicesOne(population: List<T>, weights: List<Double>): T {
        require(population.size == weights.size)
        val n = population.size
        val cumulative = ArrayList<Double>(n)
        var running = 0.0
        for (w in weights) {
            running += w
            cumulative.add(running)
        }
        require(n > 0) { "population must not be empty" }
        val total = cumulative[n - 1]
        require(total > 0.0) { "total of weights must be positive" }
        val x = nextDouble() * total
        return population[bisectRight(cumulative, x, 0, n - 1)]
    }

    private fun bisectRight(a: List<Double>, x: Double, lo0: Int, hi0: Int): Int {
        var lo = lo0
        var hi = hi0
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (x < a[mid]) hi = mid else lo = mid + 1
        }
        return lo
    }

    fun <T> shuffle(list: MutableList<T>) {
        for (i in list.size - 1 downTo 1) {
            val j = randbelow(i + 1)
            val tmp = list[i]
            list[i] = list[j]
            list[j] = tmp
        }
    }

    fun <T> sample(population: List<T>, k: Int): List<T> {
        val n = population.size
        require(k in 0..n) { "sample larger than population or is negative" }
        @Suppress("UNCHECKED_CAST")
        val result = arrayOfNulls<Any?>(k)
        var setSize = 21
        if (k > 5) setSize += 4.0.pow(ceil(log(k * 3.0, 4.0)).toInt()).toInt()
        if (n <= setSize) {
            val pool = population.toMutableList()
            for (i in 0 until k) {
                val j = randbelow(n - i)
                result[i] = pool[j]
                pool[j] = pool[n - i - 1]
            }
        } else {
            val selected = HashSet<Int>()
            for (i in 0 until k) {
                var j = randbelow(n)
                while (!selected.add(j)) j = randbelow(n)
                result[i] = population[j]
            }
        }
        return (result as Array<T>).toList()
    }

    private companion object {
        const val N = 624
        const val M = 397
        val MATRIX_A = 0x9908b0df.toInt()
        val UPPER_MASK = 0x80000000.toInt()
        const val LOWER_MASK = 0x7fffffff
    }
}
