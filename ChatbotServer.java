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
        // Cloud Port Logic
        int port = 8080;
        if (System.getenv("PORT") != null) {
            port = Integer.parseInt(System.getenv("PORT"));
        }
        
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/", new StaticHandler());
        server.createContext("/api/chat", new ProxyHandler());

        server.setExecutor(null);
        System.out.println("✅ Julu Server started on port " + port);
        server.start();
    }

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
                    try (OutputStream os = exchange.getResponseBody()) { os.write(response); }
                } catch (IOException e) {
                    String err = "Error: index.html not found.";
                    exchange.sendResponseHeaders(404, err.length());
                    try (OutputStream os = exchange.getResponseBody()) { os.write(err.getBytes()); }
                }
            } else {
                 exchange.sendResponseHeaders(404, -1);
            }
        }
    }

    static class ProxyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            try {
                // --- NEW SECURITY LOGIC ---
                // 1. Check Server Environment Variable (Best Practice)
                String apiKey = System.getenv("GEMINI_API_KEY");

                // 2. If Server Env is empty, check Frontend Header (Fallback)
                if (apiKey == null || apiKey.isEmpty()) {
                    List<String> keyHeader = exchange.getRequestHeaders().get("x-gemini-api-key");
                    if (keyHeader != null && !keyHeader.isEmpty()) {
                        apiKey = keyHeader.get(0);
                    }
                }

                // 3. If BOTH are missing, reject.
                if (apiKey == null || apiKey.isEmpty()) {
                    sendError(exchange, 400, "Error: No API Key found. Set GEMINI_API_KEY in Render Env Vars OR enter it in the Settings UI.");
                    return;
                }

                List<String> modelHeader = exchange.getRequestHeaders().get("x-gemini-model");
                String model = (modelHeader != null && !modelHeader.isEmpty()) ? modelHeader.get(0) : "gemini-3-flash-preview";

                InputStream is = exchange.getRequestBody();
                String requestBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);

                String targetUrl = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey;

                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(targetUrl))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                
                byte[] responseBytes = response.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(response.statusCode(), responseBytes.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(responseBytes); }

            } catch (Exception e) {
                e.printStackTrace();
                sendError(exchange, 500, "Internal Server Error: " + e.getMessage());
            }
        }

        private void sendError(HttpExchange exchange, int code, String message) throws IOException {
            byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        }
    }
}