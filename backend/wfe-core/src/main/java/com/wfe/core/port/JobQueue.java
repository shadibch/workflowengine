package com.wfe.core.port;

import java.time.Duration;
import java.util.Map;

/**
 * Schedules deferred work: boundary/intermediate timers, service-call retries,
 * request/reply timeouts, task reminders and escalations.
 *
 * <p>Timers never live in memory. Every one becomes a {@code wf_job} row claimed
 * with {@code FOR UPDATE SKIP LOCKED}, which is why timers survive a restart and
 * why the engine scales horizontally without a scheduler cluster.
 *
 * <p>A timer is a <em>business</em> deadline, not a wall-clock offset, so
 * {@link JobRequest#deadline()} is resolved through {@link BusinessCalendar}
 * before it is stored. "Notify after 2 working days" must not fire at 02:00 on a
 * Sunday.
 */
public interface JobQueue {

    /**
     * Enqueues a job, or returns the existing one if {@link JobRequest#dedupKey()}
     * is already pending.
     *
     * <p>The dedup key is what makes re-scheduling safe. When a token is released
     * by a message and the boundary event also schedules a timer, both paths
     * produce the same key and only one job exists — so the timer cannot fire a
     * second time against an already-completed activity.
     *
     * @return the job id, existing or new
     */
    long schedule(JobRequest request);

    /**
     * Cancels a pending job. Used when an instance completes before its
     * outstanding timers have fired; otherwise a completed instance would still
     * wake up and try to continue.
     */
    void cancel(String dedupKey);

    /** Cancels every job attached to an instance. */
    void cancelForInstance(long instanceId);

    /**
     * A unit of deferred work.
     *
     * @param dedupKey   idempotency key; must be deterministic for a given
     *                   logical event, e.g.
     *                   {@code timer:4711:instance-9:token-3}
     * @param jobType    what to do when it fires
     * @param runAt      absolute UTC time, already resolved through the calendar
     * @param payload    job-specific input
     * @param maxAttempts attempts before the job is marked DEAD and the node's
     *                   {@code onExhausted} policy applies
     */
    record JobRequest(String jobType, String dedupKey, long instanceId, Long taskId, String nodeId,
                      java.time.Instant runAt, Map<String, Object> payload, int maxAttempts,
                      Duration retryBase, Duration retryMax, double retryMultiplier) {

        public JobRequest {
            payload = payload == null ? Map.of() : Map.copyOf(payload);
            maxAttempts = maxAttempts <= 0 ? 3 : maxAttempts;
            retryBase = retryBase == null ? Duration.ofSeconds(1) : retryBase;
            retryMax = retryMax == null ? Duration.ofMinutes(5) : retryMax;
            retryMultiplier = retryMultiplier <= 0 ? 2.0 : retryMultiplier;
            if (dedupKey == null || dedupKey.isBlank()) {
                throw new IllegalArgumentException("A job requires a dedup key");
            }
            if (runAt == null) {
                throw new IllegalArgumentException("A job requires an absolute run time");
            }
        }
    }

    /** Job kinds; mirrors the {@code job_type} check constraint in {@code wf_job}. */
    final class JobType {

        private JobType() {
        }

        public static final String TIMER = "TIMER";
        public static final String SERVICE_RETRY = "SERVICE_RETRY";
        public static final String ASYNC_CONTINUE = "ASYNC_CONTINUE";
        public static final String REMINDER = "REMINDER";
        public static final String ESCALATION = "ESCALATION";
        public static final String MESSAGE_PUBLISH = "MESSAGE_PUBLISH";
        public static final String REPLY_TIMEOUT = "REPLY_TIMEOUT";
        public static final String TERMINATE_CHECK = "TERMINATE_CHECK";
        public static final String CLEANUP = "CLEANUP";
    }
}
