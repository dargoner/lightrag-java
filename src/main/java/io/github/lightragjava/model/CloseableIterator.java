package io.github.lightragjava.model;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

public interface CloseableIterator<T> extends Iterator<T>, AutoCloseable {
    @Override
    default void close() {
    }

    static <T> CloseableIterator<T> empty() {
        return new CloseableIterator<>() {
            @Override
            public boolean hasNext() {
                return false;
            }

            @Override
            public T next() {
                throw new NoSuchElementException();
            }
        };
    }

    static <T> CloseableIterator<T> of(List<T> values) {
        var items = List.copyOf(Objects.requireNonNull(values, "values"));
        return new CloseableIterator<>() {
            private int index;

            @Override
            public boolean hasNext() {
                return index < items.size();
            }

            @Override
            public T next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return items.get(index++);
            }
        };
    }
}
