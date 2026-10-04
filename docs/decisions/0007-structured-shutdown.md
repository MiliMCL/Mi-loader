# ADR 0007: Structured Shutdown

## Status
Accepted

## Decision

Shutdown propagates through ownership and structured concurrency trees.

The implementation must not rely primarily on JVM exit hooks.
