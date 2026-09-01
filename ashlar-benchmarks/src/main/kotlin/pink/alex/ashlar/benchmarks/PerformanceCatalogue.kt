package pink.alex.ashlar.benchmarks

import kotlinx.serialization.Serializable

/** One public capability covered by a representative performance contract. */
@Serializable
public data class PerformanceCapability(
    public val group: String,
    public val name: String,
    public val contract: BenchmarkId,
)

/** Machine-checkable coverage report for the framework performance catalogue. */
@Serializable
public data class PerformanceCatalogueReport(
    public val capabilityCount: Int,
    public val contractCount: Int,
    public val externalContracts: Set<BenchmarkId>,
    public val problems: List<String>,
) {
    public val complete: Boolean get() = problems.isEmpty()

    /** Throws with every missing or malformed contract, rather than stopping at the first one. */
    public fun checkComplete() {
        check(complete) { problems.joinToString(separator = "\n", prefix = "Performance catalogue is incomplete:\n") }
    }
}

/** Maps framework capabilities to source-owned benchmark scenarios. */
public class PerformanceCatalogue(capabilities: Iterable<PerformanceCapability>) {
    public val capabilities: List<PerformanceCapability> = capabilities.toList()

    init {
        require(this.capabilities.isNotEmpty()) { "A performance catalogue cannot be empty" }
        require(this.capabilities.map { "${it.group}/${it.name}" }.distinct().size == this.capabilities.size) {
            "Performance catalogue capability names must be unique within each group"
        }
    }

    /** Checks declarations, workload profiles, temperatures, and optional release maturity. */
    public fun validate(
        suites: Iterable<BenchmarkSuite>,
        externalContracts: Set<BenchmarkId> = emptySet(),
        releaseReady: Boolean = false,
    ): PerformanceCatalogueReport {
        val scenarios = suites.flatMap(BenchmarkSuite::scenarios).associateBy(BenchmarkScenario::id)
        val problems = mutableListOf<String>()
        capabilities.groupBy(PerformanceCapability::contract).forEach { (contract, covered) ->
            val scenario = scenarios[contract]
            if (scenario == null) {
                if (contract !in externalContracts) {
                    problems += "Missing contract '$contract' for ${covered.joinToString { it.name }}"
                }
                return@forEach
            }
            val profiles = scenario.profiles.mapTo(linkedSetOf(), BenchmarkProfile::name)
            val missingProfiles = REQUIRED_PROFILES - profiles
            if (missingProfiles.isNotEmpty()) {
                problems += "Contract '$contract' is missing profiles ${missingProfiles.joinToString()}"
            }
            val missingTemperatures = REQUIRED_TEMPERATURES - scenario.temperatures
            if (missingTemperatures.isNotEmpty()) {
                problems += "Contract '$contract' is missing temperatures ${missingTemperatures.joinToString()}"
            }
            if (releaseReady && scenario.status != PerformanceContractStatus.CONTRACTUAL) {
                problems += "Contract '$contract' is ${scenario.status}, expected CONTRACTUAL"
            }
        }
        return PerformanceCatalogueReport(
            capabilityCount = capabilities.size,
            contractCount = capabilities.map(PerformanceCapability::contract).distinct().size,
            externalContracts = externalContracts,
            problems = problems.sorted(),
        )
    }

    private companion object {
        val REQUIRED_PROFILES: Set<String> = setOf("small", "typical", "stress")
        val REQUIRED_TEMPERATURES: Set<BenchmarkTemperature> = BenchmarkTemperature.entries.toSet()
    }
}

/** Complete framework capability map. Keep additions beside the feature's first public release. */
public object AshlarPerformanceCatalogue {
    public val catalogue: PerformanceCatalogue by lazy { PerformanceCatalogue(buildList {
        cover("Kernel", "kernel.lifecycle", KERNEL_CAPABILITIES)
        cover("Kernel", "kernel.rollback", listOf("startup rollback"))
        cover("Kernel", "kernel.scheduler-handoff", KERNEL_PLATFORM_CAPABILITIES)
        cover("Dependency injection", "di.graph", DI_GRAPH_CAPABILITIES)
        cover("Dependency injection", "di.invocation-scopes", DI_SCOPE_CAPABILITIES)
        cover("Commands", "commands.dispatch", COMMAND_CAPABILITIES)
        cover("Commands", "commands.direct-control", listOf("matched direct control"))
        cover("Events", "events.delivery", EVENT_DELIVERY_CAPABILITIES)
        cover("Events", "events.queries", EVENT_QUERY_CAPABILITIES)
        cover("Input", "input.prompt", INPUT_CAPABILITIES)
        cover("Items", "items.specification", ITEM_SPEC_CAPABILITIES)
        cover("Items", "items.persistent-codecs", ITEM_DATA_CAPABILITIES)
        cover("Menus", "menus.runtime", MENU_CAPABILITIES)
        cover("Menu storage", "menus.storage", MENU_STORAGE_CAPABILITIES)
        cover("Native hosts", "native.hosts", NATIVE_HOST_CAPABILITIES)
        cover("Build and release", "build.toolchain", BUILD_CAPABILITIES)
        cover("Cross-feature journeys", "sample.typed-chat", JOURNEY_CAPABILITIES)
        cover("Cross-feature journeys", "load.multiplayer", LOAD_CAPABILITIES)
        cover("Cross-feature journeys", "soak.lifecycle", SOAK_CAPABILITIES)
        cover("Benchmark calibration", "commands.direct-control", CALIBRATION_CAPABILITIES)
    }) }

