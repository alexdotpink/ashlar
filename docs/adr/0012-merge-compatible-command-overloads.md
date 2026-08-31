# Merge compatible command overloads

Kebab-case function and group names will provide default command literals, with annotations overriding exact spellings and permanent aliases. Functions in one group may overload the same literal when their Brigadier syntax and requirements form distinct branches; KSP and runtime validation will reject ambiguous raw paths. Temporary renamed routes will expire against an integer command-set schema version rather than plugin or framework release versions.
