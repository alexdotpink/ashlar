# Unify session-local and external menu storage

Session-owned temporary storage and externally persisted or shared storage will implement one versioned menu-storage interface. Dedicated factories express lifecycle and commit authority, while slot bindings, rules, transfers, transaction proposals, standard components, and tests consume both identically. Local storage commits synchronously into keyed session state; external storage retains its explicit snapshot and commit contract.
