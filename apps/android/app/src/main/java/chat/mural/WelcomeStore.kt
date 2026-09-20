package chat.mural

import android.content.Context
import chat.mural.core.TouchBehavior

/** What the welcome screens learned, and whether they have been seen. */
class WelcomeStore(context: Context) {
    private val preferences = context.getSharedPreferences("welcome", Context.MODE_PRIVATE)

    var welcomed: Boolean
        get() = preferences.getBoolean(WELCOMED, false)
        set(value) = preferences.edit().putBoolean(WELCOMED, value).apply()

    /** The kinds of sound the person said they like, in the order they should appear. */
    var likes: List<TouchBehavior>
        get() = preferences.getStringSet(LIKES, emptySet()).orEmpty().mapNotNull { name -> TouchBehavior.entries.firstOrNull { it.name == name } }
            .sortedBy { ORDER.indexOf(it) }
        set(value) = preferences.edit().putStringSet(LIKES, value.map { it.name }.toSet()).apply()

    private companion object {
        const val WELCOMED = "welcomed"
        const val LIKES = "likes"
        val ORDER = listOf(TouchBehavior.Scrub, TouchBehavior.Tap, TouchBehavior.Hold)
    }
}
