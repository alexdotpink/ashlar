# Cancel active input atomically by player

`PlayerInput` will expose `cancel(player): Boolean` beside the suspending prompt operations. It atomically cancels that player's active prompt and reports whether one existed, which supports explicit cancel commands and feature handoffs without a race-prone separate status check. The module will not expose mutable prompt handles; ordinary callers retain the direct suspending `chat` interface.
