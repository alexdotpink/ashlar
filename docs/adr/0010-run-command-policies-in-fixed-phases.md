# Run command policies in fixed phases

Command policies will be typed annotations handled by injected runtime interceptors. The framework will define phases around argument resolution and handler execution; custom policies choose a phase and local order rather than relying on annotation source order or one global priority number. Built-in policies use documented sender and route identities. A custom policy that needs another identity owns that state and keying inside its interceptor.
