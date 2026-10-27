package top.e404.training.server

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import top.e404.training.shared.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.Base64

fun main() {
    val data = Path.of(System.getenv("TRAINING_DATA") ?: "data").toAbsolutePath()
    Files.createDirectories(data)
    val token = if (System.getenv("TRAINING_TOKEN") != null) {
        System.getenv("TRAINING_TOKEN").also { require(it.isNotBlank()) { "TRAINING_TOKEN 不能为空" } }
    } else {
        val tokenFile = data.resolve("token")
        if (Files.notExists(tokenFile)) {
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            Files.writeString(tokenFile, Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
        }
        Files.readString(tokenFile).trim().also { require(it.isNotBlank()) { "token 文件不能为空" } }
    }
    val seed = Thread.currentThread().contextClassLoader.getResourceAsStream("catalog.json")!!.bufferedReader().use {
        wireJson.decodeFromString<List<Document>>(it.readText())
    }
    val store = Store(data, seed)
    val archives = Archives(data, store)
    Runtime.getRuntime().addShutdownHook(Thread { store.close() })
    embeddedServer(Netty, host = System.getenv("TRAINING_HOST") ?: "0.0.0.0", port = (System.getenv("PORT") ?: "8080").toInt()) {
        trainingApplication(store, archives, token)
    }.start(wait = true)
}

fun Application.trainingApplication(store: Store, archives: Archives, token: String) {
    install(ContentNegotiation) { json(wireJson) }
    install(StatusPages) {
        exception<ImportConflict> { call, error -> call.respond(HttpStatusCode.Conflict, ApiError(error.message!!)) }
        exception<SerializationException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ApiError("请求内容与数据格式不符")) }
        exception<DateTimeParseException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ApiError("日期必须为有效的 YYYY-MM-DD")) }
        exception<IllegalArgumentException> { call, error -> call.respond(HttpStatusCode.BadRequest, ApiError(error.message ?: "请求参数不正确")) }
    }
    val tokenBytes = token.toByteArray(Charsets.UTF_8)
    install(Authentication) {
        bearer("personal-token") {
            realm = "personal-training"
            authenticate { credentials ->
                if (MessageDigest.isEqual(tokenBytes, credentials.token.toByteArray(Charsets.UTF_8))) UserIdPrincipal("personal") else null
            }
        }
    }
    routing {
        get("/health") { call.respond(buildJsonObject { put("status", "ok") }) }
        authenticate("personal-token") {
            route("/v1") {
                get("/documents") {
                    val kind = call.request.queryParameters["kind"]?.let(Kind::valueOf)
                    val deleted = call.request.queryParameters["includeDeleted"] == "true"
                    call.respond(withContext(Dispatchers.IO) { store.list(kind, deleted) })
                }
                get("/documents/{kind}/{id}") {
                    val doc = withContext(Dispatchers.IO) { store.get(Kind.valueOf(call.parameters["kind"]!!), call.parameters["id"]!!) }
                    if (doc == null || doc.deleted) call.respond(HttpStatusCode.NotFound, ApiError("资料不存在")) else call.respond(doc)
                }
                put("/documents/{kind}/{id}") {
                    val mutation = call.receive<Mutation>()
                    require(mutation.document.kind.name == call.parameters["kind"] && mutation.document.id == call.parameters["id"]) { "路径与资料标识不一致" }
                    val response = withContext(Dispatchers.IO) { store.sync(SyncRequest(mutations = listOf(mutation))) }
                    call.respond(if (response.conflicts.isEmpty()) HttpStatusCode.OK else HttpStatusCode.Conflict, response)
                }
                delete("/documents/{kind}/{id}") {
                    val kind = Kind.valueOf(call.parameters["kind"]!!)
                    val id = call.parameters["id"]!!
                    val base = call.request.queryParameters["revision"]!!.toLong()
                    val old = withContext(Dispatchers.IO) { store.get(kind, id) }
                    if (old == null) call.respond(HttpStatusCode.NotFound, ApiError("资料不存在")) else {
                        val response = withContext(Dispatchers.IO) { store.sync(SyncRequest(mutations = listOf(Mutation(old.copy(deleted = true), base)))) }
                        call.respond(if (response.conflicts.isEmpty()) HttpStatusCode.OK else HttpStatusCode.Conflict, response)
                    }
                }
                post("/sync") {
                    val request = call.receive<SyncRequest>()
                    call.respond(withContext(Dispatchers.IO) { store.sync(request) })
                }
                get("/history") {
                    val from = call.request.queryParameters["from"]?.let(LocalDate::parse)
                    val until = call.request.queryParameters["to"]?.let(LocalDate::parse)
                    val documents = withContext(Dispatchers.IO) { store.list() }
                    val sessions = documents.filter { it.kind == Kind.session }.map { it.value<Session>() }
                        .filter { session -> (from == null || sessionDate(session) >= from) && (until == null || sessionDate(session) <= until) }
                    val activities = documents.filter { it.kind == Kind.activity }.map { it.value<ActivityRecord>() }
                        .filter { activity ->
                            val date = LocalDate.parse(activity.localDate)
                            (from == null || date >= from) && (until == null || date <= until)
                        }
                    call.respond(summarizeHistory(sessions, activities))
                }
                get("/recommendations") {
                    val type = call.request.queryParameters["type"] ?: "strength"
                    require(type in setOf("strength", "stretch")) { "推荐类型不正确" }
                    val muscleIds = call.request.queryParameters["muscles"]?.split(',')?.filter { it.isNotBlank() }?.toMutableSet() ?: mutableSetOf()
                    val sourceIds = call.request.queryParameters["exercises"]?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
                    val docs = withContext(Dispatchers.IO) { store.list() }
                    val exercises = docs.filter { it.kind == Kind.exercise }.map { it.value<Exercise>() }
                    sourceIds.forEach { id ->
                        val ex = requireNotNull(exercises.singleOrNull { it.id == id }) { "训练动作不存在：$id" }
                        require(ex.type == "strength") { "对应拉伸的来源必须是力量训练动作，其他情况请按部位筛选" }
                        muscleIds.addAll(ex.muscles); muscleIds.addAll(ex.secondaryMuscles)
                    }
                    call.respond(recommendExercises(exercises, muscleIds, call.request.queryParameters["gymId"], docs.filter { it.kind == Kind.equipment }.map { it.value<Equipment>() }, type))
                }
                get("/programs/{id}/next") {
                    val docs = withContext(Dispatchers.IO) { store.list() }
                    val program = docs.singleOrNull { it.kind == Kind.program && it.id == call.parameters["id"] }
                    if (program == null) call.respond(HttpStatusCode.NotFound, ApiError("分化方案不存在")) else {
                        val value = program.value<Program>()
                        val sessions = docs.filter { it.kind == Kind.session }.map { it.value<Session>() }
                        val arrangement = docs.singleOrNull { it.kind == Kind.arrangement && it.id == "current" }?.value<Arrangement>()
                        call.respond(nextSlot(value, sessions, arrangement?.nextOverrides?.get(value.id)))
                    }
                }
                get("/files/{name}") {
                    val name = call.parameters["name"]!!
                    require(validId(name)) { "素材文件名不正确" }
                    val stream = archives.openMedia(name)
                    if (stream == null) call.respond(HttpStatusCode.NotFound, ApiError("素材文件不存在")) else {
                        call.respondOutputStream(ContentType.parse(mediaMime(name))) { stream.use { it.copyTo(this) } }
                    }
                }
                put("/files/{name}") {
                    val name = call.parameters["name"]!!
                    require(validId(name)) { "素材文件名不正确" }
                    mediaMime(name)
                    withContext(Dispatchers.IO) { call.receiveChannel().toInputStream().use { archives.saveMedia(name, it) } }
                    call.respond(HttpStatusCode.NoContent)
                }
                get("/export") {
                    call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=training-backup.zip")
                    call.respondOutputStream(ContentType.Application.Zip) { withContext(Dispatchers.IO) { archives.export(this@respondOutputStream) } }
                }
                post("/import") {
                    val replace = call.request.queryParameters["replace"] == "true"
                    val result = withContext(Dispatchers.IO) { call.receiveChannel().toInputStream().use { archives.import(it, replace) } }
                    call.respond(result)
                }
            }
        }
    }
}

fun mediaMime(name: String): String = when (name.substringAfterLast('.').lowercase()) {
    "webp" -> "image/webp"; "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"; "svg" -> "image/svg+xml"
    else -> throw IllegalArgumentException("不支持此素材文件类型")
}
