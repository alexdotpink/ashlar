# Protect the active player prompt

A framework plug-in will allow one active input prompt per player. Starting another prompt will fail immediately and leave the active prompt untouched; a caller must opt into replacement, which cancels the old prompt with the `REPLACED` reason. The module will not queue prompts because a delayed prompt can outlive the player action that made it relevant.
