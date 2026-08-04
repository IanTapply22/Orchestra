package com.iantapply.orchestra.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.iantapply.orchestra.port.NetworkTransport;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class VelocityAgentTest {
    @Test
    void publishesHeartbeatsAndDispatchesSupportedCommands() throws Exception {
        FakeTransport transport = new FakeTransport();
        RecordingProxy proxy = new RecordingProxy();
        try (VelocityAgent agent = new VelocityAgent("proxy-1", transport, proxy)) {
            agent.start();
            agent.start();
            assertTrue(transport.heartbeat.await(2, TimeUnit.SECONDS));

            transport.receive("MOVE\tplayer-id\tsurvival-1");
            transport.receive("JOINS\tsurvival\tfalse");
            transport.receive("UNKNOWN\tignored");
            transport.receive("MOVE\tincomplete");

            assertEquals(1, transport.subscriptions.get());
            assertEquals("velocity:proxy-1", transport.channel);
            assertTrue(transport.published.getFirst().startsWith("proxy-1\t7\t"));
            assertEquals(List.of("player-id:survival-1"), proxy.moves);
            assertEquals(List.of("survival:false"), proxy.joinStates);
        }

        assertTrue(transport.subscriptionClosed.get());
    }

    private static final class FakeTransport implements NetworkTransport {
        private final CountDownLatch heartbeat = new CountDownLatch(1);
        private final AtomicInteger subscriptions = new AtomicInteger();
        private final AtomicBoolean subscriptionClosed = new AtomicBoolean();
        private final List<String> published = new ArrayList<>();
        private Consumer<byte[]> listener;
        private String channel;

        @Override
        public synchronized void publish(String channel, byte[] payload) {
            published.add(new String(payload, StandardCharsets.UTF_8));
            heartbeat.countDown();
        }

        @Override
        public Subscription subscribe(String channel, Consumer<byte[]> listener) {
            this.channel = channel;
            this.listener = listener;
            subscriptions.incrementAndGet();
            return () -> subscriptionClosed.set(true);
        }

        void receive(String command) {
            listener.accept(command.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void close() {}
    }

    private static final class RecordingProxy implements ProxyFacade {
        private final List<String> moves = new ArrayList<>();
        private final List<String> joinStates = new ArrayList<>();

        @Override
        public int onlinePlayers() {
            return 7;
        }

        @Override
        public void movePlayer(String playerId, String serverId) {
            moves.add(playerId + ":" + serverId);
        }

        @Override
        public void setGroupJoins(String group, boolean enabled) {
            joinStates.add(group + ":" + enabled);
        }
    }
}
