# Return typed answers and cancel unanswered prompts

An input prompt will return its typed answer directly. User choice, external cancellation, deadline expiry, player disconnect, replacement, and plug-in shutdown will end the prompt with an `InputCancellationException` carrying a typed reason instead of returning a nullable or sealed result. This keeps ordinary multi-prompt Kotlin linear while allowing callers that care about an unanswered prompt to distinguish each expected ending without treating it as a framework failure.
