# Keep input parsers synchronous

Input prompt parsers will be non-suspending. They must decide accept, retry, cancel, or pass before the live server event callback returns, so chat cancellation remains deterministic. Database and network validation belongs in ordinary suspending Kotlin between prompts; allowing it inside the parser would require either consuming every message preemptively or adding a separate synchronous admission phase.
