package io.github.lightrag.model;

/**
 * Counts the tokens of a text snippet for budgeting decisions.
 *
 * <p>The default implementation is {@link HeuristicTokenCounter}, a Java-only approximation documented
 * there. Consumers that need exact provider tokenization can plug their own counter through the builder.</p>
 *
 * <p>Counts are expected to be monotone under appending - {@code countTokens(a + b) >= countTokens(a)}
 * for any {@code b}. Budget truncation finds the longest fitting prefix by binary search, which is
 * correct under this contract; a counter that violates it may select a different row than the
 * incremental scan would. Implementations should stay monotone to keep budgeting's cost sub-linear.
 * The default implementation satisfies this expectation, and {@link HeuristicTokenCounter} is
 * covered by TokenCounterContractTest.</p>
 */
@FunctionalInterface
public interface TokenCounter {
    int countTokens(String text);
}
