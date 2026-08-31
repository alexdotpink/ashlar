# Default custom-item data to Kotlin Serialization

Custom-item definitions will use Kotlin Serialization as their default typed payload codec behind a small pluggable binary codec contract. The framework owns schema-version framing, payload limits, canonical bytes for integrity policies, and structured decode diagnostics; definitions may supply an alternate codec for an existing protocol. Reflection, Java object serialization, and ad hoc primitive PDC field handling are not the framework data model.
