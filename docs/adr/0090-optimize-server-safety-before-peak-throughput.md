# Optimize server safety before peak throughput

When performance goals conflict, framework work will protect native callback occupancy and p99 latency first, allocation and retained memory second, and peak throughput third. Lifecycle-owned caches are acceptable only when they materially improve tail behavior and remain inside explicit per-plug-in or per-player memory budgets. Relevant contracts measure cold startup or first use separately from warmed steady state, so moving work across an initialization boundary cannot disguise its cost.
