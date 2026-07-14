package com.example.desktoppet

import android.content.Context

/**
 * Which detected row to use for each facing. Sheets differ: the McDonald's 4x5
 * sheets are 4-directional (front/back/side), while pet05/pet06 are side-scroller
 * walk sheets whose extra rows are emotes/jumps we don't want to loop. [frontRow]
 * and [backRow] are null when a sheet has no clean walk for that direction — the
 * pet then falls back to the side row for vertical movement.
 */
data class PetLayout(
    val sideRow: Int,
    val frontRow: Int? = null,
    val backRow: Int? = null,
    val sideFacesLeft: Boolean = true,
)

/** A pet available to spawn, backed by a sprite sheet in `assets/`. */
data class PetDef(
    val id: String,          // asset file name without extension, e.g. "pet01"
    val assetName: String,   // e.g. "pet01.png"
    val displayName: String, // human label shown in the UI
    val layout: PetLayout,   // how this sheet's rows map to facings
)

/**
 * Discovers the sprite sheets present in `assets/` at runtime, so dropping a new
 * `petXX.png` in makes it selectable without any code change.
 */
object PetCatalog {

    // Friendly names for the known sheets; anything else falls back to its id.
    private val friendlyNames = mapOf(
        "pet01" to "Birdie",
        "pet02" to "Grimace",
        "pet03" to "Hamburglar",
        "pet04" to "Ronald",
        "pet05" to "Maria",
        "pet06" to "Jackson",
        "pet_sheet" to "Samurai",
    )

    /** 4x5 McDonald's / samurai sheets: row 0 front, 1 back, 2 side (faces left). */
    private val defaultLayout = PetLayout(sideRow = 2, frontRow = 0, backRow = 1)

    // Sheets whose row layout differs from the default.
    private val layouts = mapOf(
        // Maria (4x4): only row 0 is a clean side walk; other rows are mixed/emotes.
        "pet05" to PetLayout(sideRow = 0),
        // Jackson (5x4): row 0 side walk, row 1 back walk; no clean front walk row.
        "pet06" to PetLayout(sideRow = 0, backRow = 1),
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
                    layout = layouts[id] ?: defaultLayout,
                )
            }
            .sortedBy { it.id }
    }

    fun byId(context: Context, id: String): PetDef? =
        available(context).firstOrNull { it.id == id }
}
