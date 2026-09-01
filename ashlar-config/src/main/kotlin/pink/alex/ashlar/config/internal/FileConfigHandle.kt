package pink.alex.ashlar.config.internal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import pink.alex.ashlar.config.ConfigBackup
import pink.alex.ashlar.config.ConfigBackupId
import pink.alex.ashlar.config.ConfigDocument
import pink.alex.ashlar.config.ConfigDocumentReload
import pink.alex.ashlar.config.ConfigEvent
import pink.alex.ashlar.config.ConfigEventOrigin
import pink.alex.ashlar.config.ConfigFormat
import pink.alex.ashlar.config.ConfigHandle
import pink.alex.ashlar.config.ConfigInspection
import pink.alex.ashlar.config.ConfigKeyPath
import pink.alex.ashlar.config.ConfigOperationProblem
import pink.alex.ashlar.config.ConfigOperationProblemCategory
import pink.alex.ashlar.config.ConfigOperationStatus
import pink.alex.ashlar.config.ConfigParse
import pink.alex.ashlar.config.ConfigProblem
import pink.alex.ashlar.config.ConfigProblemCategory
import pink.alex.ashlar.config.ConfigProblemSeverity
import pink.alex.ashlar.config.ConfigReload
import pink.alex.ashlar.config.ConfigReloadMode
import pink.alex.ashlar.config.ConfigRestore
import pink.alex.ashlar.config.ConfigSource
import pink.alex.ashlar.config.ConfigSourceRevision
import pink.alex.ashlar.config.ConfigValue
import pink.alex.ashlar.config.ConfigWatcherStatus
import pink.alex.ashlar.config.ConfigWrite
import pink.alex.ashlar.config.codegen.ConfigDefinition
import pink.alex.ashlar.config.codegen.ConfigMigrationStep
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService

