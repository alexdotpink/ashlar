# Compose static command graphs from isolated fragments

Large command roots may use independently generated command fragments, each with its own typed routes class; runtime Kotlin merges their immutable plans atomically under one owning command set. Required root and alias conflicts fail component startup, while explicitly optional aliases may be omitted. Routes cannot be added or removed while running. Targeted client command refresh is available independently; the public observable requirement contract is reserved and is not attached to generated branches yet.
