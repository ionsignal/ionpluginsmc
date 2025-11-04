# IonNerrus

![IonNerrus](./logo.png)

![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)
![Build Status](https://img.shields.io/badge/build-passing-brightgreen.svg)
![Minecraft Version](https://img.shields.io/badge/Minecraft-1.21.7-green.svg)
![Discord](https://img.shields.io/discord/your-discord-invite-code?label=Join%20Our%20Discord&logo=discord)

IonNerrus is an advanced AI engine for Minecraft NPCs, built on the PaperMC platform. Our mission is to create truly intelligent, autonomous agents that can understand complex natural language directives, formulate multi-step plans, and execute them to interact with the world in meaningful ways.

This project features a sophisticated cognitive architecture that integrates a Large Language Model (LLM) for high-level reasoning with a robust, game-native engine for performant, moment-to-moment actions.

## Project Philosophy

The goal of IonNerrus is to create AI agents, or "Personas," that are more than just mindless mobs. We aim for a level of believability and capability that makes them feel like genuine participants in the world. This is achieved by combining a high-performance engine for core actions (like movement and interaction) with a powerful cognitive director for high-level reasoning. The result is an agent that can understand a command like *"gather a stack of wood and give it to Steve"*, break it down into a logical sequence of tasks, and carry them out autonomously.

## Core Features

* **LLM-Powered ReAct Cognitive Engine:** At its core, IonNerrus uses a ReAct (Reason+Act) cognitive director. This allows an agent to receive a high-level directive in natural language, reason about the steps needed to accomplish it, and select the appropriate "tool" (a `Goal`) to execute for each step in its plan.
* **Dynamic Tool & Goal System:** Agent capabilities are not hardcoded. The system automatically discovers all available `Goals` (e.g., `GET_BLOCKS`, `GIVE_ITEM`, `FOLLOW_PLAYER`) and presents them as a library of tools to the LLM, making the agent's skillset easily extensible.
* **Hierarchical AI (`Goal -> Task -> Skill`):** A sophisticated architecture that translates high-level objectives from the LLM into concrete, low-level actions, making complex behaviors manageable and reliable.
* **Advanced Social & Interactive Goals:** The engine supports complex, multi-step social interactions. Agents can be told to follow players, gather resources and deliver them to a target, and wait for social cues (like a player looking at them) before acting.
* **Robust "Guardrail" System:** To ensure stability, the agent is equipped with "Guardrail Goals" for capabilities it doesn't have yet (e.g., building, crafting, farming). If the LLM attempts to use one, the goal immediately reports a polite failure with a reason, allowing the cognitive director to understand its limitations and avoid getting stuck.
* **High-Performance Asynchronous Navigation:** A custom, multi-threaded A* pathfinder that uses world snapshots for thread-safe calculation. It finds optimal paths through complex 3D environments, including climbing, falling, and swimming, without impacting server performance.
* **Advanced Look & Body Control:** A custom controller that provides smooth, context-aware head and body rotation. Personas will realistically turn to face navigation waypoints, look at blocks they are breaking, and turn to face players who interact with them.
* **Interactive Inventory Management:** Right-clicking a Persona opens a custom GUI, allowing players to directly view and manage the Persona's inventory alongside their own.

## The IonNerrus Architecture: A Hierarchy of Intent

The power of IonNerrus lies in its clean separation of concerns. Every action an agent takes is the result of a decision-making process that flows from the cognitive to the physical.

### **Cognitive Layer (The "Thinker")**

* **Role:** The `ReActDirector` is the agent's "brain." It takes a natural language directive from a player and uses an LLM to formulate a plan. It operates in a loop: **Reason** (analyze the objective and current state) -> **Act** (select a `Tool` to make progress).
* **Example:** Given the directive "get 32 logs and give them to Notch," the director first thinks, "I need logs." It selects the `GET_BLOCKS` tool. After that goal succeeds, the result is fed back into the loop. The director then thinks, "I have the logs, now I need to give them away." It selects the `GIVE_ITEM` tool to complete the directive.

### **Goal (The "Why")**

* **Role:** The highest-level objective selected by the Cognitive Layer. A goal is a state machine that decides which `Task` to run next based on the agent's state. It represents a single, complete tool execution.
* **Example:** The `GetBlockGoal` is given the objective "get 32 wood." It will first run a task to check its inventory, then loop through gathering tasks until the count is met, and finally report its success or failure back to the Cognitive Layer.

### **Task (The "What")**

* **Role:** A major, self-contained step required to achieve a `Goal`. A task orchestrates a sequence of low-level skills to get its job done.
* **Example:** To fulfill its objective, the `GetBlockGoal` repeatedly runs a `GatherBlockTask`. This task is responsible for the entire "find one, go to one, break one, collect one" cycle.

### **Skill (The "How")**

* **Role:** A single, atomic, and reusable action. Skills are the fundamental building blocks of all agent behavior and are often direct wrappers around game APIs or complex calculations.
* **Example:** The `GatherBlockTask` uses a chain of skills: `FindCollectableBlockSkill` -> `NavigateToLocationSkill` -> `EquipBestToolSkill` -> `BreakBlockSkill` -> `CollectItemSkill`.

## Getting Started

### Prerequisites (For Server Administrators)

* A Minecraft server running **Paper 1.21.7 or newer**.
* **Java 21** or newer.
* An OpenAI-compatible API endpoint for the LLM.

### Installation

1. Download the latest release from the [Releases](https://github.com/ionsignal/ionnerrus/releases) page.
2. Place the plugin JAR into your server's `/plugins` directory.
3. Start your server to generate the configuration file.
4. Configure your LLM API endpoint in `plugins/IonNerrus/config.yml`.
5. Restart your server.

## Usage & Commands

You can manage Personas using the `/nerrus` command.

* **Spawn a Persona:**

    ```bash
    /nerrus spawn <name> [skin_name]
    ```

    *Example: `/nerrus spawn Bob Notch`*

* **Give a Persona a complex, natural language directive:**

    ```bash
    /nerrus do <name> <directive...>
    ```

    *Example: `/nerrus do Bob get 16 wood for me`*

* **Ask a Persona a conversational question:**

    ```bash
    /nerrus ask <name> <question...>
    ```

    *Example: `/nerrus ask Bob what are you doing?`*

* **Make a Persona follow a target:**

    ```bash
    /nerrus follow <agentName> <targetName>
    ```

* **Make a Persona give items to a target:**

    ```bash
    /nerrus give <agentName> <targetName> <material> <quantity>
    ```

* **Remove a Persona:**

    ```bash
    /nerrus remove <name>
    ```

* **Stop a Persona's current goal:**

    ```bash
    /nerrus stop <name>
    ```

* **List all active Personas:**

    ```bash
    /nerrus list
    ```

* **Manage Inventory:**
  * **Right-click** a Persona to open its inventory.

## For Developers

Interested in contributing? Here's how to get a development environment set up.

### Prerequisites (Building from Source)

* **JDK 21**
* **Git**

### Build Steps

Clone the repository:

```bash
git clone https://github.com/ionsignal/ionnerrus.git
cd ionnerrus
```

Build the plugin using the Gradle wrapper:

```bash
./gradlew clean build
```

The compiled, shaded JAR will be located in `build/libs/`.

### Debug Server

Start debug server:

```bash
./gradlew runServer --no-daemon --stacktrace
```

### Paperweight Minecraft Source

```bash
jar -xf mappedServerJar.jar
```

### Refresh Task Dependencies

Clean Gradle caches and rebuild

```bash
./gradlew clean build --refresh-dependencies
./gradlew runServer --refresh-dependencies
```
