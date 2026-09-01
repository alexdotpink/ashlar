# Menu authoring for agents

Use this page when a plug-in task adds or changes a menu. The implemented reference is split by branch:

- [Menus](../reference/menus.md) for sessions, render, state, actions, effects, navigation, locals, errors, and standard components.
- [Menu hosts](../reference/menu-hosts.md) when selecting a native inventory shape or role-specific slot model.
- [Storage and transactions](../reference/menu-storage.md) when items may move.
- [Menu testing](../reference/menu-testing.md) for deterministic and native evidence.
- [Items](../reference/items.md) before authoring `ItemSpec`, custom items, or exact snapshots.

## Build path

1. Decide whether the call returns only a close result or a typed selection. Use `open` or `choose<T>` accordingly. Handle `MenuChoice.NotOpened` when conflict policy may reject the new session.
2. Choose the typed host that matches the client protocol. Use host role enums and typed host-input declarations. Do not translate a specialized control into a fake slot click.
3. Write one synchronous renderer as plain Kotlin functions. Give repeated or stateful children stable domain keys. The step is complete when every physical slot has one owner and every collection has an explicit bounded region.
4. Put local UI values in delegated `state`; use `rememberStorage` for keyed session storage; collect external Flow values with `collectAsState`. Keep services as explicit dependencies. Render must rerun without I/O, listener registration, work launch, or domain mutation.
5. Put suspending work in actions or `launchedEffect`, and closeable registrations in `effect`. Select action concurrency deliberately when the default is wrong. Removal, navigation, close, and caller cancellation must own ordinary cleanup.
6. Provide `MenuFeedbackThemeLocal` around a subtree when its action bar, sound, or target emphasis differs from the default. Feedback remains semantic inside handlers.
7. Add an error boundary where the plug-in has a useful recovery screen. Root failures should remain visible as `MenuClose.Failed`. The fallback must repair state before retrying.
8. Run `menuTest` against the production semantic runtime. Cover host type, typed host input, state identity, navigation, failure, concurrency, close, inspection, trace, and pending work for every route added by the task.
9. Run Paper and Folia fixtures for native adapter changes. Drive a connected client for packet-visible, specialized-control, cursor, close, recovery, or inventory-save claims.

## Storage branch

Take this branch when any item can enter, leave, move within, or shift-transfer through ashlar-owned storage.

1. Use `rememberStorage` for session-owned storage. Keep application-owned `MenuStorage` stable outside render. Give either form a stable ID, immutable revisions, and a rule for every slot.
2. Bind it to an equal-size region. Declare every player inventory participant and every ordered shift route.
3. For persistence, implement one idempotent `MenuTransactionDomain` that owns every storage in an atomic proposal. Return authoritative advanced revisions only after persistence commits. Register it with `PlayerMenus.registerTransactionDomain` during plug-in startup.
4. Prove conservation with detached snapshots and `MenuTransactionEngine`. Include rejection, stale revision, resource conflict, cursor, drop, drag, double collect, and maximum-stack cases that apply.
5. Exercise `commit` and `resolve` with stable transaction IDs. The framework owns journal submission, restart resolution, player receipts, mailbox replay, and join or mount delivery after the domain is registered. Durable world-drop emissions are rejected.

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
- Inspection exposes immutable storage snapshots and redacted trace values, never live storage capabilities.
- Observers cannot mutate sessions. Interceptors run synchronously and reject before action or storage dispatch.

## Current implementation boundary

All documented hosts have Paper/Folia adapters. The deterministic harness covers host-generic rendering, typed host input, traces, storage transactions, durable runtime ownership, and randomized conservation. Paper and Folia fixtures cover adapter startup and native mapping.

Do not turn that evidence into a stronger claim. Connected-client acceptance is still required for full cursor conservation, close and remount ordering, focused input, specialized client controls, crash recovery, inventory saving, and automatic mailbox delivery.
