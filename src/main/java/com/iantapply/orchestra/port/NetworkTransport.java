package com.iantapply.orchestra.port;

import java.util.function.Consumer;

/** Binary publish/subscribe transport used for cross-server messages. */
public interface NetworkTransport extends AutoCloseable {
    /**
     * Publishes one binary message.
     *
     * @param channel logical channel name
     * @param payload immutable message bytes for the duration of the call
     */
    void publish(String channel, byte[] payload);

    /**
     * Subscribes to a logical channel.
     *
     * @param channel logical channel name
     * @param listener message callback
     * @return subscription handle
     */
    Subscription subscribe(String channel, Consumer<byte[]> listener);

    /** Closes all subscriptions and rejects new work. */
    @Override
    void close();

    /** Closeable subscription to one logical channel. */
    interface Subscription extends AutoCloseable {
        /** Stops delivery and releases transport resources. */
        @Override
        void close();
    }
}
