package com.wfe.core.port;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Resolves business time.
 *
 * <h2>Why not {@link java.time.Clock} directly</h2>
 * {@code Clock} answers "what time is it". A workflow engine also has to answer
 * "when is 2 working days from now", and that answer depends on the tenant's
 * calendar, timezone and holidays. Scattering calendar logic across every timer
 * path is how timeouts end up meaning "48 hours, including the weekend". This
 * port keeps the concept explicit and testable without a database.
 *
 * <p>All methods take and return <em>local</em> times in the calendar's own
 * timezone; the caller converts to UTC for storage. Timers are persisted in UTC
 * and only interpreted here, so a timezone change for a tenant does not
 * retroactively shift timers that have already been scheduled.
 */
public interface BusinessCalendar {

    /**
     * Adds {@code amount} of wall-clock time to {@code from}, skipping
     * non-working time.
     *
     * <p>Implemented against the {@code wf_business_calendar} row named by
     * {@code calendarCode}; a {@code null} or {@link #PLAIN_CALENDAR} code means
     * a plain seven-day calendar, which is what most workflows want.
     */
    LocalDateTime add(String calendarCode, LocalDateTime from, Duration amount);

    /**
     * The next working instant at or after {@code from}. Used by a
     * {@code BUSINESS_TIME} timer that should land on the next working moment
     * rather than on a day boundary.
     */
    LocalDateTime nextWorkingTime(String calendarCode, LocalDateTime from);

    /**
     * Whether {@code instant} falls inside working hours. Decides whether a
     * non-interrupting boundary timer fires immediately or is deferred.
     */
    boolean isWorkingTime(String calendarCode, LocalDateTime instant);

    /**
     * The calendar's timezone: the zone used to decide which working window an
     * instant falls in. This is per-tenant configuration, not a JVM default.
     */
    ZoneId zoneOf(String calendarCode);

    /** The tenant's default calendar code, or empty to use a plain calendar. */
    Optional<String> defaultCalendar();

    /**
     * The working windows of one calendar.
     *
     * @param timezone the zone these windows are expressed in
     * @param windows  keyed by day; a day absent from the map is a non-working day
     * @param holidays specific dates excluded even if the day would normally work
     */
    record Calendar(String code, ZoneId timezone, java.util.Map<Day, List<Window>> windows,
                    List<java.time.LocalDate> holidays) {

        public Calendar {
            windows = windows == null ? java.util.Map.of() : java.util.Map.copyOf(windows);
            holidays = holidays == null ? List.of() : List.copyOf(holidays);
        }
    }

    /**
     * One working window.
     *
     * @param from inclusive start
     * @param to   exclusive end; must be after {@code from}
     */
    record Window(LocalTime from, LocalTime to) {
    }

    /** Day of week, mirroring {@link java.time.DayOfWeek} for JSON round-tripping. */
    enum Day {
        MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY;

        public static Day of(java.time.DayOfWeek dow) {
            return Day.valueOf(dow.name());
        }
    }

    /** A calendar code that resolves to a plain seven-day calendar. */
    String PLAIN_CALENDAR = "DEFAULT";
}
