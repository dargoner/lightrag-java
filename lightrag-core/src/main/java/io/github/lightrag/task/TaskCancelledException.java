package io.github.lightrag.task;

final class TaskCancelledException extends RuntimeException {
    TaskCancelledException(String message) {
        super(message);
    }
}
