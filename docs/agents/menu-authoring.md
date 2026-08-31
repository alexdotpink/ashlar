# Menu authoring for agents

Use this page when a plug-in task adds or changes a menu. The implemented reference is split by branch:

- [Menus](../reference/menus.md) for sessions, render, state, actions, effects, navigation, locals, errors, and standard components.
- [Menu hosts](../reference/menu-hosts.md) when selecting a native inventory shape or role-specific slot model.
- [Storage and transactions](../reference/menu-storage.md) when items may move.
- [Menu testing](../reference/menu-testing.md) for deterministic and native evidence.
- [Items](../reference/items.md) before authoring `ItemSpec`, custom items, or exact snapshots.

## Build path

1. Decide whether the call returns only a close result or a typed selection. Use `open` or `choose<T>` accordingly. The step is complete when the caller handles every sealed outcome.
2. Write one synchronous renderer as plain Kotlin functions. Give repeated or stateful children stable domain keys. The step is complete when every physical slot has one owner and every collection has an explicit bounded region.
3. Put local UI values in delegated `state`; collect external Flow values with `collectAsState`. Keep services as explicit dependencies. The step is complete when render can rerun without performing I/O, registering listeners, launching work, or mutating domain state.
4. Put suspending work in actions or `launchedEffect`, and closeable registrations in `effect`. Select action concurrency deliberately when the default single-flight behavior is wrong. The step is complete when removal, navigation, close, and caller cancellation own all cleanup.
5. Add an error boundary where the plug-in has a useful recovery screen. Root failures should remain visible as `MenuClose.Failed`. The step is complete when the fallback can actually repair state before retrying.
6. Run `menuTest` against the production semantic runtime. The step is complete when state identity, navigation, failure, concurrency, close, and pending-work assertions cover every route added by the task.
7. Run Paper and Folia fixtures for native adapter changes. Drive a connected client for packet-visible or cursor-sensitive claims. The step is complete only when the verification matches the claim.

## Storage branch

Take this branch when any item can enter, leave, move within, or shift-transfer through framework-owned storage.

1. Keep `MenuStorage` stable outside render. Give it a stable domain ID, immutable revisions, and a rule for every slot.
2. Bind it to an equal-size region. Declare every player inventory participant and every ordered shift route.
3. For persistence, implement one idempotent `MenuTransactionDomain` that owns every storage in an atomic proposal. Return authoritative advanced revisions only after persistence commits.
4. Prove conservation with detached snapshots and `MenuTransactionEngine`. Include rejection, stale revision, resource conflict, cursor, drop, drag, double collect, and maximum-stack cases that apply.
5. Add journal recovery and mailbox delivery before claiming restart-safe external storage. The current module supplies these pieces but does not install application-specific domain recovery automatically.

## Review invariants

Check every changed menu against these conditions:

- Render is synchronous and repeatable.
- Component identity comes from stable keys, and state identity comes from delegated names.
- Item specs compare structurally. A keyed Paper item mutation changes its key when its output changes.
- Slot ownership never overlaps.
- Actions consume immutable interactions and select explicit concurrency.
- Effects start after commit and have deterministic cleanup.
- Storage gestures never rely on native mutation and repair.
- Accepted storage movement conserves exact snapshots across storage, player inventory, cursor, and emissions.
- Persistent commits are pessimistic, idempotent, and atomic across every touched persistent storage.
- Tests finish with no pending action or effect work.

## Current implementation boundary

Use chest hosts for live Paper/Folia plug-ins. The semantic typed catalogue supports other host declarations and deterministic tests, but their native adapters are not shipped. Treat native-client conservation, focused-input and title remount behavior, automatic restart settlement, and mailbox delivery as unproven until the task adds matching evidence and application wiring.
