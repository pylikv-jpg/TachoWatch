package com.pylikv.tachowatch

/** Read-only diagnostic scan plan for DTCO DIDs.
 *
 * The actual transport remains UDS ReadDataByIdentifier (SID 0x22).
 * This first scan block covers the ISO-defined/readable DIDs in F900..F93F,
 * plus reserved gaps as explicit probes so that positive responses are not lost.
 */
object DidScanPlan {
    val firstBlock: IntArray = (0xF900..0xF93F).toList().toIntArray()

    val knownReadOnly: Set<Int> = setOf(
        0xF902, 0xF903, 0xF904, 0xF905, 0xF906, 0xF907, 0xF908,
        0xF909, 0xF90A, 0xF914, 0xF915, 0xF916, 0xF917, 0xF919,
        0xF91B, 0xF923, 0xF924, 0xF925, 0xF926, 0xF927, 0xF928,
        0xF930, 0xF931, 0xF932, 0xF933, 0xF936, 0xF937, 0xF938, 0xF939
    )

    fun label(did: Int): String = when (did) {
        0xF902 -> "TachographVehicleSpeed"
        0xF903 -> "Driver1WorkingState"
        0xF904 -> "Driver2WorkingState"
        0xF905 -> "DriveRecognize"
        0xF906 -> "Driver1TimeRelatedStates"
        0xF907 -> "DriverCardDriver1"
        0xF908 -> "OverSpeed"
        0xF909 -> "Driver2TimeRelatedStates"
        0xF90A -> "DriverCardDriver2"
        0xF914 -> "ServiceComponentIdentification"
        0xF915 -> "ServiceDelayCalendarTimeBased"
        0xF916 -> "Driver1Identification"
        0xF917 -> "Driver2Identification"
        0xF919 -> "SpeedMeasurementRange"
        0xF91B -> "TachographOutputShaftSpeed"
        0xF923 -> "Driver1ContinuousDrivingTime"
        0xF924 -> "Driver2ContinuousDrivingTime"
        0xF925 -> "Driver1CumulativeBreakTime"
        0xF926 -> "Driver2CumulativeBreakTime"
        0xF927 -> "Driver1CurrentDurationOfSelectedActivity"
        0xF928 -> "Driver2CurrentDurationOfSelectedActivity"
        0xF930 -> "TachographCardSlot1"
        0xF931 -> "Driver1Name"
        0xF932 -> "Driver2Name"
        0xF933 -> "TachographCardSlot2"
        0xF936 -> "OutOfScopeCondition"
        0xF937 -> "ModeOfOperation"
        0xF938 -> "Driver1CumulatedDrivingTimePreviousAndCurrentWeek"
        0xF939 -> "Driver2CumulatedDrivingTimePreviousAndCurrentWeek"
        else -> "UNKNOWN_OR_RESERVED"
    }
}
