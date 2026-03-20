package com.openbash.forja.traffic;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects encryption, encoding, and cryptographic patterns in HTTP traffic.
 * Identifies:
 * - Base64-wrapped payloads (especially JSON bodies wrapped in base64)
 * - High-entropy fields suggesting encryption (AES, RSA output)
 * - Hex-encoded blobs
 * - Custom crypto headers (X-Encrypted, X-Signature, X-Nonce, etc.)
 * - Known crypto library patterns in JavaScript
 * - HMAC signatures in query params or headers
 */
public class CryptoDetector {

    // Base64 with minimum length — likely encrypted/encoded payload, not just a small token
    private static final Pattern BASE64_PAYLOAD = Pattern.compile(
            "\"[^\"]+\"\\s*:\\s*\"([A-Za-z0-9+/]{64,}={0,2})\"");

    // Standalone base64 blob (request/response body that's entirely base64)
    private static final Pattern FULL_BASE64_BODY = Pattern.compile(
            "^[A-Za-z0-9+/\\s]{100,}={0,2}$");

    // Hex-encoded blob (min 32 hex chars = 16 bytes)
    private static final Pattern HEX_BLOB = Pattern.compile(
            "\"[^\"]+\"\\s*:\\s*\"([0-9a-fA-F]{32,})\"");

    // Crypto-related HTTP headers
    private static final Set<String> CRYPTO_HEADERS = Set.of(
            "x-encrypted", "x-encryption", "x-cipher",
            "x-signature", "x-sig", "x-hmac",
            "x-nonce", "x-iv", "x-initialization-vector",
            "x-request-signature", "x-response-signature",
            "x-digest", "x-hash", "x-checksum",
            "x-api-signature", "x-content-signature",
            "digest", "signature"
    );

    // Crypto-related JSON field names
    private static final Pattern CRYPTO_FIELD_NAMES = Pattern.compile(
            "\"(encrypted|ciphertext|cipher_text|encryptedData|encrypted_data|" +
            "signature|sig|hmac|hash|digest|checksum|" +
            "nonce|iv|initialization_vector|salt|" +
            "publicKey|public_key|privateKey|private_key|" +
            "aesKey|aes_key|encKey|enc_key|" +
            "token|encryptedToken|encrypted_token|" +
            "payload|encryptedPayload|encrypted_payload)\"\\s*:", Pattern.CASE_INSENSITIVE);

    // JS crypto patterns (in source code)
    private static final Pattern JS_CRYPTO_USAGE = Pattern.compile(
            "(CryptoJS|crypto\\.subtle|forge\\.|sjcl\\.|aesjs|" +
            "window\\.crypto|SubtleCrypto|" +
            "AES\\.encrypt|AES\\.decrypt|" +
            "RSA\\.encrypt|RSA\\.decrypt|" +
            "createCipheriv|createDecipheriv|" +
            "pbkdf2|scrypt|bcrypt|argon2|" +
            "hmac|HMAC|createHmac|" +
            "\\batob\\b.*\\bJSON\\.parse|JSON\\.stringify.*\\bbtoa\\b)", Pattern.CASE_INSENSITIVE);

    /**
     * Analyze an HTTP request/response pair for crypto patterns.
     * Returns a list of findings with descriptions.
     */
    public List<CryptoFinding> analyze(String url, String requestHeaders, String requestBody,
                                        String responseHeaders, String responseBody) {
        List<CryptoFinding> findings = new ArrayList<>();

        // Check headers for crypto indicators
        if (requestHeaders != null) {
            detectCryptoHeaders(requestHeaders, "request", url, findings);
        }
        if (responseHeaders != null) {
            detectCryptoHeaders(responseHeaders, "response", url, findings);
        }

        // Check request body
        if (requestBody != null && !requestBody.isBlank()) {
            detectCryptoInBody(requestBody, "request", url, findings);
        }

        // Check response body
        if (responseBody != null && !responseBody.isBlank()) {
            detectCryptoInBody(responseBody, "response", url, findings);
        }

        return findings;
    }

    /**
     * Analyze JavaScript source code for crypto usage.
     */
    public List<CryptoFinding> analyzeJavaScript(String url, String jsSource) {
        List<CryptoFinding> findings = new ArrayList<>();
        if (jsSource == null || jsSource.isBlank()) return findings;

        Matcher matcher = JS_CRYPTO_USAGE.matcher(jsSource);
        Set<String> seen = new HashSet<>();
        while (matcher.find()) {
            String match = matcher.group(1);
            if (seen.add(match.toLowerCase())) {
                // Extract surrounding context (100 chars before/after)
                int start = Math.max(0, matcher.start() - 100);
                int end = Math.min(jsSource.length(), matcher.end() + 100);
                String context = jsSource.substring(start, end);

                findings.add(new CryptoFinding(
                        CryptoFinding.Type.JS_CRYPTO_LIBRARY,
                        "JavaScript crypto usage: " + match,
                        url,
                        context
                ));
            }
        }

        return findings;
    }

