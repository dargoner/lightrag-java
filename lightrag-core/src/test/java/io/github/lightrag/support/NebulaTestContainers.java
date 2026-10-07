package io.github.lightrag.support;

import com.vesoft.nebula.client.graph.NebulaPoolConfig;
import com.vesoft.nebula.client.graph.data.HostAddress;
import com.vesoft.nebula.client.graph.data.ResultSet;
import com.vesoft.nebula.client.graph.data.ValueWrapper;
import com.vesoft.nebula.client.graph.exception.AuthFailedException;
import com.vesoft.nebula.client.graph.exception.ClientServerIncompatibleException;
import com.vesoft.nebula.client.graph.exception.IOErrorException;
import com.vesoft.nebula.client.graph.exception.InvalidConfigException;
import com.vesoft.nebula.client.graph.exception.InvalidValueException;
import com.vesoft.nebula.client.graph.exception.NotValidConnectionException;
import com.vesoft.nebula.client.graph.net.NebulaPool;
import com.vesoft.nebula.client.graph.net.Session;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitStrategy;
import org.testcontainers.containers.wait.strategy.WaitStrategyTarget;
import org.testcontainers.utility.DockerImageName;

import java.io.UnsupportedEncodingException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;

/**
 * A three-service NebulaGraph cluster (metad + storaged + graphd) on a private Docker network,
 * mirroring the reference deployment: the services address each other through network aliases,
 * graphd is the only exposed endpoint, and the storage host has to be registered with the meta
 * service ({@code ADD HOSTS}) before a space can be created.
 */
public final class NebulaTestContainers {
    public static final int GRAPHD_PORT = 9669;

    private static final String METAD_ALIAS = "nebula-metad";
    private static final String STORAGED_ALIAS = "nebula-storaged";
    private static final String GRAPHD_ALIAS = "nebula-graphd";
    private static final String USERNAME = "root";
    private static final String PASSWORD = "nebula";
    private static final long READY_TIMEOUT_MILLIS = 90_000;
    private static final long READY_POLL_MILLIS = 500;

    private static Cluster shared;

    private NebulaTestContainers() {
    }

    /**
     * The cluster shared by every test in the JVM - booting three services per test class is the
     * expensive part - stopped by a JVM shutdown hook.
     */
    public static synchronized Cluster shared() {
        if (shared == null) {
            var network = Network.newNetwork();
            var cluster = new Cluster(network, metad(network), storaged(network), graphd(network));
            cluster.start();
            shared = cluster;
            Runtime.getRuntime().addShutdownHook(new Thread(cluster::stop, "nebula-test-cluster-shutdown"));
        }
        return shared;
    }

    private static GenericContainer<?> metad(Network network) {
        return new GenericContainer<>(image("LIGHTRAG_NEBULA_METAD_IMAGE", "vesoft/nebula-metad:v3.8.0"))
            .withNetwork(network)
            .withNetworkAliases(METAD_ALIAS)
            .withExposedPorts(9559)
            .withCommand(
                "--meta_server_addrs=" + METAD_ALIAS + ":9559",
                "--local_ip=" + METAD_ALIAS,
                "--ws_ip=" + METAD_ALIAS,
                "--port=9559",
                "--ws_http_port=19559",
                "--data_path=/data/meta",
                "--log_dir=/logs",
                "--heartbeat_interval_secs=1",
                "--v=0",
                "--minloglevel=0"
            )
            .waitingFor(Wait.forListeningPort());
    }

    private static GenericContainer<?> storaged(Network network) {
        return new GenericContainer<>(image("LIGHTRAG_NEBULA_STORAGED_IMAGE", "vesoft/nebula-storaged:v3.8.0"))
            .withNetwork(network)
            .withNetworkAliases(STORAGED_ALIAS)
            .withExposedPorts(9779)
            .withCommand(
                "--meta_server_addrs=" + METAD_ALIAS + ":9559",
                "--local_ip=" + STORAGED_ALIAS,
                "--ws_ip=" + STORAGED_ALIAS,
                "--port=9779",
                "--ws_http_port=19779",
                "--data_path=/data/storage",
                "--log_dir=/logs",
                "--heartbeat_interval_secs=1",
                "--v=0",
                "--minloglevel=0"
            )
            .waitingFor(new NoStartupWait());
    }

    /**
     * storaged binds its storage port (9779) only after the meta service acknowledges it through
     * {@code ADD HOSTS}, and that cannot happen before graphd has started, so a listening-port
     * wait here could never pass. The cluster's real readiness gate is the ONLINE check in
     * {@link Cluster#start()}.
     */
    private static final class NoStartupWait implements WaitStrategy {
        @Override
        public void waitUntilReady(WaitStrategyTarget waitStrategyTarget) {
        }

        @Override
        public WaitStrategy withStartupTimeout(Duration startupTimeout) {
            return this;
        }
    }

