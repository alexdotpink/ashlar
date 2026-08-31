# Make custom-item payload signing optional

Custom-item definitions may authenticate their namespaced identity, schema version, and canonical typed payload through a versioned HMAC keyring with rotation support. Definitions remain unsigned by default, and integrity is distinct from encryption or item-duplication prevention. Decoding reports invalid signatures separately from wrong-item and malformed-data outcomes; mutable presentation data is covered only when the definition opts it in.
