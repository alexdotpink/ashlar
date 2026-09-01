package pink.alex.ashlar.config.internal

import pink.alex.ashlar.config.ConfigKeyPath
import pink.alex.ashlar.config.ConfigProblem
import pink.alex.ashlar.config.ConfigProblemCategory
import pink.alex.ashlar.config.ConfigProblemSeverity
import pink.alex.ashlar.config.ConfigValidationScope
import pink.alex.ashlar.config.codegen.ConfigValidator
import pink.alex.ashlar.config.ConfigSourceLocation
import kotlin.reflect.KProperty1

internal fun <T : Any> validateConfig(
    path: String,
    value: T,
    validators: List<ConfigValidator<T>>,
    keyNames: Map<String, String> = emptyMap(),
    locate: (ConfigKeyPath) -> ConfigSourceLocation? = { null },
): List<ConfigProblem> {
    val scope = DefaultValidationScope(path, value, keyNames, locate)
    validators.forEach { validator -> validator.validate(scope) }
    return scope.problems
}

private class DefaultValidationScope<T : Any>(
    private val path: String,
    override val current: T,
    private val keyNames: Map<String, String>,
    private val locate: (ConfigKeyPath) -> ConfigSourceLocation?,
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
        val key = ConfigKeyPath(keyNames[property.name] ?: property.name.toKebabCase())
        problems += ConfigProblem(
            path = path,
            key = key,
            category = ConfigProblemCategory.VALIDATION,
            severity = severity,
            message = message,
            location = locate(key),
        )
    }

    private fun String.toKebabCase(): String =
        replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1-$2")
            .replace(Regex("([a-z0-9])([A-Z])"), "$1-$2")
            .lowercase()
}
