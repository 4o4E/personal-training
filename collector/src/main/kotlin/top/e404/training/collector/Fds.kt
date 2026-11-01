package top.e404.training.collector

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class GpsPoint(
    val timestamp: Long,
    val latitude: Double?,
    val longitude: Double?,
    val accuracy: Double?,
    val speedMps: Double?,
    val gpsSource: Int?,
    val altitudeM: Double?,
    val hdop: Double?,
)

/** FDS encrypted-file protocol and record decoders. */
object Fds {
    private val AES_IV = "1234567887654321".toByteArray(StandardCharsets.US_ASCII)
    private const val HEADER_META_LEN = 7

    private class FdsParseError(message: String) : IllegalArgumentException(message)
    private data class Header(
        val timestamp: Long,
        val tzIn15min: Int,
        val version: Int,
        val sportType: Int,
        val dataValid: ByteArray,
        val body: ByteArray,
    )
    private data class FourType(
        val typeId: Int,
        val byteSize: Int,
        val supportVersion: Int,
        val highStartBit: Int? = null,
        val highBitCount: Int? = null,
    )
    private data class FourValid(val exist: Boolean, val high: Boolean, val middle: Boolean, val low: Boolean)
    private data class OneType(val typeId: Int, val byteCount: Int, val supportVersion: Int)
    private data class RecordConfig(
        val protoTypes: Set<Int>,
        val dataValidLen: Map<Int, Int>,
        val fourTypes: List<FourType>,
        val itSummaryTypes: List<OneType> = emptyList(),
        val pauseInitTypes: List<OneType> = emptyList(),
    )

    private const val CALORIES = 2
    private const val PACE = 12
    private const val HR = 5
    private const val INTEGER_KM = 6
    private const val DISTANCE = 9
    private const val HEIGHT_VALUE = 87
    private const val CADENCE = 49
    private const val RUNNING_POWER = 57

    private val outdoor = RecordConfig(
        protoTypes = setOf(1, 2, 4, 5, 15),
        dataValidLen = mapOf(1 to 2, 2 to 2),
        itSummaryTypes = listOf(OneType(41, 1, 2)),
        pauseInitTypes = listOf(OneType(0, 4, 1)),
        fourTypes = listOf(
            FourType(CALORIES, 1, 1, 4, 4), FourType(HR, 1, 1),
            FourType(INTEGER_KM, 1, 1, 7, 1), FourType(DISTANCE, 1, 1),
        ),
    )
    private val step = RecordConfig(
        protoTypes = setOf(22, 23),
        dataValidLen = mapOf(1 to 2, 2 to 3, 3 to 3, 4 to 3, 5 to 5, 6 to 6, 7 to 6, 8 to 7, 9 to 7),
        itSummaryTypes = listOf(OneType(41, 1, 1), OneType(43, 4, 3), OneType(78, 4, 7), OneType(54, 4, 6), OneType(55, 2, 6)),
        pauseInitTypes = listOf(OneType(0, 4, 1)),
        fourTypes = listOf(
            FourType(CALORIES, 1, 1, 4, 4), FourType(HR, 1, 1), FourType(HEIGHT_VALUE, 4, 9),
            FourType(INTEGER_KM, 2, 9, 15, 1), FourType(INTEGER_KM, 1, 1, 7, 1),
            FourType(DISTANCE, 1, 1), FourType(40, 1, 2), FourType(44, 4, 4, 26, 6),
            FourType(48, 1, 5), FourType(CADENCE, 1, 5), FourType(PACE, 2, 5),
            FourType(56, 2, 6), FourType(RUNNING_POWER, 2, 6), FourType(79, 2, 8), FourType(80, 2, 8),
        ),
    )
    private val indoorRun = RecordConfig(
        protoTypes = setOf(3),
        dataValidLen = mapOf(1 to 2, 2 to 2, 6 to 4),
        itSummaryTypes = listOf(OneType(41, 1, 2), OneType(43, 4, 4), OneType(78, 4, 8), OneType(55, 2, 7)),
        fourTypes = listOf(
            FourType(CALORIES, 1, 1, 4, 4), FourType(HR, 1, 1), FourType(DISTANCE, 1, 1),
            FourType(40, 1, 3), FourType(44, 4, 5, 26, 6), FourType(48, 1, 6),
            FourType(CADENCE, 1, 6), FourType(PACE, 2, 6), FourType(RUNNING_POWER, 2, 7),
            FourType(79, 2, 9), FourType(80, 2, 9),
        ),
    )
    private val freeTraining = RecordConfig(
        protoTypes = setOf(8, 12, 16),
        dataValidLen = mapOf(3 to 2, 4 to 2, 5 to 2),
        itSummaryTypes = listOf(OneType(41, 1, 2), OneType(78, 4, 4), OneType(89, 2, 5), OneType(90, 2, 5), OneType(91, 2, 5)),
        fourTypes = listOf(FourType(HR, 1, 3), FourType(CALORIES, 1, 3), FourType(38, 1, 3), FourType(39, 1, 3)),
    )
    private val recordConfigs = listOf(outdoor, step, indoorRun, freeTraining)

