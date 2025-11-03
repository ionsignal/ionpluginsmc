# IonCore Debug Framework

A generic, thread-safe debug framework for Paper/Bukkit plugins that provides execution control and visualization coordination.

## Architecture Overview

### Core Components

1. **`DebugSession<TState>`**: Thread-safe container for debug session state
   - Player ownership (UUID-based)
   - Lifecycle management (CREATED → ACTIVE → PAUSED → COMPLETED/CANCELLED/FAILED)
   - Visualization dirty flags for efficient rendering
   - Optional execution controller integration

2. **`ExecutionController`**: Interface for pause/resume control
   - `LatchBasedController`: Blocks async threads using `CountDownLatch`
   - Configurable timeout behavior (AUTO_RESUME, REQUIRE_MANUAL, CANCEL)
   - Session status synchronization

3. **`DebugSessionRegistry`**: Thread-safe session management
   - One session per player (UUID-based)
   - Lifecycle hooks (create, remove, cancel)
   - Bulk operations for shutdown

### Threading Model

The framework is **executor-agnostic** - consumers inject their own executors:

```java
// Plugin provides executors
Executor mainThread = IonNerrus.getInstance().getMainThreadExecutor();
Executor offloadThread = IonNerrus.getInstance().getOffloadThreadExecutor();

// Create session with controller
LatchBasedController controller = ExecutionControllerFactory.createLatchBased();
DebugSession<MyState> session = registry.createSession(
    player.getUniqueId(), 
    initialState, 
    controller
);

// Async work with pause points
CompletableFuture.runAsync(() -> {
    controller.pause("Phase 1", "Starting operation");
    // Do work...
    controller.pause("Phase 2", "Halfway done");
    // More work...
}, offloadThread);

// Main thread command handler
public void onResumeCommand(Player player) {
    registry.getActiveSession(player.getUniqueId())
        .ifPresent(session -> 
            session.getController().ifPresent(ExecutionController::resume)
        );
}