# Author benchmarks as framework scenarios

Plug-in authors will declare benchmark scenarios through a small Kotlin `setup`/`measure`/`verify` DSL that composes framework-aware fixtures across commands, events, input, items, and menus. Layer-specific runners may use specialized JVM or live-server engines, but engine lifecycle, warmup, sampling, environment capture, and comparison remain outside ordinary scenario code; annotated benchmark methods and raw engine APIs remain internal escape hatches rather than the primary authoring model.
