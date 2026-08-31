# Framework

This glossary distinguishes the framework itself from the Minecraft plug-ins built with it.

## Language

**Plug-in author**:
A Kotlin developer who uses the framework to build a Minecraft server plug-in.
_Avoid_: Framework user, consumer

**Framework contributor**:
A developer who changes the framework itself.
_Avoid_: Plug-in author

**Framework plug-in**:
A Minecraft server plug-in built by a plug-in author using one or more framework modules.
_Avoid_: Module, framework

**Kernel**:
The small framework module required by every framework plug-in. Optional capabilities belong outside it.
_Avoid_: Foundation, platform, base

**Framework module**:
An independently consumable artifact that provides one framework capability. A framework module does not necessarily have runtime state.
_Avoid_: Plug-in, plugin component

**Framework contribution**:
An annotated class listed in generated module metadata for automatic discovery inside one framework plug-in classloader.
_Avoid_: Registration, extension

**Plugin component**:
A stateful part of a framework plug-in that owns synchronous start and stop behavior. A component may contain child components; the kernel manages the resulting lifecycle tree, and each component object provides the capability used by its parent or plug-in code.
_Avoid_: Runtime component, framework module, service

**Plug-in task**:
A named coroutine owned by one framework plug-in and cancelled when that plug-in disables. A task may be ordinary or critical.
_Avoid_: Server task, thread

**Owned resource**:
A closeable resource registered to one framework plug-in and closed during its disable sequence.
_Avoid_: Plugin component, service

**Dependency graph**:
The typed, plug-in-owned set of dependency bindings available to generated constructors and lifecycle-aware injection.
_Avoid_: Container, service locator

**Plugin-scoped dependency**:
A dependency instance shared for the lifetime of one framework plug-in.
_Avoid_: Singleton, global

**Invocation-scoped dependency**:
A dependency instance shared within one command invocation and unavailable outside it.
_Avoid_: Request-scoped dependency, command context

**Factory dependency**:
A dependency binding that creates a new instance at each injection point.
_Avoid_: Transient dependency, prototype

**Execution context**:
Typed proof that code may access a particular server ownership domain. It is not a thread or a scheduling mechanism.
_Avoid_: Thread, scheduler, dispatcher

**Entity retirement**:
The expected case where an entity becomes unavailable before scheduled entity work can run. Retirement is distinct from task failure and coroutine cancellation.
_Avoid_: Failure, exception

## Events

**Event module**:
The framework module that supports server, lifecycle, and application events while preserving each family's native dispatch contract.
_Avoid_: Universal event bus, event system

**Event set**:
A final class marked `@Events`, or a concrete descendant of an abstract marked event-set base, whose DI-constructed instance groups static handlers and lifecycle configuration.
_Avoid_: Listener class, event container

**Server event**:
A Bukkit or Paper `Event` instance dispatched through a `HandlerList`. It is live server state for the duration of its synchronous callback.
_Avoid_: Bukkit event, platform event

**Server event handler**:
A synchronous annotated function that may inspect or mutate a live server event at a declared Bukkit priority.
_Avoid_: Listener method, synchronous observer

**Server event observer**:
A suspending annotated function whose prefix runs inside a `MONITOR` server event callback and whose continuation becomes plug-in-owned coroutine work. The raw event is valid only before the first suspension and grants no ownership afterward.
_Avoid_: Async handler, suspend listener

**Event registration**:
The lifecycle-owned binding between one server event type and a synchronous handler. Closing it prevents future callbacks.
_Avoid_: Listener, subscription

**Event failure**:
An exception thrown by a synchronous server event handler and reported with its event-set, handler, and event-type identity. It does not roll back prior mutation or stop later server listeners.
_Avoid_: Task failure, event rejection

**Lifecycle event**:
A notification from Paper's Lifecycle API, registered through its owner-specific manager and governed by that API's priority or monitor rules.
_Avoid_: Server event, plugin lifecycle hook

**Application event**:
A plug-in-local immutable value published by framework plug-in code rather than Paper or Bukkit. It implements `ApplicationEvent` and carries notification data, not a request for a return value.
_Avoid_: Custom Bukkit event, domain message

