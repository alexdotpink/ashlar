# Commit storage transactions pessimistically

The runtime will cancel the native inventory gesture, compute a complete menu-transaction proposal, and await a suspending commit callback before changing storage or cursor state. Accepted commits apply atomically; rejection preserves the original snapshot. This gives in-memory storage an immediate path while allowing database-backed storage to validate and persist without optimistic rollback or duplication races.
