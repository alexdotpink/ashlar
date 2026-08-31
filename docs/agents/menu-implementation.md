# Menu engine implementation workflow

Status: implementation guidance for shipped item and chest-menu modules

Use this page when changing framework item or menu internals. Plug-in authors should use [menu authoring](menu-authoring.md) and the implemented reference pages instead.

## Read first

1. [Items reference](../reference/items.md)
2. [Menus reference](../reference/menus.md)
3. [Storage and transactions reference](../reference/menu-storage.md)
4. [Menu testing reference](../reference/menu-testing.md)
5. [Items module design](../design/items-module.md)
6. [Menus module design](../design/menus-module.md)
7. [Architecture](../explanation/architecture.md)
8. [Coroutines and ownership](../explanation/coroutines-and-ownership.md)
9. The relevant decisions from [ADR index](../adr/README.md)
10. [Verification matrix](verification.md)

Inspect current source, API dumps, samples, and test fixtures before changing a public signature. Source, ABI dumps, and reference pages are authoritative for shipped declarations. Design pages and ADRs constrain unfinished hosts and later slices.

## Choose one vertical slice

Take the next incomplete slice from the design document. A slice includes its production API, pure model, focused tests, native adapter evidence where applicable, KDoc, reference prose, agent guidance, sample, and ABI update. Do not implement several host families or durability systems in parallel merely because their types can be scaffolded.

The item, action-only chest, storage planner, transaction coordinator, semantic test runtime, and native chest adapter are shipped. A new concrete host starts only after the shared gesture and transaction engines pass their existing contract suites.

## Invariants that may not be traded away

- Render is synchronous, declarative, and side-effect free.
- Component state identity never depends on call order.
- A physical slot has one owner; collisions fail before reconciliation.
- Plug-in code receives immutable gestures, not mutable Paper events.
- Native mutation is cancelled for framework-owned storage.
- Storage movement conserves exact item snapshots across every outcome.
- External commits are pessimistic; rejection preserves the before-state.
- Cross-persistent-storage proposals have one atomic owner.
- Submitted durable commits have stable IDs and resolvable restart outcomes.
- A true session end cannot silently discard the committed cursor.
- Paper/Folia ownership uses existing kernel contexts.
- Services remain explicit dependencies; menu locals carry presentation only.
- Standard components use no privileged internal API.
- Public menu declarations use no KSP, annotations, reflection, or compiler plug-in.

If an implementation appears to require weakening one invariant, stop and update the design decision explicitly before changing code.

## Keep public APIs small

Prefer one deep runtime interface over one public type per internal phase. Internal render nodes, reconciliation commands, Paper event adapters, locks, journals, and trace buffers stay private unless plug-in authors must name them to express domain behavior.

Use sealed outcomes for expected close, rejection, retirement, decode, and recovery states. Throw for violated framework contracts and unexpected failures. Do not expose a mutable session, inventory, action, transaction, or effect handle when one atomic capability operation is enough.

## Item changes

For an item-model change:

1. Prove immutable model behavior without Paper where possible.
2. Add capture/materialization round-trip coverage on the pinned Paper line.
3. Verify unknown or unrelated data survives edits.
4. Add persistence compatibility evidence for snapshot-envelope changes.
5. Test every structured custom-item decode outcome.
6. Test signature tampering and rotation when integrity changes.

Do not normalize a captured stack through an authored `ItemSpec`. Do not add gameplay callbacks to custom-item definitions.

## Menu engine changes

For state, rendering, navigation, effects, or components:

1. Drive the production semantic engine through `framework-menus-test`.
2. Assert stable keys, one render per synchronous mutation batch, and deterministic cleanup.
3. Capture a semantic snapshot when tree shape changes.
4. Verify a failure leaves the last committed tree intact or closes through the documented boundary.
5. Run a native fixture when the change affects host reconciliation or close behavior.

## Transaction changes

Every gesture algorithm needs conservation evidence over exact `ItemSnapshot` values. Cover empty, partial, full, filtered, max-stack, stale-revision, conflicting-viewer, pending, rejected, close, disconnect, and restart cases. Prefer model/property tests over dozens of hand-coded examples, then retain named regressions for every discovered bug.

A test passing against mocked Bukkit events is not native evidence. Run real Paper and Folia fixtures for adapter changes and drive a real client when packet-visible gesture, cursor, creative, title, remount, or close behavior changes.

## Host changes

Document the host's typed properties, slot roles, client inputs, native events, close behavior, and unsupported states before coding the adapter. Implement its server-free semantic model first. The Paper adapter may translate native behavior; it may not mutate natively and repair afterward or fall back to raw generic inventory callbacks.

Finish one host with tests, docs, sample coverage, and inspection output before starting another.

## Documentation completion

An implemented slice is incomplete until:

- every public declaration has KDoc;
- the reference page describes actual source behavior;
- one how-to or standard component demonstrates normal use;
- the agent index routes the task correctly;
- sample code compiles against the public API;
- API dumps are committed;
- verification evidence is recorded in the commit or task handoff.

Keep the implemented references and ABI dumps synchronized with every public change.
