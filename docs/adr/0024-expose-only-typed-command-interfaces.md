# Expose only typed command interfaces

The command module will not expose raw `CommandSourceStack`, Brigadier builders, or Brigadier contexts as part of its interface. Redirects, forks, external route references, native argument coverage, and other supported capabilities enter the stable module only through developer-friendly typed designs. The observable requirement interface remains reserved until it can be attached without exposing Brigadier. Plug-in authors may still use Paper directly outside the framework, but unsupported Brigadier features do not justify a framework escape hatch.
