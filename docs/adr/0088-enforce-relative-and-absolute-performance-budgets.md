# Enforce relative and absolute performance budgets

Performance contracts will use same-worker relative budgets to block pull-request regressions and absolute ceilings to protect objective release quality once a scenario is mature. Pull requests run small and typical profiles, nightly lanes run stress and sustained load, and weekly or release lanes run soak workloads; every lane emits a human-readable comparison, stable JSON, and an annotated CI summary rather than requiring a dashboard to interpret the gate.
