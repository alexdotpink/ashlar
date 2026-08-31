# Suspend menu presentation around focused input

Chat and other focused input may temporarily hide a menu's native host while retaining the logical session, keyed state, and navigation history. The input module owns answer, retry, cancellation, pass-through, timeout, and disconnect semantics; completion rerenders and remounts the menu from current state. Visibility-dependent effects pause during suspension, while explicitly persistent storage observation may continue. This bridge avoids manual close-and-reconstruction in every menu action.
