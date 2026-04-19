package io.github.lightrag.api;

@FunctionalInterface
public interface TaskEventListener {
    void onEvent(TaskEvent event);
}
