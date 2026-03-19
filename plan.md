# Forja — Plan de Proyecto

**Forja** — Extension de Burp Suite que observa el trafico del proxy dentro del scope, lo analiza con IA (Claude/OpenAI), y forja herramientas de analisis custom basadas en el trafico real observado — scripts JS de instrumentacion, extensiones de Burp especializadas, y PoCs ajustados al comportamiento dinamico de la aplicacion.

**Mismo concepto que WebAudit (tool that generates tools) pero alimentado con trafico real en vez de codigo estatico.** WebAudit analiza el codigo fuente y genera herramientas; Forja observa el trafico vivo y forja herramientas mas precisas. Juntos cubren estatico + dinamico.

Un proyecto de [OpenBash](https://www.openbash.com).

---

## Arquitectura General

```
┌──────────────────────────────────────────────────────┐
│  Forja — Burp Suite Extension (Java — Montoya API)   │
│                                                      │
│  ┌─ Tab: Config ──────────────────────────────────┐  │
│  │  Provider: [Anthropic ▼] [OpenAI ▼] [Custom ▼] │  │
│  │  API Key: [sk-ant-...]                          │  │
│  │  Model: [claude-sonnet-4-6 ▼]                   │  │
│  │  Budget: [5.0 USD]  Max tokens: [128000]        │  │
│  └─────────────────────────────────────────────────┘  │
│                                                      │
│  ┌─ Tab: Traffic Intelligence ─────────────────────┐  │
│  │  Captura en tiempo real (IProxyListener)        │  │
│  │  Solo trafico dentro del scope de Burp          │  │
│  │  Clasifica y mapea la aplicacion                │  │
│  └─────────────────────────────────────────────────┘  │
│                                                      │
│  ┌─ Tab: Analysis ─────────────────────────────────┐  │
│  │  Boton "Analyze" → envia contexto a la IA       │  │
│  │  Muestra hallazgos y recomendaciones            │  │
│  └─────────────────────────────────────────────────┘  │
│                                                      │
│  ┌─ Tab: Generated Toolkit ────────────────────────┐  │
│  │  Lista de herramientas generadas                │  │
│  │  Preview de codigo                              │  │
│  │  Botones: Copy / Save / Load Extension          │  │
│  └─────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────┘
```

---

## Decision Tecnica: Java con Montoya API

**NO usar Jython.** Razones:
- Jython = Python 2.7, sin soporte moderno, limitaciones de librerias
- Montoya API es la API nueva y oficial de Burp (reemplaza a la legacy IBurpExtender)
- Java tiene HTTP clients nativos para llamar APIs REST (HttpURLConnection, OkHttp)
- Mejor performance para procesar trafico pesado
- Mejor soporte de UI (Swing nativo)
- Burp recomienda Montoya para extensiones nuevas

**Build system:** Gradle con Java 17+
**Dependencias:** OkHttp (HTTP client), Gson (JSON), Montoya API

---

## Fases de Desarrollo

### Fase 1: Skeleton + Config Tab (MVP base)

**Objetivo:** Extension que carga en Burp, con tab de configuracion funcional.

1. Setup del proyecto Gradle con Montoya API
2. Clase principal que implementa `BurpExtension`
3. Tab "Config" con:
   - Dropdown provider: Anthropic, OpenAI, Custom endpoint
   - Campo API key (password field, no visible)
   - Dropdown modelo (se popula segun provider)
   - Campo budget USD
   - Boton "Test Connection" que valida la API key
   - Persistencia: guardar config con `persistence()` de Montoya
4. Capa de abstraccion LLM:
   - Interface `LLMProvider` con metodo `chat(messages, model, maxTokens) -> response`
   - `AnthropicProvider` — llama a `POST https://api.anthropic.com/v1/messages`
   - `OpenAIProvider` — llama a `POST https://api.openai.com/v1/chat/completions`
   - `CustomProvider` — endpoint configurable, formato OpenAI-compatible
   - Todos usan OkHttp, streaming opcional
   - Manejo de rate limits y errores

**Entregable:** Extension .jar que carga en Burp, configuras la API key, y "Test Connection" confirma que funciona.

---

### Fase 2: Traffic Capture + Intelligence

**Objetivo:** Capturar trafico del scope y construir un modelo de la aplicacion.

1. `TrafficCollector` — implementa `ProxyRequestHandler` y `ProxyResponseHandler` de Montoya:
   - Filtra SOLO requests/responses dentro del scope de Burp
   - Ignora assets estaticos (imagenes, fonts, CSS) a menos que sean interesantes
   - Almacena en memoria con limite configurable (ej: ultimos 500 request/response pairs)

2. `AppModel` — estructura que se construye del trafico observado:
   ```
   AppModel:
     domain: "app.example.com"
     endpoints: Map<String, EndpointInfo>
       EndpointInfo:
         method: GET/POST/PUT/DELETE
         path: "/api/users/{id}"
         params: [query params, body params, path params]
         headers_sent: [custom headers observados]
         content_types: [request content-type, response content-type]
         auth_pattern: "Bearer token in Authorization header"
         sample_request: (un request representativo)
         sample_response: (un response representativo)
         response_codes: [200, 401, 403] (codigos observados)
         times_seen: 47
     auth_info:
       type: "bearer" | "cookie" | "custom_header" | "api_key"
       header_name: "Authorization"
       token_format: "JWT (HS256)" | "opaque" | "API key"
       token_sample: "eyJ..." (redactado parcialmente)
       storage: "localStorage key 'auth_token'" (si se detecta)
     cookies: [nombres y atributos observados]
     cors_config: {origins observados, headers permitidos}
     crypto_hints: [si se detectan patrones de cifrado en bodies]
     tech_stack: [detectado de headers: X-Powered-By, Server, etc.]
     interesting_patterns:
       - "JWT sin verificar en cliente"
       - "IDs secuenciales en /api/users/{id}"
       - "Endpoints que responden 200 sin auth"
       - "Parametros reflejados en response"
   ```

3. Tab "Traffic Intelligence" con:
   - Tabla de endpoints descubiertos (method, path, params, auth, veces visto)
   - Panel lateral con detalle del endpoint seleccionado (request/response de ejemplo)
   - Indicadores: total requests capturados, endpoints unicos, patrones detectados
   - Boton "Clear" para resetear
   - Filtros por metodo, path, status code
   - Auto-refresh en tiempo real

**Entregable:** Extension que mientras navegas muestra un mapa creciente de la aplicacion.

---

### Fase 3: AI Analysis

**Objetivo:** Enviar el AppModel a la IA y obtener un analisis de seguridad.

1. `ContextBuilder` — prepara el contexto para la IA:
   - Serializa el AppModel a un formato compacto (no mandar cada byte de trafico)
   - Incluye: endpoints, auth patterns, headers interesantes, samples representativos
   - Si el contexto es muy grande, prioriza: endpoints con auth, endpoints con params, patrones inusuales
   - Limite configurable de tokens de input

2. System prompt para el analisis:
   - "Sos un analista de seguridad. Te doy el mapa de trafico observado de una aplicacion web..."
   - Pedir: hallazgos de seguridad, patrones interesantes, endpoints prioritarios para testing
   - Pedir que clasifique por severidad y que de evidencia del trafico
   - Pedir que identifique que herramientas serian utiles para el analista

3. Tab "Analysis" con:
   - Boton "Analyze" que manda el contexto a la IA
   - Progress bar / spinner
   - Panel de resultados con hallazgos, clasificados por severidad
   - Cada hallazgo con: titulo, severidad, evidencia (request/response), recomendacion
   - Boton "Re-analyze" (para despues de capturar mas trafico)
   - Estimacion de costo antes de enviar

**Entregable:** Boton que analiza el trafico capturado y muestra hallazgos de seguridad.

---

### Fase 4: Toolkit Generation

**Objetivo:** Generar herramientas custom basadas en el trafico analizado.

1. `ToolkitGenerator` — orquesta la generacion de herramientas:
   - Recibe el AppModel + los hallazgos del analisis
   - Para cada tipo de herramienta, hace un request a la IA con contexto especifico
   - Guarda los artefactos generados en memoria y en disco

2. Herramientas a generar:

   **a) JS Instrumentation Scripts** (inyectables en consola del browser):
   - Sniffer custom: hookea las APIs especificas observadas en el trafico (no generico)
   - PoCs para cada hallazgo: basados en requests reales observados, no teoricos
   - Token manipulator: si hay JWT, genera script para decodear/modificar/re-firmar
   - Request replayer: script que replica requests interesantes con modificaciones

   **b) Burp Extensions especializadas** (Java o Jython):
   - **Auth Tester**: similar al de WebAudit pero con requests REALES como base (no construidos desde codigo)
   - **Parameter Fuzzer**: extension que toma los parametros observados y fuzea con payloads contextuales
   - **IDOR Scanner**: si se detectaron IDs secuenciales, extension que automatiza el testeo
   - **Custom Scanner Check**: regla de scan custom para el patron especifico detectado

   **c) Reportes**:
   - Resumen de inteligencia recopilada
   - Mapa de endpoints con clasificacion de riesgo
   - Hallazgos con evidencia de trafico real

