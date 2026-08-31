# Inject command policy state

Stateful command policies will share one injected atomic state interface with an ephemeral in-memory default. Deployments may replace it with Redis or another implementation for persistence and cross-server coordination, while injected time keeps behavior deterministic in tests. Confirmation will compare canonical route identity plus codec-encoded domain arguments, so aliases, quoting, and option order do not change semantic identity.
