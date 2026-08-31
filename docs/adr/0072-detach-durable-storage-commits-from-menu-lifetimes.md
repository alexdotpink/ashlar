# Detach durable storage commits from menu lifetimes

Before invoking external persistence, the runtime will journal a durable menu transaction under a stable ID. Its commit operation must be idempotent and its transaction domain must be able to resolve an ambiguous outcome after restart. Once submitted, that work survives menu close, disconnect, and caller cancellation; accepted player-bound deltas settle through live inventory or durable recovery. In-memory commits remain lightweight, while ordinary actions and effects still cancel with their owning session.