internal class FileConfigHandle<T : Any> private constructor(
    private val definition: ConfigDefinition<T>,
    private val format: ConfigFormat,
    private val path: Path,
    private val files: ConfigFiles,
    initial: AcceptedConfig<T>,
    private val ioDispatcher: CoroutineDispatcher,
    private val reporter: ConfigRuntimeReporter,
) : ConfigHandle<T>, InternalConfigHandle {
    private val mutex = Mutex()
    private val state = MutableStateFlow(initial.value)
    private val attempts = MutableSharedFlow<ConfigEvent<T>>(replay = 1, extraBufferCapacity = 63)
    private var accepted: AcceptedConfig<T> = initial
    @Volatile
    private var inspection = ConfigInspection(
        path = definition.path,
        format = format.id,
        schemaVersion = definition.schemaVersion,
        sourceRevision = initial.revision,
        reloadMode = definition.reloadMode,
        watcherStatus = ConfigWatcherStatus.DISABLED,
        status = ConfigOperationStatus.ACCEPTED,
        warningCount = initial.warnings.size,
        problems = initial.warnings,
    )
    @Volatile
    private var backupMetadata: List<ConfigBackup> = files.listBackups(definition.path).map(StoredBackup::publicMetadata)
    private var watchService: WatchService? = null
    private var watcher: Job? = null
    private var rejectedWatchRevision: ConfigSourceRevision? = null

    init {
        attempts.tryEmit(
            ConfigEvent.Accepted(
                origin = initial.origin,
                value = initial.value,
                revision = initial.revision,
                changed = true,
                warnings = initial.warnings,
            ),
        )
    }

    override val current: T get() = state.value
    override val values: StateFlow<T> = state.asStateFlow()
    override val events: Flow<ConfigEvent<T>> = attempts.asSharedFlow()

    override suspend fun reload(): ConfigReload<T> = reload(ConfigEventOrigin.MANUAL_RELOAD)

    override suspend fun update(transform: (T) -> T): ConfigWrite<T> = withContext(ioDispatcher) {
        mutex.withLock {
            val before = accepted
            val transformed = transform(before.value)
            if (transformed == before.value) return@withLock ConfigWrite.Unchanged(before.value)
            val validation = validateConfig(definition.path, transformed, definition.validators)
            val errors = validation.errors()
            if (errors.isNotEmpty()) {
                publishRejected(ConfigEventOrigin.UPDATE, before.revision, errors)
                return@withLock ConfigWrite.Rejected(before.value, errors)
            }
            when (val disk = files.read(path, definition.path, definition.limits.maximumBytes)) {
                is FileRead.Accepted -> if (disk.revision != before.revision) {
                    return@withLock ConfigWrite.SourceChanged(before.value, before.revision)
                }
                is FileRead.TooLarge -> return@withLock ConfigWrite.SourceChanged(before.value, before.revision)
                is FileRead.Unavailable -> {
                    publishUnavailable(ConfigEventOrigin.UPDATE, disk.problem)
                    return@withLock ConfigWrite.Unavailable(before.value, disk.problem)
                }
            }
            val encoded = try {
                val semantic = ConfigCodec.encode(
                    definition.serializer,
                    transformed,
                    definition.keyNames,
                ).withSchema(definition.schemaVersion)
                val document = before.document.patch(semantic, definition.comments)
                document to format.write(document)
            } catch (failure: IllegalArgumentException) {
                val problems = listOf(ConfigProblem(
                    path = definition.path,
                    category = ConfigProblemCategory.UNSUPPORTED_FEATURE,
                    message = failure.message ?: "The selected format cannot represent this value",
                ))
                publishRejected(ConfigEventOrigin.UPDATE, before.revision, problems)
                return@withLock ConfigWrite.Rejected(before.value, problems)
            }
            val (document, text) = encoded
            textLimitProblem(definition, text)?.let { problem ->
                val problems = listOf(problem)
                publishRejected(ConfigEventOrigin.UPDATE, before.revision, problems)
                return@withLock ConfigWrite.Rejected(before.value, problems)
            }
            val backupProblem = createBackup(before)
            if (backupProblem != null) {
                publishUnavailable(ConfigEventOrigin.UPDATE, backupProblem)
                return@withLock ConfigWrite.Unavailable(before.value, backupProblem)
            }
            files.writeAtomically(path, definition.path, text)?.let { problem ->
                publishUnavailable(ConfigEventOrigin.UPDATE, problem)
                return@withLock ConfigWrite.Unavailable(before.value, problem)
            }
            val revision = checkNotNull(readRevision())
            val next = AcceptedConfig(
                value = transformed,
                document = document,
                revision = revision,
                warnings = validation.warnings(),
                origin = ConfigEventOrigin.UPDATE,
            )
            publishAccepted(next, changedPaths(before.value, transformed))
            ConfigWrite.Accepted(transformed, next.warnings)
        }
    }

    override suspend fun backups(): List<ConfigBackup> = withContext(ioDispatcher) {
        files.listBackups(definition.path).map(StoredBackup::publicMetadata).also { backups ->
            backupMetadata = backups
        }
    }

    override suspend fun restore(id: ConfigBackupId): ConfigRestore<T> = withContext(ioDispatcher) {
        mutex.withLock {
            val stored = files.listBackups(definition.path).firstOrNull { backup -> backup.id == id.value }
                ?: return@withLock ConfigRestore.NotFound(current, id)
            val source = when (val read = files.read(stored.path, definition.path, definition.limits.maximumBytes)) {
                is FileRead.Accepted -> read
                is FileRead.TooLarge -> return@withLock ConfigRestore.Rejected(
                    current,
                    listOf(resourceProblem(read.bytes)),
                )
                is FileRead.Unavailable -> return@withLock ConfigRestore.Unavailable(current, read.problem)
            }
            when (val loaded = decodeSource(source, ConfigEventOrigin.RESTORE, persistMigration = false)) {
                is LoadResult.Rejected -> ConfigRestore.Rejected(current, loaded.problems)
                is LoadResult.Unavailable -> ConfigRestore.Unavailable(current, loaded.problem)
                is LoadResult.Accepted -> {
                    val backupProblem = createBackup(accepted)
                    if (backupProblem != null) return@withLock ConfigRestore.Unavailable(current, backupProblem)
                    val text = format.write(loaded.config.document)
                    textLimitProblem(definition, text)?.let { problem ->
                        return@withLock ConfigRestore.Rejected(current, listOf(problem))
                    }
                    files.writeAtomically(path, definition.path, text)?.let { problem ->
                        return@withLock ConfigRestore.Unavailable(current, problem)
                    }
                    val revision = checkNotNull(readRevision())
                    val restored = loaded.config.copy(revision = revision)
                    publishAccepted(restored, changedPaths(accepted.value, restored.value))
                    ConfigRestore.Accepted(restored.value, restored.warnings)
                }
            }
        }
    }

    override suspend fun reloadAny(): ConfigDocumentReload = when (val result = reload()) {
        is ConfigReload.Accepted -> ConfigDocumentReload(
            path = definition.path,
            status = ConfigOperationStatus.ACCEPTED,
            changed = result.changed,
            problems = result.warnings,
        )
        is ConfigReload.Rejected -> ConfigDocumentReload(
            path = definition.path,
            status = ConfigOperationStatus.REJECTED,
            problems = result.problems,
        )
        is ConfigReload.Unavailable -> ConfigDocumentReload(
            path = definition.path,
            status = ConfigOperationStatus.UNAVAILABLE,
            operationProblem = result.problem,
        )
    }

    override fun inspect(): ConfigInspection = inspection.copy(backups = backupMetadata)

    override fun startWatching(scope: CoroutineScope) {
        if (definition.reloadMode != ConfigReloadMode.WATCH || watcher != null) return
        inspection = inspection.copy(watcherStatus = ConfigWatcherStatus.STARTING)
        val service = FileSystems.getDefault().newWatchService()
        watchService = service
        path.parent.register(
            service,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_DELETE,
        )
        inspection = inspection.copy(watcherStatus = ConfigWatcherStatus.WATCHING)
        watcher = scope.launch(ioDispatcher) {
            while (isActive) {
                val key = runCatching { service.take() }.getOrElse { break }
                val relevant = key.pollEvents().any { event -> event.context() == path.fileName }
                key.reset()
                if (!relevant) continue
                delay(WATCH_DEBOUNCE_MILLIS)
                while (true) {
                    val pending = service.poll() ?: break
                    pending.pollEvents()
                    pending.reset()
                }
                reload(ConfigEventOrigin.WATCHED_RELOAD)
            }
            inspection = inspection.copy(watcherStatus = ConfigWatcherStatus.STOPPED)
        }
    }

    override fun close() {
        watcher?.cancel()
        watcher = null
        runCatching { watchService?.close() }
        watchService = null
        inspection = inspection.copy(watcherStatus = ConfigWatcherStatus.STOPPED)
    }

    private suspend fun reload(origin: ConfigEventOrigin): ConfigReload<T> = withContext(ioDispatcher) {
        mutex.withLock {
            val source = when (val read = files.read(path, definition.path, definition.limits.maximumBytes)) {
                is FileRead.Accepted -> read
                is FileRead.TooLarge -> {
                    val problems = listOf(resourceProblem(read.bytes))
                    publishRejected(origin, null, problems)
                    return@withLock ConfigReload.Rejected(current, problems)
                }
                is FileRead.Unavailable -> {
                    publishUnavailable(origin, read.problem)
                    return@withLock ConfigReload.Unavailable(current, read.problem)
                }
            }
            if (origin == ConfigEventOrigin.WATCHED_RELOAD && source.revision == accepted.revision) {
                return@withLock ConfigReload.Accepted(current, changed = false, accepted.warnings)
            }
            when (val loaded = decodeSource(source, origin, persistMigration = true)) {
                is LoadResult.Rejected -> {
                    if (origin != ConfigEventOrigin.WATCHED_RELOAD || rejectedWatchRevision != source.revision) {
                        publishRejected(origin, source.revision, loaded.problems)
                        if (origin == ConfigEventOrigin.WATCHED_RELOAD) {
                            reporter.report(definition.path, recovered = false, loaded.problems.size)
                        }
                    }
                    if (origin == ConfigEventOrigin.WATCHED_RELOAD) rejectedWatchRevision = source.revision
                    ConfigReload.Rejected(current, loaded.problems)
                }
                is LoadResult.Unavailable -> {
                    publishUnavailable(origin, loaded.problem)
                    ConfigReload.Unavailable(current, loaded.problem)
                }
                is LoadResult.Accepted -> {
                    val recovered = rejectedWatchRevision != null
                    rejectedWatchRevision = null
                    val before = accepted.value
                    val paths = changedPaths(before, loaded.config.value)
                    publishAccepted(loaded.config, paths)
                    if (origin == ConfigEventOrigin.WATCHED_RELOAD && recovered) {
                        reporter.report(definition.path, recovered = true, problemCount = 0)
                    }
                    ConfigReload.Accepted(loaded.config.value, loaded.config.value != before, loaded.config.warnings)
                }
            }
        }
    }

    private fun decodeSource(
        source: FileRead.Accepted,
        origin: ConfigEventOrigin,
        persistMigration: Boolean,
    ): LoadResult<T> {
        val parsed = when (val result = format.parse(
            ConfigSource(definition.path, source.text),
            definition.limits,
        )) {
            is ConfigParse.Accepted -> result
            is ConfigParse.Rejected -> return LoadResult.Rejected(result.problems)
        }
        val schema = schemaOf(parsed.document.value)
            ?: return LoadResult.Rejected(listOf(schemaProblem("Missing or invalid _ashlar-schema")))
        if (schema > definition.schemaVersion) {
            return LoadResult.Rejected(listOf(schemaProblem(
                "Source schema $schema is newer than supported schema ${definition.schemaVersion}",
            )))
        }
        var document = parsed.document
        var semantic = document.value.withoutSchema()
        var currentSchema = schema
        var serializer: KSerializer<Any> = serializerAt(schema)
            ?: return LoadResult.Rejected(listOf(schemaProblem("No migration starts at schema $schema")))
        var serializerKeyNames: Map<ConfigKeyPath, String> = keyNamesAt(schema)
        var migratedValue: Any? = null
        try {
            while (currentSchema < definition.schemaVersion) {
                val step = definition.migrations.singleOrNull { migration -> migration.fromSchema == currentSchema }
                    ?: return LoadResult.Rejected(listOf(schemaProblem(
                        "No migration from schema $currentSchema to ${currentSchema + 1}",
                    )))
                val unknown = ConfigCodec.unknownKeys(
                    serializer,
                    semantic,
                    definition.path,
                    serializerKeyNames,
                    document::location,
                )
                if (unknown.isNotEmpty()) return LoadResult.Rejected(unknown)
                val decoded = ConfigCodec.decode(serializer, semantic, serializerKeyNames)
                migratedValue = step.migrateValue(decoded)
                serializer = step.targetSerializer
                serializerKeyNames = step.targetKeyNames
                semantic = ConfigCodec.encode(serializer, migratedValue, serializerKeyNames)
                currentSchema++
            }
            @Suppress("UNCHECKED_CAST")
            val finalValue = if (migratedValue != null) migratedValue as T else {
                val unknown = ConfigCodec.unknownKeys(
                    definition.serializer,
                    semantic,
                    definition.path,
                    definition.keyNames,
                    document::location,
                )
                if (unknown.isNotEmpty()) return LoadResult.Rejected(unknown)
                ConfigCodec.decode(definition.serializer, semantic, definition.keyNames)
            }
            val validation = validateConfig(definition.path, finalValue, definition.validators)
            val errors = validation.errors()
            if (errors.isNotEmpty()) return LoadResult.Rejected(errors)
            val warnings = parsed.warnings + validation.warnings()
            var revision = source.revision
            if (schema != definition.schemaVersion) {
                document = document.patch(semantic.withSchema(definition.schemaVersion), definition.comments)
                if (persistMigration) {
                    val text = format.write(document)
                    textLimitProblem(definition, text)?.let { return LoadResult.Rejected(listOf(it)) }
                    createBackup(accepted)?.let { return LoadResult.Unavailable(it) }
                    files.writeAtomically(path, definition.path, text)
                        ?.let { return LoadResult.Unavailable(it) }
                    revision = checkNotNull(readRevision())
                }
            }
            return LoadResult.Accepted(
                AcceptedConfig(finalValue, document, revision, warnings, origin),
            )
        } catch (failure: Exception) {
            return LoadResult.Rejected(
                listOf(
                    ConfigProblem(
                        path = definition.path,
                        category = if (currentSchema < definition.schemaVersion) {
                            ConfigProblemCategory.MIGRATION
                        } else {
                            ConfigProblemCategory.DECODING
                        },
                        message = failure.message?.lineSequence()?.firstOrNull() ?: "Configuration could not be decoded",
                    ),
                ),
            )
        }
    }

    private fun schemaOf(value: ConfigValue.ObjectValue): Int? {
        val marker = value.entries[SCHEMA_KEY]
        if (marker == null) {
            return when {
                definition.schemaVersion == 1 -> 1
                definition.unversionedSchema > 0 -> definition.unversionedSchema
                else -> null
            }
        }
        val schema = (marker as? ConfigValue.IntegerValue)?.value ?: return null
        return schema.toInt().takeIf { it >= 1 && it.toLong() == schema }
    }

    @Suppress("UNCHECKED_CAST")
    private fun serializerAt(schema: Int): KSerializer<Any>? = when {
        schema == definition.schemaVersion -> definition.serializer as KSerializer<Any>
        else -> definition.migrations.singleOrNull { it.fromSchema == schema }?.sourceSerializer
    }

    private fun keyNamesAt(schema: Int): Map<ConfigKeyPath, String> = when {
        schema == definition.schemaVersion -> definition.keyNames
        else -> definition.migrations.singleOrNull { it.fromSchema == schema }?.sourceKeyNames.orEmpty()
    }

    private fun createBackup(config: AcceptedConfig<T>): ConfigOperationProblem? = files.backup(
        path = path,
        relative = definition.path,
        schemaVersion = definition.schemaVersion,
        revision = config.revision,
        maximumRetained = definition.backups,
    ).fold(
        onSuccess = {
            backupMetadata = files.listBackups(definition.path).map(StoredBackup::publicMetadata)
            null
        },
        onFailure = {
            ConfigOperationProblem(
                definition.path,
                ConfigOperationProblemCategory.BACKUP_FAILED,
                "Could not retain the previous valid configuration source",
            )
        },
    )

    private fun publishAccepted(next: AcceptedConfig<T>, changedPaths: List<ConfigKeyPath>) {
        val changed = next.value != accepted.value
        accepted = next
        if (changed) state.value = next.value
        inspection = inspection.copy(
            sourceRevision = next.revision,
            status = ConfigOperationStatus.ACCEPTED,
            warningCount = next.warnings.size,
            problems = next.warnings,
        )
        attempts.tryEmit(
            ConfigEvent.Accepted(
                origin = next.origin,
                value = next.value,
                revision = next.revision,
                changed = changed,
                changedPaths = changedPaths,
                warnings = next.warnings,
            ),
        )
    }

    private fun publishRejected(
        origin: ConfigEventOrigin,
        revision: ConfigSourceRevision?,
        problems: List<ConfigProblem>,
    ) {
        inspection = inspection.copy(
            status = ConfigOperationStatus.REJECTED,
            warningCount = problems.count { it.severity == ConfigProblemSeverity.WARNING },
            problems = problems,
        )
        attempts.tryEmit(ConfigEvent.Rejected(origin, current, revision, problems))
    }

    private fun publishUnavailable(origin: ConfigEventOrigin, problem: ConfigOperationProblem) {
        inspection = inspection.copy(status = ConfigOperationStatus.UNAVAILABLE)
        attempts.tryEmit(ConfigEvent.Unavailable(origin, current, problem))
    }

    private fun readRevision(): ConfigSourceRevision? = when (
        val read = files.read(path, definition.path, definition.limits.maximumBytes)
    ) {
        is FileRead.Accepted -> read.revision
        else -> null
    }

    private fun schemaProblem(message: String): ConfigProblem = ConfigProblem(
        path = definition.path,
        key = ConfigKeyPath(SCHEMA_KEY),
        category = ConfigProblemCategory.UNSUPPORTED_SCHEMA,
        message = message,
    )

    private fun resourceProblem(bytes: Long): ConfigProblem = ConfigProblem(
        path = definition.path,
        category = ConfigProblemCategory.RESOURCE_LIMIT,
        message = "Configuration exceeds the ${definition.limits.maximumBytes}-byte limit ($bytes bytes)",
    )

    private fun changedPaths(before: T, after: T): List<ConfigKeyPath> {
        val beforeValue = ConfigCodec.encode(definition.serializer, before, definition.keyNames)
        val afterValue = ConfigCodec.encode(definition.serializer, after, definition.keyNames)
        return buildList { collectChangedPaths(beforeValue, afterValue, emptyList(), this) }
    }

    companion object {
        suspend fun <T : Any> open(
            definition: ConfigDefinition<T>,
            format: ConfigFormat,
            files: ConfigFiles,
            ioDispatcher: CoroutineDispatcher,
            reporter: ConfigRuntimeReporter,
        ): OpenResult<T> = withContext(ioDispatcher) {
            require(definition.schemaVersion >= 1) { "schemaVersion must be at least 1" }
            require(definition.unversionedSchema in 0..definition.schemaVersion) {
                "unversionedSchema must be zero or a supported schema"
            }
            require(definition.backups in 0..100) { "backups must be between 0 and 100" }
            val extension = definition.path.substringAfterLast('.', missingDelimiterValue = "")
            require(format.extensions.any { supported -> extension.equals(supported, true) }) {
                "Format '${format.id}' does not support '${definition.path}'"
            }
            val path = files.resolve(definition.path)
            if (!files.exists(path)) {
                val value = try {
                    ConfigCodec.decode(definition.serializer, ConfigValue.ObjectValue(emptyMap()), definition.keyNames)
                } catch (failure: Exception) {
                    return@withContext OpenResult.Rejected(
                        listOf(ConfigProblem(
                            path = definition.path,
                            category = ConfigProblemCategory.DECODING,
                            message = "Configuration defaults could not be constructed",
                        )),
                    )
                }
                val validation = validateConfig(definition.path, value, definition.validators)
                if (validation.errors().isNotEmpty()) return@withContext OpenResult.Rejected(validation.errors())
                val semantic = ConfigCodec.encode(
                    definition.serializer,
                    value,
                    definition.keyNames,
                ).withSchema(definition.schemaVersion)
                val document = try {
                    format.create(semantic, definition.comments)
                } catch (failure: Exception) {
                    return@withContext OpenResult.Rejected(
                        listOf(ConfigProblem(
                            path = definition.path,
                            category = ConfigProblemCategory.UNSUPPORTED_FEATURE,
                            message = failure.message ?: "Format could not create this configuration",
                        )),
                    )
                }
                val text = format.write(document)
                textLimitProblem(definition, text)?.let { return@withContext OpenResult.Rejected(listOf(it)) }
                files.writeAtomically(path, definition.path, text)
                    ?.let { return@withContext OpenResult.Unavailable(it) }
            }
            val source = when (val read = files.read(path, definition.path, definition.limits.maximumBytes)) {
                is FileRead.Accepted -> read
                is FileRead.TooLarge -> return@withContext OpenResult.Rejected(
                    listOf(ConfigProblem(
                        path = definition.path,
                        category = ConfigProblemCategory.RESOURCE_LIMIT,
                        message = "Configuration exceeds the ${definition.limits.maximumBytes}-byte limit",
                    )),
                )
                is FileRead.Unavailable -> return@withContext OpenResult.Unavailable(read.problem)
            }
            val provisional = AcceptedConfig(
                value = ConfigCodec.decode(
                    definition.serializer,
                    ConfigValue.ObjectValue(emptyMap()),
                    definition.keyNames,
                ),
                document = format.create(
                    ConfigCodec.encode(
                        definition.serializer,
                        ConfigCodec.decode(
                            definition.serializer,
                            ConfigValue.ObjectValue(emptyMap()),
                            definition.keyNames,
                        ),
                        definition.keyNames,
                    ).withSchema(definition.schemaVersion),
                    definition.comments,
                ),
                revision = source.revision,
                warnings = emptyList(),
                origin = ConfigEventOrigin.INITIAL_LOAD,
            )
            val handle = FileConfigHandle(definition, format, path, files, provisional, ioDispatcher, reporter)
            when (val loaded = handle.decodeSource(source, ConfigEventOrigin.INITIAL_LOAD, persistMigration = true)) {
                is LoadResult.Accepted -> {
                    val ready = FileConfigHandle(
                        definition,
                        format,
                        path,
                        files,
                        loaded.config,
                        ioDispatcher,
                        reporter,
                    )
                    OpenResult.Accepted(ready)
                }
                is LoadResult.Rejected -> OpenResult.Rejected(loaded.problems)
                is LoadResult.Unavailable -> OpenResult.Unavailable(loaded.problem)
            }
        }

        private const val SCHEMA_KEY = "_ashlar-schema"
        private const val WATCH_DEBOUNCE_MILLIS = 125L
    }
}

