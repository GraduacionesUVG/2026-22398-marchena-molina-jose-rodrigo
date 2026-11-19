package com.example.smartuse_frontend.inference

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * The researcher's golden fixtures (models/fixtures, copied to test resources):
 * two MPUD people, the input epochs and every intermediate result. offset_s = +2 h.
 */
object Fixtures {
    val people = listOf("u40466b", "u6dae97")
    const val OFFSET_S = 7200.0

    /** `yyyy-MM-dd HH:mm:ss[.SSS]` in UTC → epoch seconds. */
    fun utcSeconds(s: String): Double {
        val dt = LocalDateTime.parse(s.replace(' ', 'T'))
        return dt.toEpochSecond(ZoneOffset.UTC) + dt.nano / 1e9
    }

    fun csv(person: String, name: String): List<Map<String, String>> {
        val lines = javaClass.getResourceAsStream("/fixtures/$person/$name")!!.bufferedReader().readLines().filter { it.isNotBlank() }
        val header = lines.first().split(",")
        return lines.drop(1).map { line -> header.zip(line.split(",")).toMap() }
    }

    fun epochs(person: String): List<ModelEpoch> = csv(person, "epochs.csv").map {
        val start = utcSeconds(it.getValue("start_utc"))
        ModelEpoch(
            start = start,
            end = start + it.getValue("duration_s").toDouble(),
            isUse = it.getValue("is_use") == "True",
            apps = it.getValue("distinct_app_count").toInt(),
        )
    }

    fun profileJson(person: String, pattern: Pattern): JsonObject = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/fixtures/$person/profile_${pattern.name}.json")!!.bufferedReader().readText(),
    ).jsonObject

    fun bool(s: String): Boolean = s == "True" || s == "true"
}
