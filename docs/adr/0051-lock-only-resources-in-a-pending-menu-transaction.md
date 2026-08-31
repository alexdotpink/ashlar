# Lock only resources in a pending menu transaction

A pending menu transaction will hold single-flight locks for the storage models and cursor state it touches. Conflicting gestures are rejected immediately, while unrelated action slots and independent storage remain usable. The runtime will never queue inventory gestures for later replay because their slot and item assumptions belong to the render on which the player clicked.
