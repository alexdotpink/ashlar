# Model concrete menu hosts

The menu module will model chest, hopper, anvil, merchant, furnace-family, crafting-family, and other inventory forms as concrete typed hosts over one shared session runtime. It will not flatten specialized rename text, trades, recipes, progress, or result extraction into a generic `InventoryType` and raw-slot builder. Hosts may ship in independently verified slices without making chest the permanent interface.
