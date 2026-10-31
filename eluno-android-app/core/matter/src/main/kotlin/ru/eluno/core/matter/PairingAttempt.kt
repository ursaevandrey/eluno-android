package ru.eluno.core.matter

/** Completed commissioning is already released by CHIP; stopping it again reports error 0x33. */
internal suspend fun <T> pairingAttempt(
    stop: () -> Unit,
    clearListener: () -> Unit,
    block: suspend () -> T,
): T {
    var failure: Throwable? = null
    try {
        return block()
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        // Cleanup errors must never replace the original timeout, cancellation or protocol failure.
        var cleanupFailure: Throwable? = null
        if (failure != null) {
            try { stop() } catch (error: Throwable) { cleanupFailure = error }
        }
        try { clearListener() } catch (error: Throwable) {
            if (cleanupFailure == null) cleanupFailure = error else cleanupFailure.addSuppressed(error)
        }
        cleanupFailure?.let { cleanup ->
            if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
        }
    }
}