    private void detectCryptoHeaders(String headers, String location, String url,
                                      List<CryptoFinding> findings) {
        String headersLower = headers.toLowerCase();
        for (String cryptoHeader : CRYPTO_HEADERS) {
            if (headersLower.contains(cryptoHeader + ":")) {
                // Extract the header value
                int idx = headersLower.indexOf(cryptoHeader + ":");
                int valueStart = idx + cryptoHeader.length() + 1;
                int lineEnd = headers.indexOf('\n', valueStart);
                String value = lineEnd > 0
                        ? headers.substring(valueStart, lineEnd).trim()
                        : headers.substring(valueStart).trim();

                findings.add(new CryptoFinding(
                        CryptoFinding.Type.CRYPTO_HEADER,
                        "Crypto header in " + location + ": " + cryptoHeader,
                        url,
                        cryptoHeader + ": " + value
                ));
            }
        }
    }

    private void detectCryptoInBody(String body, String location, String url,
                                     List<CryptoFinding> findings) {
        // Check if entire body is base64 (encrypted payload)
        String trimmed = body.trim();
        if (FULL_BASE64_BODY.matcher(trimmed).matches() && trimmed.length() > 100) {
            findings.add(new CryptoFinding(
                    CryptoFinding.Type.BASE64_BODY,
                    "Entire " + location + " body appears base64-encoded (" + trimmed.length() + " chars)",
                    url,
                    trimmed.substring(0, Math.min(200, trimmed.length())) + "..."
            ));
        }

        // Check for base64 values in JSON fields
        Matcher b64 = BASE64_PAYLOAD.matcher(body);
        while (b64.find()) {
            String value = b64.group(1);
            if (isHighEntropy(value)) {
                int fieldStart = body.lastIndexOf('"', b64.start(1) - 3);
                String fieldContext = body.substring(Math.max(0, fieldStart), Math.min(body.length(), b64.end() + 1));
                findings.add(new CryptoFinding(
                        CryptoFinding.Type.ENCRYPTED_FIELD,
                        "High-entropy base64 field in " + location + " (" + value.length() + " chars)",
                        url,
                        fieldContext
                ));
            }
        }

        // Check for hex-encoded blobs
        Matcher hex = HEX_BLOB.matcher(body);
        while (hex.find()) {
            String value = hex.group(1);
            if (value.length() >= 64) { // At least 32 bytes
                int fieldStart = body.lastIndexOf('"', hex.start(1) - 3);
                String fieldContext = body.substring(Math.max(0, fieldStart), Math.min(body.length(), hex.end() + 1));
                findings.add(new CryptoFinding(
                        CryptoFinding.Type.HEX_ENCODED,
                        "Hex-encoded blob in " + location + " (" + value.length() / 2 + " bytes)",
                        url,
                        fieldContext
                ));
            }
        }

        // Check for crypto-related field names
        Matcher fieldNames = CRYPTO_FIELD_NAMES.matcher(body);
        Set<String> seenFields = new HashSet<>();
        while (fieldNames.find()) {
            String fieldName = fieldNames.group(1);
            if (seenFields.add(fieldName.toLowerCase())) {
                // Get surrounding context
                int start = Math.max(0, fieldNames.start() - 10);
                int end = Math.min(body.length(), fieldNames.end() + 200);
                String context = body.substring(start, end);
                int contextEnd = context.indexOf('\n');
                if (contextEnd < 0) contextEnd = Math.min(context.length(), 250);

                findings.add(new CryptoFinding(
                        CryptoFinding.Type.CRYPTO_FIELD_NAME,
                        "Crypto-related field '" + fieldName + "' in " + location,
                        url,
                        context.substring(0, contextEnd)
                ));
            }
        }
    }

    /**
     * Calculate Shannon entropy to distinguish encrypted data from regular base64.
     * Encrypted data has entropy close to 6.0 (per character from base64 alphabet).
     * Regular text encoded as base64 has lower entropy (~4.5-5.5).
     */
    private boolean isHighEntropy(String data) {
        if (data.length() < 32) return false;

        Map<Character, Integer> freq = new HashMap<>();
        for (char c : data.toCharArray()) {
            freq.merge(c, 1, Integer::sum);
        }

        double entropy = 0.0;
        double len = data.length();
        for (int count : freq.values()) {
            double p = count / len;
            entropy -= p * (Math.log(p) / Math.log(2));
        }

        // Base64 alphabet has 64 chars → max entropy ~6.0
        // Encrypted data typically > 5.5, regular encoded text < 5.0
        return entropy > 5.2;
    }

    /**
     * Represents a detected cryptographic pattern.
     */
    public static class CryptoFinding {
        public enum Type {
            CRYPTO_HEADER,
            BASE64_BODY,
            ENCRYPTED_FIELD,
            HEX_ENCODED,
            CRYPTO_FIELD_NAME,
            JS_CRYPTO_LIBRARY
        }

        private final Type type;
        private final String description;
        private final String url;
        private final String sample;

        public CryptoFinding(Type type, String description, String url, String sample) {
            this.type = type;
            this.description = description;
            this.url = url;
            this.sample = sample;
        }

        public Type getType() { return type; }
        public String getDescription() { return description; }
        public String getUrl() { return url; }
        public String getSample() { return sample; }

        @Override
        public String toString() {
            return "[" + type + "] " + description + " @ " + url;
        }
    }
}
