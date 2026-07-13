package com.example.desktoppet

import android.content.Context

/** A pet available to spawn, backed by a sprite sheet in `assets/`. */
data class PetDef(
    val id: String,          // asset file name without extension, e.g. "pet01"
    val assetName: String,   // e.g. "pet01.png"
    val displayName: String, // human label shown in the UI
)

/**
 * Discovers the sprite sheets present in `assets/` at runtime, so dropping a new
 * `petXX.png` in makes it selectable without any code change.
 */
object PetCatalog {

    // Friendly names for the known McDonald's sheets; anything else falls back to its id.
    private val friendlyNames = mapOf(
        "pet01" to "Birdie",
        "pet02" to "Grimace",
        "pet03" to "Hamburglar",
        "pet04" to "Ronald",
        "pet_sheet" to "Samurai",
    )

    fun available(context: Context): List<PetDef> {
        val files = runCatching { context.assets.list("") }.getOrNull() ?: emptyArray()
        return files
            .filter { it.endsWith(".png", ignoreCase = true) }
            .map { file ->
                val id = file.substringBeforeLast(".")
                PetDef(
                    id = id,
                    assetName = file,
                    displayName = friendlyNames[id] ?: id,
                )
            }
            .sortedBy { it.id }
    }

    fun byId(context: Context, id: String): PetDef? =
        available(context).firstOrNull { it.id == id }
}
