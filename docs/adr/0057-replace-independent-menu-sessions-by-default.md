# Replace independent menu sessions by default

Opening an independent menu for a player will close the active session with `REPLACED`, matching Minecraft's one-open-inventory model. Callers may explicitly choose `REJECT` when the current interaction must be protected. Independent sessions will never form a hidden back stack; intentional history belongs to the typed navigator inside one session.
