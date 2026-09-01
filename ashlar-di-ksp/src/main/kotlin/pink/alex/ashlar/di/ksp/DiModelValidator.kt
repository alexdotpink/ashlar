package pink.alex.ashlar.di.ksp

internal class DiModelValidator {
    fun validate(factory: FactoryModel): List<String> = buildList {
        if (factory.typeParameters.isNotEmpty()) {
            add(
                "Injectable dependency '${factory.qualifiedName}' declares unresolved type parameters " +
                    factory.typeParameters.joinToString(prefix = "<", postfix = ">") +
                    "; bind closed generic instances explicitly instead",
            )
        }
        factory.parameters.forEach { parameter ->
            if (parameter.type.typeNames.isEmpty()) {
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
