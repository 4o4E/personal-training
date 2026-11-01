package top.e404.training.collector

import kotlinx.serialization.json.*
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit

private const val API_VERSION = 1
private const val FORMAT_VERSION = 6
private const val BATCH_SIZE = 500
private const val SOURCE_ZONE = "+08:00"
private val DATASETS = listOf("heartRate", "steps", "calories", "restingHeartRate", "sleep", "body", "activity")

internal data class CollectorSettings(
    val stateDir: Path,
    val seedDb: Path,
    val trainingUrl: String,
    val trainingToken: String,
    val intervalHours: Int,
) {
    val sourceRoot: Path get() = stateDir.resolve("source")
    val sourceDb: Path get() = sourceRoot.resolve("data/misports.db")

    companion object {
        fun fromEnv(): CollectorSettings {
            val stateDir = Path.of(System.getenv("COLLECTOR_STATE_DIR") ?: "/data/collector").toAbsolutePath().normalize()
            val seedDb = Path.of(System.getenv("COLLECTOR_SEED_DB") ?: "/data/misports.db").toAbsolutePath().normalize()
            val trainingUrl = (System.getenv("TRAINING_URL") ?: "").trim().trimEnd('/')
            val tokenEnv = (System.getenv("TRAINING_TOKEN") ?: "").trim()
            val tokenFile = System.getenv("TRAINING_TOKEN_FILE")
            val token = tokenEnv.ifEmpty { tokenFile?.let { Files.readString(Path.of(it)).trim() } ?: "" }
            val interval = try { (System.getenv("COLLECTOR_INTERVAL_HOURS") ?: "1").toInt() } catch (_: NumberFormatException) {
                throw IllegalArgumentException("COLLECTOR_INTERVAL_HOURS must be a positive integer")
            }
            require(interval > 0) { "COLLECTOR_INTERVAL_HOURS must be a positive integer" }
            require(trainingUrl.isNotEmpty() && token.isNotEmpty()) { "TRAINING_URL and TRAINING_TOKEN are required" }
            require(URI(trainingUrl).scheme in setOf("http", "https")) { "TRAINING_URL must use HTTP or HTTPS" }
            return CollectorSettings(stateDir, seedDb, trainingUrl, token, interval)
        }
    }
}

