# Reserve no player-facing cancellation syntax

The input module will reserve neither a command nor a chat keyword for cancellation. It provides the parser's `cancel()` decision and the atomic `playerInput.cancel(player)` operation; each framework plug-in chooses its commands, words, permissions, and localization. This avoids command-name conflicts, keeps input independent of commands, and permits domains where the literal word “cancel” is a valid answer.
