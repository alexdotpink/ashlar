# Feed synchronous menu renders with Flow state

Menu renders will remain synchronous and side-effect free. Local delegated state and lifecycle-owned `collectAsState` subscriptions will trigger rerenders, with each Flow collection tied to keyed menu-component identity and cancelled when that component or session leaves. The runtime will not allow suspending loads inside render or require callers to manage subscriptions and invalidation manually.
