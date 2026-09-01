package pink.alex.ashlar.di.ksp

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSName
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeArgument
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.Nullability
import com.google.devtools.ksp.symbol.Variance
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DiModelReaderTest {
    private val reader = DiModelReader()

    @Test
    fun `reader retains recursive closed type arguments`() {
        val string = type(classDeclaration("kotlin", "String"), label = "kotlin.String")
        val list = type(
            classDeclaration("kotlin.collections", "List"),
            arguments = listOf(argument(Variance.INVARIANT, string)),
            label = "kotlin.collections.List<kotlin.String>",
        )
        val repository = type(
            classDeclaration("example", "Repository"),
            arguments = listOf(argument(Variance.INVARIANT, list)),
            label = "example.Repository<kotlin.collections.List<kotlin.String>>",
        )

        assertEquals(
            DependencyTypeModel(
                packageName = "example",
                typeNames = listOf("Repository"),
                arguments = listOf(
                    DependencyTypeModel(
                        packageName = "kotlin.collections",
                        typeNames = listOf("List"),
                        arguments = listOf(DependencyTypeModel("kotlin", listOf("String"))),
                    ),
                ),
            ),
            reader.dependencyType(repository, "Dependency parameter 'example.Consumer.repository'"),
        )
    }

    @Test
    fun `reader rejects star projections with an actionable diagnostic`() {
        val repository = type(
            classDeclaration("example", "Repository"),
            arguments = listOf(argument(Variance.STAR)),
            label = "example.Repository<*>",
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            reader.dependencyType(repository, "Dependency parameter 'example.Consumer.repository'")
        }

        assertEquals(
            "Dependency parameter 'example.Consumer.repository' contains a star projection; " +
                "dependencies must use closed types",
            failure.message,
        )
    }

    @Test
    fun `reader rejects unresolved type parameters and use-site variance`() {
        val typeParameter = type(
            declaration = proxy<KSTypeParameter>(
                "getSimpleName" to name("T"),
            ),
            label = "T",
        )
        val unresolved = type(
            classDeclaration("example", "Repository"),
            arguments = listOf(argument(Variance.INVARIANT, typeParameter)),
            label = "example.Repository<T>",
        )
        val covariant = type(
            classDeclaration("example", "Repository"),
            arguments = listOf(argument(Variance.COVARIANT, type(classDeclaration("kotlin", "String")))),
            label = "example.Repository<out kotlin.String>",
        )

        assertEquals(
            "Dependency parameter 'example.Consumer.repository' contains unresolved type parameter 'T'; " +
                "dependencies must use closed types",
            assertFailsWith<IllegalArgumentException> {
                reader.dependencyType(unresolved, "Dependency parameter 'example.Consumer.repository'")
            }.message,
        )
        assertEquals(
            "Dependency parameter 'example.Consumer.repository' contains use-site variance 'out'; " +
                "dependency arguments must be invariant",
            assertFailsWith<IllegalArgumentException> {
                reader.dependencyType(covariant, "Dependency parameter 'example.Consumer.repository'")
            }.message,
        )
    }

    @Test
    fun `reader rejects nullable nested arguments`() {
        val nullableString = type(
            declaration = classDeclaration("kotlin", "String"),
            nullability = Nullability.NULLABLE,
            label = "kotlin.String?",
        )
        val repository = type(
            classDeclaration("example", "Repository"),
            arguments = listOf(argument(Variance.INVARIANT, nullableString)),
            label = "example.Repository<kotlin.String?>",
        )

        assertEquals(
            "Dependency parameter 'example.Consumer.repository' has a nullable nested type argument " +
                "'kotlin.String?'; nullable dependency arguments are not supported",
            assertFailsWith<IllegalArgumentException> {
                reader.dependencyType(repository, "Dependency parameter 'example.Consumer.repository'")
            }.message,
        )
    }

    private fun classDeclaration(packageName: String, simpleName: String): KSClassDeclaration =
        proxy(
            "getPackageName" to name(packageName),
            "getSimpleName" to name(simpleName),
            "getParentDeclaration" to null,
        )

    private fun type(
        declaration: com.google.devtools.ksp.symbol.KSDeclaration,
        arguments: List<KSTypeArgument> = emptyList(),
        nullability: Nullability = Nullability.NOT_NULL,
        label: String = declaration.simpleName.asString(),
    ): KSType = proxy(
        "getDeclaration" to declaration,
        "getArguments" to arguments,
        "getNullability" to nullability,
        "toString" to label,
    )

    private fun argument(variance: Variance, type: KSType? = null): KSTypeArgument =
        proxy(
            "getVariance" to variance,
            "getType" to type?.let { resolved ->
                proxy<KSTypeReference>("resolve" to resolved)
            },
        )

    private fun name(value: String): KSName = proxy("asString" to value)

    private inline fun <reified T : Any> proxy(vararg methods: Pair<String, Any?>): T {
        val values = methods.toMap()
        return Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { instance, method, arguments ->
            when (method.name) {
                "equals" -> instance === arguments?.singleOrNull()
                "hashCode" -> System.identityHashCode(instance)
                else -> if (method.name in values) values[method.name] else defaultValue(method)
            }
        } as T
    }

    private fun defaultValue(method: Method): Any? = when (method.returnType) {
        Boolean::class.javaPrimitiveType -> false
        Int::class.javaPrimitiveType -> 0
        else -> null
    }
}
