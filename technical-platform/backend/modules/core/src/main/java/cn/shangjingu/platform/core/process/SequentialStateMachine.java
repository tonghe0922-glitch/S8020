package cn.shangjingu.platform.core.process;

import java.util.List;
import java.util.Objects;

public final class SequentialStateMachine {
    public static final String CLOSED = "CLOSED";
    private final List<String> states;

    public SequentialStateMachine(List<String> states) {
        Objects.requireNonNull(states, "states");
        if (states.isEmpty() || states.stream().anyMatch(state -> state == null || state.isBlank() || CLOSED.equals(state))) {
            throw new IllegalArgumentException("states must be non-empty and must not contain CLOSED");
        }
        if (states.stream().distinct().count() != states.size()) {
            throw new IllegalArgumentException("states must be unique");
        }
        this.states = List.copyOf(states);
    }

    public String initial() {
        return states.getFirst();
    }

    public String next(String current) {
        if (CLOSED.equals(current)) {
            throw new ProcessRejectedException("process is already closed");
        }
        int index = states.indexOf(current);
        if (index < 0) {
            throw new ProcessRejectedException("unknown process state: " + current);
        }
        return index == states.size() - 1 ? CLOSED : states.get(index + 1);
    }

    public void requireTransition(String current, String requested) {
        String legal = next(current);
        if (!Objects.equals(legal, requested)) {
            throw new ProcessRejectedException("illegal state transition: " + current + " -> " + requested);
        }
    }
}
