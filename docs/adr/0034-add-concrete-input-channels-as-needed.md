# Add concrete input channels as needed

The input module will expose one `PlayerInput` capability and ship only its concrete `chat` operation initially. Anvil, sign, book, and other input operations may become sibling functions after their real lifecycle and cancellation contracts are understood. We will not introduce a generic input-channel adapter before a second channel proves which behavior actually varies.
