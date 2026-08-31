# Contain menu failures with error boundaries

A keyed menu error boundary will capture unexpected failures from descendant rendering, actions, and effects and render a fallback with an explicit retry operation. The runtime will retain the last successfully reconciled inventory until that fallback is ready. An unhandled failure, or a failure in the fallback itself, will be reported and safely end the session as `MenuClose.Failed`; ordinary domain rejection remains outside this mechanism.
