# Configure plug-in lifecycle events with native keys

An `@Events` class may provide one `@ConfigureLifecycleEvents` extension on `LifecycleEventRegistry`. Its synchronous `on` and `monitor` calls accept Paper's native typed `LifecycleEventType` objects, preserving owner and configuration constraints without a framework enum or one annotation per key. The first events module supports keys valid for the current plug-in owner; bootstrap-only tag and datapack events wait for a separate kernel and managed-build decision.
