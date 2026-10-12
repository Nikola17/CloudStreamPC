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
 * Interface language. The French texts of the code are the keys; [tr] gives their English version
 * when English is chosen. Texts with variable parts ("Lecture sur {}") are matched as templates.
 */
object I18n {
    val languages = listOf("fr" to "Français", "en" to "English")

    private val templates: List<Pair<Regex, String>> by lazy {
        EnglishTexts.map.filterKeys { "{}" in it }.map { (key, value) ->
            Regex("^" + key.split("{}").joinToString("(.+?)") { Regex.escape(it) } + "$", RegexOption.DOT_MATCHES_ALL) to value
        }
    }
    private val cache = ConcurrentHashMap<String, String>()

    val english get() = Store.settings.language == "en"

    fun tr(text: String): String = if (english) toEnglish(text) else text

    internal fun toEnglish(text: String): String {
        if (text.isBlank()) return text
        EnglishTexts.map[text]?.let { return it }
        if (cache.size > 5000) cache.clear()
        return cache.getOrPut(text) { translate(text) }
    }

    private fun translate(text: String): String {
        val trimmed = text.trim()
        if (trimmed != text) EnglishTexts.map[trimmed]?.let { return text.replace(trimmed, it) }
        for ((regex, value) in templates) {
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
