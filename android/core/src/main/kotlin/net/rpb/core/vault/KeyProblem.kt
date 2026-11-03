package net.rpb.core.vault

/** Why the wrapping key can't be used right now (ADR 0028). */
sealed class KeyProblem(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The screen lock wasn't passed within the window: ask for it, then try again. */
    class NeedsAuthentication(cause: Throwable? = null) : KeyProblem("screen lock needed", cause)

    /** The phone has no screen lock, so a key bound to it can't exist. */
    class NoScreenLock : KeyProblem("no screen lock")

    /** The key is gone for good (the screen lock was removed): what it wrapped can't be read again. */
    class Invalidated(cause: Throwable? = null) : KeyProblem("key invalidated", cause)

    companion object {
        private const val MAX_DEPTH = 16

        /**
         * The problem named anywhere in [e]'s cause chain, the first one found from the outside in; null if none.
         * [invalidated] and [needsAuthentication] recognise the platform's exceptions.
         */
        fun of(e: Throwable, invalidated: (Throwable) -> Boolean, needsAuthentication: (Throwable) -> Boolean): KeyProblem? {
            for (cause in generateSequence(e) { it.cause }.take(MAX_DEPTH)) {
                if (invalidated(cause)) return Invalidated(e)
                if (needsAuthentication(cause)) return NeedsAuthentication(e)
            }
            return null
        }
    }
}
