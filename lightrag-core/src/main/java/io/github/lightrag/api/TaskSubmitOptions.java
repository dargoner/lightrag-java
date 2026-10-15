package io.github.lightrag.api;

import io.github.lightrag.ontology.OntologyTaskContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record TaskSubmitOptions(
    List<TaskEventListener> listeners,
    OntologyTaskContext ontologyContext
) {
    public TaskSubmitOptions {
        listeners = List.copyOf(Objects.requireNonNull(listeners, "listeners"));
        ontologyContext = Objects.requireNonNull(ontologyContext, "ontologyContext");
    }

    /**
     * Compatibility constructor for existing consumers that only attach listeners.
     * The ontology context defaults to empty.
     */
    public TaskSubmitOptions(List<TaskEventListener> listeners) {
        this(listeners, OntologyTaskContext.empty());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static TaskSubmitOptions defaults() {
        return new TaskSubmitOptions(List.of(), OntologyTaskContext.empty());
    }

    public static final class Builder {
        private final List<TaskEventListener> listeners = new ArrayList<>();
        private OntologyTaskContext ontologyContext = OntologyTaskContext.empty();

        public Builder listener(TaskEventListener listener) {
            listeners.add(Objects.requireNonNull(listener, "listener"));
            return this;
        }

        /** Attaches a task-level ontology snapshot; the snapshot is immutable for the task lifetime. */
        public Builder ontologyContext(OntologyTaskContext context) {
            this.ontologyContext = Objects.requireNonNull(context, "context");
            return this;
        }

        public TaskSubmitOptions build() {
            return new TaskSubmitOptions(listeners, ontologyContext);
        }
    }
}
