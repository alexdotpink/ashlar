# Run command invocations concurrently

Accepted command invocations will run as independent supervised plug-in tasks in the kernel’s ordinary coroutine context. The runtime will not serialize commands per source or start them on an I/O-specific pool; blocking libraries require an explicit injected blocking dispatcher, while single-flight and timeout behavior remain policies. Executor retirement drops undeliverable responses but does not cancel domain work unless a route opts into that policy.