internal interface InternalConfigHandle : AutoCloseable {
    suspend fun reloadAny(): ConfigDocumentReload
    fun inspect(): ConfigInspection
    fun startWatching(scope: CoroutineScope)
}

internal sealed interface OpenResult<out T : Any> {
    data class Accepted<T : Any>(val handle: FileConfigHandle<T>) : OpenResult<T>
    data class Rejected(val problems: List<ConfigProblem>) : OpenResult<Nothing>
    data class Unavailable(val problem: ConfigOperationProblem) : OpenResult<Nothing>
}

private sealed interface LoadResult<out T : Any> {
    data class Accepted<T : Any>(val config: AcceptedConfig<T>) : LoadResult<T>
    data class Rejected(val problems: List<ConfigProblem>) : LoadResult<Nothing>
    data class Unavailable(val problem: ConfigOperationProblem) : LoadResult<Nothing>
}

private data class AcceptedConfig<T : Any>(
    val value: T,
    val document: ConfigDocument,
    val revision: ConfigSourceRevision,
    val warnings: List<ConfigProblem>,
    val origin: ConfigEventOrigin,
)

private fun List<ConfigProblem>.errors(): List<ConfigProblem> =
    filter { problem -> problem.severity == ConfigProblemSeverity.ERROR }

private fun List<ConfigProblem>.warnings(): List<ConfigProblem> =
    filter { problem -> problem.severity == ConfigProblemSeverity.WARNING }

