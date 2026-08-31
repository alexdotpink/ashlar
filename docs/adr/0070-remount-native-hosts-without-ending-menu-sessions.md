# Remount native hosts without ending menu sessions

Reconciliation will update a concrete host in place when its native form permits and transparently remount it when title, capacity, or host kind cannot be changed safely. Internal close/open events do not end the logical menu session, and surviving keyed state remains retained. Remount waits for conflicting pending transactions and fails through the nearest error boundary if committed storage cannot be represented, rather than dropping or silently relocating items.
