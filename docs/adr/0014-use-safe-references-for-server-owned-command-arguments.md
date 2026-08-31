# Use safe references for server-owned command arguments

Normal asynchronous commands will receive stable framework references for players, entities, blocks, regions, and other server-owned targets. These references expose explicit non-suspending access operations that enter the kernel’s entity, region, or global execution contexts and preserve retirement as an expected outcome. Immutable values and copied data may pass directly; raw Paper-owned objects remain available only through the explicit native or synchronous interfaces.