3. Tab "Generated Toolkit" con:
   - Lista de herramientas generadas (tipo, nombre, descripcion)
   - Preview del codigo al seleccionar
   - Botones por herramienta:
     - "Copy to Clipboard"
     - "Save to File"
     - "Load in Burp" (para extensiones .jar/.py generadas)
   - Boton "Regenerate" por herramienta individual
   - Boton "Generate All" para regenerar todo

**Entregable:** Set completo de herramientas generadas a partir del trafico real.

---

### Fase 5: Context Menu + Integration

**Objetivo:** Integracion profunda con el workflow de Burp.

1. Context menu (click derecho en cualquier request):
   - "Forja: Analyze" → analiza ese request/response especifico
   - "Forja: Generate PoC" → genera JS PoC para ese request
   - "Forja: Add to Intelligence" → agrega manualmente un request al AppModel

2. Scanner integration:
   - Registrar un `ScanCheck` custom que usa la IA para evaluar cada request/response
   - Modo pasivo: analiza responses en busca de patrones (no invasivo)
   - Hallazgos aparecen en el Issue Activity de Burp como issues nativos

3. Intruder integration (opcional):
   - Generar payloads contextuales basados en el trafico analizado
   - Payload generator que usa la IA para crear payloads especificos

---

## Estructura del Proyecto

