package com.haoyu.inbound.common;

import java.time.DayOfWeek;
import java.time.LocalDate;

/** Warehouse days: the FCs receive, put away and ship Monday to Saturday; Sunday is closed. */
public final class BusinessCalendar {

    private BusinessCalendar() {}

    public static boolean isWorkingDay(LocalDate d) {
        return d.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    /** {@code days} working days after {@code from} (0 = from itself). */
    public static LocalDate addWorkingDays(LocalDate from, int days) {
        LocalDate d = from;
        for (int added = 0; added < days; ) {
            d = d.plusDays(1);
            if (isWorkingDay(d)) added++;
        }
        return d;
    }

    public static LocalDate nextWorkingDay(LocalDate after) {
        return addWorkingDays(after, 1);
    }
}
