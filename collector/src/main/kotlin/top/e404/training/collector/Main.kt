package top.e404.training.collector

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.sqlite.SQLiteConfig
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private val usage = """Usage: collector [serve|run|publish|rescan|init|import-raw|sync|stats] ...

Backend commands:
  serve                 Run startup, hourly collection, and backend-request polling (default)
  run                   Run one incremental collection and submission
  publish               Publish persisted source data without Xiaomi login
  rescan --from DATE    Collect and resubmit a historical date range
      --to DATE         Optional inclusive end date

Standalone Xiaomi database commands (XIAOMI_ROOT, default data/xiaomi):
  init                  Create the SQLite database and existing tables
  import-raw DIR        Import a captured raw data directory
  sync [options]        Fetch and import Xiaomi data without backend access
  stats                 Read-only source database statistics

sync options: --days N (default 7), --start DATE, --end DATE, --no-fds, --sport-only.
Backend commands use TRAINING_URL and TRAINING_TOKEN (or TRAINING_TOKEN_FILE),
COLLECTOR_STATE_DIR, COLLECTOR_SEED_DB, and COLLECTOR_INTERVAL_HOURS.
"""

fun main(args: Array<String>) {
    if (args.any { it == "--help" || it == "-h" }) {
        println(usage)
        return
    }
    val command = args.firstOrNull()?.takeUnless { it.startsWith("--") } ?: "serve"
    if (command in setOf("init", "import-raw", "sync", "stats")) {
        try {
            val status = runStandalone(command, args.drop(1))
            if (status != 0) kotlin.system.exitProcess(status)
            return
        } catch (exc: Exception) {
            System.err.println("$command failed (${exc.javaClass.simpleName})")
            kotlin.system.exitProcess(1)
        }
    }
    if (command !in setOf("serve", "run", "publish", "rescan")) fail("unknown command: $command\n$usage")
    val options = parseOptions(args.drop(if (args.firstOrNull() == command) 1 else 0))
    val start = options["--from"]
    val end = options["--to"]
    if (command == "rescan") {
        if (start == null) fail("rescan requires --from YYYY-MM-DD")
        val fromDate = parseDate(start)
        val toDate = end?.let(::parseDate)
        if (toDate != null && toDate.isBefore(fromDate)) fail("--to must not be earlier than --from")
    } else if (start != null || end != null) {
        fail("--from and --to are only valid for rescan")
    }
    try {
        val status = CollectorService.fromEnvironment().use { service ->
            when (command) {
                "run" -> if (service.runCollection()) 0 else 1
                "publish" -> if (service.publishExisting()) 0 else 1
                "rescan" -> if (service.runCollection(start, end)) 0 else 1
                else -> { serve(service); 0 }
            }
        }
        if (status != 0) kotlin.system.exitProcess(status)
    } catch (exc: Exception) {
        System.err.println("collector command failed (${exc.javaClass.simpleName})")
        kotlin.system.exitProcess(1)
    }
}


private fun xiaomiRoot(): Path = (System.getenv("XIAOMI_ROOT")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
    ?: Path.of("data", "xiaomi")).toAbsolutePath().normalize()

private fun xiaomiDbPath(root: Path) = root.resolve("data/misports.db")

private fun connectXiaomi(root: Path): Connection {
    val dbPath = xiaomiDbPath(root)
    Files.createDirectories(dbPath.parent)
    val schema = Thread.currentThread().contextClassLoader.getResourceAsStream("schema.sql")?.bufferedReader()?.use { it.readText() }
        ?: error("packaged schema.sql is missing")
    val conn = DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}")
    try {
        conn.createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys=ON")
            statement.execute("PRAGMA journal_mode=WAL")
            conn.autoCommit = false
            schema.split(';').map(String::trim).filter(String::isNotEmpty).forEach { statement.execute(it) }
        }
        conn.commit()
        try { Files.setPosixFilePermissions(dbPath, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) } catch (_: Exception) { }
        return conn
    } catch (exc: Exception) {
        conn.close()
        throw exc
    }
}

private fun readOnlyXiaomi(root: Path): Connection {
    val dbPath = xiaomiDbPath(root)
    require(Files.isRegularFile(dbPath)) { "Xiaomi source database is missing; run init first" }
    val config = SQLiteConfig().apply { setReadOnly(true) }
    return DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}", config.toProperties())
}

