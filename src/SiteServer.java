import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.*;
import java.util.*;

public class SiteServer {
    private static final int PORT = 8080;
    private static final String WEB_DIR = "web/";
    private static final String LOG_FILE = "web/logs/access.log";
    private static final String MESSAGES_FILE = "web/logs/messages.log";
    private static final DateTimeFormatter LOG_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void main(String[] args) throws Exception {
        // Создаем папку для логов
        new File("web/logs").mkdirs();

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            // Логирование
            logRequest(exchange.getRemoteAddress(), method, path);

            try {
                if (path.equals("/contact") && "POST".equalsIgnoreCase(method)) {
                    handleContactForm(exchange);
                } else if (path.startsWith("/download")) {
                    handleDownload(exchange);
                } else if (path.startsWith("/images/") || path.startsWith("/css/")) {
                    serveStaticFile(exchange, WEB_DIR + path);
                } else {
                    String page = getPathForRoute(path);
                    if (page != null && new File(WEB_DIR + page).exists()) {
                        serveTemplatePage(exchange, page);
                    } else {
                        serveErrorPage(exchange, 404);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                try {
                    serveErrorPage(exchange, 500);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        });

        server.start();
        System.out.println("✅ Сервер запущен: http://localhost:" + PORT);
        System.out.println("📁 HTML файлы должны быть в папке 'web/'");
        System.out.println("📝 Логи сохраняются в: " + LOG_FILE);
        System.out.println("📩 Сообщения сохраняются в: " + MESSAGES_FILE);
    }

    private static String getPathForRoute(String path) {
        if (path.equals("/") || path.equals("/index.html")) return "index.html";
        if (path.equals("/about")) return "about.html";
        if (path.equals("/blog")) return "blog.html";
        if (path.equals("/apps")) return "apps.html";
        if (path.equals("/contacts")) return "contacts.html";
        if (path.equals("/resume")) return "resume.html";
        if (path.equals("/faq")) return "faq.html";
        if (path.equals("/gallery")) return "gallery.html";
        return null;
    }

    private static void serveTemplatePage(HttpExchange exchange, String page) throws IOException {
        // Защита от path traversal
        if (page.contains("..") || page.contains("\\")) {
            serveErrorPage(exchange, 403);
            return;
        }

        String header = readFile(WEB_DIR + "parts/header.html");
        String footer = readFile(WEB_DIR + "parts/footer.html");
        String content = readFile(WEB_DIR + page);

        // Определяем активную ссылку для меню
        String activePage = page.replace(".html", "");
        if (activePage.equals("index")) activePage = "/";
        else activePage = "/" + activePage;

        header = header.replace("{{ACTIVE_PAGE}}", activePage);

        String fullPage = header + content + footer;

        byte[] bytes = fullPage.getBytes("UTF-8");
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void serveStaticFile(HttpExchange exchange, String filePath) throws IOException {
        // Защита от path traversal
        if (filePath.contains("..") || filePath.contains("\\")) {
            serveErrorPage(exchange, 403);
            return;
        }

        File file = new File(filePath);
        if (!file.exists()) {
            serveErrorPage(exchange, 404);
            return;
        }

        byte[] content = Files.readAllBytes(file.toPath());
        String contentType = getContentType(filePath);

        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, content.length);
        exchange.getResponseBody().write(content);
        exchange.close();
    }

    private static void serveErrorPage(HttpExchange exchange, int statusCode) throws IOException {
        if (statusCode == 404) {
            String errorPage = readFile(WEB_DIR + "errors/404.html");
            byte[] bytes = errorPage.getBytes("UTF-8");
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(404, bytes.length);
            exchange.getResponseBody().write(bytes);
        } else {
            String error = "<h1>" + statusCode + " Error</h1>";
            byte[] errBytes = error.getBytes("UTF-8");
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(statusCode, errBytes.length);
            exchange.getResponseBody().write(errBytes);
        }
        exchange.close();
    }

    private static String getContentType(String filePath) {
        if (filePath.endsWith(".html") || filePath.endsWith(".htm")) return "text/html; charset=UTF-8";
        if (filePath.endsWith(".css")) return "text/css";
        if (filePath.endsWith(".js")) return "application/javascript";
        if (filePath.endsWith(".jpg") || filePath.endsWith(".jpeg")) return "image/jpeg";
        if (filePath.endsWith(".png")) return "image/png";
        if (filePath.endsWith(".gif")) return "image/gif";
        if (filePath.endsWith(".svg")) return "image/svg+xml";
        if (filePath.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    private static void handleContactForm(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        Scanner scanner = new Scanner(is, "UTF-8").useDelimiter("\\A");
        String body = scanner.hasNext() ? scanner.next() : "";
        scanner.close();

        // Парсинг параметров
        Map<String, String> params = new HashMap<>();
        for (String pair : body.split("&")) {
            if (pair.contains("=")) {
                String[] kv = pair.split("=", 2);
                params.put(URLDecoder.decode(kv[0], "UTF-8"), URLDecoder.decode(kv[1], "UTF-8"));
            }
        }

        String name = params.getOrDefault("name", "Аноним");
        String email = params.getOrDefault("email", "Не указан");
        String subject = params.getOrDefault("subject", "Без темы");
        String message = params.getOrDefault("message", "");

        // Сохранение в файл
        saveMessageToFile(name, email, subject, message);

        // Логирование в консоль
        System.out.println("\n📩 НОВОЕ СООБЩЕНИЕ:");
        System.out.println("От: " + name + " (" + email + ")");
        System.out.println("Тема: " + subject);
        System.out.println("Сообщение: " + message);
        System.out.println("---\n");

        // Страница успеха
        String html = "<!DOCTYPE html><html><head><meta charset='UTF-8'><title>Спасибо!</title>" +
                "<style>body{font-family:sans-serif;display:flex;justify-content:center;align-items:center;height:100vh;margin:0;background:linear-gradient(135deg,#0f172a,#1e293b);color:#fff;}" +
                ".box{background:rgba(255,255,255,0.05);padding:3rem;border-radius:20px;text-align:center;border:1px solid rgba(255,255,255,0.1);animation:fadeIn 0.5s ease;}" +
                "@keyframes fadeIn{from{opacity:0;transform:translateY(20px);}to{opacity:1;transform:translateY(0);}}" +
                "a{display:inline-block;margin-top:1.5rem;padding:1rem 2rem;background:#3b82f6;color:#fff;border-radius:50px;font-weight:600;transition:transform 0.2s;}" +
                "a:hover{transform:translateY(-2px);}</style></head><body>" +
                "<div class='box'><h1 style='font-size:3rem;margin-bottom:1rem;'>✅</h1>" +
                "<h2>Спасибо, " + escapeHtml(name) + "!</h2>" +
                "<p style='color:#94a3b8;margin:1rem 0;'>Ваше сообщение получено. Я отвечу вам в ближайшее время.</p>" +
                "<a href='/'>← На главную</a></div></body></html>";

        byte[] bytes = html.getBytes("UTF-8");
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void saveMessageToFile(String name, String email, String subject, String message) {
        try {
            String timestamp = LocalDateTime.now().format(LOG_FORMATTER);
            String logEntry = String.format(
                    "=== НОВОЕ СООБЩЕНИЕ ===%n" +
                            "Дата: %s%n" +
                            "От: %s <%s>%n" +
                            "Тема: %s%n" +
                            "Сообщение:%n%s%n" +
                            "========================%n%n",
                    timestamp, name, email, subject, message
            );

            Files.write(Paths.get(MESSAGES_FILE),
                    logEntry.getBytes("UTF-8"),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);

            System.out.println("💾 Сообщение сохранено в " + MESSAGES_FILE);
        } catch (IOException e) {
            System.err.println("❌ Не удалось сохранить сообщение: " + e.getMessage());
        }
    }

    private static void handleDownload(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String app = "Приложение";
        if (query != null && query.startsWith("app=")) {
            app = URLDecoder.decode(query.substring(4), "UTF-8");
        }

        String html = "<!DOCTYPE html><html><head><meta charset='UTF-8'><title>Загрузка</title>" +
                "<style>body{font-family:sans-serif;display:flex;justify-content:center;align-items:center;height:100vh;margin:0;background:linear-gradient(135deg,#0f172a,#1e293b);color:#fff;}" +
                ".box{background:rgba(255,255,255,0.05);padding:3rem;border-radius:20px;text-align:center;border:1px solid rgba(255,255,255,0.1);}" +
                "a{color:#3b82f6;margin-top:1.5rem;display:inline-block;font-weight:600;}</style></head><body>" +
                "<div class='box'><h2 style='font-size:2rem;margin-bottom:1rem;'>⬇️</h2>" +
                "<h3>Загрузка: " + escapeHtml(app) + "</h3>" +
                "<p style='color:#94a3b8;margin:1rem 0;'>Файл готовится...</p>" +
                "<a href='/apps'>← К приложениям</a></div></body></html>";

        byte[] bytes = html.getBytes("UTF-8");
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String readFile(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)), "UTF-8");
        } catch (IOException e) {
            return "<!-- File not found: " + path + " -->";
        }
    }

    private static void logRequest(InetSocketAddress address, String method, String path) {
        try {
            String timestamp = LocalDateTime.now().format(LOG_FORMATTER);
            String logEntry = String.format("[%s] %s - %s %s%n",
                    timestamp, address.getAddress().getHostAddress(), method, path);
            Files.write(Paths.get(LOG_FILE), logEntry.getBytes("UTF-8"),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Failed to write log: " + e.getMessage());
        }
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}