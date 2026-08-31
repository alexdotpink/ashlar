# Make live menu sessions semantically inspectable

The menu runtime will expose redacted semantic snapshots, bounded typed lifecycle traces, and typed observers for live sessions. Inspection covers the component tree, state cells, slot bindings, navigation, renders, gestures, actions, effects, transactions, and recovery without exposing mutable internals. Optional command-module integration will inspect and export sessions in development, while sensitive payloads remain summarized unless a plug-in supplies a safe renderer.
