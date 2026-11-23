package io.github.univers629.millet.core

/** Calls are serialized by the controller monitor and the shell lock. */
internal class ConditionalDozeLifecycle {
    data class Conditions(
        val screenOn: Boolean?,
        val charging: Boolean?,
        val enabled: Boolean,
        val ready: Boolean,
        val stopped: Boolean,
        val forced: Boolean?,
        val deepIdle: Boolean?,
    ) {
        val permitsIdle: Boolean
            get() = screenOn == false && charging == false && enabled && ready && !stopped
        val permitsForce: Boolean
            get() = permitsIdle && forced == false && deepIdle == false
    }

    private var epoch = 0L
    private var pending: Long? = null
    private var executing: Long? = null
    private var stopped = false
    var ownsForce: Boolean = false
        private set

    fun schedule(conditions: Conditions): Long? {
        cancel()
        if (conditions.forced == false) ownsForce = false
        if (stopped || ownsForce || !conditions.permitsForce) return null
        return epoch.also { pending = it }
    }

    fun cancel() {
        epoch++
        pending = null
    }

    fun cancelPendingForIdle() {
        // A synchronous state callback during force-idle does not cancel its own acquisition.
        if (executing == null) cancel()
    }

    fun stop() {
        stopped = true
        cancel()
    }

    fun run(
        token: Long,
        read: () -> Conditions,
        enable: () -> Boolean,
        force: () -> Boolean,
        unforce: () -> Boolean,
    ): Boolean {
        if (stopped || pending != token || epoch != token) return false
        pending = null
        executing = token
        try {
            val beforeEnable = read()
            if (stopped || epoch != token || !beforeEnable.permitsForce || !enable()) return false
            val beforeForce = read()
            if (stopped || epoch != token || !beforeForce.permitsForce) return false
            val succeeded = force()
            val after = read()
            // The pre-command force flag was confirmed false. State 5/6 alone is not ownership.
            ownsForce = after.forced == true
            if (!succeeded || stopped || epoch != token || !after.permitsIdle) {
                release({ read().forced }, unforce)
                return false
            }
            return ownsForce
        } finally {
            executing = null
        }
    }

    fun release(readForced: () -> Boolean?, unforce: () -> Boolean): Boolean {
        // Android exposes no owner token for an external unforce/re-force cycle.
        if (!ownsForce) return false
        when (readForced()) {
            false -> {
                ownsForce = false
                return true
            }
            null -> return false
            true -> {
                unforce()
                // A failed shell call can change the flag; a successful code alone cannot clear ownership.
                if (readForced() == false) {
                    ownsForce = false
                    return true
                }
            }
        }
        return false
    }
}
