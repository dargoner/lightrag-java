package io.github.lightrag.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record TaskSubmitOptions(List<TaskEventListener> listeners) {
    public TaskSubmitOptions {
        listeners = List.copyOf(Objects.requireNonNull(listeners, "listeners"));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static TaskSubmitOptions defaults() {
        return new TaskSubmitOptions(List.of());
    }

    public static final class Builder {
        private final List<TaskEventListener> listeners = new ArrayList<>();

        public Builder listener(TaskEventListener listener) {
            listeners.add(Objects.requireNonNull(listener, "listener"));
            return this;
        }

        public TaskSubmitOptions build() {
            return new TaskSubmitOptions(listeners);
        }
    }
}