**Application event publication**:
One structured, suspending delivery of an application event to every matching handler. Handlers are unordered and may run concurrently.
_Avoid_: Event emission, broadcast

**Event query**:
A suspending wait that synchronously selects a framework-owned value from matching event callbacks and completes when the query accepts one value.
_Avoid_: One-off listener, event awaiter

**Event capture**:
An event query for cancellable server events that synchronously cancels each selected event while accepting or retrying.
_Avoid_: Chat capture, consuming listener

**Event stream**:
A Flow of framework-owned values selected synchronously from event callbacks, with an explicit bounded capacity and overflow policy.
_Avoid_: Raw event Flow, event queue

## Input

**Input module**:
The framework module that prompts one player for typed input while owning retries, conflicts, disconnects, deadlines, and cleanup.
_Avoid_: Conversation engine, input system

**Input prompt**:
One suspending player interaction that accepts a typed answer. Plug-in authors compose multiple prompts with ordinary Kotlin control flow.
_Avoid_: Conversation step, input request, question

**Input cancellation**:
The expected end of an unanswered input prompt, identified as user choice, external cancellation, deadline expiry, player disconnect, replacement, or plug-in shutdown.
_Avoid_: Input failure, empty answer

**Active input prompt**:
The single input prompt currently waiting for one player's answer inside a framework plug-in.
_Avoid_: Input session, conversation

**Chat answer**:
The value examined by a chat input prompt, containing convenient plain text and the original Adventure component.
_Avoid_: Chat event, chat message

**Input decision**:
The parser's decision for one attempted answer: accept it, retry with feedback, cancel the prompt, or pass the chat through unchanged.
_Avoid_: Parse result, validation result

## Menus

**Item module**:
The reusable framework module that describes and materializes Minecraft item stacks, including modern data components and presentation policy.
_Avoid_: Item utility, menu item builder

**Item specification**:
An immutable complete description from which the framework can materialize an equivalent item stack.
_Avoid_: Item builder, item template

**Item snapshot**:
An immutable lossless capture of one live item stack, including data the framework does not yet interpret directly.
_Avoid_: Item clone, serialized item

**Item presentation**:
The declared tooltip and visual policy applied when materializing an item specification for a particular use.
_Avoid_: Item flags, menu defaults

**Custom item definition**:
A namespaced, versioned definition that creates and recognizes one kind of plug-in item with a typed persistent payload.
_Avoid_: Custom item class, item handler

**Item integrity policy**:
An optional custom-item rule that authenticates canonical identity and payload data and supports signing-key rotation.
_Avoid_: Item encryption, anti-dupe check

**Menu module**:
The framework module that presents declarative inventory interactions while owning rendering, reconciliation, input safety, navigation, and lifecycle.
_Avoid_: GUI framework, inventory utility

**Menu session**:
One player's active interaction with a rendered menu, including its local state and navigation history.
_Avoid_: Open inventory, menu instance

**Menu render**:
The declarative inventory description produced from the current menu-session state.
_Avoid_: Inventory builder, menu contents

**Menu reconciliation**:
The transition from one menu render to the next by applying only the inventory changes required to match the new description.
_Avoid_: Refresh, redraw, rebuild

**Menu component**:
A reusable part of a menu render with stable identity and optional menu-session-local state.
_Avoid_: Widget, menu class

**Standard menu component**:
A framework-supplied menu component implemented entirely through the same public composition API available to plug-ins.
_Avoid_: Built-in widget, native component

**Menu state**:
A value retained for one menu component inside one menu session and observed by later renders.
_Avoid_: Menu data, inventory state

**Menu local**:
A typed value scoped through a rendered component subtree for cross-cutting presentation context such as theme or messages.
_Avoid_: Service locator, thread-local

**Menu feedback**:
A typed player-facing success, warning, or rejection value whose active menu presentation chooses visual and audible delivery.
_Avoid_: Chat response, click message

**Menu region**:
An explicitly bounded ordered set of inventory slots used to place repeated menu content.
_Avoid_: Container, layout box

