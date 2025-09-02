
# Contributing to IonNerrus

First off, thank you for considering contributing to IonNerrus! We're excited to build the future of intelligent Minecraft entities with you. This document provides guidelines for contributing to the project, from setting up your development environment to submitting your first pull request.

## Table of Contents

- [Getting Started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Setup](#setup)
- [Understanding the Architecture](#understanding-the-architecture)
  - [The Goal -> Task -> Skill Hierarchy](#the-goal---task---skill-hierarchy)
- [How to Contribute](#how-to-contribute)
  - [Contribution Workflow](#contribution-workflow)
  - [Coding Style](#coding-style)
- [Git Commit Guide](#git-commit-guide)

## Getting Started

Before you can contribute, you need to set up your local development environment.

### Prerequisites

- **Java Development Kit (JDK) 21** or newer. The project is configured to use Gradle Toolchains, which may attempt to auto-provision it for you.
- **Git** for version control.
- An IDE like **VS Code** with Java support is highly recommended.

### Setup

1. **Fork & Clone the Repository**
    - Fork the official repository to your own GitHub account.
    - Clone your fork to your local machine:

        ```bash
        git clone https://github.com/ionsignal/ionnerrus.git
        cd ionnerrus
        ```

2. **Build the Project**
    - This project uses the Gradle Wrapper, so you don't need to install Gradle manually.
    - Build the plugin by running the following command from the project's root directory. This will compile the code and create a shaded JAR with all necessary libraries.

        ```bash
        ./gradlew build
        ```

    - The compiled plugin `.jar` file will be located in the `build/libs/` directory.

### Running & Debugging

The project includes a `run-paper` Gradle plugin that completely automates setting up a test server.

#### Command Line

You can run a Paper server with the IonNerrus plugin using a single command:

```bash
./gradlew runServer
```

The server will download all required files on the first run and start with the plugin loaded.

#### Visual Studio Code (Recommended)

The repository is pre-configured for a seamless debugging experience in VS Code.

1. **Run the Server:** Open the Command Palette (`Ctrl+Shift+P` or `Cmd+Shift+P`) and run the task `Tasks: Run Task`, then select `Run Paper Server`. The server will start in the background and wait for a debugger to connect.
2. **Attach the Debugger:** Go to the "Run and Debug" view (the bug icon on the sidebar) and press the green play button for the **"Attach to Minecraft Server"** configuration.

The server will finish starting up, and your breakpoints will now be active, allowing you to debug the plugin live.

## Understanding the Architecture

To contribute effectively, it's important to understand the core design of IonNerrus. The agent's behavior is modeled on a three-tiered hierarchy.

### The Goal -> Task -> Skill Hierarchy

- **Goal (The "Why"):** The highest-level objective. A `Goal` defines *what* the agent wants to achieve (e.g., "get 5 oak logs"). It is responsible for creating a plan, which is a sequence of `Tasks`.
  - *When to add a `Goal`?* When you want to define a new, complex, multi-stage objective for an agent.

- **Task (The "What"):** A concrete, sequential step to accomplish a `Goal`. A `Task` orchestrates one or more `Skills` to perform a high-level action like "gather all nearby blocks of a certain type" or "travel to a biome."
  - *When to add a `Task`?* When you need to create a new high-level behavior that combines several low-level actions.

- **Skill (The "How"):** The lowest-level, atomic action. A `Skill` is a direct wrapper around a game API (Bukkit, Paper). Examples include "break a single block" or "find the nearest block of a specific material." All skills are designed to be asynchronous from the `Task`'s perspective.
  - *When to add a `Skill`?* When you need to give the agent a new, fundamental ability that directly interacts with the game world.

This structure creates a clean separation of concerns, making the agent's logic easier to manage, extend, and debug. When adding new functionality, first identify which layer it belongs to.

## How to Contribute

We welcome contributions of all kinds, from bug fixes to new features.

### Contribution Workflow

1. **Find an Issue:** Look for an existing issue on our [GitHub Issues page](https://github.com/ionsignal/IonNerrus/issues) or create a new one to discuss your proposed changes. This helps prevent duplicate work.

2. **Create a Branch:** Create a new branch from `main` for your changes. A good branch name is descriptive, like `feat/agent/add-crafting-task`.

    ```bash
    git checkout -b your-branch-name
    ```

3. **Develop:** Make your changes, following the project's architecture and coding style.

4. **Test:** Build the plugin and thoroughly test your changes on a local server to ensure they work as expected and don't introduce new bugs.

5. **Commit:** Commit your changes using the conventional commit format described in the [Git Commit Guide](#git-commit-guide).

6. **Push and Open a Pull Request:** Push your branch to your fork and open a Pull Request (PR) against the `main` branch of the official repository.

7. **Review:** Provide a clear description of your changes in the PR. A project maintainer will review your code, and you may be asked to make adjustments before it's merged.

### Coding Style

- **Emulate Existing Code:** Strive to match the style and patterns found in the existing codebase.
- **Use the Interfaces:** When adding new behaviors, implement the `Goal`, `Task`, or `Skill` interfaces.
- **Keep it Clean:** Follow modern Java best practices.
- **Minimal Commenting:** Avoid excessive comments. Code should be as self-documenting as possible. Only comment on complex algorithms or non-obvious logic.

## Git Commit Guide

```txt
<type>(<scope>): <subject>
<BLANK LINE>
[optional body]
<BLANK LINE>
[optional footer]
```

### Header

The header is the most important part of the message. It is mandatory and consists of three parts: `type`, `scope`, and `subject`.

#### **Type**

The type describes the *kind* of change you are making.

- **feat**: A new feature or a significant enhancement to an existing one.
- **fix**: A bug fix that corrects incorrect behavior.
- **refactor**: A code change that neither fixes a bug nor adds a feature, but improves the internal structure, readability, or design.
- **arch**: Architectural changes. Affects the core design, contracts between components, or introduces new architectural patterns.
- **perf**: A code change that improves performance.
- **style**: Changes that do not affect the meaning of the code (white-space, formatting, missing semi-colons, etc.).
- **dev**: Changes related to the development experience (e.g., adding debug tools, improving build process, CI/CD changes).
- **docs**: Documentation-only changes.
- **chore**: Other changes that don't modify source code (e.g., updating dependencies, modifying `.gitignore`).

#### **Scope** (optional)

The scope is a noun that specifies the area or component of the codebase affected by the change. Use a comma separated list if multiple are involved.

- `agent`
- `skill`
- `persona`
- `navigation`
- `build`
- `nmsbridge`
- `command`
- `listener` (for event handlers)
- `api` (we aren't doing these yet)
- `config`
- `data` (for data storage)
- `gui` (for inventory-based menus)
- `task` (for schedulers)

#### **Subject**

The subject is a concise, imperative-tense description of the change.

- Use the imperative mood (e.g., "add" not "added", "change" not "changed").
- Don't capitalize the first letter.
- Do not end the subject line with a period.

### Body (optional)

The body is used to provide additional context and explain the *what* and *why* of the change, not the *how*. It should be used for any non-trivial commit.

## Git Tagging

Don't forget to bump version.

```bash
git tag -n # show previous tags
git checkout <commit_hash_for_the_version_bump> # checkout commit to tag
git tag -a v0.0.2-alpha.1 -m "Release version 0.0.2-alpha.1: Social Interaction & Item Transfer"
```

## Latest Release Notes

Title: IonNerrus 0.0.2-alpha.1: Social Interaction & Item Transfer

This alpha release builds upon the foundational engine by introducing the first set of social and interactive capabilities. Agents can now understand and execute requests to transfer items to players or other agents, making them more collaborative participants in the world.

This is an early **alpha** build intended for developers and testers. Expect bugs, incomplete features, and rapid changes.

## Key Features

- - **Social Interaction & Item Transfer (`GIVE_ITEM` Goal):** The cornerstone of this update.

## Known Issues

- **Finding Blocks** The `FindCollectableBlockSkill` fails to start the search if we're on a ledge
- **No Persistence:** Agents do not yet persist across server restarts. This is a top priority for the next development cycle.
- **Limited Hazard Awareness:** The agent's ability to avoid environmental dangers is currently minimal.
