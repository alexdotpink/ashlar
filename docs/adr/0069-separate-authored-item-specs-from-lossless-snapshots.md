# Separate authored item specs from lossless snapshots

An `ItemSpec` will be the readable immutable recipe used to author and parameterize items, while an `ItemSnapshot` will losslessly capture arbitrary live stacks for transactions, persistence, equality, and diagnostics. Snapshot capture and materialization must round-trip all current and unknown item data without normalization. Mutable Paper `ItemStack` values remain adapter-boundary objects rather than framework state.
