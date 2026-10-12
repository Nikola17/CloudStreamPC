package com.cloudstreampc.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.lagradost.cloudstream3.TvType

/**
 * Parental control of the child profiles: age ratings read from the extension, Cinemeta or TMDB
 * (French, American, German… systems), NSFW and chosen extensions hidden, and the parental code
 * that unlocks the settings and the other profiles.
 */
object Parental {
    /** The settings were unlocked with the parental code since the child profile was opened. */
    var unlocked by androidx.compose.runtime.mutableStateOf(false)

    val kids get() = Store.profile.kids
    val nsfwAllowed get() = Store.settings.nsfw && !kids

    /** Minimum age of a rating ("-12", "PG-13", "TV-MA", "FSK 16", "12+", "Tous publics"…), null if unknown. */
    fun ageOf(rating: String?): Int? {
        val r = rating?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
        when (r.replace(" ", "").replace("_", "-")) {
            "G", "TV-Y", "TV-G", "U", "TP", "TOUSPUBLICS", "ALL", "AL", "0", "0+", "FSK0", "PEGI3", "E" -> return 0
            "TV-Y7", "TV-Y7-FV", "7", "7+" -> return 7
            "PG", "TV-PG" -> return 10
            "PG-13" -> return 13
            "TV-14" -> return 14
            "M", "MA15+" -> return 15
            "R" -> return 17
            "NC-17", "TV-MA", "X", "XXX", "R18+", "R18", "ADULT", "ADULTS" -> return 18
            "NR", "UNRATED", "NOTRATED", "N/A", "NONE" -> return null
        }
        Regex("""(\d{1,2})""").find(r)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..21 }?.let { return it }
        return null
    }

    /** Whether [profile] may open a title rated [ratings] (the first known one counts). */
    fun allowed(profile: Profile, type: TvType?, vararg ratings: String?): Boolean {
        if (!profile.kids) return true
        if (type == TvType.NSFW) return false
        val age = ratings.firstNotNullOfOrNull { ageOf(it) } ?: return !profile.blockUnrated
        return age <= profile.maxAge
    }

    /** Code asked to switch to [target]: its own, or the parental code to leave a child profile. */
    fun codeFor(target: Profile): String? {
        val s = Store.settings
        target.pin?.let { return it }
        val leavingKids = Store.profile.kids && !target.kids
        val anyKids = s.profiles.any { it.kids }
        return if (!target.kids && (leavingKids || anyKids)) s.parentalPin else null
    }
}
