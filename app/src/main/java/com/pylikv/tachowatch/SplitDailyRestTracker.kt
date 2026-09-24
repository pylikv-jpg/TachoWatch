package com.pylikv.tachowatch

/**
 * Tracks the live 3h + 9h split-daily-rest state independently from F925.
 *
 * The first 3h part is credited only when a REST segment of at least 3h and
 * less than 9h is actually closed by switching to a non-rest activity.
 */
object SplitDailyRestTracker {
    const val FIRST_PART_MINUTES = 3 * 60
    const val SECOND_PART_MINUTES = 9 * 60

    data class State(
        val firstPartTaken: Boolean = false,
        val previousResting: Boolean = false,
        val previousRestMinutes: Int = 0,
        val completedAsSplit: Boolean = false
    )

    /**
     * Card history contains only closed activity periods. The recovery provider already
     * limits these periods to the current shift (after the latest >=9h daily-rest boundary),
     * so any closed REST from 3:00 up to 8:59 is a valid completed first part of 3+9.
     */
    fun recoverFirstPartFromClosedRest(restPeriodsMinutes: Iterable<Int>): Boolean =
        restPeriodsMinutes.any { it in FIRST_PART_MINUTES until SECOND_PART_MINUTES }

    fun update(state: State, resting: Boolean, restMinutes: Int): State {
        val minutes = restMinutes.coerceAtLeast(0)

        if (resting) {
            return state.copy(
                previousResting = true,
                previousRestMinutes = minutes,
                completedAsSplit = state.firstPartTaken && minutes >= SECOND_PART_MINUTES
            )
        }

        if (!state.previousResting) {
            return state.copy(
                previousResting = false,
                previousRestMinutes = 0,
                completedAsSplit = false
            )
        }

        return when {
            state.previousRestMinutes >= SECOND_PART_MINUTES -> State()
            state.previousRestMinutes >= FIRST_PART_MINUTES -> state.copy(
                firstPartTaken = true,
                previousResting = false,
                previousRestMinutes = 0,
                completedAsSplit = false
            )
            else -> state.copy(
                previousResting = false,
                previousRestMinutes = 0,
                completedAsSplit = false
            )
        }
    }
}
