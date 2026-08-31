# Test menu behavior with a deterministic harness

The menu module will expose a server-free test harness that drives the production rendering, reconciliation, navigation, action-concurrency, and transaction engines through a fake host adapter. It will support virtual time, typed gestures, storage outcomes, semantic snapshots, and item-conservation assertions. Separate contract suites will exercise each native adapter on real Paper and Folia, keeping plug-in tests fast without pretending mocked Bukkit events prove native behavior.
