package io.github.selfupdater

/** Exponential-backoff settings used for retryable network failures. */
public data class RetryPolicy(
    /** Total attempts, including the first request. */
    public val maxAttempts: Int = 3,
    /** Delay before the second attempt. */
    public val initialDelayMillis: Long = 500,
    /** Maximum delay between attempts. */
    public val maxDelayMillis: Long = 4_000,
    /** Multiplier applied after each failed attempt. */
    public val multiplier: Double = 2.0,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
        require(initialDelayMillis >= 0) { "initialDelayMillis cannot be negative" }
        require(maxDelayMillis >= initialDelayMillis) {
            "maxDelayMillis cannot be less than initialDelayMillis"
        }
        require(multiplier >= 1.0) { "multiplier must be at least 1.0" }
    }

    internal fun delayBeforeAttempt(attemptIndex: Int): Long {
        if (attemptIndex <= 0) return 0
        var value = initialDelayMillis.toDouble()
        repeat(attemptIndex - 1) { value = (value * multiplier).coerceAtMost(maxDelayMillis.toDouble()) }
        return value.toLong().coerceAtMost(maxDelayMillis)
    }
}

