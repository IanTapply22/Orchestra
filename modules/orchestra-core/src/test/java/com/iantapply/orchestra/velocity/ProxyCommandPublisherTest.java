package com.iantapply.orchestra.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.iantapply.orchestra.port.NetworkTransport;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ProxyCommandPublisherTest {
    @Test
    void publishesCommandsToTheSelectedProxyChannel() {
        RecordingTransport transport = new RecordingTransport();
        ProxyCommandPublisher publisher = new ProxyCommandPublisher(transport);

        publisher.movePlayer("proxy-1", "player-id", "survival-1");
        assertEquals("velocity:proxy-1", transport.channel);
        assertEquals("MOVE\tplayer-id\tsurvival-1", transport.payload);

        publisher.setGroupJoins("proxy-2", "survival", false);
        assertEquals("velocity:proxy-2", transport.channel);
        assertEquals("JOINS\tsurvival\tfalse", transport.payload);
    }

    @Test
    void rejectsFieldsThatCanBreakTheWireFormat() {
        ProxyCommandPublisher publisher = new ProxyCommandPublisher(new RecordingTransport());
        assertThrows(IllegalArgumentException.class, () -> publisher.movePlayer("proxy\tother", "player", "server"));
    }

    private static final class RecordingTransport implements NetworkTransport {
        private String channel;
        private String payload;

        @Override
        public void publish(String channel, byte[] payload) {
            this.channel = channel;
            this.payload = new String(payload, StandardCharsets.UTF_8);
        }

        @Override
        public Subscription subscribe(String channel, Consumer<byte[]> listener) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {}
    }
}