**Action slot**:
A virtual menu slot whose displayed item triggers typed actions and never participates in item storage.
_Avoid_: Button slot, locked slot

**Slot modifier**:
An explicit composable transformation of one slot declaration's presentation or behavior that does not claim separate physical ownership.
_Avoid_: Slot override, layer

**Storage slot**:
A menu or player-inventory slot that participates in framework-controlled item movement and storage rules.
_Avoid_: Editable slot, vanilla slot

**Menu storage**:
A stable, versioned item-storage model that may be attached to one or more menu sessions while retaining shared transaction identity.
_Avoid_: Inventory contents, shared menu

**Transfer route**:
An ordered declaration of the storage models considered as destinations for an automatic item transfer.
_Avoid_: Shift-click handler, destination callback

**Menu transaction**:
One atomic proposed item movement across storage slots and the player's cursor, including complete before and after values.
_Avoid_: Inventory event, click action

**Durable menu transaction**:
A journaled menu transaction with stable identity whose externally committed outcome can be resolved independently of the menu session that proposed it.
_Avoid_: Background click, detached job

**Transaction domain**:
The single commit owner for a menu transaction spanning more than one persistent storage model.
_Avoid_: Transaction manager, rollback group

**Pending menu transaction**:
A menu transaction awaiting application approval while its original storage and cursor remain authoritative.
_Avoid_: Loading click, queued action

**Item recovery mailbox**:
Durable per-player storage for committed items that could not be returned safely to player inventory when a menu session ended.
_Avoid_: Overflow chest, dropped items

**Menu effect**:
Keyed resource or suspending work owned by a menu component and cleaned up when its key, component, or session leaves.
_Avoid_: Render callback, menu task

**Menu error boundary**:
A keyed component that captures unexpected descendant render, action, and effect failures and renders a retryable fallback.
_Avoid_: Error handler, exception listener

**Menu action**:
A typed suspending reaction to one immutable player gesture, owned by its menu component and concurrency policy.
_Avoid_: Click handler, event callback

**Menu gesture**:
An immutable typed description of one player's inventory input against a particular committed render revision.
_Avoid_: Inventory event, click type

**Menu screen**:
One typed route and declarative render in a menu session's navigation history.
_Avoid_: Page, submenu, child menu

**Menu host**:
A concrete Minecraft inventory form whose render exposes that form's typed slots, properties, and interactions.
_Avoid_: Inventory type, menu layout

**Host remount**:
The internal replacement of a menu session's native inventory when its declarative host can no longer be reconciled in place.
_Avoid_: Reopen, inventory switch

**Suspended menu presentation**:
A menu session state in which its native host is temporarily hidden while another focused framework interaction owns player input.
_Avoid_: Closed menu, paused session

**Menu close**:
The typed ending of a menu session after player close, replacement, disconnect, caller cancellation, or plug-in shutdown.
_Avoid_: Inventory close event, menu result

**Menu test harness**:
A deterministic server-free driver for the production renderer, reconciler, navigation, action, and transaction engines through a fake native host adapter.
_Avoid_: Mock inventory, menu unit test

**Menu inspection**:
A redacted semantic snapshot and bounded typed trace of a live menu session's components, state, renders, gestures, actions, and transactions.
_Avoid_: Debug log, inventory dump

## Performance

**Performance contract**:
The versioned scenarios, measurements, regression budgets, and maturity status that protect one public framework capability.
_Avoid_: Performance test, benchmark score

**Benchmark scenario**:
A named framework or plug-in operation with fixed setup, a measured interaction, and correctness checks.
_Avoid_: Benchmark method, timing test

**Benchmark profile**:
A named, versioned workload scale that gives one benchmark scenario explicit input size, concurrency, and duration.
_Avoid_: Real-world workload, scale factor

**Matched control**:
The direct Kotlin, Paper, Folia, or toolchain path used to isolate framework overhead from the complete scenario cost.
_Avoid_: Baseline implementation, raw benchmark

**Performance budget**:
A source-controlled relative regression allowance or absolute release ceiling for one metric in one benchmark profile.
_Avoid_: Performance goal, acceptable speed

