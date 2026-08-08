package com.iantapply.orchestra.platform.paper;

import io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/** Thread-safe server-local gate that rejects new connections while disabled. */
public final class JoinGate implements Listener {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private final AtomicBoolean enabled = new AtomicBoolean(true);

    /** Creates an enabled join gate. */
    public JoinGate() {}

    /**
     * Updates the gate state.
     *
     * @param enabled whether new player connections are accepted
     */
    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
    }

    /**
     * Rejects a login early when event operations have disabled joins.
     *
     * @param event connection validation event
     */
    @EventHandler
    public void onLogin(PlayerConnectionValidateLoginEvent event) {
        if (!enabled.get()) {
            event.kickMessage(MINI_MESSAGE.deserialize("<red>This server is temporarily unavailable for an event."));
        }
    }
}
