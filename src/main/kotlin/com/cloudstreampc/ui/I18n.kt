package com.cloudstreampc.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import com.cloudstreampc.core.Store
import java.util.concurrent.ConcurrentHashMap

/**
 * Interface language. The French texts of the code are the keys; each other language is a JSON file
 * (resources/i18n/<code>.json, read only when that language is chosen) giving their translation.
 * Texts with variable parts ("Lecture sur {}") are matched as templates.
 * To add a language: copy en.json, translate the values, add the code to [languages].
 */
object I18n {
    val languages = listOf(
        "fr" to "Français", "en" to "English", "es" to "Español", "de" to "Deutsch",
        "it" to "Italiano", "pt" to "Português", "ar" to "العربية",
    )

    private class Table(val texts: Map<String, String>) {
        val templates: List<Pair<Regex, String>> = texts.filterKeys { "{}" in it }.map { (key, value) ->
            Regex("^" + key.split("{}").joinToString("(.+?)") { Regex.escape(it) } + "$", RegexOption.DOT_MATCHES_ALL) to value
        }
        val cache = ConcurrentHashMap<String, String>()
    }

    private val tables = ConcurrentHashMap<String, Table>()

    private fun table(language: String): Table? = tables[language] ?: run {
        val stream = I18n::class.java.getResourceAsStream("/i18n/$language.json") ?: return null
        val texts: Map<String, String> = stream.use { com.cloudstreampc.core.mapper.readValue(it, object : com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {}) }
        Table(texts).also { tables[language] = it }
    }

    /** All the texts of a language (for tests and contributors). */
    internal fun texts(language: String): Map<String, String> = table(language)?.texts.orEmpty()

    val language: String get() = Store.settings.language.takeIf { code -> languages.any { it.first == code } } ?: "fr"

    /** Arabic is written from right to left. */
    val rightToLeft: Boolean get() = language == "ar"

    fun tr(text: String): String = if (language == "fr") text else translate(language, text)

    internal fun toEnglish(text: String): String = translate("en", text)

    internal fun translate(language: String, text: String): String {
        if (text.isBlank()) return text
        val table = table(language) ?: return text
        table.texts[text]?.let { return it }
        if (table.cache.size > 5000) table.cache.clear()
        return table.cache.getOrPut(text) { match(table, text) }
    }

    private fun match(table: Table, text: String): String {
        val trimmed = text.trim()
        if (trimmed != text) table.texts[trimmed]?.let { return text.replace(trimmed, it) }
        for ((regex, value) in table.templates) {
            val match = regex.matchEntire(text) ?: continue
            var index = 0
            return Regex("\\{\\}").replace(value) { Regex.escapeReplacement(match.groupValues.getOrElse(++index) { "" }) }
        }
        return text
    }
}

/** Same as Material's Text, in the chosen language (every screen of the app uses this one). */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = androidx.compose.material3.LocalTextStyle.current,
) = androidx.compose.material3.Text(
    text = I18n.tr(text), modifier = modifier, color = color, fontSize = fontSize, fontStyle = fontStyle,
    fontWeight = fontWeight, fontFamily = fontFamily, letterSpacing = letterSpacing, textDecoration = textDecoration,
    textAlign = textAlign, lineHeight = lineHeight, overflow = overflow, softWrap = softWrap, maxLines = maxLines,
    minLines = minLines, onTextLayout = onTextLayout, style = style,
)
