package com.cloudstreampc.providers

import com.fasterxml.jackson.databind.JsonNode

/** Lenient accessors: IPTV panels and addons mix strings, numbers and nulls freely. */
internal fun JsonNode?.str(name: String): String? =
    this?.get(name)?.takeIf { !it.isNull && !it.isMissingNode }?.asText()?.takeIf { it.isNotBlank() && it != "null" }

internal fun JsonNode?.int(name: String): Int? = str(name)?.toDoubleOrNull()?.toInt()

internal fun JsonNode?.double(name: String): Double? = str(name)?.replace(',', '.')?.toDoubleOrNull()

internal fun JsonNode?.items(): List<JsonNode> = if (this != null && isArray) toList() else emptyList()

/** Lower case without accents, for searching. */
internal fun String.normalized(): String =
    java.text.Normalizer.normalize(lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

internal val adultPattern = Regex("(?i)(xxx|adult|porn|\\+18|18\\+)")
