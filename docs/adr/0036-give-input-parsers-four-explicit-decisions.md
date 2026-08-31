# Give input parsers four explicit decisions

A chat prompt parser will return one of four decisions. `accept(value)` consumes the chat and completes, `retry(feedback)` consumes it and continues, `cancel()` consumes it and ends with user cancellation, and `pass()` leaves it uncancelled and continues waiting. This vocabulary keeps event cancellation and retry scheduling inside the input module while allowing a prompt to coexist deliberately with unrelated public chat.
