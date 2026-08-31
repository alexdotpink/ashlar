# Generate command bindings only

Command declarations will use KSP, but generated code will contain only immutable metadata, direct handler calls, typed route functions, and registration linkage. KotlinPoet will emit one file per command set; parsing, Brigadier construction, permissions, policies, scheduling, responses, scopes, codecs, and route encoding remain ordinary runtime Kotlin. This keeps compile-time validation and typed generated code without turning the processor into a second command implementation.
