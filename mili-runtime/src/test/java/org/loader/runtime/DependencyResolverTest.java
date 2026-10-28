package org.loader.runtime;

import org.junit.jupiter.api.*;
import org.loader.runtime.kernel.DependencyResolver;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DependencyResolverTest {

    @Test
    void initializationOrder_respectsDependencies() {
        DependencyResolver resolver = new DependencyResolver();
        resolver.registerNode("database");
        resolver.registerNode("cache");
        resolver.registerNode("api");

        resolver.addDependency("api", "cache");
        resolver.addDependency("cache", "database");

        List<String> order = resolver.resolveInitializationOrder();
        assertTrue(order.indexOf("database") < order.indexOf("cache"));
        assertTrue(order.indexOf("cache") < order.indexOf("api"));
    }

    @Test
    void shutdownOrder_isReverseOfInitialization() {
        DependencyResolver resolver = new DependencyResolver();
        resolver.registerNode("foundation");
        resolver.registerNode("middle");
        resolver.registerNode("top");

        resolver.addDependency("top", "middle");
        resolver.addDependency("middle", "foundation");

        List<String> initOrder = resolver.resolveInitializationOrder();
        List<String> shutdownOrder = resolver.resolveShutdownOrder();

        assertEquals(initOrder.reversed(), shutdownOrder);
    }

    @Test
    void circularDependency_preventsAtAddTime() {
        DependencyResolver resolver = new DependencyResolver();
        resolver.registerNode("a");
        resolver.registerNode("b");
        resolver.registerNode("c");

        resolver.addDependency("a", "b");
        resolver.addDependency("b", "c");
        // This should throw because adding c -> a creates a cycle
        assertThrows(IllegalStateException.class, () -> resolver.addDependency("c", "a"));
    }

    @Test
    void unknownNodes_throw() {
        DependencyResolver resolver = new DependencyResolver();
        resolver.registerNode("known");

        assertThrows(IllegalArgumentException.class,
                () -> resolver.addDependency("known", "unknown"));
    }

    @Test
    void cycleRejectionNamesBothSides() {
        DependencyResolver resolver = new DependencyResolver();
        resolver.registerNode("a");
        resolver.registerNode("b");
        resolver.addDependency("a", "b");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> resolver.addDependency("b", "a"));
        assertTrue(e.getMessage().contains("b") && e.getMessage().contains("a"));
    }

    @Test
    void multipleRootsStillTopologicallyOrdered() {
        DependencyResolver resolver = new DependencyResolver();
        resolver.registerNode("base");
        resolver.registerNode("x");
        resolver.registerNode("y");
        resolver.registerNode("app");

        resolver.addDependency("x", "base");
        resolver.addDependency("y", "base");
        resolver.addDependency("app", "x");

        List<String> order = resolver.resolveInitializationOrder();
        assertEquals(4, order.size());
        assertTrue(order.indexOf("base") < order.indexOf("x"));
        assertTrue(order.indexOf("x") < order.indexOf("app"));
        // 确定性：同一图重复求解结果一致
        assertEquals(order, resolver.resolveInitializationOrder());
    }
}
