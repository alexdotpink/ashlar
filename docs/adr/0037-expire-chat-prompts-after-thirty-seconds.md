# Expire idle chat prompts after thirty seconds

Chat prompts will expire after thirty seconds of inactivity unless the caller chooses another idle timeout or explicitly removes it. A consumed retry restarts the idle timer, while passed-through chat does not. A required value would repeat the same duration at most call sites, and callers that need an absolute cap can wrap the suspending prompt in Kotlin's `withTimeout`. Disconnect, explicit replacement, and plug-in shutdown still clean up prompts with no idle timeout.
