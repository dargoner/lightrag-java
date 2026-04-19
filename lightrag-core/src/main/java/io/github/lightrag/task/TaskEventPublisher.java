package io.github.lightrag.task;

import io.github.lightrag.api.TaskEvent;
import io.github.lightrag.api.TaskEventListener;

import java.util.List;
import java.util.Objects;

class TaskEventPublisher {
    private final List<TaskEventListener> listeners;

    TaskEventPublisher(List<TaskEventListener> listeners) {
        this.listeners = List.copyOf(Objects.requireNonNull(listeners, "listeners"));
    }

    void publish(TaskEvent event) {
        var source = Objects.requireNonNull(event, "event");
        for (var listener : listeners) {
            try {
                listener.onEvent(source);
            } catch (RuntimeException ignored) {
                // Listener failures are isolated in phase 1 and will be surfaced later.
            }
        }
    }
}
