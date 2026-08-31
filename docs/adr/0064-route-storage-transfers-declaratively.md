# Route storage transfers declaratively

Storage slots will declare insertion, extraction, and stack-limit rules, while ordered transfer routes will define automatic destinations between storage models. The transaction engine will apply vanilla-correct merging and empty-slot placement consistently for shifts, swaps, collection, and drags. Concrete hosts may supply overridable vanilla defaults; absent a valid route, the engine rejects the transfer instead of guessing or delegating inventory algorithms to each menu.
