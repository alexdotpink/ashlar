# Deliver typed menu gestures directly

Menu interactions will arrive as immutable snapshots with sealed gesture types, stable slot identity, and the committed render revision. Convenience handlers will cover common gestures while a complete handler retains access to every supported gesture. Interactions target their action slot or storage transaction directly; explicit observers and interceptors replace implicit capture or bubbling, and mutable Paper events remain internal adapters.
