package com.example.desktoppet

import kotlin.random.Random

/**
 * Shared roaming behaviour for pets, used by both the overlay and the wallpaper so
 * they move and emote identically.
 *
 * Directions are biased towards clean cardinal movement (mostly left/right, some
 * up/down) with only occasional 45° diagonals. This keeps the facing unambiguous:
 * a pet walking left actually shows the left walk instead of flipping to the
 * back/front row on a shallow diagonal.
 */
object PetBehavior {

    /** A fresh walk velocity at [speed] px/frame: 55% horizontal, 25% vertical, 20% diagonal. */
    fun walkVelocity(speed: Float): Pair<Float, Float> = when (Random.nextInt(100)) {
        in 0..54 -> (if (Random.nextBoolean()) speed else -speed) to 0f
        in 55..79 -> 0f to (if (Random.nextBoolean()) speed else -speed)
        else -> {
            val d = speed * DIAGONAL_FACTOR
            (if (Random.nextBoolean()) d else -d) to (if (Random.nextBoolean()) d else -d)
        }
    }

    /** How long a pet walks in one direction before re-deciding. */
    fun walkDurationMs(): Long = Random.nextLong(2500L, 6000L)

    /** How long an emote plays before the pet resumes walking. */
    fun emoteDurationMs(): Long = Random.nextLong(1500L, 3200L)

    /** Chance to play an emote (instead of turning) when a walk segment ends. */
    fun shouldEmote(): Boolean = Random.nextFloat() < EMOTE_CHANCE

    private const val DIAGONAL_FACTOR = 0.70710677f // cos(45°): keeps diagonal speed equal
    private const val EMOTE_CHANCE = 0.35f
}
