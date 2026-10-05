# Event Bus

`EventBus` is instantiated per project (not a singleton). Events are defined in `CoreApplicationEvents` as nested classes. Subscribe with `eventBus.subscribe(EventClass.class, handler)`. The optional `runOnFxThread` flag wraps delivery in `Platform.runLater()`. A handler that throws is logged at `SEVERE` with the event name and cause and the publish continues — on **both** branches; the guard lives in the delivery, not around the call, because on the `runLater` branch `publish` has already returned by the time the handler runs. A subscriber shorter-lived than the project keeps the returned `Subscription` and closes it (`RunConsole`).

