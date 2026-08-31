# Access Paper safely from coroutines

Framework tasks and command handlers do not own a Paper server region. Enter the relevant ownership block around each Paper access.

## Global state

```kotlin
val world = plugin.withGlobal {
    plugin.server.getWorld(worldKey)
}
```

Use `withGlobal` for server registries, loaded-world lookup, and operations Paper assigns to the global region.

## Region state

```kotlin
val type = plugin.withRegion(world, blockX shr 4, blockZ shr 4) {
    world.getBlockAt(blockX, blockY, blockZ).type
}
```

The block cannot suspend. Copy the value you need before leaving it.

## Entity state

```kotlin
when (val outcome = plugin.withEntity(player) { player.health }) {
    is EntityOutcome.Completed -> logger.info("Health: ${outcome.value}")
    EntityOutcome.Retired -> logger.info("Player retired before access")
}
```

Entity retirement is an expected race. Handle it when the caller needs a result.

Command arguments such as `PlayerRef`, `EntityRef`, and `BlockRef` provide `access` functions that enter the same ownership APIs from stable identities.

## Require ownership in an API

```kotlin
context(entityContext: EntityContext)
fun Player.sendHomesMessage(message: Component) {
    entityContext.checkOwnership()
    sendMessage(message)
}
```

Call `checkOwnership()` immediately before Paper access. A captured context value does not preserve ownership after its callback returns.

The change is complete when direct Paper access is lexically inside the matching ownership block and no ownership block suspends.
