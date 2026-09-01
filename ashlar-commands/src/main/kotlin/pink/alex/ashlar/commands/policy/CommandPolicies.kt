package pink.alex.ashlar.commands.policy

import pink.alex.ashlar.commands.CommandInvocation
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Stable interception points used by built-in and custom command policies. */
public enum class CommandPolicyPhase {
    BEFORE_RESOLUTION,
    AFTER_RESOLUTION,
    BEFORE_HANDLER,
    AFTER_HANDLER,
}

/** Meta-annotation linking a policy annotation to its injected interceptor. */
@Target(AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class CommandPolicy(
    val interceptor: kotlin.reflect.KClass<out CommandPolicyInterceptor<*>>,
    val phase: CommandPolicyPhase,
    val order: Int = 0,
)

/** Runtime implementation of one typed policy annotation. */
public interface CommandPolicyInterceptor<A : Annotation> {
    public suspend fun intercept(
        annotation: A,
        context: CommandPolicyContext,
        next: suspend () -> Any?,
    ): Any?
}

/** Input shared by policy interceptors without exposing Brigadier or Paper internals. */
public data class CommandPolicyContext(
    public val invocation: CommandInvocation,
    public val canonicalArguments: List<String>,
)

/** Atomic state used by cooldown, rate-limit, confirmation, and single-flight policies. */
public interface CommandPolicyState {
    public fun update(
        key: String,
        transform: (CommandPolicyRecord?) -> CommandPolicyRecord?,
    ): CommandPolicyRecord?
}

/** Opaque timestamped policy value suitable for local or distributed implementations. */
public data class CommandPolicyRecord(
    public val value: String,
    public val expiresAt: Instant?,
)

/** Thread-safe ephemeral policy state. */
public class InMemoryCommandPolicyState(
    private val clock: Clock = Clock.systemUTC(),
) : CommandPolicyState {
    private val values: MutableMap<String, CommandPolicyRecord> = mutableMapOf()

    override fun update(
        key: String,
        transform: (CommandPolicyRecord?) -> CommandPolicyRecord?,
    ): CommandPolicyRecord? = synchronized(values) {
        val current = values[key]?.takeUnless { value ->
            value.expiresAt?.let { expiry -> !expiry.isAfter(clock.instant()) } == true
        }
        transform(current).also { updated ->
            if (updated == null) values.remove(key) else values[key] = updated
        }
    }
}

public enum class CooldownMode { SUCCESS, ATTEMPT, ACCEPTED }

public enum class RateLimitMode { TOKEN_BUCKET, SLIDING_WINDOW }

/** Immutable built-in policy metadata emitted by command KSP. */
public sealed interface CommandPolicyDefinition {
    public data class Cooldown(
        public val seconds: Long,
        public val mode: CooldownMode,
    ) : CommandPolicyDefinition

    public data class RateLimit(
        public val permits: Int,
        public val seconds: Long,
        public val mode: RateLimitMode,
    ) : CommandPolicyDefinition

    public data object SingleFlight : CommandPolicyDefinition

    public data class Confirm(public val seconds: Long) : CommandPolicyDefinition

    public data class Custom(
        public val annotation: Annotation,
        public val interceptor: kotlin.reflect.KClass<out CommandPolicyInterceptor<*>>,
        public val phase: CommandPolicyPhase,
        public val order: Int,
    ) : CommandPolicyDefinition
}

/** Limits successful invocations by sender unless a custom interceptor changes the key. */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class Cooldown(
    val value: Long,
    val mode: CooldownMode = CooldownMode.SUCCESS,
)

/** Limits invocation volume by sender. [per] is measured in seconds. */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class RateLimit(
    val permits: Int,
    val per: Long,
    val mode: RateLimitMode = RateLimitMode.TOKEN_BUCKET,
)

/** Rejects a second overlapping invocation for the same sender and route. */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class SingleFlight

/** Requires the canonical semantic invocation to be repeated within the expiry window. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class Confirm(val expiresAfterSeconds: Long = 30)

/** Cancels unfinished work when its executor retires instead of only dropping its response. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class CancelOnExecutorRetire

/** Creates a policy record expiring after [duration]. */
public fun policyRecord(
    value: String,
    duration: Duration,
    clock: Clock,
): CommandPolicyRecord = CommandPolicyRecord(value, clock.instant().plus(duration))