private fun ConfigValue.ObjectValue.withSchema(schema: Int): ConfigValue.ObjectValue {
    val userEntries = entries.filterKeys { key -> key != "_ashlar-schema" }
    return ConfigValue.ObjectValue(buildMap {
        put("_ashlar-schema", ConfigValue.IntegerValue(schema.toLong()))
        putAll(userEntries)
    })
}

private fun ConfigValue.ObjectValue.withoutSchema(): ConfigValue.ObjectValue =
    ConfigValue.ObjectValue(entries.filterKeys { key -> key != "_ashlar-schema" })

private fun StoredBackup.publicMetadata(): ConfigBackup = ConfigBackup(
    id = ConfigBackupId(id),
    createdAt = createdAt,
    schemaVersion = schemaVersion,
    sourceRevision = revision,
)

private fun collectChangedPaths(
    before: ConfigValue?,
    after: ConfigValue?,
    path: List<String>,
    changed: MutableList<ConfigKeyPath>,
) {
    if (before == after) return
    when {
        before is ConfigValue.ObjectValue && after is ConfigValue.ObjectValue -> {
            (before.entries.keys + after.entries.keys).forEach { key ->
                collectChangedPaths(before.entries[key], after.entries[key], path + key, changed)
            }
        }
        before is ConfigValue.ArrayValue && after is ConfigValue.ArrayValue -> {
            val maximum = maxOf(before.values.size, after.values.size)
            repeat(maximum) { index ->
                collectChangedPaths(
                    before.values.getOrNull(index),
                    after.values.getOrNull(index),
                    path + index.toString(),
                    changed,
                )
            }
        }
        else -> changed += ConfigKeyPath(path)
    }
}

private fun textLimitProblem(
    definition: ConfigDefinition<*>,
    text: String,
): ConfigProblem? {
    val bytes = text.toByteArray(Charsets.UTF_8).size.toLong()
    return if (bytes > definition.limits.maximumBytes) {
        ConfigProblem(
            path = definition.path,
            category = ConfigProblemCategory.RESOURCE_LIMIT,
            message = "Encoded configuration exceeds the ${definition.limits.maximumBytes}-byte limit ($bytes bytes)",
        )
    } else {
        null
    }
}
