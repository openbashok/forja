# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Forja** is a Burp Suite extension (Java, Montoya API) that observes live proxy traffic within scope, analyzes it with AI (Claude/OpenAI), and generates custom security testing tools — JS instrumentation scripts, specialized Burp extensions, and PoCs based on real observed traffic.

Sister project to WebAudit (static code analysis → tools). Forja covers the dynamic side. An [OpenBash](https://www.openbash.com) project.

## Build & Run

```bash
# Build the extension JAR
./gradlew build

# Run tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.openbash.forja.SomeTest"

# Clean build
./gradlew clean build
```

The output JAR is loaded into Burp Suite via Extensions > Add > Extension Type: Java.

## Tech Stack

- **Language:** Java 17+
- **Build:** Gradle with Kotlin DSL
- **Burp API:** Montoya API (modern, NOT legacy IBurpExtender)
- **HTTP Client:** OkHttp 4.x
- **JSON:** Gson
- **UI:** Swing (native Burp)
- **LLM:** Anthropic API / OpenAI API / Custom OpenAI-compatible endpoints

## Architecture

The extension has 5 major subsystems organized under `src/main/java/com/openbash/forja/`:

- **`ForjaExtension`** — Entry point implementing `BurpExtension`. Registers all components.
- **`ui/`** — Swing tabs: Config, Traffic Intelligence, Analysis, Generated Toolkit. All UI updates must go through `SwingUtilities.invokeLater` (EDT).
- **`traffic/`** — `TrafficCollector` (implements `ProxyRequestHandler`/`ProxyResponseHandler`) captures in-scope traffic. `AppModel` builds a map of endpoints, auth patterns, tech stack, and security-relevant patterns.
- **`llm/`** — Provider abstraction: `LLMProvider` interface with Anthropic, OpenAI, and Custom implementations. `ContextBuilder` serializes the AppModel into compact LLM context. Supports streaming (SSE).
- **`analysis/`** — `SecurityAnalyzer` sends traffic context to the LLM, returns severity-classified findings with evidence.
- **`toolkit/`** — Generates custom tools from analysis: JS scripts (sniffers, PoCs, JWT manipulation), Burp extensions (auth testers, parameter fuzzers, IDOR scanners), and reports.

Prompts live in `src/main/resources/prompts/`.

## Critical Implementation Notes

- **Thread safety:** Burp proxy callbacks are multi-threaded. Use `ConcurrentHashMap` and thread-safe collections for `AppModel` and `TrafficCollector`.
- **Scope filtering:** Only capture traffic within Burp's scope via `api.scope().isInScope(url)`. Ignore static assets (images, fonts, CSS) unless interesting.
- **Dark theme:** Burp defaults to dark theme. Use Swing look-and-feel colors, never hardcode light colors.
- **Persistence:** Store config (API key, model, budget) via Montoya's `persistence().preferences()`.
- **API key security:** Never log API keys to stdout/stderr. Never include in exports. Warn users that keys are stored on disk via Burp preferences.
- **Token estimation:** ~4 chars ≈ 1 token. Show cost estimate and require confirmation before expensive LLM calls.
- **Rate limits:** Implement exponential backoff for 429/529 errors from both APIs.
- **Montoya API reference:** https://portswigger.github.io/burp-extensions-montoya-api/javadoc/

## Development Phases

The project follows 5 phases defined in `plan.md`:
1. Skeleton + Config Tab (MVP) — extension loads, config works, LLM connection tested
2. Traffic Capture + Intelligence — proxy listener, AppModel, traffic UI tab
3. AI Analysis — send AppModel to LLM, display security findings
4. Toolkit Generation — generate JS scripts, Burp extensions, reports from analysis
5. Context Menu + Integration — right-click analysis, scanner integration, intruder payloads
