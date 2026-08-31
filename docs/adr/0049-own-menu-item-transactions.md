# Own menu item transactions

The menu module will translate pickup, placement, swap, drag, shift transfer, hotbar and offhand swaps, double-click collection, drops, stack limits, player-inventory movement, close recovery, and creative actions into validated atomic menu transactions. Action slots remain virtual while declared storage slots participate in the same engine. The runtime will cancel native mutation and commit its own transaction rather than infer authoritative state after Bukkit has already changed the inventory.
