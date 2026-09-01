package pink.alex.ashlar.incubator

/**
 * Marks preview APIs that may change or disappear without a SemVer-major release.
 *
 * Stable framework modules never expose declarations that require experimental Kotlin compiler
 * flags. Preview declarations live in the incubator artifact and require an explicit opt-in.
 */
@RequiresOptIn(
    message = "This framework API is experimental and is not covered by stable API compatibility.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.ANNOTATION_CLASS,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.TYPEALIAS,
    AnnotationTarget.CONSTRUCTOR,
)
public annotation class ExperimentalAshlarApi
