package pink.alex.ashlar.config

/** Prevents plug-in enable when one required configuration cannot be accepted. */
public class ConfigStartupException(
    public val documentPath: String,
    public val problems: List<ConfigProblem> = emptyList(),
    public val operationProblem: ConfigOperationProblem? = null,
) : IllegalStateException(
    buildString {
        append("Configuration '").append(documentPath).append("' could not be loaded")
        if (problems.isNotEmpty()) append(": ").append(problems.joinToString { it.message })
        operationProblem?.let { append(": ").append(it.message) }
    },
)
