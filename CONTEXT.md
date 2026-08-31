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
