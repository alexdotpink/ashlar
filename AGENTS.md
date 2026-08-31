# Framework agent guide

Build against the checked-in APIs and examples. The repository targets Kotlin 2.4, Java 25, Paper 26.2, and Folia.

- Read [docs/agents/plugin-authoring.md](docs/agents/plugin-authoring.md) before creating or changing a framework plug-in.
- Read [docs/agents/command-authoring.md](docs/agents/command-authoring.md) before adding command routes, arguments, policies, responses, fragments, or graph edges.
- Read [docs/agents/verification.md](docs/agents/verification.md) before claiming a plug-in or framework change works.
- Use [docs/reference/api-index.md](docs/reference/api-index.md) to find the authoritative page for a public type.
- Use [CONTEXT.md](CONTEXT.md) for project terminology and [docs/adr/](docs/adr/) for design rationale.

Prefer the smallest module and the narrowest framework feature that solves the plug-in requirement. Keep Paper objects inside explicit global, region, or entity ownership blocks. Keep KSP output limited to metadata and direct calls.