private fun runStandalone(command: String, args: List<String>): Int {
    val root = xiaomiRoot()
    return when (command) {
        "init" -> {
            require(args.isEmpty()) { "init takes no arguments" }
            connectXiaomi(root).close()
            println("数据库: ${xiaomiDbPath(root)}")
            println("表结构: packaged schema.sql")
            0
        }
        "import-raw" -> {
            require(args.size == 1) { "import-raw requires a raw data directory" }
            var rawDir = Path.of(args.single())
            if (!rawDir.isAbsolute) rawDir = Path.of("").toAbsolutePath().resolve(rawDir)
            require(Files.exists(rawDir)) { "目录不存在: $rawDir" }
            connectXiaomi(root).use { conn ->
                val counts = SourceImporter(conn).importRawDir(rawDir, root)
                println("导入: $rawDir")
                counts.forEach { (name, count) -> println("  $name: $count") }
                println("数据库: ${xiaomiDbPath(root)}")
            }
            0
        }
        "sync" -> runStandaloneSync(root, args)
        "stats" -> {
            require(args.isEmpty()) { "stats takes no arguments" }
            printXiaomiStats(root)
            0
        }
        else -> error("unsupported standalone command")
    }
}

private fun runStandaloneSync(root: Path, args: List<String>): Int {
    var days = 7
    var start: String? = null
    var end: String? = null
    var includeFds = true
    var sportOnly = false
    val seen = mutableSetOf<String>()
    var index = 0
    while (index < args.size) {
        when (val option = args[index]) {
            "--no-fds" -> { require(seen.add(option)) { "duplicate option: $option" }; includeFds = false; index++ }
            "--sport-only" -> { require(seen.add(option)) { "duplicate option: $option" }; sportOnly = true; index++ }
            "--days", "--start", "--end" -> {
                require(seen.add(option)) { "duplicate option: $option" }
                require(index + 1 < args.size) { "$option requires a value" }
                val value = args[index + 1]
                when (option) {
                    "--days" -> days = value.toIntOrNull() ?: error("--days must be an integer")
                    "--start" -> start = LocalDate.parse(value).toString()
                    "--end" -> end = LocalDate.parse(value).toString()
                }
                index += 2
            }
            else -> error("unknown sync option: $option")
        }
    }
    val credentials = try { Credentials.load(root) } catch (_: CredentialError) {
        System.err.println("unable to load Xiaomi credentials")
        return 2
    }
    val client = MiClient(credentials.first, credentials.second)
    try {
        val rotated = try { client.login() } catch (_: AuthError) {
            System.err.println("Xiaomi login failed; refresh the local browser cookie and retry")
            return 3
        }
        val credentialPath = Credentials.save(root, rotated.first, rotated.second)
        try { Files.setPosixFilePermissions(credentialPath, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) } catch (_: Exception) { }
        val metaPath = connectXiaomi(root).use { conn ->
            SourceSync(conn, client, root).sync(days, start, end, includeFds, sportOnly)
        }
        val meta = kotlinx.serialization.json.Json.parseToJsonElement(Files.readString(metaPath)).jsonObject
        val errors = meta["errors"]?.jsonObject
        if (errors == null) {
            System.err.println("Xiaomi sync failed: result metadata has no errors object")
            return 1
        }
        if (errors.isNotEmpty()) {
            System.err.println("Xiaomi sync failed for datasets: ${errors.keys.sorted().joinToString()}")
            return 1
        }
        val counts = meta["counts"]?.jsonObject.orEmpty()
        println("刷新完成:")
        counts.forEach { (name, count) -> println("  $name: ${count.jsonPrimitive.contentOrNull}") }
        println("数据库: ${xiaomiDbPath(root)}")
        return 0
    } finally { client.close() }
}

