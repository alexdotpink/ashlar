# Own menu effects by component key

The menu runtime will start closeable `effect` and suspending `launchedEffect` work only after successful reconciliation. Effects are identified by their menu component and caller-supplied key; key changes, component removal, navigation, and menu close dispose or cancel old work before starting replacements. The interface will not expose render-time side effects or make callers rebuild lifecycle from a raw coroutine scope.
