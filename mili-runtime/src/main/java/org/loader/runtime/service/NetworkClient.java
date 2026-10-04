package org.loader.runtime.service;

import org.loader.runtime.kernel.Scope;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages client-side network connections.
 * <p>
 * When the scope stops, the connection is closed.
 */
public class NetworkClient extends NetworkResource {

    private final InetSocketAddress remoteAddress;
    private final AtomicBoolean connected = new AtomicBoolean(true);

    public NetworkClient(String id, Scope owner, InetSocketAddress remoteAddress) {
        super(id, owner);
        this.remoteAddress = remoteAddress;
    }

    public InetSocketAddress remoteAddress() {
        return remoteAddress;
    }

    public boolean isConnected() {
        return connected.get() && !isClosed();
    }

    @Override
    protected void doClose() {
        connected.set(false);
    }
}
