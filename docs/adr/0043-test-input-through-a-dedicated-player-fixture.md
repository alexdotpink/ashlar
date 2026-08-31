# Test input through a dedicated player fixture

The input module will provide a server-free test kit that drives the same `PlayerInput` interface with simulated players, answers, passed chat, disconnects, conflicts, cancellation, captured messages, and virtual time. Plug-in tests should not construct Paper chat events or inspect input internals. Real Paper and Folia fixtures remain responsible for native chat cancellation, delivery, and ownership behavior.
