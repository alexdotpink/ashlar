plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
}

group = providers.gradleProperty("framework.group").get()
version = providers.gradleProperty("framework.version").get()

allprojects {
    group = rootProject.group
    version = rootProject.version
}

tasks.register("integrationTest") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs the pinned Paper and Folia integration fixtures."
    dependsOn(
        ":integration-test-fixture:paperIntegrationTest",
        ":integration-test-fixture:foliaIntegrationTest",
    )
}

tasks.register("checkKotlinAbi") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Checks the committed Kotlin ABI baselines for all published Kotlin artifacts."
    dependsOn(
        ":kernel:checkKotlinAbi",
        ":framework-di:checkKotlinAbi",
        ":framework-di-ksp:checkKotlinAbi",
        ":framework-commands:checkKotlinAbi",
        ":framework-commands-ksp:checkKotlinAbi",
        ":framework-events:checkKotlinAbi",
        ":framework-events-ksp:checkKotlinAbi",
        ":framework-testkit:checkKotlinAbi",
        ":framework-incubator:checkKotlinAbi",
        ":framework-gradle-plugin:checkKotlinAbi",
    )
}

tasks.register("updateKotlinAbi") {
    group = "build setup"
    description = "Updates the Kotlin ABI baselines for all published Kotlin artifacts."
    dependsOn(
        ":kernel:updateKotlinAbi",
        ":framework-di:updateKotlinAbi",
        ":framework-di-ksp:updateKotlinAbi",
        ":framework-commands:updateKotlinAbi",
        ":framework-commands-ksp:updateKotlinAbi",
        ":framework-events:updateKotlinAbi",
        ":framework-events-ksp:updateKotlinAbi",
        ":framework-testkit:updateKotlinAbi",
        ":framework-incubator:updateKotlinAbi",
        ":framework-gradle-plugin:updateKotlinAbi",
    )
}
