package com.example.historyprobe

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

val JST: ZoneOffset = ZoneOffset.ofHours(9)

/** ISO 8601 (+09:00)、秒精度。 */
fun iso(ms: Long): String = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), JST)
    .truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

fun nz(v: Any?): Any = v ?: JSONObject.NULL

fun JSONObject.strOrNull(k: String): String? = if (isNull(k)) null else getString(k)
fun JSONObject.longOrNull(k: String): Long? = if (isNull(k)) null else getLong(k)
