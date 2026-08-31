# Inject typed routes and configure advanced graphs

KSP will generate one plugin-scoped typed routes class per command set, and DI will inject it wherever commands, menus, or other modules need canonical encoded command links. Redirects, forks, and unusual Brigadier edges will use marked startup functions with an injected runtime command-graph interface and typed route references; KSP will bind those functions but generate no graph behavior. Required missing external routes fail startup, while explicitly optional references are skipped with a warning.
