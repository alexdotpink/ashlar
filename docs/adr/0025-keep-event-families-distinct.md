# Keep event families distinct inside one module

The events module will support Bukkit/Paper server events, Paper lifecycle events, and plug-in application events without erasing them behind one dispatch model. Each family gets a distinct annotation and capability because its registration timing, ordering, mutation, and suspension rules differ. Application events remain inside one plug-in classloader; public cross-plug-in contracts stay custom Bukkit events. This keeps one module convenient without teaching agents a false universal event abstraction or creating a shared server-wide bus.
