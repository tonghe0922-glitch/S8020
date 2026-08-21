package cn.shangjingu.platform.core.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class SequentialStateMachineTest {
    @Test
    void onlyAllowsPublishedSequentialTransitions() {
        SequentialStateMachine machine = new SequentialStateMachine(List.of("S01", "S02", "S03"));
        assertEquals("S01", machine.initial());
        assertEquals("S02", machine.next("S01"));
        assertEquals("S03", machine.next("S02"));
        assertEquals(SequentialStateMachine.CLOSED, machine.next("S03"));
        assertThrows(ProcessRejectedException.class, () -> machine.requireTransition("S01", "S03"));
        assertThrows(ProcessRejectedException.class, () -> machine.next(SequentialStateMachine.CLOSED));
    }
}
