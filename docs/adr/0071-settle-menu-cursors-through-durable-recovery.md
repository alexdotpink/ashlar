# Settle menu cursors through durable recovery

Internal host remounts and typed navigation retain the committed logical cursor. When a menu session truly ends, the runtime will merge that cursor into the player's inventory and durably mailbox any overflow before clearing it; world drops remain an explicit policy rather than a safety fallback. A requested return-to-origin may succeed only against a compatible current storage revision and otherwise falls back to recovery. Pending proposals require no settlement because their original state remains authoritative.
