package restaurant;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public final class Main {
    private final RestaurantService service;
    private final Path frontend;

    private Main(RestaurantService service, Path frontend) {
        this.service = service;
        this.frontend = frontend;
    }

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        int taxRate = Integer.parseInt(System.getenv().getOrDefault("TAX_RATE", "5"));
        if (taxRate < 0 || taxRate > 100) {
            throw new IllegalArgumentException("TAX_RATE must be a whole number between 0 and 100.");
        }
        Path data = Path.of(System.getenv().getOrDefault("DATA_FILE", "backend/data/restaurant.tsv"));
        Path frontend = Path.of("frontend/dist").toAbsolutePath().normalize();
        Main app = new Main(new RestaurantService(new FileRestaurantStore(data), taxRate), frontend);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/api/", app::api);
        server.createContext("/", app::staticFile);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        System.out.println("Table & Till is running at http://127.0.0.1:" + port);
    }

    private void api(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                Object result = route(exchange);
                sendJson(exchange, 200, result);
            } catch (RestaurantException error) {
                sendJson(exchange, error.status(), Map.of("error", error.getMessage()));
            } catch (IllegalArgumentException | DateTimeParseException error) {
                sendJson(exchange, 400, Map.of("error", "Check the numbers and date you entered."));
            } catch (IOException error) {
                System.err.println(error.getMessage());
                sendJson(exchange, 500, Map.of("error", "Could not complete the operation. Refresh and try again."));
            } catch (RuntimeException error) {
                error.printStackTrace();
                sendJson(exchange, 500, Map.of("error", "An unexpected server error occurred."));
            }
        }
    }

    private Object route(HttpExchange exchange) throws IOException, RestaurantException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        if ("GET".equals(method)) {
            return switch (path) {
                case "/api/state" -> service.snapshot();
                case "/api/report" -> {
                    String date = parameters(exchange.getRequestURI().getRawQuery()).get("date");
                    yield service.report(date == null ? LocalDate.now(RestaurantService.BUSINESS_ZONE)
                            : LocalDate.parse(date));
                }
                default -> throw new RestaurantException(404, "Endpoint not found.");
            };
        }
        if (!"POST".equals(method)) {
            throw new RestaurantException(405, "Method not allowed.");
        }
        if (!"1".equals(exchange.getRequestHeaders().getFirst("X-Restaurant-Request"))) {
            throw new RestaurantException(403, "Missing restaurant request header.");
        }
        byte[] body = exchange.getRequestBody().readNBytes(8193);
        if (body.length > 8192) {
            throw new RestaurantException(413, "Request is too large.");
        }
        Map<String, String> form = parameters(new String(body, StandardCharsets.UTF_8));
        switch (path) {
            case "/api/assign" -> service.assign(number(form, "table"), form.get("guest"), number(form, "party"));
            case "/api/order" -> {
                int quantity = number(form, "quantity");
                boolean add = "true".equals(form.get("add"));
                if (quantity < (add ? 1 : 0) || quantity > 99) {
                    throw new RestaurantException(400, "Invalid order quantity.");
                }
                service.updateLine(number(form, "table"), number(form, "item"), quantity, add);
            }
            case "/api/continue" -> service.continueOrder(number(form, "table"), number(form, "bill"));
            case "/api/release" -> service.release(number(form, "table"));
            case "/api/checkout" -> {
                Map<String, Object> bill = service.checkout(number(form, "table"));
                return Map.of("bill", bill, "state", service.snapshot());
            }
            case "/api/menu/save" -> service.saveMenu(number(form, "id"), form.get("name"), form.get("category"),
                    form.get("price"), "true".equals(form.get("available")));
            case "/api/menu/delete" -> service.removeMenu(number(form, "id"));
            default -> throw new RestaurantException(404, "Endpoint not found.");
        }
        return service.snapshot();
    }

    private static Map<String, String> parameters(String raw) {
        Map<String, String> parameters = new HashMap<>();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                String[] fields = pair.split("=", 2);
                if (fields.length == 2) {
                    parameters.put(URLDecoder.decode(fields[0], StandardCharsets.UTF_8),
                            URLDecoder.decode(fields[1], StandardCharsets.UTF_8));
                }
            }
        }
        return parameters;
    }

    private static int number(Map<String, String> form, String key) {
        return Integer.parseInt(form.get(key));
    }

    private static void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        StringBuilder json = new StringBuilder();
        appendJson(json, value);
        send(exchange, status, "application/json; charset=utf-8", json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void appendJson(StringBuilder json, Object value) {
        if (value instanceof Map<?, ?> map) {
            json.append('{');
            String separator = "";
            for (var entry : map.entrySet()) {
                json.append(separator);
                appendJson(json, entry.getKey());
                json.append(':');
                appendJson(json, entry.getValue());
                separator = ",";
            }
            json.append('}');
        } else if (value instanceof Iterable<?> items) {
            json.append('[');
            String separator = "";
            for (Object item : items) {
                json.append(separator);
                appendJson(json, item);
                separator = ",";
            }
            json.append(']');
        } else if (value instanceof String string) {
            json.append('"');
            for (int i = 0; i < string.length(); i++) {
                char character = string.charAt(i);
                switch (character) {
                    case '"' -> json.append("\\\"");
                    case '\\' -> json.append("\\\\");
                    default -> {
                        if (character < 32) {
                            json.append(String.format("\\u%04x", (int) character));
                        } else {
                            json.append(character);
                        }
                    }
                }
            }
            json.append('"');
        } else {
            json.append(value);
        }
    }

    private void staticFile(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, Map.of("error", "Method not allowed."));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            Path file = frontend.resolve(path.equals("/") ? "index.html" : "." + path).normalize();
            if (!file.startsWith(frontend) || !Files.isRegularFile(file)) {
                sendJson(exchange, 404, Map.of("error", "Page unavailable. Build the frontend with npm run build in frontend."));
                return;
            }
            String name = file.getFileName().toString();
            String type = name.endsWith(".js") ? "text/javascript" : name.endsWith(".css")
                    ? "text/css" : "text/html";
            send(exchange, 200, type + "; charset=utf-8", Files.readAllBytes(file));
        }
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
