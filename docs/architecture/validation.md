# Validation

`DiagnosticsManager` holds the current set of compiler diagnostics. The compile is the core's (since 2026-11-05), and so is `ErrorTranslator`, which maps Eclipse JDT error codes to user-friendly messages. Diagnostics are surfaced to blocks via `CodeBlock.setError()` / `clearError()`.
