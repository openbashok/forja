# Forja

A project by [OpenBash](https://www.openbash.com).

Burp Suite extension that observes live proxy traffic within scope, analyzes it with AI (Claude/OpenAI), and generates custom security testing tools — JS instrumentation scripts, specialized Burp extensions, and PoCs based on real observed traffic. The dynamic counterpart to [WebAudit](https://github.com/openbashok/webaudit) (static analysis).

**The core idea:** while WebAudit reads source code, Forja watches the application breathe. It captures live HTTP traffic, builds an intelligence model of the target (endpoints, auth patterns, tech stack, parameter behavior), feeds that context to an LLM, and produces two things: a security assessment with classified findings, and a **custom toolkit** — purpose-built scripts and extensions generated from what the application actually does at runtime. The analyst doesn't configure anything — Forja learns the target by observing it.

**This is not a passive scanner.** Forja doesn't match signatures or run generic checks. Every tool it generates is specific to the target — the IDOR scanner knows which endpoints use sequential IDs because it saw them, the auth tester knows the exact token format because it captured it, and the PoC scripts replay real requests from the proxy history. You can also describe any tool you need in plain language and Forja will generate it with full application context.

## Why a Burp Extension and Not an MCP?

If you've used AI-powered security tools through MCP (Model Context Protocol), you might wonder why Forja runs inside Burp instead of as an external service. The difference is fundamental.

An MCP server is a **data bridge** — it exposes predefined tools and context to an LLM, but it's stateless, sandboxed, and limited to whatever someone decided to expose. It can read some data and call some functions. That's it.

A Burp extension has **full runtime access** to the application under test:

- **Intercept and modify traffic in real time** — not just read it, but rewrite requests and responses as they pass through the proxy
- **Inject code into live responses** — Forja injects generated JS scripts directly into HTML responses, turning the target's browser into an instrumentation platform
- **Access the full Burp API** — scanner, intruder, repeater, sequencer, site map, scope control, all programmatically
- **Generate tools that also run inside Burp** — the Jython extensions Forja creates load as first-class Burp extensions with the same privileges
- **Persistent observation** — Forja watches every request/response continuously, building context over hours of testing

An MCP gives you **windows into context**. A Burp extension **is part of the context**. Forja doesn't ask Burp for data — it lives inside Burp, sees everything the analyst sees, and builds its own understanding of the target in real time. The tools it generates aren't generic scripts that need configuration — they're pre-calibrated instruments built from live observation.

## How It Works

1. **Install** — Load `forja.jar` in Burp Suite, configure your API key in the Forja tab
2. **Browse** — Navigate the target through Burp's proxy (or import existing proxy history)
3. **Observe** — Forja captures in-scope traffic and builds an intelligence map in real time
4. **Analyze** — One click sends the traffic context to the LLM, returns severity-classified findings
5. **Generate** — Auto-generates a toolkit from the findings, or describe what you need in the prompt

The entire flow runs inside Burp. No external tools, no CLI, no context switching.

## What It Produces

### Security Analysis

| Output | Description |
|--------|-------------|
| Findings list | Severity-classified vulnerabilities (CRITICAL → INFO) with evidence from real traffic |
| Affected endpoints | Each finding maps to specific endpoints observed in the proxy |
| CWE references | Standard weakness classification for each finding |
| Recommendations | Actionable remediation guidance per finding |

### Generated Toolkit

| Tool Type | Examples |
|-----------|----------|
| **JS Scripts** | Traffic sniffers, PoC exploits, JWT manipulators, request replayers |
| **Burp Extensions (Jython)** | Auth bypass testers, IDOR scanners, parameter fuzzers, custom scan checks — generated as `.py` files, load directly in Burp without compilation |
| **Custom tools** | Anything you describe in the prompt — Python scripts, HTML PoCs, whatever the assessment needs |

Every tool is **generated from the intelligence gathered during live observation** — real endpoints, real auth tokens, real parameter names. The analyst starts testing with instruments already calibrated to the target.

## Traffic Intelligence

Forja builds a live application model from proxy traffic:

- **Endpoint mapping** — Method, path, path pattern normalization (`/users/123` → `/users/{id}`)
- **Auth detection** — Bearer tokens, API keys, cookies, JWT format identification
- **Tech stack fingerprinting** — Server headers, session cookie patterns, framework signatures
- **Pattern detection** — Sequential IDs (IDOR potential), reflected parameters (XSS potential), CORS headers, JWT in responses
- **Request/response samples** — Stored per endpoint for context in analysis and generation

The model is thread-safe and auto-evicts least-seen endpoints when the configurable limit is reached.

### Import Existing Traffic

Forja doesn't require you to start from zero. The **Import Proxy History** button pulls all existing in-scope traffic from Burp's proxy history into the intelligence model. Install Forja mid-assessment and immediately leverage hours of previously captured traffic.

## Free-Form Prompt

The Generated Toolkit tab includes a prompt field where you describe what you need in plain language:

- *"Generate a JS script that tests IDOR on all endpoints with sequential IDs"*
- *"Create a Python script to brute-force JWT secrets using the tokens found"*
- *"Build a Burp extension that replays all authenticated requests without the auth header"*
- *"Make a PoC that demonstrates the CORS misconfiguration with credential theft"*

The LLM receives your prompt plus the full application context — every endpoint, auth pattern, tech stack detail, and security finding. Quick-pick examples are available in a dropdown for common testing scenarios.

## Burp Integration

| Feature | Description |
|---------|-------------|
| **Context menu** | Right-click any request: "Analyze This Request", "Generate PoC", "Add to Intelligence" |
| **Passive scanner** | Automatic audit issues for JWT tokens in responses, sequential IDs, CORS wildcards, reflected parameters |
| **Suite tab** | Four sub-tabs: Config, Traffic Intelligence, Analysis, Generated Toolkit |
| **Persistence** | API key, model, budget, and preferences stored via Burp's persistence API |

## Installation

### Download (recommended)

1. Download `forja.jar` from the [latest release](https://github.com/openbashok/forja/releases/latest)
2. In Burp Suite: **Extensions → Installed → Add**
3. Extension Type: **Java**
4. Select the downloaded JAR

### Build from source

```bash
git clone https://github.com/openbashok/forja.git
cd forja
./gradlew build
# JAR at build/libs/forja-1.0.0.jar
```

### Requirements

- Burp Suite Professional or Community (Montoya API support)
- API key: [Anthropic](https://console.anthropic.com/) (Claude) or [OpenAI](https://platform.openai.com/) or any OpenAI-compatible endpoint
- Java 17+ (provided by Burp's bundled JRE)

## Configuration

| Setting | Description | Default |
|---------|-------------|---------|
| Provider | Anthropic, OpenAI, or Custom | Anthropic |
| API Key | Your provider API key | — |
| Model | LLM model to use | claude-sonnet-4-20250514 |
| Budget | Max USD per session (confirms before expensive calls) | $1.00 |
| Custom Endpoint | OpenAI-compatible API URL (Custom provider only) | — |
| Max Generation Tokens | LLM output limit for generated tools (increase if scripts truncate) | 16384 |
| Max Traffic Entries | Endpoint limit before eviction | 500 |

API keys are stored on disk via Burp's persistence preferences. They are never logged or included in exports.

## Architecture

```
src/main/java/com/openbash/forja/
├── ForjaExtension.java          # Entry point (BurpExtension)
├── config/
│   └── ConfigManager.java       # Persistence wrapper
├── llm/
│   ├── LLMProvider.java         # Provider interface
│   ├── AnthropicProvider.java   # Claude API
│   ├── OpenAIProvider.java      # OpenAI API
│   ├── CustomProvider.java      # OpenAI-compatible endpoints
│   ├── ContextBuilder.java      # AppModel → LLM context serializer
│   └── ...                      # Message, Response, Exception, Factory
├── traffic/
│   ├── TrafficCollector.java    # Proxy handler + history importer
│   ├── AppModel.java            # Thread-safe application model
│   ├── PatternDetector.java     # JWT, IDs, CORS, tech stack detection
│   └── ...                      # EndpointInfo, AuthInfo
├── analysis/
│   ├── SecurityAnalyzer.java    # LLM orchestrator → findings
│   ├── Finding.java             # Vulnerability data structure
│   └── Severity.java            # CRITICAL → INFO with colors
├── toolkit/
│   ├── ToolkitGenerator.java    # Orchestrator + free-form prompt
│   ├── JSGenerator.java         # JS script generation
│   ├── BurpPluginGenerator.java # Burp extension generation
│   └── GeneratedTool.java       # Tool data structure
├── integration/
│   ├── ForjaContextMenu.java    # Right-click menu items
│   └── ForjaScanCheck.java      # Passive scan check
└── ui/
    ├── ConfigTab.java           # Provider/key/model config
    ├── TrafficTab.java          # Endpoint table + detail view
    ├── AnalysisTab.java         # Findings table + detail view
    ├── ToolkitTab.java          # Tool list + code preview + prompt
    └── UIConstants.java         # Dark theme compatible colors
```

## How Forja and WebAudit Complement Each Other

| | WebAudit | Forja |
|---|---------|-------|
| **Input** | Downloaded source code | Live proxy traffic |
| **Analysis** | Static — reads every JS line | Dynamic — observes runtime behavior |
| **Runs** | CLI, standalone | Inside Burp Suite |
| **Sees** | Client-side code, hardcoded secrets, crypto schemes | Server responses, auth flows, API behavior |
| **Blind spots** | Server-side logic, runtime auth | Minified/obfuscated client code |

Use WebAudit first for source-level reconnaissance. Load Forja in Burp for the dynamic phase. The toolkit from both feeds directly into manual testing.

## Security Model

- API keys are stored via Burp's preference persistence — never logged to stdout/stderr, never included in exports
- Traffic capture is scoped — only in-scope URLs are processed, static assets are filtered
- Cost confirmation — expensive LLM calls show estimated cost and require confirmation
- Rate limiting — exponential backoff for 429/529 responses from both APIs
- Token estimation — ~4 chars ≈ 1 token, displayed before every analysis

## License

MIT

## Contributing

Issues and PRs at [github.com/openbashok/forja](https://github.com/openbashok/forja).