/** Durable source-to-backend collector, compatible with the existing collector.sqlite3 outbox. */
class CollectorService internal constructor(private val settings: CollectorSettings) : AutoCloseable {
    private val state = CollectorState(settings.stateDir.resolve("collector.sqlite3"))
    private val http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(30)).build()
    private val sourceRoot: Path
    private val sourceDbPath: Path
    private val sourceSchema: String
    private val secrets = java.util.concurrent.CopyOnWriteArraySet<String>().apply { add(settings.trainingToken) }
    private val lockPath = settings.stateDir.resolve("collector.lock")
    private val localLock = java.util.concurrent.locks.ReentrantLock()

    init {
        Files.createDirectories(settings.stateDir)
        sourceRoot = settings.sourceRoot
        sourceDbPath = settings.sourceDb
        sourceSchema = CollectorService::class.java.getResourceAsStream("/schema.sql")?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalStateException("packaged schema.sql is missing")
        Files.createDirectories(sourceDbPath.parent)
        if (!Files.exists(sourceDbPath)) createPrivateSource()
        try { Files.setPosixFilePermissions(settings.stateDir, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")) } catch (_: Exception) { }
        try { Files.setPosixFilePermissions(lockPath, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) } catch (_: Exception) { }
        openSource().use { }
    }

    private fun createPrivateSource() {
        require(Files.isRegularFile(settings.seedDb)) {
            "seed database not found at ${settings.seedDb}; install a consistent private copy first"
        }
        val seed = DriverManager.getConnection("jdbc:sqlite:${settings.seedDb.toAbsolutePath()}")
        try {
            val escaped = sourceDbPath.toAbsolutePath().toString().replace("'", "''")
            seed.createStatement().use { it.execute("VACUUM INTO '$escaped'") }
        } finally { seed.close() }
        try { Files.setPosixFilePermissions(sourceDbPath, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) } catch (_: Exception) { }
    }

    private fun openSource(): Connection {
        val conn = DriverManager.getConnection("jdbc:sqlite:${sourceDbPath.toAbsolutePath()}")
        conn.createStatement().use { st ->
            st.execute("PRAGMA foreign_keys=ON")
            st.execute("PRAGMA journal_mode=WAL")
            conn.autoCommit = false
            for (sql in sourceSchema.split(';').map(String::trim).filter(String::isNotEmpty)) st.execute(sql)
        }
        conn.commit()
        return conn
    }

    private fun endpoint(path: String) = "${settings.trainingUrl}/v1/${path.trimStart('/')}"

    private fun request(method: String, path: String, body: JsonObject? = null): JsonObject {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                val builder = HttpRequest.newBuilder(URI(endpoint(path)))
                    .timeout(java.time.Duration.ofSeconds(30))
                    .header("Authorization", "Bearer ${settings.trainingToken}")
                    .header("Accept", "application/json")
                if (body != null) builder.header("Content-Type", "application/json")
                val request = when (method) {
                    "GET" -> builder.GET().build()
                    else -> builder.method(method, HttpRequest.BodyPublishers.ofString(body?.toString() ?: "{}")).build()
                }
                val response = http.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() in 500..599 && attempt < 2) {
                    Thread.sleep(500L shl attempt)
                    return@repeat
                }
                if (response.statusCode() !in 200..299) throw IOException("backend $method $path returned HTTP ${response.statusCode()}")
                val decoded = try { Json.parseToJsonElement(response.body()) } catch (_: Exception) {
                    throw IOException("backend $method $path returned invalid JSON")
                }
                return decoded as? JsonObject ?: throw IOException("backend $method $path returned a non-object response")
            } catch (exc: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("backend request interrupted")
            } catch (exc: IOException) {
                lastError = exc
                if (attempt < 2) Thread.sleep(500L shl attempt)
            }
        }
        throw lastError ?: IOException("backend request failed")
    }

    private fun checkCapabilities() {
        val capabilities = request("GET", "capabilities")
        val expected = linkedMapOf("healthApiVersion" to API_VERSION, "formatVersion" to FORMAT_VERSION, "backupFormatVersion" to FORMAT_VERSION)
        val mismatches = expected.filter { (key, value) -> capabilities[key]?.jsonPrimitive?.intOrNull != value }.keys
        if (mismatches.isNotEmpty()) throw IOException("backend capability mismatch: ${mismatches.joinToString()}")
    }

    private fun recordState(event: String, phase: String? = null, error: String? = null) {
        val body = buildJsonObject {
            put("event", event)
            if (phase != null) put("phase", phase)
            if (error != null) put("error", error.take(1000))
        }
        request("POST", "health/collection/state", body)
    }

    fun pollRequest(): Boolean = request("GET", "health/status")["collection"]?.jsonObject?.get("requested")?.jsonPrimitive?.booleanOrNull == true

    private fun enqueueLocalSource(start: String?, end: String?): Map<String, Int> = openSource().use { SourceRecords.enqueueSourceRecords(it, state, start, end) }
    private fun enqueueRecentWorkoutHeartRate(): Int = openSource().use { SourceRecords.enqueueRecentWorkoutHeartRate(it, state) }
    private fun enqueueWorkoutBackfillBatch(): Pair<Int, Boolean> = openSource().use { SourceRecords.enqueueWorkoutHeartRateBackfill(it, state) }

    private fun runSourceSync(start: String?, end: String?): Path {
        val credentials = Credentials.load(sourceRoot)
        secrets.add(credentials.first)
        secrets.add(credentials.second)
        val client = MiClient(credentials.first, credentials.second)
        try {
            val rotated = client.login()
            secrets.add(rotated.first)
            secrets.add(rotated.second)
            val saved = Credentials.save(sourceRoot, rotated.first, rotated.second)
            try { Files.setPosixFilePermissions(saved, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) } catch (_: Exception) { }
            val startedAt = Instant.now()
            openSource().use { conn ->
                val meta = SourceSync(conn, client, sourceRoot).sync(days = 7, start = start, end = end, includeFds = true)
                val root = Json.parseToJsonElement(Files.readString(meta)).jsonObject
                val errors = root["errors"]?.jsonObject ?: throw IOException("this round's meta.json has no errors object")
                if (errors.isNotEmpty()) throw IOException("milib collection dataset errors: " + errors.entries.sortedBy { it.key }.joinToString("; ") { "${it.key}: ${it.value}" })
                val fetchedAt = root["fetched_at"]?.jsonPrimitive?.contentOrNull?.let(Instant::parse)
                    ?: throw IOException("this round's meta.json has no valid fetched_at")
                if (fetchedAt.isBefore(startedAt)) throw IOException("latest meta.json does not belong to this collection round")
                return meta
            }
        } finally { client.close() }
    }

    private fun submitPending(): Int {
        var sent = 0
        for (dataset in DATASETS) {
            while (true) {
                val batch = state.nextBatch(dataset, BATCH_SIZE)
                if (batch.isEmpty()) break
                val body = buildJsonObject {
                    put("dataset", dataset)
                    put("records", JsonArray(batch.map { row -> buildJsonObject {
                        put("sourceId", row.sourceId); put("localDate", row.localDate); put("measuredAt", row.measuredAt); put("payload", row.payload)
                    } }))
                }
                val result = request("POST", "health/import", body)
                val accepted = result["accepted"]?.jsonPrimitive?.intOrNull
                val unchanged = result["unchanged"]?.jsonPrimitive?.intOrNull
                if (accepted == null || unchanged == null || accepted < 0 || unchanged < 0 || accepted + unchanged != batch.size) {
                    throw IOException("backend returned an incomplete $dataset import acknowledgement")
                }
                state.acknowledge(dataset, batch)
                sent += batch.size
            }
        }
        return sent
    }

    private fun publishWorkoutSamples(): Int {
        enqueueRecentWorkoutHeartRate()
        var sent = submitPending()
        while (true) {
            val (staged, complete) = enqueueWorkoutBackfillBatch()
            System.err.println("workout heart-rate backfill batch staged=$staged complete=$complete")
            sent += submitPending()
            if (complete) return sent
        }
    }

    fun runCollection(startDate: String? = null, endDate: String? = null): Boolean = withRunLock("collection") {
        try { recordState("started", "mi-collection") } catch (_: Exception) { System.err.println("unable to report collection start") }
        var phase = "seed-read"
        try {
            enqueueLocalSource(startDate, endDate)
            phase = "mi-collection"
            runSourceSync(startDate, endDate)
            enqueueLocalSource(startDate, endDate)
            phase = "health-submit"
            checkCapabilities()
            recordState("started", "health-submit")
            publishWorkoutSamples()
            recordState("submitted", "health-submit")
            recordState("succeeded", "complete")
            true
        } catch (exc: Exception) {
            val reason = safeReason(exc)
            if (phase == "mi-collection") {
                try { enqueueLocalSource(startDate, endDate); enqueueRecentWorkoutHeartRate() } catch (_: Exception) { }
            }
            System.err.println("collector run failed at phase $phase: $reason")
            try { recordState("failed", phase, reason) } catch (_: Exception) { }
            throw exc
        }
    }

    fun publishExisting(startDate: String? = null, endDate: String? = null): Boolean = withRunLock("publish") {
        val phase = "existing-data-publish"
        try {
            checkCapabilities()
            recordState("started", phase)
            enqueueLocalSource(startDate, endDate)
            publishWorkoutSamples()
            recordState("published", phase)
            true
        } catch (exc: Exception) {
            try { recordState("failed", phase, safeReason(exc)) } catch (_: Exception) { }
            throw exc
        }
    }

    private fun withRunLock(label: String, action: () -> Boolean): Boolean {
        if (!localLock.tryLock()) { System.err.println("$label already running; trigger coalesced"); return false }
        try {
            FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                val fileLock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (fileLock == null) { System.err.println("another collector process owns the run lock"); return false }
                fileLock.use { return action() }
            }
        } finally { localLock.unlock() }
    }

    private fun safeReason(exc: Exception): String {
        var message = exc.message ?: exc.javaClass.simpleName
        secrets.forEach { secret -> if (secret.isNotEmpty()) message = message.replace(secret, "[redacted]") }
        return message.take(1000)
    }

    override fun close() { state.close() }

    companion object {
        fun fromEnvironment(): CollectorService = CollectorService(CollectorSettings.fromEnv())
    }
}
