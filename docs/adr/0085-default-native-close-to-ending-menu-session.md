# Default player native close to ending the menu session

A player-initiated native inventory close will default to ending the complete logical menu session as `MenuClose.PlayerClosed`, regardless of typed navigation depth. A screen may explicitly choose `NativeClose.BACK`; a genuine player close then pops one route and remounts the previous screen, while the root still closes. Ashlar-owned host remounts, focused-input suspension, inventories opened by other plug-ins, disconnect, death, kick, and replacement remain distinct transitions and never trigger Back implicitly.
