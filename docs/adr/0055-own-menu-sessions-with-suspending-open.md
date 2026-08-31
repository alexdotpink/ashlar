# Own menu sessions with suspending open

`PlayerMenus.open` will suspend for the lifetime of one menu session and return a typed close reason after disposal. Cancelling the caller closes the inventory and all session-owned actions, effects, and subscriptions, while `PlayerMenus.close(player)` provides atomic external closure without exposing mutable handles. A submitted durable storage commit transfers to the transaction runtime under its separate durability contract. Fire-and-forget sessions would break structured cleanup and make close outcomes difficult to test.
