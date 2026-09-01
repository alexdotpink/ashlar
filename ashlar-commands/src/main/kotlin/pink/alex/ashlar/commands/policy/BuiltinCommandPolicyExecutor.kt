package pink.alex.ashlar.commands.policy

import pink.alex.ashlar.commands.CommandInvocation
import pink.alex.ashlar.commands.reject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.ceil

/** Handwritten execution of the policies represented by generated route metadata. */
internal class BuiltinCommandPolicyExecutor(
    private val clock: Clock,
    private val state: CommandPolicyState,
) {
    fun beforeResolution(
        policies: List<CommandPolicyDefinition>,
        invocation: CommandInvocation,
        routeIdentity: String,
    ) {
        policies.filterIsInstance<CommandPolicyDefinition.RateLimit>().forEach { policy ->
            rateLimit(policy, invocation, routeIdentity)
        }
        policies.filterIsInstance<CommandPolicyDefinition.Cooldown>().forEach { policy ->
            if (policy.mode == CooldownMode.ATTEMPT) {
                acquireCooldown(policy, invocation, routeIdentity)
            } else {
                checkCooldown(invocation, routeIdentity)
            }
        }
    }

    fun afterResolution(
        policies: List<CommandPolicyDefinition>,
        invocation: CommandInvocation,
        routeIdentity: String,
        canonicalArguments: List<String>,
    ) {
        policies.filterIsInstance<CommandPolicyDefinition.Cooldown>()
            .filter { policy -> policy.mode == CooldownMode.ACCEPTED }
            .forEach { policy -> acquireCooldown(policy, invocation, routeIdentity) }
        policies.filterIsInstance<CommandPolicyDefinition.Confirm>().forEach { policy ->
            confirm(policy, invocation, routeIdentity, canonicalArguments)
        }
    }

    suspend fun <T> invokeHandler(
        policies: List<CommandPolicyDefinition>,
        invocation: CommandInvocation,
        routeIdentity: String,
        handler: suspend () -> T,
    ): T {
        val flightToken = if (policies.any { policy -> policy is CommandPolicyDefinition.SingleFlight }) {
            acquireSingleFlight(invocation, routeIdentity)
        } else {
            null
        }
        try {
            val result = handler()
            policies.filterIsInstance<CommandPolicyDefinition.Cooldown>()
                .filter { policy -> policy.mode == CooldownMode.SUCCESS }
                .forEach { policy -> recordCooldown(policy, invocation, routeIdentity) }
            return result
        } finally {
            if (flightToken != null) releaseSingleFlight(invocation, routeIdentity, flightToken)
        }
    }

    private fun checkCooldown(
        invocation: CommandInvocation,
        routeIdentity: String,
    ) {
        var current: CommandPolicyRecord? = null
        state.update(key("cooldown", invocation, routeIdentity)) { record ->
            current = record
            record
        }
        current?.let(::rejectCooldown)
    }

    private fun acquireCooldown(
        policy: CommandPolicyDefinition.Cooldown,
        invocation: CommandInvocation,
        routeIdentity: String,
    ) {
        var rejected: CommandPolicyRecord? = null
        state.update(key("cooldown", invocation, routeIdentity)) { current ->
            if (current == null) {
                policyRecord(COOLDOWN_VALUE, Duration.ofSeconds(policy.seconds), clock)
            } else {
                rejected = current
                current
            }
        }
        rejected?.let(::rejectCooldown)
    }

    private fun recordCooldown(
        policy: CommandPolicyDefinition.Cooldown,
        invocation: CommandInvocation,
        routeIdentity: String,
    ) {
        state.update(key("cooldown", invocation, routeIdentity)) {
            policyRecord(COOLDOWN_VALUE, Duration.ofSeconds(policy.seconds), clock)
        }
    }

    private fun rejectCooldown(record: CommandPolicyRecord): Nothing {
        val remaining = record.expiresAt
            ?.let { expiry -> Duration.between(clock.instant(), expiry).toNanos().coerceAtLeast(0) }
            ?.let { nanos -> ceil(nanos / NANOS_PER_SECOND).toLong().coerceAtLeast(1) }
            ?: 1
        reject("Wait $remaining second${if (remaining == 1L) "" else "s"} before using this command again.")
    }

    private fun rateLimit(
        policy: CommandPolicyDefinition.RateLimit,
        invocation: CommandInvocation,
        routeIdentity: String,
    ) {
        require(policy.permits > 0) { "Rate-limit permits must be positive" }
        require(policy.seconds > 0) { "Rate-limit duration must be positive" }
        var accepted = false
        state.update(key("rate-limit", invocation, routeIdentity)) { current ->
            val now = clock.instant()
            val result = when (policy.mode) {
                RateLimitMode.TOKEN_BUCKET -> tokenBucket(policy, current, now)
                RateLimitMode.SLIDING_WINDOW -> slidingWindow(policy, current, now)
            }
            accepted = result.accepted
            result.record
        }
        if (!accepted) reject("You are using this command too quickly.")
    }

    private fun tokenBucket(
        policy: CommandPolicyDefinition.RateLimit,
        current: CommandPolicyRecord?,
        now: Instant,
    ): RateLimitResult {
        val previous = current?.value?.split(':')
        val previousTokens = previous?.getOrNull(0)?.toDoubleOrNull() ?: policy.permits.toDouble()
        val previousMillis = previous?.getOrNull(1)?.toLongOrNull() ?: now.toEpochMilli()
        val elapsedMillis = (now.toEpochMilli() - previousMillis).coerceAtLeast(0)
        val replenished = (previousTokens + elapsedMillis.toDouble() * policy.permits / (policy.seconds * 1_000.0))
            .coerceAtMost(policy.permits.toDouble())
        val accepted = replenished >= 1.0
        val remaining = if (accepted) replenished - 1.0 else replenished
        return RateLimitResult(
            accepted,
            CommandPolicyRecord(
                "$remaining:${now.toEpochMilli()}",
                now.plusSeconds(policy.seconds),
            ),
        )
    }

    private fun slidingWindow(
        policy: CommandPolicyDefinition.RateLimit,
        current: CommandPolicyRecord?,
        now: Instant,
    ): RateLimitResult {
        val threshold = now.minusSeconds(policy.seconds).toEpochMilli()
        val recent = current?.value.orEmpty().split(',')
            .mapNotNull(String::toLongOrNull)
            .filter { timestamp -> timestamp > threshold }
        val accepted = recent.size < policy.permits
        val updated = if (accepted) recent + now.toEpochMilli() else recent
        return RateLimitResult(
            accepted,
            CommandPolicyRecord(updated.joinToString(","), now.plusSeconds(policy.seconds)),
        )
    }

    private fun confirm(
        policy: CommandPolicyDefinition.Confirm,
        invocation: CommandInvocation,
        routeIdentity: String,
        canonicalArguments: List<String>,
    ) {
        require(policy.seconds > 0) { "Confirmation expiry must be positive" }
        val semanticIdentity = buildString {
            append(routeIdentity)
            canonicalArguments.forEach { argument ->
                append('\u0000')
                append(argument)
            }
        }
        val stateKey = key("confirm", invocation, semanticIdentity)
        var confirmed = false
        state.update(stateKey) { current ->
            if (current == null) {
                policyRecord(CONFIRM_VALUE, Duration.ofSeconds(policy.seconds), clock)
            } else {
                confirmed = true
                null
            }
        }
        if (!confirmed) {
            reject("Repeat this command within ${policy.seconds} seconds to confirm.")
        }
    }

    private fun acquireSingleFlight(
        invocation: CommandInvocation,
        routeIdentity: String,
    ): String {
        val token = UUID.randomUUID().toString()
        var accepted = false
        state.update(key("single-flight", invocation, routeIdentity)) { current ->
            if (current == null) {
                accepted = true
                CommandPolicyRecord(token, null)
            } else {
                current
            }
        }
        if (!accepted) reject("This command is already running.")
        return token
    }

    private fun releaseSingleFlight(
        invocation: CommandInvocation,
        routeIdentity: String,
        token: String,
    ) {
        state.update(key("single-flight", invocation, routeIdentity)) { current ->
            current?.takeUnless { record -> record.value == token }
        }
    }

    private fun key(
        kind: String,
        invocation: CommandInvocation,
        semanticIdentity: String,
    ): String {
        val sender = invocation.sender.uniqueId?.toString() ?: invocation.sender.name
        val bytes = "$kind\u0000$sender\u0000$semanticIdentity".toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte -> "%02x".format(byte) }
    }

    private data class RateLimitResult(
        val accepted: Boolean,
        val record: CommandPolicyRecord,
    )

    private companion object {
        const val COOLDOWN_VALUE = "cooldown"
        const val CONFIRM_VALUE = "pending"
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
