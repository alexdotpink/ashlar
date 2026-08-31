# Use an ordinary PlayerInput capability

Plug-in code will inject `PlayerInput` and call `playerInput.chat(player, prompt, ...)` with named options and one parser receiver. We will not require a context receiver or a prompt-builder DSL. One operation with one parser block keeps the performing capability visible and avoids turning simple arguments such as deadline and conflict policy into a nested configuration language.
