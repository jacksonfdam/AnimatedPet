package com.example.desktoppet

import android.content.Context

/**
 * Persisted pet selections. The floating overlay and the live wallpaper are kept
 * independent on purpose: choosing pets for the overlay must not change the
 * wallpaper (which is a persistent system-wide choice the user opts into
 * explicitly via "Set as live wallpaper").
 */
object PetPrefs {
    const val FILE = "pets"

    /** Pets shown by the floating overlay. */
    const val KEY_SELECTED = "selected_pets"

    /** Pets shown by the live wallpaper — only written when the wallpaper is set. */
    const val KEY_WALLPAPER = "wallpaper_pets"

    fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun selected(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_SELECTED, emptySet()) ?: emptySet()

    fun setSelected(context: Context, ids: Set<String>) {
        prefs(context).edit().putStringSet(KEY_SELECTED, ids).apply()
    }

    fun wallpaperPets(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_WALLPAPER, emptySet()) ?: emptySet()

    fun setWallpaperPets(context: Context, ids: Set<String>) {
        prefs(context).edit().putStringSet(KEY_WALLPAPER, ids).apply()
    }
}
