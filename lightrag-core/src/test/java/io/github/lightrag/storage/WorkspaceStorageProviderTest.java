package io.github.lightrag.storage;

import io.github.lightrag.api.WorkspaceScope;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceStorageProviderTest {

    @Test
    void singleScopeListFallsBackToForWorkspace() {
        var single = InMemoryStorageProvider.create();
        var provider = new SingleWorkspaceProvider(single);

        assertThat(provider.forWorkspaces(List.of(new WorkspaceScope("alpha")))).isSameAs(single);
        assertThat(provider.lastWorkspaceId).isEqualTo("alpha");
    }

    @Test
    void emptyScopeListIsRejected() {
        var provider = new SingleWorkspaceProvider(InMemoryStorageProvider.create());

        assertThatThrownBy(() -> provider.forWorkspaces(List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("scopes must not be empty");
    }

    @Test
    void multiScopeListIsRejectedByDefaultWithAnExplicitMessage() {
        var provider = new SingleWorkspaceProvider(InMemoryStorageProvider.create());

        assertThatThrownBy(() -> provider.forWorkspaces(
            List.of(new WorkspaceScope("alpha"), new WorkspaceScope("beta"))
        ))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("workspace-set queries are not supported by");
    }

    private static final class SingleWorkspaceProvider implements WorkspaceStorageProvider {
        private final AtomicStorageProvider singleWorkspace;
        private String lastWorkspaceId;

        private SingleWorkspaceProvider(AtomicStorageProvider singleWorkspace) {
            this.singleWorkspace = singleWorkspace;
        }

        @Override
        public AtomicStorageProvider forWorkspace(WorkspaceScope scope) {
            lastWorkspaceId = scope.workspaceId();
            return singleWorkspace;
        }

        @Override
        public void close() {
        }
    }
}
