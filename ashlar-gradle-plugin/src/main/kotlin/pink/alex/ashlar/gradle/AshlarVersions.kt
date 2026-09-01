package pink.alex.ashlar.gradle

import java.util.Properties

internal object AshlarVersions {
    private val values: Properties = Properties().apply {
        AshlarVersions::class.java.getResourceAsStream("ashlar-version.properties").use { input ->
            checkNotNull(input) { "Missing embedded ashlar-version.properties" }
            load(input)
        }
    }

    val ashlar: String = values.required("ashlar.version")
    val kotlin: String = values.required("kotlin.version")
    val paper: String = values.required("paper.version")

    private fun Properties.required(key: String): String =
        checkNotNull(getProperty(key)) { "Missing embedded version property: $key" }
}
