# Support options without combinatorial generation

Named options may appear anywhere before a standalone `--` and support long names, `--name=value`, one short alias at a time, and boolean negation. Reusable options classes may use ordinary Kotlin constructor defaults through one generated defaults instance; direct option parameters must be required, nullable, or `OptionValue<T>` so KSP never emits every present/absent combination. Repeated collection options preserve order, while repeated scalar options are syntax errors.