private fun printXiaomiStats(root: Path) {
    readOnlyXiaomi(root).use { conn ->
        println("表行数:")
        val tables = listOf("step_minutes", "calorie_minutes", "heart_rate_samples", "resting_heart_rate", "sleep_sessions", "sleep_stages", "body_measurements", "activities", "activity_samples", "activity_track_points", "raw_captures")
        for (table in tables) {
            conn.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM $table").use { rs -> rs.next(); println("  ${table.padEnd(24)} ${rs.getLong(1)}") }
            }
        }
        println("日期范围:")
        val dateQueries = listOf(
            "step_minutes" to "SELECT MIN(date_local),MAX(date_local) FROM step_minutes",
            "heart_rate_samples" to "SELECT MIN(date_local),MAX(date_local) FROM heart_rate_samples",
            "sleep_sessions" to "SELECT MIN(date_local),MAX(date_local) FROM sleep_sessions",
            "body_measurements" to "SELECT MIN(date_local),MAX(date_local) FROM body_measurements",
            "activities" to "SELECT MIN(date_local),MAX(date_local) FROM activities",
        )
        dateQueries.forEach { (table, sql) ->
            conn.createStatement().use { statement -> statement.executeQuery(sql).use { rs ->
                rs.next(); println("  ${table.padEnd(24)} ${rs.getString(1) ?: "-"} 到 ${rs.getString(2) ?: "-"}")
            } }
        }
        println("同步状态:")
        conn.createStatement().use { statement ->
            statement.executeQuery("SELECT dataset,data_key,last_synced_at,coverage_start,coverage_end FROM sync_state ORDER BY dataset,data_key").use { rs ->
                while (rs.next()) println("  ${rs.getString("dataset")}/${rs.getString("data_key").padEnd(20)} last=${rs.getString("last_synced_at")} coverage=${rs.getString("coverage_start")}..${rs.getString("coverage_end")}")
            }
        }
        println("运动类型:")
        conn.createStatement().use { statement ->
            statement.executeQuery("SELECT sport_key,COUNT(*) AS n FROM activities GROUP BY sport_key ORDER BY n DESC").use { rs ->
                while (rs.next()) println("  ${rs.getString("sport_key").padEnd(24)} ${rs.getLong("n")}")
            }
        }
        println("FDS 状态:")
        conn.createStatement().use { statement ->
            statement.executeQuery("SELECT COALESCE(fds_status,'pending') AS status,COUNT(*) AS n FROM activities GROUP BY status ORDER BY status").use { rs ->
                while (rs.next()) println("  ${rs.getString("status").padEnd(24)} ${rs.getLong("n")}")
            }
        }
        println("运动记录:")
        conn.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT sport_key,date_local,distance_m,duration_s,stroke_count,avg_swolf,fds_status," +
                    "(SELECT COUNT(*) FROM activity_samples s WHERE s.activity_id=a.id) AS samples," +
                    "(SELECT COUNT(*) FROM activity_track_points p WHERE p.activity_id=a.id) AS points " +
                    "FROM activities a ORDER BY start_ts DESC",
            ).use { rs ->
                while (rs.next()) println(
                    "  ${rs.getString("date_local")} ${rs.getString("sport_key").padEnd(20)} ${rs.getString("distance_m")}m ${rs.getString("duration_s")}s " +
                        "stroke=${rs.getString("stroke_count")} swolf=${rs.getString("avg_swolf")} fds=${rs.getString("fds_status") ?: "pending"} " +
                        "samples=${rs.getLong("samples")} points=${rs.getLong("points")}",
                )
            }
        }
    }
}

private fun serve(service: CollectorService) {
    val interval = (System.getenv("COLLECTOR_INTERVAL_HOURS") ?: "1").toLong()
    val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "collector-scheduler").apply { isDaemon = false }
    }
    val stopping = AtomicBoolean(false)
    val shutdown = Thread {
        if (stopping.compareAndSet(false, true)) {
            executor.shutdown()
            try { if (!executor.awaitTermination(30, TimeUnit.SECONDS)) executor.shutdownNow() } catch (_: InterruptedException) { executor.shutdownNow(); Thread.currentThread().interrupt() }
        }
    }
    Runtime.getRuntime().addShutdownHook(shutdown)
    executor.schedule({ safeScheduled("startup collection") { service.runCollection() } }, 0, TimeUnit.SECONDS)
    executor.schedule({ safeScheduled("backend request poll") { if (service.pollRequest()) service.runCollection() } }, 0, TimeUnit.SECONDS)
    executor.scheduleAtFixedRate({ safeScheduled("hourly collection") { service.runCollection() } }, interval, interval, TimeUnit.HOURS)
    executor.scheduleAtFixedRate({ safeScheduled("backend request poll") { if (service.pollRequest()) service.runCollection() } }, 1, 1, TimeUnit.MINUTES)
    System.err.println("collector scheduler started (hour interval=$interval)")
    try { executor.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
}

private fun safeScheduled(name: String, action: () -> Unit) {
    try { action() } catch (exc: Exception) { System.err.println("$name failed (${exc.javaClass.simpleName})") }
}

private fun parseOptions(args: List<String>): Map<String, String> {
    val result = mutableMapOf<String, String>()
    var index = 0
    while (index < args.size) {
        val key = args[index]
        if (key !in setOf("--from", "--to") || index + 1 >= args.size) fail("invalid option: $key\n$usage")
        if (result.containsKey(key)) fail("duplicate option: $key")
        result[key] = args[index + 1]
        index += 2
    }
    return result
}

private fun parseDate(value: String): LocalDate = try {
    LocalDate.parse(value)
} catch (_: Exception) {
    fail("date must use YYYY-MM-DD: $value")
}

private fun fail(message: String): Nothing {
    System.err.println(message)
    kotlin.system.exitProcess(2)
}
