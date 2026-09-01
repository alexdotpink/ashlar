package pink.alex.ashlar.config.internal

import pink.alex.ashlar.config.ConfigKeyPath
import pink.alex.ashlar.config.ConfigProblem
import pink.alex.ashlar.config.ConfigProblemCategory
import pink.alex.ashlar.config.ConfigProblemSeverity
import pink.alex.ashlar.config.ConfigValidationScope
import pink.alex.ashlar.config.codegen.ConfigValidator
import kotlin.reflect.KProperty1

internal fun <T : Any> validateConfig(
    path: String,
    value: T,
    validators: List<ConfigValidator<T>>,
): List<ConfigProblem> {
    val scope = DefaultValidationScope(path, value)
    validators.forEach { validator -> validator.validate(scope) }
    return scope.problems
}

private class DefaultValidationScope<T : Any>(
    private val path: String,
    override val current: T,
) : ConfigValidationScope<T> {
    val problems: MutableList<ConfigProblem> = mutableListOf()

    override fun requireValue(
        condition: Boolean,
        property: KProperty1<T, *>,
        message: () -> String,
    ) {
        if (!condition) add(property, ConfigProblemSeverity.ERROR, message())
    }

    override fun warnIf(
        condition: Boolean,
        property: KProperty1<T, *>,
        message: () -> String,
    ) {
        if (condition) add(property, ConfigProblemSeverity.WARNING, message())
    }

    private fun add(
        property: KProperty1<T, *>,
        severity: ConfigProblemSeverity,
        message: String,
    ) {
        problems += ConfigProblem(
            path = path,
            key = ConfigKeyPath(property.name.toKebabCase()),
            category = ConfigProblemCategory.VALIDATION,
            severity = severity,
            message = message,
        )
    }

    private fun String.toKebabCase(): String = replace(Regex("([a-z0-9])([A-Z])"), "$1-$2").lowercase()
}
