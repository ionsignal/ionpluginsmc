# IonNerrus

![IonNerrus](./logo.png)

![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)
![Build Status](https://img.shields.io/badge/build-passing-brightgreen.svg)
![Minecraft Version](https://img.shields.io/badge/Minecraft-1.21.7-green.svg)
![Discord](https://img.shields.io/discord/your-discord-invite-code?label=Join%20Our%20Discord&logo=discord)

IonNerrus is an advanced AI engine for Minecraft NPCs, built on the PaperMC platform. Our mission is to create truly intelligent, autonomous agents that can understand complex goals and execute multi-step plans to interact with the world in meaningful ways.

This project is architected from the ground up to be "LLM-Ready," providing a robust framework for integrating Large Language Models to drive NPC decision-making, while keeping the moment-to-moment actions performant and reliable.

## Project Philosophy

The goal of IonNerrus is to create AI agents, or "Personas," that are more than just mindless mobs. We aim for a level of believability and capability that makes them feel like genuine participants in the world. This is achieved by combining a high-performance, game-native engine for core actions (like movement and interaction) with a sophisticated hierarchical AI for high-level reasoning. The result is an agent that can navigate complex terrain, use tools, manage its inventory, and react to its environment, all while providing a stable foundation for LLM-driven planning (coming soon).

## Core Features

* **Hierarchical AI (`Goal -> Task -> Skill`):** A sophisticated architecture that translates high-level objectives into concrete, low-level actions, making complex behaviors manageable and extensible.
* **High-Performance Asynchronous Navigation:** A custom, multi-threaded A* pathfinder that uses world snapshots for thread-safe calculation. It finds optimal paths through complex 3D environments, including climbing, falling, and swimming, without impacting server performance.
* **Advanced Look & Body Control:** A custom controller that provides smooth, context-aware head and body rotation. Personas will realistically turn to face their navigation waypoints, look at blocks they are breaking, and turn to face players who interact with them.
* **Queued Action System:** A robust controller that manages a queue of actions (e.g., breaking a block, looking at a target), ensuring that a Persona can perform complex sequences of behaviors without conflicts.
* **Interactive Inventory Management:** Right-clicking a Persona opens a custom GUI, allowing players to directly view and manage the Persona's inventory alongside their own.
* **Interaction State-Locking:** To ensure stability, a Persona's AI, navigation, and actions are automatically paused when a player is viewing its inventory. The Persona will remain stationary and look at the player until the inventory is closed, at which point it seamlessly resumes its prior activity.
* **Extensible Skill Library:** A collection of fundamental, reusable abilities (e.g., `FindAccessibleBlockSkill`, `BreakBlockSkill`, `EquipBestToolSkill`, `CollectItemSkill`) that serve as the building blocks for all higher-level tasks.

## The IonNerrus Architecture: A Hierarchy of Intent

The power of IonNerrus lies in its clean separation of concerns. Every action an agent takes is the result of a three-tiered decision-making process:

### **Goal (The "Why")**

* **Role:** The highest-level objective. This is what the agent ultimately wants to achieve. A goal is a state machine that decides which task to run next based on the agent's state and the results of previous tasks.
* **Example:** A `GetBlockGoal` is given the objective "get 64 oak logs." It will first run a task to check its inventory, then loop through gathering tasks until the count is met.
* **LLM Integration:** This is the primary entry point for an LLM. A natural language command will be interpreted by an LLM, which then generates a `Goal` plan for the agent to follow.
clear

### **Task (The "What")**

* **Role:** A major, self-contained step required to achieve a `Goal`. A task orchestrates a sequence of low-level skills to get its job done.
* **Example:** To fulfill its objective, the `GetBlockGoal` repeatedly runs a `GatherBlockTask`. This task is responsible for the entire "find one, go to one, break one, collect one" cycle.

### **Skill (The "How")**

* **Role:** A single, atomic, and reusable action. Skills are the fundamental building blocks of all agent behavior and are often direct wrappers around game APIs or complex calculations.
* **Example:** The `GatherBlockTask` uses a chain of skills: `FindAccessibleBlockSkill` -> `NavigateToLocationSkill` -> `EquipBestToolSkill` -> `BreakBlockSkill` -> `CollectItemSkill`.

This hierarchy makes the system incredibly robust and extensible. To teach an agent a new trick, you just need to write a new `Skill`—the `Task` and `Goal` layers can then orchestrate it to build complex behaviors.

## Getting Started

### Prerequisites (For Server Administrators)

* A Minecraft server running **Paper 1.21 or newer**.
* **Java 21** or newer.

### Installation

1. Download the latest release from the [Releases](https://github.com/ionsignal/ionnerrus/releases) page.
2. Place the plugin file into your server's `/plugins` directory.
3. Start or restart your server.
4. (Optional) Configure the `plugins/IonNerrus/config.yml` file.

## Usage & Commands

You can manage Personas using the `/nerrus` command.

* **Spawn a Persona:**

    ```bash
    /nerrus spawn <name> [skin_name]
    ```

    *Example: `/nerrus spawn Bob Notch`*

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

* **Give a Persona a simple goal:**

    ```bash
    /nerrus getblock <name> [amount]
    ```

    *This command demonstrates the Goal system by telling a Persona to gather wood logs.*

* **Manage Inventory:**
  * **Right-click** a Persona to open its inventory.

* **(Future) Give a Persona a complex, natural language goal:**

    ```bash
    /nerrus ask <name> "find a cave and gather a stack of iron ore for me"
    ```

## For Developers

Interested in contributing? Here's how to get a development environment set up.

### Prerequisites (Building from Source)

* **JDK 21**
* **Git**

### Build Steps

1. Clone the repository:

    ```bash
    git clone https://github.com/ionsignal/ionnerrus.git
    cd ionnerrus
    ```

2. Build the plugin using the Gradle wrapper:

    ```bash
    ./gradlew build
    ```

3. The compiled, shaded JAR will be located in `build/libs/`.

## Project Status & Roadmap

This project is under active development.

### **Current Status: Engine Complete**

The core engine is feature-complete and stable. Key systems developed include:

* **Core Engine & Behavior Systems:** Foundational infrastructure (`Persona`, `NerrusManager`), robust NMS integration (`PersonaEntity`), asynchronous navigation, and a queued action/animation controller.
* **World Presence & Foundational Skills:** World presence through chat and player list management, basic player engagement reactions, and core pathfinding utilities.
* **Advanced Intelligence & Believability:** Sophisticated skills for pathing dead-end resolution and advanced look/body controllers for smooth, context-aware rotation.
* **Interactive Inventory Management:** A fully interactive, custom inventory GUI with a robust state-locking mechanism to ensure stability during player interaction.

### **Next Steps: LLM Planner Integration**

* Develop the proprietary API endpoint that translates structured requests into optimized LLM prompts.
* Create the `LLMService` in the plugin to communicate with the API.
* Implement an `LLMPlanner` to generate and execute plans received from the API.
* Add the `/nerrus ask` command for natural language instructions.

### **Future Vision**

* **Advanced Skill Library:** Expand the `Skill` library to include combat, farming, building, and crafting.
* **Long-Term Memory:** Implement a persistent memory system for agents.
* **Agent Collaboration:** Develop agent-to-agent communication and collaborative task execution.
* **Autonomous Drives:** Introduce agent drives (e.g., self-preservation, resource stockpiling) for fully autonomous behavior.

## Contributing

Contributions are what make the open-source community such an amazing place to learn, inspire, and create. Any contributions you make are **greatly appreciated**.

1. Fork the Project
2. Create your Feature Branch (`git checkout -b feature/AmazingFeature`)
3. Commit your Changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the Branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

Please report bugs or suggest features by opening an issue on the [GitHub Issues](https://github.com/your-username/ionnerrus/issues) page.

## License

This project is licensed under the **GNU General Public License v3.0**. See the `LICENSE` file for more information. This means the code is free to use, modify, and distribute, but any derivative works must also be open-source under the same license. The LLM endpoint that this plugin will communicate with remains proprietary.
