# Preserve chat content behind a typed answer

A chat prompt parser will receive a typed `ChatAnswer` receiver containing plain `text` and the original Adventure `component`. Passing only a string would discard useful information, while passing only a component would force routine parsers to repeat plain-text decoding. The receiver keeps common parsing concise without losing the richer value.
