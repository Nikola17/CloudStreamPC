package com.cloudstreampc.core

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File

val mapper = jacksonObjectMapper()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    .configure(SerializationFeature.INDENT_OUTPUT, true)

inline fun <reified T> readJsonFile(file: File, default: () -> T): T =
    try {
        if (file.exists()) mapper.readValue<T>(file) else default()
    } catch (t: Throwable) {
        Logger.error("Impossible de lire ${file.name}", t)
        default()
    }

fun writeJsonFile(file: File, value: Any) {
    val tmp = File(file.parentFile, file.name + ".tmp")
    mapper.writeValue(tmp, value)
    if (!tmp.renameTo(file)) {
        file.delete()
        tmp.renameTo(file)
    }
}
