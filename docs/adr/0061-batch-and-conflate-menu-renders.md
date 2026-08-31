# Batch and conflate menu renders

Menu state mutations made during one synchronous callback will schedule one render after that callback completes. While reconciliation is pending, external state emissions will conflate to their newest value instead of creating a stale render queue. Reconciliation will remain serialized, so a player never observes partially applied state or overlapping inventory diffs.
