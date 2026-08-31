package dev.placeholder.framework.gradle

import java.util.Properties

internal object FrameworkVersions {
    private val values: Properties = Properties().apply {
        FrameworkVersions::class.java.getResourceAsStream("framework-version.properties").use { input ->
            checkNotNull(input) { "Missing embedded framework-version.properties" }
            load(input)
        }
    }

    val framework: String = values.required("framework.version")
    val kotlin: String = values.required("kotlin.version")
    val paper: String = values.required("paper.version")

    private fun Properties.required(key: String): String =
        checkNotNull(getProperty(key)) { "Missing embedded version property: $key" }
}
