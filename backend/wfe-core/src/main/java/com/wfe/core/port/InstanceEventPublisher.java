package com.wfe.core.port;

import java.util.List;
import java.util.Map;

/**
 * Pushes execution-state changes to connected clients.
 *
 * <p>Feeds the live instance monitor: tokens moving on the diagram, a task
 * appearing in someone's inbox, a job failing. Carried as a plain
 * {@link Map<String, Object>} because the payload is forwarded verbatim to a
 * websocket/SSE frame whose shape is owned by the SPA, and coupling the engine to
 * a DTO hierarchy here would mean a breaking change in Java for every new UI
 * field.
 */
public interface InstanceEventPublisher {

    /**
     * Publishes a change.
     *
     * <p>Must be non-blocking and must not throw. A slow or disconnected client
     * may lose a frame; the SPA reconciles by refetching on reconnect, so a
     * dropped notification is a staleness issue rather than a correctness one.
     * An exception here must never abort the engine transaction that produced
     * the event.
     *
     * @param topic   logical channel, e.g. {@code instance}, {@code task}
     * @param subject the entity the event is about, so the SPA can filter
     *               without parsing the body
     * @param event   type discriminator, e.g. {@code token.moved}
     * @param payload event-specific fields
     */
    void publish(String topic, Long subject, String event, Map<String, Object> payload);

    /**
     * Which topics a user may observe.
     *
     * <p>This is enforced server-side, not by the SPA: a user only receives
     * instance events for definitions and instances their roles grant them
     * access to. Filtering on the client would leak the existence of other
     * tenants' work.
     */
    interface SubscriptionFilter {

        /** Whether this user may observe events for this subject. */
        boolean canObserve(IdentityProvider.CurrentUser user, String topic, Long subjectId);
    }

    /** Well-known topics. */
    final class Topic {

        private Topic() {
        }

        public static final String INSTANCE = "instance";
        public static final String TASK = "task";
        public static final String JOB = "job";
        public static final String DEFINITION = "definition";
    }
}
