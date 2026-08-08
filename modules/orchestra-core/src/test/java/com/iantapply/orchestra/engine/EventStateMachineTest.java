package com.iantapply.orchestra.engine;

import static org.junit.jupiter.api.Assertions.*;

import com.iantapply.orchestra.api.EventStatus;
import org.junit.jupiter.api.Test;

class EventStateMachineTest {
    private final EventStateMachine machine = new EventStateMachine();

    @Test
    void permitsExpectedRecoveryPath() {
        assertDoesNotThrow(() -> machine.requireTransition(EventStatus.FAILED, EventStatus.SCHEDULED));
        assertDoesNotThrow(() -> machine.requireTransition(EventStatus.PAUSED, EventStatus.RUNNING));
    }

    @Test
    void terminalStatesCannotRestart() {
        assertThrows(
                IllegalStateException.class,
                () -> machine.requireTransition(EventStatus.COMPLETED, EventStatus.RUNNING));
    }
}
