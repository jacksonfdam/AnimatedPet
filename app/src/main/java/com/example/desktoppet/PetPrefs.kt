package com.example.desktoppet

import android.content.Context

/** Single source of truth for which pets are selected, shared by the app and the wallpaper. */
object PetPrefs {
    const val FILE = "pets"
    const val KEY_SELECTED = "selected_pets"

    fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun selected(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_SELECTED, emptySet()) ?: emptySet()

    fun setSelected(context: Context, ids: Set<String>) {
        prefs(context).edit().putStringSet(KEY_SELECTED, ids).apply()
    }
}
