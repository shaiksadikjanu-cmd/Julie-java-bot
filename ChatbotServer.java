import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

public class ChatbotServer {

    public static void main(String[] args) throws IOException {
        int port = 8080;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        // 1. Serve the UI (index.html)
        server.createContext("/", new StaticHandler());

        // 2. The API Proxy Endpoint
        server.createContext("/api/chat", new ProxyHandler());

        server.setExecutor(null);
        System.out.println("✅ Julu Server started: http://localhost:" + port);
        server.start();
    }

    // Serves the HTML file
    static class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if ("/".equals(path) || "/index.html".equals(path)) {
                try {
                    String content = Files.readString(Paths.get("index.html"));
                    byte[] response = content.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
                    exchange.sendResponseHeaders(200, response.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response);
                    }
                } catch (IOException e) {
                    String err = "Error: index.html not found. Please create it.";
                    exchange.sendResponseHeaders(404, err.length());
                    try (OutputStream os = exchange.getResponseBody()) { os.write(err.getBytes()); }
                }
            } else {
                 exchange.sendResponseHeaders(404, -1);
            }
        }
    }

    // Proxies requests to Gemini to avoid CORS issues and handle networking in Java
    static class ProxyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // Only allow POST
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            try {
                // 1. Read Headers from Frontend
                List<String> keyHeader = exchange.getRequestHeaders().get("x-gemini-api-key");
                List<String> modelHeader = exchange.getRequestHeaders().get("x-gemini-model");

                if (keyHeader == null || keyHeader.isEmpty()) {
                    sendError(exchange, 400, "Missing API Key header");
                    return;
                }

                String apiKey = keyHeader.get(0);
                String model = (modelHeader != null && !modelHeader.isEmpty()) ? modelHeader.get(0) : "gemini-3-flash-preview";

                // 2. Read the JSON Body sent by Frontend
                InputStream is = exchange.getRequestBody();
                String requestBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);

                // 3. Construct Gemini URL
                String targetUrl = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey;

                // 4. Send to Google (Java 11+ HttpClient)
                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(targetUrl))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                // 5. Send Google's response back to Frontend
                byte[] responseBytes = response.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(response.statusCode(), responseBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseBytes);
                }

            } catch (Exception e) {
                e.printStackTrace();
                sendError(exchange, 500, "Internal Server Error: " + e.getMessage());
            }
        }

        private void sendError(HttpExchange exchange, int code, String message) throws IOException {
            byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
}