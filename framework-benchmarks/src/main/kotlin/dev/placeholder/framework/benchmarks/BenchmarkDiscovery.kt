package dev.placeholder.framework.benchmarks

import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.streams.asSequence

/** Finds top-level or static zero-argument getters returning [BenchmarkSuite]. */
public object BenchmarkDiscovery {
    /** Discovers suites from compiled class directories without scanning production dependencies. */
    public fun discover(classDirectories: Iterable<Path>): List<BenchmarkSuite> {
        val roots = classDirectories.map(Path::toAbsolutePath).map(Path::normalize).distinct()
        require(roots.isNotEmpty()) { "No benchmark class directories were supplied" }
        val loader = URLClassLoader(
            roots.filter(Files::exists).map { it.toUri().toURL() }.toTypedArray(),
            BenchmarkSuite::class.java.classLoader,
        )
        loader.use {
            val suites = roots.asSequence()
                .filter(Files::isDirectory)
                .flatMap(::classNames)
                .distinct()
                .flatMap { className -> suitesIn(className, loader) }
                .toList()
            require(suites.isNotEmpty()) {
                "No public static BenchmarkSuite declarations were found in ${roots.joinToString()}"
            }
            require(suites.flatMap(BenchmarkSuite::scenarios).map(BenchmarkScenario::id).distinct().size ==
                suites.sumOf { it.scenarios.size }) {
                "Discovered benchmark suites contain duplicate scenario ids"
            }
            return suites.sortedBy(BenchmarkSuite::namespace)
        }
    }

    private fun classNames(root: Path): Sequence<String> = Files.walk(root).use { paths ->
        paths.asSequence()
            .filter(Path::isRegularFile)
            .filter { it.extension == "class" }
            .filterNot { it.fileName.toString() == "module-info.class" }
            .filterNot { '$' in it.fileName.toString() }
            .map { path ->
                root.relativize(path).toString()
                    .removeSuffix(".class")
                    .replace(root.fileSystem.separator, ".")
            }
            .toList()
            .asSequence()
    }

    private fun suitesIn(className: String, loader: ClassLoader): Sequence<BenchmarkSuite> {
        val type = try {
            Class.forName(className, false, loader)
        } catch (_: LinkageError) {
            return emptySequence()
        } catch (_: ClassNotFoundException) {
            return emptySequence()
        }
        return type.declaredMethods.asSequence()
            .filter { method ->
                Modifier.isPublic(method.modifiers) &&
                    Modifier.isStatic(method.modifiers) &&
                    method.parameterCount == 0 &&
                    method.returnType == BenchmarkSuite::class.java
            }
            .map { method -> method.invoke(null) as BenchmarkSuite }
    }
}
