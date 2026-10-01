package io.github.lightrag.model;

/**
 * Counts the tokens of a text snippet for budgeting decisions.
 *
 * <p>The default implementation is {@link HeuristicTokenCounter}, a Java-only approximation documented
 * there. Consumers that need exact provider tokenization can plug their own counter through the builder.</p>
 */
@FunctionalInterface
public interface TokenCounter {
    int countTokens(String text);
}
