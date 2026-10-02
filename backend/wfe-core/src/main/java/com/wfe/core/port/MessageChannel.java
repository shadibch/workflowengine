package com.wfe.core.port;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The messaging SPI. Phase 0 defines the contract and ships a Kafka adapter;
 * RabbitMQ and JMS adapters land in v1.5 against the same interface, so no
 * engine or BPMN change is needed to add one.
 *
 * <h2>Why a port rather than a Kafka-first design</h2>
 * A BPMN message task is authored as "send to this destination" or "catch from
 * this channel". If that vocabulary leaked broker concepts — partitions,
 * consumer groups, offsets — every diagram would be coupled to one broker, and
 * a stored definition could not be replayed against a different transport. The
 * port keeps {@code destination} and {@code channel} as opaque strings resolved
 * by a {@code wf_connection}, so the same diagram runs on Kafka or RabbitMQ
 * depending only on which connection the tenant has registered.
 *
 * <h2>Delivery guarantees</h2>
 * <ul>
 *   <li><b>Publishing</b> goes through {@code wf_outbox}: the engine writes the
 *       outbox row in the same transaction as the state change, and a relay
 *       publishes it. A crash between commit and publish is recovered; the
 *       message is neither lost nor duplicated.</li>
 *   <li><b>Consuming</b> goes through {@code wf_inbox}: the message id is
 *       recorded before dispatch, so a redelivery after a crash cannot start a
 *       second instance or fire a waiting token twice.</li>
 *   <li>{@link OutboundMessage#messageId()} is therefore the unit of
 *       idempotency, and {@link OutboundMessage#correlationKey()} is what a
 *       reply is matched on.</li>
 * </ul>
 */
public interface MessageChannel {

    /**
     * The transports this adapter speaks. A definition may be authored against
     * any of these; the effective broker comes from the connection.
     */
    enum Kind {
        KAFKA,
        RABBITMQ,
        JMS
    }

    /**
     * Enqueues {@code message} for delivery.
     *
     * <p>Must return as soon as the message is durably handed to the outbox, not
     * when the broker acknowledges: blocking the engine thread on a broker round
     * trip is exactly the coupling this port removes. Implementations that
     * deliver in-process (the simulator) may return immediately regardless.
     */
    void send(OutboundMessage message);

    /**
     * Registers interest in a channel. Implementations start a consumer and hand
     * each message to {@code handler}; the handler is expected to write the
     * inbox row first, so it must be idempotent-safe to call more than once with
     * the same message id.
     */
    Subscription subscribe(String connectionCode, String channel, MessageHandler handler);

    /**
     * Releases a subscription. Idempotent.
     */
    void unsubscribe(Subscription subscription);

    /**
     * A message on its way out.
     *
     * @param messageId      stable, generated once by the engine; the
     *                       idempotency unit for both the outbox and the inbox
     * @param destination    topic, queue or exchange, resolved per transport
     * @param messageKey     broker-level partitioning key; keeping it equal to
     *                       {@code correlationKey} preserves per-subject order
     * @param correlationKey business correlation handle; also the handle a
     *                       reply is matched on, and what a correlation
     *                       sub-process matches on
     * @param expectReply    when true the token parks in {@code wf_pending_reply}
     *                       until a matching reply arrives or the timeout fires
     * @param replyTimeout   how long to wait for that reply
     */
    record OutboundMessage(String messageId, String connectionCode, String destination,
                           String messageKey, String correlationKey, Map<String, String> headers,
                           String payload, boolean expectReply, Duration replyTimeout,
                           Map<String, Object> variables) {

        public OutboundMessage {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            variables = variables == null ? Map.of() : Map.copyOf(variables);
            messageId = messageId == null || messageId.isBlank()
                    ? java.util.UUID.randomUUID().toString()
                    : messageId;
        }
    }

    /** A message received from a broker. */
    record InboundMessage(String messageId, String connectionCode, String channel,
                          String messageKey, String correlationKey, Map<String, String> headers,
                          String payload) {

        public InboundMessage {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    /**
     * Handles one inbound message.
     *
     * <p>Implementations must not throw for a message that matched no waiting
     * token: an unrelated message on a shared topic is normal, not an error.
     * A genuine failure throws and the broker redelivers.
     */
    @FunctionalInterface
    interface MessageHandler {
        void handle(InboundMessage message);
    }

    /** A live subscription, held so it can be released. */
    interface Subscription extends AutoCloseable {

        String id();

        String channel();

        @Override
        void close();
    }

    /**
     * Identifiers every adapter understands, so the validator can reject a
     * diagram referencing an unregistered destination before publish.
     */
    interface Registry {

        /** Destinations a definition may reference, for validation. */
        List<String> destinations(String connectionCode);

        /**
         * Resolves the transport for a connection code, or throws when the
         * connection is missing or is a different kind.
         */
        Kind kindOf(String connectionCode);
    }
}
