package dev.placeholder.framework.di.ksp

internal class DiModelValidator {
    fun validate(factory: FactoryModel): List<String> = buildList {
        factory.parameters.forEach { parameter ->
            if (parameter.typeNames.isEmpty()) {
                add("Dependency parameter '${factory.qualifiedName}.${parameter.name}' has no concrete class type")
            }
        }
    }

    fun validate(root: RootComponentModel): List<String> = buildList {
        root.bindings.forEach { binding ->
            if (binding.typeNames.isEmpty()) {
                add("Root component '${root.qualifiedName}' has an invalid @Binds type")
            }
        }
    }
}