    private fun MutableList<PerformanceCapability>.cover(group: String, contract: String, names: List<String>) {
        names.forEach { name -> add(PerformanceCapability(group, name, BenchmarkId(contract))) }
    }

    private val KERNEL_CAPABILITIES = listOf(
        "component discovery", "start", "task launch", "cancellation and drain", "resource teardown",
    )
    private val KERNEL_PLATFORM_CAPABILITIES = listOf(
        "execution-context fast paths", "Paper scheduler handoff", "Folia scheduler handoff",
    )
    private val DI_GRAPH_CAPABILITIES = listOf(
        "cold graph construction", "cached resolution", "contribution discovery", "factory bindings",
        "generated constructor dispatch", "large graphs",
    )
    private val DI_SCOPE_CAPABILITIES = listOf("plug-in scopes", "invocation scopes")
    private val COMMAND_CAPABILITIES = listOf(
        "registration", "direct parsing", "scanned parsing", "options", "codecs", "resolution", "policies",
        "suggestions", "help", "routes", "responses", "observers", "admission", "scheduling", "completion",
    )
    private val EVENT_DELIVERY_CAPABILITIES = listOf(
        "generated handlers", "observer prefix", "observer continuation", "dynamic registration",
        "application-event fan-out", "cancellation",
    )
    private val EVENT_QUERY_CAPABILITIES = listOf("queries", "captures", "streams", "stream overflow")
    private val INPUT_CAPABILITIES = listOf(
        "prompt acquisition", "conflicts", "chat projection", "parsing", "retries", "cancellation", "deadlines",
        "disconnects", "answer bursts", "composed prompts",
    )
    private val ITEM_SPEC_CAPABILITIES = listOf("specification construction", "editing", "materialization", "capture")
    private val ITEM_DATA_CAPABILITIES = listOf(
        "snapshot envelopes", "checksums", "fingerprints", "canonical data", "persistent codecs", "migrations",
        "HMAC", "payload sizes",
    )
    private val MENU_CAPABILITIES = listOf(
        "render", "state", "Flow invalidation", "conflation", "validation", "reconciliation", "actions",
        "concurrency", "effects", "locals", "navigation", "boundaries", "focused input", "inspection", "feedback",
        "viewers",
    )
    private val MENU_STORAGE_CAPABILITIES = listOf(
        "gesture families", "rules", "proposals", "locks", "local storage", "external storage", "durable commits",
        "journal I/O", "journal replay", "cursor settlement", "mailbox delivery", "conflicts", "recovery",
    )
    private val NATIVE_HOST_CAPABILITIES = listOf(
        "creation", "opening", "full writes", "partial writes", "properties", "remounts", "close", "input projection",
        "host catalogue", "packet volume", "visible latency", "client frame health",
    )
    private val BUILD_CAPABILITIES = listOf(
        "DI KSP", "command KSP", "event KSP", "clean compilation", "incremental compilation",
        "contribution scaling", "generated size", "Gradle configuration", "dependency wiring", "shaded size",
        "startup", "shutdown",
    )
    private val JOURNEY_CAPABILITIES = listOf(
        "command to input to menu", "event to coroutine to storage", "startup to discovery to registration",
    )
    private val LOAD_CAPABILITIES = listOf("multi-player load", "churn", "saturation")
    private val SOAK_CAPABILITIES = listOf("soak", "retained-memory growth", "delayed cleanup")
    private val CALIBRATION_CAPABILITIES = listOf(
        "empty boundaries", "fixture overhead", "actor overhead", "result serialization", "runner self-checks",
    )
}
