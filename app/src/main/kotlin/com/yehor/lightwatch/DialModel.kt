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

    /** With two rings the first ring holds the slots 0-23 (00:00-12:00) and the second one 24-47. */
    fun ringOfSlot(slot: Int, rings: Int): Int = if (rings == 2 && slot >= 24) 1 else 0

    /** Degrees of one slot: 7.5 on a 24 h ring, 15 on a 12 h ring. */
    fun slotDegrees(rings: Int): Float = if (rings == 2) 15f else 7.5f

    /** Start angle of [slot] on its ring, clockwise from the top. */
    fun slotAngle(slot: Int, rings: Int): Float = (if (rings == 2) slot % 24 else slot) * slotDegrees(rings)

    /** Angle of a time of day on its ring: a full turn is 24 h on one ring and 12 h on two. */
    fun angleOfMinute(minuteOfDay: Int, rings: Int): Float {
        val m = minuteOfDay.coerceIn(0, 1440)
        return if (rings == 2) (m % 720) * 360f / 720f else m * 360f / 1440f
    }

    /** The ring that holds a time of day (0 = outer). */
    fun ringOfMinute(minuteOfDay: Int, rings: Int): Int = if (rings == 2 && minuteOfDay >= 720) 1 else 0

    /**
     * The outer label to highlight for [hour]. With two rings and no labels inside, the afternoon hours are
     * shown by the outer label at the same angle (hour - 12); otherwise the hour has its own label.
     */
    fun hotLabel(hour: Int, rings: Int, innerLabels: Boolean): Int =
        if (rings == 2 && !innerLabels) hour % 12 else hour
}
