# Compose conversations from typed prompts

The input module will make one suspending typed prompt its central abstraction. Each prompt owns one player interaction and returns one typed answer; plug-in authors compose multi-step conversations with ordinary Kotlin control flow rather than declaring a ashlar-owned workflow or state machine. This keeps branching, loops, and domain state in Kotlin while the module concentrates prompting, retry, conflict, disconnect, deadline, and cleanup behavior.
