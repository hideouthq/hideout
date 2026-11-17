package gg.hideouthq.core.data

import kotlinx.coroutines.CancellationException

internal suspend fun <T> quietly(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}
