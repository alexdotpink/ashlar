# Never intercept commands as chat input

A chat input prompt will observe only Paper chat events. Minecraft slash commands use command dispatch and therefore remain available while a prompt is active; the input module will not intercept command preprocessing or offer a toggle that makes commands into chat answers. A future command-input feature would need its own explicit contract rather than changing what `PlayerInput.chat` means.