    val supportedProtoTypes: Set<Int> = recordConfigs.flatMapTo(linkedSetOf()) { it.protoTypes }.toSet()

    private val gpsTypes = listOf(
        OneType(0, 4, 1), OneType(1, 4, 1), OneType(2, 4, 1), OneType(3, 4, 2),
        OneType(4, 2, 2), OneType(5, 0, 2), OneType(6, 4, 3), OneType(7, 4, 3),
    )
    private val gpsFloatTypes = setOf(1, 2, 3, 6, 7)

    fun buildSuffix(sid: String, timestamp: Long, tzIn15min: Int, protoType: Int, fileType: Int): String {
        require(timestamp in 0..0xffff_ffffL) { "timestamp does not fit unsigned 32-bit field: $timestamp" }
        val bytes = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(timestamp.toInt()).put(tzIn15min.toByte()).put((((1 shl 7) + (protoType shl 2) + fileType) and 0xff).toByte()).array()
        val sidHash = MessageDigest.getInstance("SHA-1").digest(sid.toByteArray(StandardCharsets.UTF_8))
        return "${base64Url(bytes)}_${base64Url(sidHash)}"
    }

    fun serverKey(suffix: String, timestamp: Long): String = "${suffix}_${timestamp}"

    fun decrypt(blob: String, objKey: String): ByteArray {
        val key = decodeBase64Url(objKey.toByteArray(StandardCharsets.UTF_8))
        if (key.size != 16) throw FdsParseError("FDS obj_key must decode to 16 bytes, got ${key.size}")
        var raw = blob.toByteArray(StandardCharsets.UTF_8)
        if (raw.isNotEmpty() && raw[0] == '"'.code.toByte() && raw.last() == '"'.code.toByte()) {
            raw = raw.copyOfRange(1, raw.size - 1)
        }
        val text = try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString().trim()
        } catch (e: Exception) {
            throw FdsParseError("FDS ciphertext is not valid UTF-8")
        }
        val encrypted = try { decodeBase64Url(text.toByteArray(StandardCharsets.US_ASCII)) }
        catch (e: IllegalArgumentException) { throw FdsParseError("FDS ciphertext is not valid base64url") }
        if (encrypted.isEmpty() || encrypted.size % 16 != 0) throw FdsParseError("FDS ciphertext length invalid: ${encrypted.size}")
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(AES_IV))
        val padded = cipher.doFinal(encrypted)
        val padLength = padded.last().toInt() and 0xff
        if (padLength !in 1..16 || (padded.size - padLength until padded.size).any { (padded[it].toInt() and 0xff) != padLength }) {
            throw FdsParseError("FDS blob PKCS7 padding invalid")
        }
        return padded.copyOfRange(0, padded.size - padLength)
    }

    fun parseSportRecord(data: ByteArray, protoType: Int): List<Pair<Long, List<Map<Int, Int>>>> {
        val config = recordConfigs.firstOrNull { protoType in it.protoTypes } ?: return emptyList()
        if (headerVersion(data) < 1) return emptyList()
        val version = headerVersion(data)
        val validLength = config.dataValidLen[version] ?: return emptyList()
        return parseRecordBody(parseHeader(data, validLength), config)
    }

    fun parseGpsRecord(data: ByteArray): List<GpsPoint> {
        val version = headerVersion(data)
        val validLength = when (version) { 1, 2, 3, 4 -> 1; else -> return emptyList() }
        val header = parseHeader(data, validLength)
        val valid = parseOneDimenValid(version, header.dataValid)
        if (valid[0] != true || valid[1] != true || valid[2] != true) {
            throw FdsParseError("GPS validity missing required time/lon/lat fields")
        }
        val body = header.body
        val minimumBytes = gpsTypes.filter { it.supportVersion <= version && it.byteCount > 0 }.sumOf { it.byteCount }
        var offset = 0
        val count: Long
        if (version >= 4 && body.size >= 4) {
            count = readUnsigned(body, 0, 4).first
            offset = 4
        } else count = if (minimumBytes == 0) 0 else (body.size / minimumBytes).toLong()
        val samples = ArrayList<GpsPoint>()
        var i = 0L
        while (i < count) {
            if (offset + minimumBytes > body.size) break
            val raw = HashMap<Int, Number>()
            for (type in gpsTypes) {
                if (type.supportVersion > version || type.byteCount == 0) continue
                if (offset + type.byteCount > body.size) return samples
                val value: Number
                if (type.typeId in gpsFloatTypes && type.byteCount == 4) {
                    value = ByteBuffer.wrap(body, offset, 4).order(ByteOrder.LITTLE_ENDIAN).float
                    offset += 4
                } else {
                    val read = readUnsigned(body, offset, type.byteCount)
                    value = read.first
                    offset = read.second
                }
                if (valid[type.typeId] == true) raw[type.typeId] = value
            }
            val time = raw[0]?.toLong()
            val longitude = raw[1]?.toDouble()
            val latitude = raw[2]?.toDouble()
            if (time != null && longitude != null && latitude != null) {
                val speedRaw = raw[4]?.toInt()
                samples += GpsPoint(
                    timestamp = time, latitude = latitude, longitude = longitude,
                    accuracy = raw[3]?.toDouble(),
                    speedMps = speedRaw?.let { ((it and 0xfff0) ushr 4) / 10.0 },
                    gpsSource = if (speedRaw != null && valid[5] == true) speedRaw and 0x0f else null,
                    altitudeM = raw[6]?.toDouble(), hdop = raw[7]?.toDouble(),
                )
            }
            i++
        }
        return samples
    }

    private fun parseRecordBody(header: Header, config: RecordConfig): List<Pair<Long, List<Map<Int, Int>>>> {
        val version = header.version
        val valid = parseFourDimenValid(config.fourTypes, version, header.dataValid)
        val itBytes = config.itSummaryTypes.filter { it.supportVersion <= version }.sumOf { it.byteCount }
        val initBytes = config.pauseInitTypes.filter { it.supportVersion <= version }.sumOf { it.byteCount }
        val minimumSegment = initBytes + 8 + itBytes
        val body = header.body
        val segments = ArrayList<Pair<Long, List<Map<Int, Int>>>>()
        var offset = 0
        while (offset + minimumSegment <= body.size) {
            offset += initBytes
            val countRead = readUnsigned(body, offset, 4); val count = countRead.first; offset = countRead.second
            val startRead = readUnsigned(body, offset, 4); val startTime = startRead.first; offset = startRead.second
            offset += itBytes
            val recordBytes = config.fourTypes.filter { it.supportVersion <= version && valid[it.typeId]?.exist == true }.sumOf { it.byteSize }
            if (count > 0 && (recordBytes == 0 || count > ((body.size - offset) / recordBytes).toLong())) {
                throw FdsParseError("FDS sample count exceeds available record bytes")
            }
            val records = ArrayList<Map<Int, Int>>()
            var recordIndex = 0L
            while (recordIndex < count) {
                val record = LinkedHashMap<Int, Int>()
                for (type in config.fourTypes) {
                    if (type.supportVersion > version) continue
                    val fieldValid = valid[type.typeId]
                    if (fieldValid?.exist != true) continue
                    if (offset + type.byteSize > body.size) break
                    val read = readUnsigned(body, offset, type.byteSize)
                    val value = read.first
                    offset = read.second
                    if (fieldValid.high) {
                        val extracted = if (type.highStartBit != null && type.highBitCount != null)
                            (value ushr type.highStartBit) and ((1L shl type.highBitCount) - 1) else value
                        record[type.typeId] = extracted.toInt()
                    }
                }
                records += record
                recordIndex++
            }
            segments += startTime to records
        }
        return segments
    }

    private fun parseFourDimenValid(types: List<FourType>, version: Int, dataValid: ByteArray): Map<Int, FourValid> {
        val valid = HashMap<Int, FourValid>()
        var nibbleIndex = 0
        for (type in types) {
            if (type.supportVersion > version) {
                valid[type.typeId] = FourValid(false, false, false, false)
                continue
            }
            val byteIndex = nibbleIndex / 2
            if (byteIndex >= dataValid.size) throw FdsParseError("dataValid too short: need byte $byteIndex")
            val byte = dataValid[byteIndex].toInt() and 0xff
            val nibble = if (nibbleIndex % 2 == 0) (byte and 0xf0) ushr 4 else byte and 0x0f
            valid[type.typeId] = FourValid((nibble and 8) != 0, (nibble and 4) != 0, (nibble and 2) != 0, (nibble and 1) != 0)
            nibbleIndex++
        }
        return valid
    }

    private fun parseOneDimenValid(version: Int, dataValid: ByteArray): Map<Int, Boolean> {
        val valid = HashMap<Int, Boolean>()
        var bitIndex = 0
        for (type in gpsTypes) {
            if (type.typeId < 0) continue
            if (type.supportVersion > version) { valid[type.typeId] = false; continue }
            if (dataValid.isEmpty()) { valid[type.typeId] = true; continue }
            val byteIndex = bitIndex / 8
            if (byteIndex >= dataValid.size) throw FdsParseError("dataValid too short: need byte $byteIndex")
            val bit = bitIndex % 8
            valid[type.typeId] = (dataValid[byteIndex].toInt() and (1 shl (7 - bit))) != 0
            bitIndex++
        }
        return valid
    }

    private fun parseHeader(data: ByteArray, dataValidLength: Int): Header {
        val headerLength = HEADER_META_LEN + 1 + dataValidLength
        if (data.size < headerLength) throw FdsParseError("Decrypted blob too short (${data.size} bytes) for header ($headerLength bytes)")
        return Header(
            timestamp = readUnsigned(data, 0, 4).first,
            tzIn15min = data[4].toInt() and 0xff,
            version = data[5].toInt() and 0xff,
            sportType = data[6].toInt() and 0xff,
            dataValid = data.copyOfRange(8, 8 + dataValidLength),
            body = data.copyOfRange(headerLength, data.size),
        )
    }

    private fun headerVersion(data: ByteArray): Int {
        if (data.size < HEADER_META_LEN + 1) throw FdsParseError("Decrypted blob too short to read version byte")
        return data[5].toInt() and 0xff
    }

    private fun readUnsigned(data: ByteArray, offset: Int, size: Int): Pair<Long, Int> {
        if (size != 1 && size != 2 && size != 4) throw FdsParseError("Unsupported read size: $size")
        if (offset < 0 || offset + size > data.size) throw IndexOutOfBoundsException("buffer too short")
        var value = 0L
        for (i in 0 until size) value = value or ((data[offset + i].toLong() and 0xff) shl (8 * i))
        return value to (offset + size)
    }

    private fun base64Url(data: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(data)
    private fun decodeBase64Url(data: ByteArray): ByteArray {
        val text = String(data, StandardCharsets.US_ASCII)
        val padded = text + "=".repeat((4 - text.length % 4) % 4)
        return Base64.getUrlDecoder().decode(padded)
    }
}
