# Provide default input lifecycle feedback

The input module will send the initial prompt, explicit retry feedback, a configurable expiry message, and an active-prompt-conflict message. An injected `InputMessages` catalogue supplies framework defaults, while a prompt may override domain-specific copy. Player disconnect and plug-in shutdown remain silent because no useful delivery is possible; typed cancellation reasons still let specialized callers react.
