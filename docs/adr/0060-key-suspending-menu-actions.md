# Key suspending menu actions

Each menu action will have stable component and handler identity and default to `SINGLE_FLIGHT`, preventing duplicate purchases, teleports, and similar work. Callers may explicitly select `RESTART_LATEST` or `PARALLEL`; state reconciliation remains serialized and storage transactions retain separate resource locks. The runtime will not queue clicks whose assumptions belong to an older render.
