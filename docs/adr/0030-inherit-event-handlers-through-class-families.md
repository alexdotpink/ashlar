# Inherit event handlers through class families

An event-set class marked `@Events` must be final or abstract. A final class contributes itself; every concrete transitive descendant of an abstract marked base contributes automatically, including through unmarked abstract intermediates. Handler metadata follows virtual overrides until a replacement annotation changes it or `@DisableEventHandler` suppresses it, and `@DisableEvents` suppresses an entire descendant branch. Interfaces do not contribute handler metadata, avoiding multiple-inheritance conflicts.
