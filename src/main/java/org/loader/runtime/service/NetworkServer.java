package org.loader.runtime.service;

import org.loader.runtime.kernel.Scope;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages server-side network listeners.
 * <p>
 * When the scope stops, all server connections are closed.
 */
public class NetworkServer extends NetworkResource {

    private final InetSocketAddress bindAddress;
    private final ConcurrentHashMap<String, ClientConnection> connections = new ConcurrentHashMap<>();
    private final AtomicInteger connectionCounter = new AtomicInteger(0);

    public NetworkServer(String id, Scope owner, InetSocketAddress bindAddress) {
        super(id, owner);
        this.bindAddress = bindAddress;
    }

    public InetSocketAddress bindAddress() {
        return bindAddress;
    }

    /**
     * Accepts a new client connection.
     */
    public ClientConnection acceptConnection(String remoteAddress) {
        String connId = id() + "-conn-" + connectionCounter.incrementAndGet();
        ClientConnection conn = new ClientConnection(connId, connections::remove);
        connections.put(connId, conn);
        return conn;
    }

    /**
     * Returns the number of active connections.
     */
    public int activeConnections() {
        return connections.size();
    }

    @Override
    protected void doClose() {
        // Close all connections
        connections.values().forEach(ClientConnection::close);
        connections.clear();
    }

    /**
     * Represents a connected client.
     */
    public static class ClientConnection {
        private final String id;
        private final java.util.function.Consumer<String> onClose;
        private volatile boolean closed = false;

        ClientConnection(String id, java.util.function.Consumer<String> onClose) {
            this.id = id;
            this.onClose = onClose;
        }

        public String id() {
            return id;
        }

        public boolean isClosed() {
            return closed;
        }

        public void close() {
            if (!closed) {
                closed = true;
                onClose.accept(id);
            }
        }
    }
}