```
forja/
├── build.gradle.kts
├── settings.gradle.kts
├── src/main/java/com/openbash/forja/
│   ├── ForjaExtension.java           # Entry point (BurpExtension)
│   ├── ui/
│   │   ├── ConfigTab.java            # Tab de configuracion
│   │   ├── TrafficTab.java           # Tab de Traffic Intelligence
│   │   ├── AnalysisTab.java          # Tab de Analysis
│   │   ├── ToolkitTab.java           # Tab de Generated Toolkit
│   │   └── UIConstants.java          # Dark theme, colores, fonts
│   ├── traffic/
│   │   ├── TrafficCollector.java      # ProxyRequestHandler/ResponseHandler
│   │   ├── AppModel.java             # Modelo de la aplicacion
│   │   ├── EndpointInfo.java          # Info de cada endpoint
│   │   └── PatternDetector.java       # Detecta JWT, IDs seq, etc.
│   ├── llm/
│   │   ├── LLMProvider.java           # Interface
│   │   ├── AnthropicProvider.java     # Claude API
│   │   ├── OpenAIProvider.java        # OpenAI API
│   │   ├── CustomProvider.java        # Endpoint custom
│   │   └── ContextBuilder.java        # Prepara contexto para la IA
│   ├── analysis/
│   │   ├── SecurityAnalyzer.java      # Orquesta el analisis
│   │   ├── Finding.java               # Modelo de hallazgo
│   │   └── AnalysisPrompts.java       # System prompts
│   ├── toolkit/
│   │   ├── ToolkitGenerator.java      # Orquesta generacion
│   │   ├── JSGenerator.java           # Genera scripts JS
│   │   ├── BurpPluginGenerator.java   # Genera extensiones Burp
│   │   └── GeneratedTool.java         # Modelo de herramienta generada
│   └── util/
│       ├── HttpUtil.java              # Helpers HTTP
│       └── TokenEstimator.java        # Estima tokens/costo
├── src/main/resources/
│   └── prompts/
│       ├── analysis_system.txt        # System prompt para analisis
│       ├── js_generator.txt           # Prompt para generar JS
│       └── burp_generator.txt         # Prompt para generar extensiones
└── README.md
```

---

## Stack Tecnico

| Componente | Tecnologia |
|-----------|-----------|
| Lenguaje | Java 17+ |
| API de Burp | Montoya API (nueva) |
| HTTP Client | OkHttp 4.x |
| JSON | Gson |
| Build | Gradle (Kotlin DSL) |
| UI | Swing (nativo de Burp) |
| LLM | Anthropic API / OpenAI API / Custom |

---

## Notas para el Agente que Implemente

1. **Montoya API docs**: https://portswigger.github.io/burp-extensions-montoya-api/javadoc/ — es la referencia principal. Buscar `BurpExtension`, `ProxyRequestHandler`, `ProxyResponseHandler`, `UserInterface`, `persistence()`.

2. **El .jar resultante se carga en Burp > Extensions > Add > Extension Type: Java**. No necesita Jython.

3. **Thread safety**: Burp es multi-threaded. El TrafficCollector recibe callbacks desde threads del proxy. Usar `ConcurrentHashMap`, `synchronized`, o colas thread-safe para el AppModel.

4. **UI en EDT**: Toda actualizacion de UI debe hacerse en el Event Dispatch Thread (`SwingUtilities.invokeLater`).

5. **Persistencia**: Montoya API tiene `persistence().preferences()` para guardar config (API key, modelo, etc.) entre sesiones de Burp.

6. **Scope check**: Usar `api.scope().isInScope(url)` para filtrar solo trafico relevante.

7. **Streaming de la IA**: Para respuestas largas, implementar streaming (SSE para Anthropic, SSE para OpenAI) y mostrar progreso en la UI.

8. **Token estimation**: Antes de enviar, estimar tokens (~4 chars = 1 token) y mostrar costo estimado al usuario. No mandar sin confirmacion si el costo es alto.

9. **Dark theme**: Burp usa dark theme por default. Respetar los colores del look-and-feel de Swing, no hardcodear colores blancos.

10. **Rate limits**: Implementar retry con backoff exponencial para 429/529 de ambas APIs.

11. **Relacion con WebAudit**: Si en el directorio del proyecto hay un `AGENT.md` de WebAudit, Forja podria importar esa inteligencia estatica como base para enriquecer el analisis dinamico. Considerar un boton "Import WebAudit Intelligence" en la tab Config. WebAudit = estatico, Forja = dinamico.

12. **Seguridad**: La API key se guarda en las preferences de Burp (que estan en disco). Advertir al usuario. No loguear la API key en stdout/stderr. No incluirla en exports.
