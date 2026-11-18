package app.refocus.core.lf

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * A small fork-join helper for the decoder. Uses its own pool of background
 * threads (lower priority than the UI) instead of the common pool, so heavy
 * processing never starves coroutine dispatchers.
 */
object Parallel {
    val threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)

    private val pool: ExecutorService = Executors.newFixedThreadPool(threads, object : ThreadFactory {
        private val n = AtomicInteger()
        override fun newThread(r: Runnable) = Thread(r, "lf-worker-${n.incrementAndGet()}").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    })

    /** Runs [body] for every index in [0, n) split into contiguous ranges, in parallel. */
    fun forRange(n: Int, body: (from: Int, to: Int) -> Unit) {
        if (n <= 0) return
        val parts = minOf(threads, n)
        if (parts == 1 || Thread.currentThread().name.startsWith("lf-worker")) {
            body(0, n)
            return
        }
        val tasks = (0 until parts).map { p ->
            val from = (n.toLong() * p / parts).toInt()
            val to = (n.toLong() * (p + 1) / parts).toInt()
            Callable { body(from, to) }
        }
        try {
            pool.invokeAll(tasks).forEach { it.get() }
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    inline fun forEach(n: Int, crossinline body: (i: Int) -> Unit) =
        forRange(n) { a, b -> for (i in a until b) body(i) }

    /** Number of parts [map] and [mapParts] split [0, n) into. */
    fun parts(n: Int): Int =
        if (n <= 0) 0 else if (Thread.currentThread().name.startsWith("lf-worker")) 1 else minOf(threads, n)

    /** Like [map], but also passes the part index (stable for a given n), e.g. to reuse buffers. */
    fun <T> mapParts(n: Int, body: (part: Int, from: Int, to: Int) -> T): List<T> {
        val parts = parts(n)
        if (parts == 0) return emptyList()
        if (parts == 1) return listOf(body(0, 0, n))
        val tasks = (0 until parts).map { p ->
            val from = (n.toLong() * p / parts).toInt()
            val to = (n.toLong() * (p + 1) / parts).toInt()
            Callable { body(p, from, to) }
        }
        try {
            return pool.invokeAll(tasks).map { it.get() }
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /** Splits [0, n) into one range per worker and returns each worker's result. */
    fun <T> map(n: Int, body: (from: Int, to: Int) -> T): List<T> {
        if (n <= 0) return emptyList()
        val parts = minOf(threads, n)
        if (parts == 1 || Thread.currentThread().name.startsWith("lf-worker")) return listOf(body(0, n))
        val tasks = (0 until parts).map { p ->
            val from = (n.toLong() * p / parts).toInt()
            val to = (n.toLong() * (p + 1) / parts).toInt()
            Callable { body(from, to) }
        }
        try {
            return pool.invokeAll(tasks).map { it.get() }
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }
}