**Performance regression**:
A statistically confirmed budget violation against a compatible same-worker `main` run.
_Avoid_: Slowdown, noisy result

**Measurement environment**:
The hardware, operating system, JVM, framework toolchain, Paper or Folia build, and fixture fingerprint attached to a benchmark run.
_Avoid_: Benchmark machine, CI runner

**Framework overhead**:
The measured cost added by a framework path relative to its matched native or direct control under the same workload.
_Avoid_: Zero overhead, framework speed

**Native callback occupancy**:
The elapsed time spent inside a Paper or Folia callback before control returns to the platform.
_Avoid_: Main-thread time, callback speed

## Commands

**Command set**:
A Kotlin class that declares one root command and the routes beneath it.
_Avoid_: Command class, command container

**Command fragment**:
An independently generated class that contributes routes to one command set without owning its root metadata.
_Avoid_: Partial command set, command extension

**Command route**:
One executable path through a command set, including its literals and typed arguments.
_Avoid_: Subcommand, handler

**Command graph**:
The complete Brigadier structure compiled from command routes plus redirects, forks, and external route references.
_Avoid_: Command tree, dispatcher

**External route**:
A vanilla or third-party command path referenced by name because the framework cannot generate its Kotlin route type.
_Avoid_: Raw command, foreign command

**Command requirement**:
The reserved observable predicate contract for a future dynamic command branch. The current command compiler does not attach it to generated branches.
_Avoid_: Policy, permission

**Command schema version**:
An integer revision of one command set’s public command grammar, used to expire temporary migration routes.
_Avoid_: Plugin version, framework version

**Command group**:
A nested command-set class that contributes a literal and may contribute positional arguments shared by every route beneath it.
_Avoid_: Subcommand class, nested command

**Command scope**:
A nested command-set class that contributes resolved invocation dependencies and requirements without adding a path segment.
_Avoid_: Hidden group, context

**Command invocation**:
One accepted execution of a command route with a source and decoded arguments.
_Avoid_: Command call, execution

**Command sender**:
The Paper sender that initiated a command invocation, receives ordinary responses, and supplies permission checks.
_Avoid_: Executor, actor

**Command executor**:
The entity on whose behalf a command runs; it may differ from the command sender when commands use vanilla execution redirection.
_Avoid_: Sender, player

**Asynchronous command**:
A command route whose invocation runs as a lifecycle-owned plug-in task after Brigadier accepts it.
_Avoid_: Async command, background command

**Argument codec**:
A typed command extension that defines Brigadier syntax, decoding, suggestions, and route encoding for one Kotlin argument type and optional qualifier.
_Avoid_: Argument parser, converter

**Argument qualifier**:
A typed annotation that selects one argument codec when a Kotlin argument type has more than one meaning.
_Avoid_: Codec name, argument mode

**Entity reference**:
A stable command argument identity that can enter a non-suspending entity execution context to access its current live Paper entity.
_Avoid_: Entity handle, raw entity

**Greedy text**:
A string command argument that consumes the decoded positional remainder after option extraction.
_Avoid_: Remaining string, raw arguments

**Command option**:
A named, non-positional command input represented either by an annotated handler parameter or a property in an options class.
_Avoid_: Flag, named argument

**Command result**:
The explicit outcome of a handler that may contain multiple command responses.
_Avoid_: Response, return value

**Command help**:
The permission-filtered help model derived from command declarations and their KDoc metadata.
_Avoid_: Usage text, command documentation

**Command rejection**:
An expected refusal of a command invocation with a user-facing response and no error report.
_Avoid_: Failure, exception

**Response codec**:
A typed command extension that converts one handler return type into a command result.
_Avoid_: Renderer, result mapper

**Command policy**:
A typed, injected interceptor that applies reusable invocation rules in one framework-defined execution phase.
_Avoid_: Middleware, command annotation

**Command observer**:
An injected recipient of command invocation lifecycle events that cannot alter the invocation outcome.
_Avoid_: Policy, event listener

**Policy key**:
The stable identity used to share policy state across command invocations.
_Avoid_: Cache key, player key