    private static GenericContainer<?> graphd(Network network) {
        return new GenericContainer<>(image("LIGHTRAG_NEBULA_GRAPHD_IMAGE", "vesoft/nebula-graphd:v3.8.0"))
            .withNetwork(network)
            .withNetworkAliases(GRAPHD_ALIAS)
            .withExposedPorts(GRAPHD_PORT)
            .withCommand(
                "--meta_server_addrs=" + METAD_ALIAS + ":9559",
                "--local_ip=" + GRAPHD_ALIAS,
                "--ws_ip=" + GRAPHD_ALIAS,
                "--port=" + GRAPHD_PORT,
                "--ws_http_port=19669",
                "--log_dir=/logs",
                "--heartbeat_interval_secs=1",
                "--v=0",
                "--minloglevel=0"
            )
            .waitingFor(Wait.forListeningPort());
    }

    private static DockerImageName image(String variable, String fallback) {
        return DockerImageName.parse(System.getenv().getOrDefault(variable, fallback));
    }

    /** A running three-service NebulaGraph cluster. */
    public static final class Cluster implements AutoCloseable {
        private final Network network;
        private final GenericContainer<?> metad;
        private final GenericContainer<?> storaged;
        private final GenericContainer<?> graphd;

        private Cluster(Network network, GenericContainer<?> metad, GenericContainer<?> storaged, GenericContainer<?> graphd) {
            this.network = network;
            this.metad = metad;
            this.storaged = storaged;
            this.graphd = graphd;
        }

        private void start() {
            metad.start();
            storaged.start();
            graphd.start();
            var pool = new NebulaPool();
            try {
                if (!pool.init(List.of(new HostAddress(host(), port())), new NebulaPoolConfig())) {
                    throw new IllegalStateException("NebulaGraph graphd is unreachable after startup");
                }
                var session = pool.getSession(USERNAME, PASSWORD, true);
                try {
                    registerStorageHost(session);
                    awaitStorageOnline(session);
                } finally {
                    session.release();
                }
            } catch (UnknownHostException | InvalidConfigException exception) {
                throw new IllegalStateException("NebulaGraph test cluster failed to initialise the connection pool", exception);
            } catch (NotValidConnectionException | IOErrorException | AuthFailedException
                     | ClientServerIncompatibleException exception) {
                throw new IllegalStateException("NebulaGraph test cluster failed to open a session", exception);
            } finally {
                pool.close();
            }
        }

        private void stop() {
            graphd.stop();
            storaged.stop();
            metad.stop();
            network.close();
        }

        public String host() {
            return graphd.getHost();
        }

        public int port() {
            return graphd.getMappedPort(GRAPHD_PORT);
        }

        @Override
        public void close() {
            stop();
        }
    }

    /** {@code ADD HOSTS} reports {@code Existed!} when the storage host is already registered. */
    private static void registerStorageHost(Session session) throws IOErrorException {
        var deadline = System.currentTimeMillis() + READY_TIMEOUT_MILLIS;
        while (true) {
            var result = session.execute("ADD HOSTS \"" + STORAGED_ALIAS + "\":9779;");
            if (result.isSucceeded() || result.getErrorMessage().contains("Existed")) {
                return;
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new IllegalStateException("ADD HOSTS did not succeed within " + READY_TIMEOUT_MILLIS + " ms: "
                    + errorText(result));
            }
            sleep();
        }
    }

    /** A freshly created space only accepts partitions once the storage host reports ONLINE. */
    private static void awaitStorageOnline(Session session) throws IOErrorException {
        var deadline = System.currentTimeMillis() + READY_TIMEOUT_MILLIS;
        while (true) {
            if (hasOnlineStorageHost(session)) {
                return;
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new IllegalStateException(
                    "NebulaGraph storage host did not come ONLINE within " + READY_TIMEOUT_MILLIS + " ms"
                );
            }
            sleep();
        }
    }

    private static boolean hasOnlineStorageHost(Session session) throws IOErrorException {
        var hosts = session.execute("SHOW HOSTS;");
        if (!hosts.isSucceeded()) {
            return false;
        }
        for (var index = 0; index < hosts.rowsSize(); index++) {
            if ("ONLINE".equals(text(hosts.rowValues(index).get("Status")))) {
                return true;
            }
        }
        return false;
    }

    private static String errorText(ResultSet resultSet) {
        return resultSet == null
            ? "no result"
            : "[%d] %s".formatted(resultSet.getErrorCode(), resultSet.getErrorMessage());
    }

    private static String text(ValueWrapper value) {
        try {
            return value == null ? "" : value.asString();
        } catch (InvalidValueException | UnsupportedEncodingException exception) {
            throw new IllegalStateException("NebulaGraph SHOW HOSTS returned a non-string value", exception);
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(READY_POLL_MILLIS);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the NebulaGraph test cluster", interruptedException);
        }
    }
}
