# Ashlar agent guide

Build against the checked-in APIs and examples. The repository targets Kotlin 2.4, Java 25, Paper 26.2, and Folia.

- Read [docs/agents/plugin-authoring.md](docs/agents/plugin-authoring.md) before creating or changing a framework plug-in.
- Read [docs/agents/command-authoring.md](docs/agents/command-authoring.md) before adding command routes, arguments, policies, responses, fragments, or graph edges.
- Read [docs/agents/event-authoring.md](docs/agents/event-authoring.md) before adding server handlers, observers, temporal queries, application events, or lifecycle registrations.
- Read [docs/agents/input-authoring.md](docs/agents/input-authoring.md) before adding typed chat prompts, retries, prompt conflicts, or player-input cancellation.
- Read [docs/agents/menu-authoring.md](docs/agents/menu-authoring.md) before adding plug-in menus, item movement, storage, or menu tests. Read [docs/agents/menu-implementation.md](docs/agents/menu-implementation.md) before changing the framework menu engine, native hosts, transactions, or recovery.
- Read [docs/agents/verification.md](docs/agents/verification.md) before claiming a plug-in or framework change works.
- Use [docs/reference/api-index.md](docs/reference/api-index.md) to find the authoritative page for a public type.
- Use [CONTEXT.md](CONTEXT.md) for project terminology and the [ADR index](docs/adr/README.md) for design rationale.

Prefer the smallest module and the narrowest framework feature that solves the plug-in requirement. Keep Paper objects inside explicit global, region, or entity ownership blocks. Keep KSP output limited to metadata and direct calls.
