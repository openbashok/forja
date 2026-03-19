package com.openbash.forja.integration;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.proxy.http.*;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Intercepts HTML responses passing through the proxy and injects active JS scripts.
 * Scripts are added/removed via activate()/deactivate() from the ToolkitTab.
 */
public class ScriptInjector implements ProxyResponseHandler {

    private final MontoyaApi api;
    private final ConcurrentHashMap<String, String> activeScripts = new ConcurrentHashMap<>();

    public ScriptInjector(MontoyaApi api) {
        this.api = api;
    }

    public void activate(String name, String jsCode) {
        activeScripts.put(name, jsCode);
        api.logging().logToOutput("Forja: Injecting script '" + name + "' into proxy responses.");
    }

    public void deactivate(String name) {
        activeScripts.remove(name);
        api.logging().logToOutput("Forja: Stopped injecting script '" + name + "'.");
    }

    public boolean isActive(String name) {
        return activeScripts.containsKey(name);
    }

    public Set<String> getActiveScriptNames() {
        return activeScripts.keySet();
    }

    public int activeCount() {
        return activeScripts.size();
    }

    @Override
    public ProxyResponseReceivedAction handleResponseReceived(InterceptedResponse response) {
        return ProxyResponseReceivedAction.continueWith(response);
    }

    @Override
    public ProxyResponseToBeSentAction handleResponseToBeSent(InterceptedResponse response) {
        if (activeScripts.isEmpty()) {
            return ProxyResponseToBeSentAction.continueWith(response);
        }

        // Only inject into in-scope HTML responses
        try {
            String url = response.initiatingRequest().url();
            if (!api.scope().isInScope(url)) {
                return ProxyResponseToBeSentAction.continueWith(response);
            }

            String contentType = response.headerValue("Content-Type");
            if (contentType == null || !contentType.contains("text/html")) {
                return ProxyResponseToBeSentAction.continueWith(response);
            }

            String body = response.bodyToString();
            if (body == null || body.isEmpty()) {
                return ProxyResponseToBeSentAction.continueWith(response);
            }

            // Build the injection payload
            StringBuilder injection = new StringBuilder();
            injection.append("\n<!-- Forja Script Injection -->\n");
            for (var entry : activeScripts.entrySet()) {
                injection.append("<script data-forja=\"").append(escapeAttr(entry.getKey())).append("\">\n");
                injection.append("/* Forja: ").append(entry.getKey()).append(" */\n");
                injection.append("try {\n");
                injection.append(entry.getValue()).append("\n");
                injection.append("} catch(e) { console.error('[Forja] Error in ").append(escapeJs(entry.getKey())).append(":', e); }\n");
                injection.append("</script>\n");
            }

            // Inject before </body> or </html>, or append at end
            String modified;
            String lowerBody = body.toLowerCase();
            int insertPos = lowerBody.lastIndexOf("</body>");
            if (insertPos < 0) insertPos = lowerBody.lastIndexOf("</html>");
            if (insertPos >= 0) {
                modified = body.substring(0, insertPos) + injection + body.substring(insertPos);
            } else {
                modified = body + injection;
            }

            return ProxyResponseToBeSentAction.continueWith(
                    response.withBody(modified)
            );
        } catch (Exception e) {
            api.logging().logToError("Forja ScriptInjector error: " + e.getMessage());
            return ProxyResponseToBeSentAction.continueWith(response);
        }
    }

    private static String escapeAttr(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }

    private static String escapeJs(String s) {
        return s.replace("'", "\\'").replace("\\", "\\\\");
    }
}
