# Navigate menus with typed retained screens

Multi-screen menu sessions will use an optional typed route stack with push, replace, back, and root close. Covered screens retain route values and local state cells but release inventory declarations, Flow subscriptions, and effects; returning rerenders current data and restarts owned work without losing pagination, filters, or selection. Single-screen menus need no navigation declarations.
