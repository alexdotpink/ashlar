# Split command parsing from domain resolution

Argument codecs will give Brigadier a synchronous raw syntax, then resolve that raw value into the handler’s Kotlin type inside the lifecycle-owned command task. Native syntax failures retain Brigadier’s cursor-aware errors, while database-backed lookups and contextual validation may suspend and report ordinary command rejections. Suggestions may also suspend, and the same codec must encode domain values for generated routes.
