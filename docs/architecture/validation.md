# Validation

`DiagnosticsManager` holds the current set of compiler diagnostics. `ErrorTranslator` maps Eclipse JDT error codes to user-friendly messages. Diagnostics are surfaced to blocks via `CodeBlock.setError()` / `clearError()`.
