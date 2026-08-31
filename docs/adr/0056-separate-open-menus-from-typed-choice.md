# Separate open menus from typed choice

`PlayerMenus.open` will model a long-lived menu session and return `MenuClose`, while `PlayerMenus.choose<T>` will add `finish(value)` and return either a selected value or the same typed close outcome. Sharing one runtime preserves lifecycle and navigation semantics without forcing every ordinary menu to carry a meaningless generic result.
