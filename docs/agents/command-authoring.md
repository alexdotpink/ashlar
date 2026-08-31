# Command authoring workflow for agents

Use this sequence for a new command root or route.

## 1. Write the grammar as Kotlin structure

- One `@Commands` class owns the root.
- A public function owns one leaf route.
- An `@Group` inner class adds a literal and may resolve shared positional constructor arguments.
- A `@Scope` inner class injects invocation dependencies without adding a literal.
- `@Root` handles the current path.

Choose function and class names that produce the desired kebab-case literals. Add `@Command` only for an override or alias.

## 2. Model inputs as domain types

Use primitives for primitive input. Use `GreedyText` for a decoded terminal remainder. Use `@Repeated` for an ordered terminal collection. Use `@Option` or an `@Options` data class for named input.

Create a `CommandArgumentCodec<T>` when a handler needs a domain value. Resolution may suspend. Keep lookup, validation, suggestions, and route encoding in that codec. Add an argument qualifier only when the same Kotlin type has distinct command meanings.

## 3. Keep handlers small

Handlers receive resolved values. They may suspend. Return `String`, `Component`, `CommandResult`, or a domain result with a contributed response codec. Use `reject` for an expected refusal. Let unexpected exceptions reach a typed exception handler or the framework failure response.

## 4. Add reusable behavior at the right seam

- Use permissions for static access checks.
- Use built-in policies for cooldown, rate limit, confirmation, and single-flight behavior.
- Use a custom policy annotation and interceptor for reusable invocation rules.
- Use an observer for metadata-only telemetry.
- Use a response codec for a domain return type.
- Use a fragment when multiple features contribute to one large root.
- Use the command graph only for redirects, forks, or external commands.

## 5. Document the route

Every handler needs a one-sentence KDoc summary. Add `@param` entries when the type and parameter name do not explain the value. Add `@example` entries for quoting, options, or non-obvious native syntax.

Enable `commands(strictDocumentation = true)` so missing summaries fail compilation.

## 6. Test it

Use `CommandTestHarness` for parsing, defaults, codecs, options, direct invocation, and basic results. Test policy and observer units directly. Use a real server for the integrated policy pipeline, native Minecraft arguments, help, command-tree delivery, player retirement, and Paper/Folia ownership.

The route is complete when its successful path, expected rejection, and relevant lifecycle edge have evidence.
