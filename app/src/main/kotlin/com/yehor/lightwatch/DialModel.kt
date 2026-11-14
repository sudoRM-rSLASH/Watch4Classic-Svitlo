package com.yehor.lightwatch

/** Pure logic of the day dial: which colour a slot gets, which slots are over, where "now" is. */
object DialModel {
    enum class Role { GREEN, RED, AMBER, NO_DATA }

    /**
     * The colour role of slot [i]. A yellow slot (the switching time after an outage) is amber when the
     * "yellow slots mean an outage" setting is on and green when it means power is on; a red slot is an
     * outage; everything else is power. Without data ([flags] null) the slot is neutral.
     */
    fun roleOf(flags: BooleanArray?, maybe: BooleanArray?, i: Int, yellowAsOutage: Boolean): Role = when {
        flags == null -> Role.NO_DATA
        maybe?.getOrNull(i) == true -> if (yellowAsOutage) Role.AMBER else Role.GREEN
        flags.getOrNull(i) == true -> Role.RED
        else -> Role.GREEN
    }

    /** Slots before the current one are over and drawn dark; [curSlot] < 0 means "not today": nothing is over. */
    fun isPast(i: Int, curSlot: Int): Boolean = curSlot >= 0 && i < curSlot

    /** Angle of a time of day on the dial, clockwise from the top (00:00). */
    fun angleOfMinute(minuteOfDay: Int): Float = minuteOfDay.coerceIn(0, 1440) * 360f / 1440f

    /** The hour that contains [minuteOfDay]; used to highlight its label. */
    fun hourOf(minuteOfDay: Int): Int = (minuteOfDay / 60).coerceIn(0, 23)
}
