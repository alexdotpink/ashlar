# Run every generated command asynchronously

Every accepted annotated command handler will launch as a lifecycle-owned plug-in task and return Brigadier `SINGLE_SUCCESS` immediately. Handler results contain responses but no native integer, and asynchronous forks use supervised child tasks. The framework does not expose a second synchronous handler model. This supersedes ADR 0006.
