# Require one owner for cross-storage commits

A transaction that spans multiple persistent storage models will require one explicit transaction domain to approve the complete proposal atomically. A single persistent model automatically owns proposals involving itself and ashlar-managed player inventory or cursor state. Menu validation rejects persistent cross-model routes without one common owner; the framework will not simulate atomicity with sequential callbacks and compensating rollback.
