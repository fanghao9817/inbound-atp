package com.haoyu.inbound.orders;

import java.time.LocalDate;
import java.util.Optional;

/**
 * What to do with a new order, given the earliest date ATP can cover its quantity. Pure function.
 *
 * <ul>
 *   <li>RESERVE - ATP covers it today and it is wanted now (or within the allocation window).
 *   <li>SCHEDULE - a B2B order wanted later than the window that ATP covers by its date: it holds its
 *       place in the timeline but locks no stock, so customers who want the sofa today can still have it.
 *   <li>BACKORDER - ATP covers it only later than wanted: promised at max(earliest date, requested date).
 *   <li>REJECT - ATP cannot cover it within the horizon (recorded as lost demand).
 * </ul>
 * "ATP covers it today" is stricter than "stock on hand is enough": units a later commitment needs
 * are not handed out, which is the point of the look-ahead minimum.
 */
public final class OrderPolicy {

    public enum Action { RESERVE, SCHEDULE, BACKORDER, REJECT }

    public record Decision(Action action, LocalDate promiseDate) {}

    private OrderPolicy() {}

    public static Decision decide(LocalDate needBy, LocalDate today, Optional<LocalDate> earliestAtp, int allocationWindowDays) {
        if (earliestAtp.isEmpty()) {
            return new Decision(Action.REJECT, null);
        }
        LocalDate earliest = earliestAtp.get();
        boolean neededSoon = needBy == null || !needBy.isAfter(today.plusDays(allocationWindowDays));
        if (!earliest.isAfter(today) && neededSoon) {
            return new Decision(Action.RESERVE, today);
        }
        if (needBy != null && !neededSoon && !earliest.isAfter(needBy)) {
            return new Decision(Action.SCHEDULE, needBy);
        }
        LocalDate promise = needBy != null && needBy.isAfter(earliest) ? needBy : earliest;
        return new Decision(Action.BACKORDER, promise);
    }
}
