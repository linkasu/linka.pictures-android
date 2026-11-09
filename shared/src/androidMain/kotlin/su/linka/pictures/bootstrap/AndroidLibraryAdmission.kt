package su.linka.pictures.bootstrap

import android.content.Context
import androidx.sqlite.SQLiteDriver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import su.linka.pictures.access.FileCredentialStore
import su.linka.pictures.access.LegacyCredentialImporter
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.migration.*
import su.linka.pictures.portability.linka.LinkaCodec
import su.linka.pictures.storage.*

/** The only startup selector. Existing development bytes stay at their original location. */
internal class AndroidLibraryAdmission(
    private val context: Context,
    private val fs: FileSystem,
    private val driver: SQLiteDriver,
    private val ids: IdSource,
    private val inspection: MediaInspectionPort,
    private val createFiles: (Path) -> PlatformFilesPort,
) {
    private val home = context.filesDir.canonicalPath.toPath()
    private val root = home / "pokazhi-v1"
    private val source = home / "sets"
    val credentials = FileCredentialStore((context.noBackupFilesDir.canonicalPath + "/pokazhi-assistant").toPath(), fs, ids)
    private val importer = LegacyCredentialImporter(credentials)
    private val coordinator = MutableStateFlow<LegacyUpgrade?>(null)
    private var legacy: LegacySettingsReader.Snapshot? = null
    @OptIn(ExperimentalCoroutinesApi::class)
    val report: Flow<MigrationReport> = coordinator.flatMapLatest { it?.state ?: flowOf(MigrationReport()) }

    suspend fun open(resume: Boolean): OperationResult<LegacyOpenedLibrary> = storageWorker { attempt {
        val development = home / "pokazhi-development"
        if (context.packageName == "su.linka.pictures.development" && fs.exists(development)) {
            if (fs.exists(root) && fs.list(root).isNotEmpty()) reject(ProblemCode.CONFLICT)
            importer.validate().required()
            val runtime = StorageRuntime.open(development, fs, driver).required()
            try {
                val entries = runtime.library.listing.value.items
                LegacyOpenedLibrary(runtime, createFiles(development), MigrationReport(MigrationPhase.COMMITTED,
                    entries.size, entries.count { it.kind == LibraryEntryKind.BOARD }, entries.count { it.kind == LibraryEntryKind.RETAINED_ARCHIVE }))
            } catch (error: Throwable) { runtime.close(); throw error }
        } else {
            val upgrade = coordinator.value ?: createCoordinator().also { coordinator.value = it }
            upgrade.open(resume).required().also { legacy = null }
        }
    } }

    private suspend fun createCoordinator(): LegacyUpgrade {
        val admission = AdmissionFiles(root, fs, ids)
        val active = admission.read("active")
        val journal = if (active == null) admission.read("journal") else null
        if (active == null && journal == null && credentials.read().required() != null) reject(ProblemCode.CONFLICT)
        val reader by lazy { LegacySettingsReader(context) }
        if (active == null) legacy = reader.read()
        val kind = active?.sourceKind ?: journal?.sourceKind ?: if (fs.exists(source) || legacy!!.hasPreferences)
            MigrationSourceKind.LEGACY_FILES else MigrationSourceKind.BUNDLED
        if (active == null && kind == MigrationSourceKind.BUNDLED && (fs.exists(source) || legacy!!.hasPreferences)) reject(ProblemCode.CONFLICT)
        val incoming: MigrationSource = when (kind) {
            MigrationSourceKind.LEGACY_FILES -> FileMigrationSource(source, fs)
            MigrationSourceKind.BUNDLED -> when {
                active != null -> StarterMigrationSource() // Active opens without consulting source bytes.
                journal == null || journal.total == 5 -> StarterMigrationSource()
                journal.total == 7 -> BundledMigrationSource(context.assets)
                else -> reject(ProblemCode.CONFLICT)
            }
        }
        return LegacyUpgrade(root, incoming, fs, driver, createFiles, ids,
            readSettings = {
                val current = reader.read()
                val original = legacy ?: reject(ProblemCode.STALE_REQUEST)
                if (current.password != original.password || current.settings != original.settings || current.hasPreferences != original.hasPreferences ||
                    kind == MigrationSourceKind.BUNDLED && fs.exists(source)) reject(ProblemCode.INTEGRITY_FAILURE)
                current.settings
            },
            prepareCredentials = {
                val original = legacy ?: reject(ProblemCode.STALE_REQUEST)
                val caller = currentCoroutineContext()
                importer.prepare(original.settings.parentEnabled, original.password) {
                    caller.ensureStorageActive()
                    OperationResult.Completed(Unit)
                }
            },
            validateCredentials = importer::validate,
            readBoard = { file, name, media, files -> LinkaCodec(media, files, inspection, ids).readLegacyUpgrade(file, name) },
        )
    }
}
