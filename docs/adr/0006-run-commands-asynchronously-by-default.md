---
status: superseded by ADR-0020
---

# Run commands asynchronously by default

Normal command invocations will run as lifecycle-owned plug-in tasks after Brigadier accepts them, even when the handler itself is not a suspending function. A command must opt into synchronous execution to return its true Brigadier result, and the compiler will reject synchronous routes that depend on suspending codecs, scopes, policies, or handlers. This gives database-backed arguments and policies one uniform execution model without pretending an execution context can survive suspension.
