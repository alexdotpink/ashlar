# Declare command structure with Kotlin nesting

Command-set function names will declare leaf literals, command groups will declare shared literals, and command-group constructor parameters may declare shared positional arguments. Command scopes will add invocation dependencies without adding literals. This structural model supports interleaved literals and arguments without path-template strings, while KSP can validate the resulting tree and generate direct calls.
