# IonNerrus

![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)
![Build Status](https://img.shields.io/badge/build-passing-brightgreen.svg)
![Minecraft Version](https://img.shields.io/badge/Minecraft-1.21+-green.svg)
<!-- ![Discord](https://img.shields.io/discord/your-discord-invite-code?label=Join%20Our%20Discord&logo=discord) -->

IonNerrus is an advanced AI engine for Minecraft NPCs, built on the PaperMC platform. Our mission is to create truly intelligent, autonomous agents that can understand complex, natural language commands and execute multi-step plans to interact with the world in meaningful ways.

This project is architected from the ground up to be "LLM-Ready," providing a robust framework for integrating Large Language Models to drive NPC decision-making, while keeping the moment-to-moment actions performant and reliable.

## Core Features

* **Hierarchical Task Execution:** A sophisticated `Goal -> Task -> Skill` architecture that translates high-level objectives into concrete, low-level actions.
* **Dynamic Goal Planning:** Agents can be assigned complex goals that they will pursue until completion, reacting to their environment along the way.
* **Extensible Skill System:** Easily add new abilities to agents (from breaking blocks to crafting items) by implementing a simple `Skill` interface, without needing to modify the core agent logic.
* **LLM-Ready Architecture:** The entire system is designed for a Large Language Model to act as a "planner," generating `Goal` plans that the agent can execute. The expensive AI thinking happens once, and the fast Java code handles the rest.

## The IonNerrus Architecture: A Hierarchy of Intent

The power of IonNerrus lies in its clean separation of concerns. Every action an agent takes is the result of a three-tiered decision-making process:

### **Goal (The "Why")**

* **Role:** The highest-level objective. This is what the agent ultimately wants to achieve. A goal is responsible for creating a plan, which is a sequence of tasks.
* **Example:** "Get 64 oak logs."
* **LLM Integration:** This is the primary entry point for an LLM. A natural language command like "build me a shelter" will be interpreted by an LLM, which then generates a `Goal` plan (a list of tasks) for the agent to follow.

### **Task (The "What")**

* **Role:** A major, self-contained step required to achieve a `Goal`. A task orchestrates a sequence of low-level skills to get its job done.
* **Example:** To fulfill the "Get Logs" goal, an agent might execute a `FindBiomeTask` (to find a forest), a `GoToLocationTask` (to travel there), and a `GatherBlocksTask`.

### **Skill (The "How")**

* **Role:** A single, atomic, and reusable action. Skills are the fundamental building blocks of all agent behavior and are direct wrappers around game APIs.
* **Example:** The `GatherBlocksTask` uses a loop of `FindNearestBlockSkill`, `NavigateToLocationSkill`, `BreakBlockSkill`, and `CollectItemSkill` to do its job.

This hierarchy makes the system incredibly robust and extensible. To teach an agent a new trick, you just need to write a new `Skill`—the `Task` and `Goal` layers can then orchestrate it to build complex behaviors.

## Getting Started

### Prerequisites (For Server Administrators)

* A Minecraft server running **Paper 1.21 or newer**.
* **Java 21** or newer.

### Installation

1. Download the latest release from the [Releases](https://github.com/your-username/ionnerrus/releases) page.
2. Place the plugin file into your server's `/plugins` directory.
3. Start or restart your server.
4. (Optional) Configure the `plugins/IonNerrus/config.yml` file with your settings, such as the LLM API endpoint and key when that feature is available.

## Usage & Commands

You can manage Nerrus agents using the `/nerrus` command.

* **Spawn an agent:**

```bash
/nerrus spawn <name> [skin_name]
```

*Example: `/nerrus spawn Bob Notch`*

* **Remove an agent:**

```bash
/nerrus remove <name>
```

* **Stop an agent's current goal:**

```bash
/nerrus stop <name>
```

* **Give an agent a simple goal:**

```bash
/nerrus getblock <name> [amount]
```

*This command demonstrates the Goal system by telling an agent to gather wood logs.*

* **(Future) Give an agent a complex, natural language goal:**

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

## Roadmap

This project is under active development. Our roadmap is broken down into several key phases.

* **Phase 1: Core Framework**
  * Establish the `Goal -> Task -> Skill` architecture.
  * Implement a robust `AgentService` for managing NPCs.
  * Create a foundational set of skills for navigation, mining, and collection.
  * Build basic commands for spawning and managing agents.

* **Phase 2: LLM Integration (In Progress)**
  * Develop the proprietary API endpoint that translates structured requests into optimized LLM prompts.
  * Create the `LLMService` in the plugin to communicate with the API.
  * Implement the `LLMGeneratedGoal` to execute plans received from the API.
  * Add the `/nerrus ask` command for natural language instructions.

* **Phase 3: Advanced Behaviors & Memory (Spring 2026)**
  * Expand the `Skill` library to include combat, farming, building, and crafting.
  * Implement a long-term memory system for agents using a persistent database.
  * Develop agent-to-agent communication and collaboration.
  * Introduce autonomous agent drives (e.g., self-preservation, resource stockpiling).

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
