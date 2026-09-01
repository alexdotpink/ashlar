# Generate command help from KDoc

KSP will extract command summaries, parameter descriptions, examples, and migration notices from KDoc into immutable route metadata. The runtime provides automatic permission-aware root and paged help through an injected renderer. Ashlar-originated permission, argument, and unexpected-failure responses use a locale-aware injected Adventure message provider; built-in policy rejections and plain `String` handler results remain literal text.
