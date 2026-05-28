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
    private static final String ACCESS_LOG = "web/logs/access.log";
    private static final String MESSAGE_LOG = "web/logs/messages.log";
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void main(String[] args) throws Exception {
        new File("web/logs").mkdirs();
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", new SiteHandler());
        server.start();
        System.out.println("✅ SiteServer запущен: http://0.0.0.0:" + PORT);
        System.out.println("📁 Файлы ожидаются в папке: " + WEB_DIR);
    }

    static class SiteHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            logRequest(ex, method, path);

            try {
                // 1. Форма контактов
                if (path.equals("/contact") && "POST".equals(method)) {
                    handleContact(ex);
                }
                // 2. СТАТИЧЕСКИЕ ФАЙЛЫ (резюме, картинки, стили)
                else if (path.startsWith("/files/") || path.startsWith("/images/") || path.startsWith("/css/")) {
                    serveStatic(ex);
                }
                // 3. Страница загрузки приложений
                else if (path.startsWith("/download")) {
                    handleDownload(ex);
                }
                // 4. Основные страницы сайта
                else {
                    String page = resolvePage(path);
                    if (page != null) serveTemplate(ex, page);
                    else serveError(ex, 404);
                }
            } catch (Exception e) {
                e.printStackTrace();
                try { serveError(ex, 500); } catch (Exception ignored) {}
            }
        }

        private String resolvePage(String path) {
            if (path.equals("/") || path.equals("/index.html")) return "index.html";
            if (path.equals("/about")) return "about.html";
            if (path.equals("/resume")) return "resume.html";
            if (path.equals("/blog")) return "blog.html";
            if (path.equals("/apps")) return "apps.html";
            if (path.equals("/gallery")) return "gallery.html";
            if (path.equals("/faq")) return "faq.html";
            if (path.equals("/contacts")) return "contacts.html";
            return null;
        }

        private void serveStatic(HttpExchange ex) throws IOException {
            String urlPath = ex.getRequestURI().getPath();
            if (isUnsafe(urlPath)) { serveError(ex, 403); return; }

            String filePath = WEB_DIR + urlPath;
            File f = new File(filePath);
            if (!f.exists() || f.isDirectory()) {
                System.err.println("⚠️ Файл не найден: " + filePath);
                serveError(ex, 404);
                return;
            }

            byte[] data = Files.readAllBytes(f.toPath());
            System.out.println("📤 Отдача файла: " + urlPath + " (" + data.length + " bytes)");
            send(ex, 200, getContentType(filePath), data);
        }

        private void serveTemplate(HttpExchange ex, String page) throws IOException {
            if (isUnsafe(page)) { serveError(ex, 403); return; }

            String header = readFile(WEB_DIR + "parts/header.html");
            String footer = readFile(WEB_DIR + "parts/footer.html");
            String content = readFile(WEB_DIR + page);

            String active = page.replace(".html", "");
            if (active.equals("index")) active = "/"; else active = "/" + active;
            header = header.replace("{{ACTIVE_PAGE}}", active);

            byte[] out = (header + content + footer).getBytes("UTF-8");
            send(ex, 200, "text/html; charset=UTF-8", out);
        }

        private void handleContact(HttpExchange ex) throws IOException {
            String body = new String(ex.getRequestBody().readAllBytes(), "UTF-8");
            Map<String, String> p = parseForm(body);

            String name = p.getOrDefault("name", "Аноним");
            String email = p.getOrDefault("email", "-");
            String subj = p.getOrDefault("subject", "-");
            String msg = p.getOrDefault("message", "");

            saveMessage(name, email, subj, msg);
            System.out.printf("📩 Новое сообщение от %s (%s)\n", name, email);

            String html = "<!DOCTYPE html><html><head><meta charset='UTF-8'><title>Спасибо</title>" +
                    "<style>body{font-family:sans-serif;display:flex;justify-content:center;align-items:center;height:100vh;background:#0f172a;color:#fff;text-align:center}" +
                    ".b{background:rgba(255,255,255,0.05);padding:2rem;border-radius:12px}a{color:#3b82f6;margin-top:1rem;display:inline-block}</style></head><body>" +
                    "<div class='b'><h1>✅</h1><h2>Спасибо, " + escapeHtml(name) + "!</h2><p>Сообщение сохранено. Я отвечу в ближайшее время.</p>" +
                    "<a href='/'>← На главную</a></div></body></html>";
            send(ex, 200, "text/html; charset=UTF-8", html.getBytes("UTF-8"));
        }

        private void handleDownload(HttpExchange ex) throws IOException {
            String q = ex.getRequestURI().getQuery();
            String app = q != null && q.startsWith("app=") ? q.substring(4) : "Приложение";
            String html = "<!DOCTYPE html><html><head><meta charset='UTF-8'><title>Загрузка</title>" +
                    "<style>body{font-family:sans-serif;display:flex;justify-content:center;align-items:center;height:100vh;background:#0f172a;color:#fff;text-align:center}" +
                    ".b{background:rgba(255,255,255,0.05);padding:2rem;border-radius:12px}a{color:#3b82f6;margin-top:1rem;display:inline-block}</style></head><body>" +
                    "<div class='b'><h2>️ " + escapeHtml(app) + "</h2><p>Загрузка началась...</p><a href='/apps'>← К приложениям</a></div></body></html>";
            send(ex, 200, "text/html; charset=UTF-8", html.getBytes("UTF-8"));
        }

        private void saveMessage(String name, String email, String subj, String msg) {
            try {
                String entry = String.format("=== СООБЩЕНИЕ ===\nДата: %s\nОт: %s <%s>\nТема: %s\nСообщение:\n%s\n=================\n\n",
                        LocalDateTime.now().format(FMT), name, email, subj, msg);
                Files.write(Paths.get(MESSAGE_LOG), entry.getBytes("UTF-8"), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) { System.err.println("Ошибка записи в лог: " + e.getMessage()); }
        }

        private boolean isUnsafe(String p) { return p.contains("..") || p.contains("\\"); }

        private String readFile(String path) {
            try { return new String(Files.readAllBytes(Paths.get(path)), "UTF-8"); }
            catch (IOException e) { return "<!-- Файл не найден: " + path + " -->"; }
        }

        private Map<String, String> parseForm(String body) {
            Map<String, String> m = new HashMap<>();
            for (String pair : body.split("&")) {
                if (pair.contains("=")) {
                    String[] kv = pair.split("=", 2);
                    try { m.put(URLDecoder.decode(kv[0], "UTF-8"), URLDecoder.decode(kv[1], "UTF-8")); } catch (Exception ignored) {}
                }
            }
            return m;
        }

        private String escapeHtml(String s) {
            return s == null ? "" : s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");
        }

        private String getContentType(String f) {
            if (f.endsWith(".html") || f.endsWith(".htm")) return "text/html; charset=UTF-8";
            if (f.endsWith(".css")) return "text/css";
            if (f.endsWith(".js")) return "application/javascript";
            if (f.endsWith(".pdf")) return "application/pdf";
            if (f.endsWith(".doc")) return "application/msword";
            if (f.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            if (f.endsWith(".jpg") || f.endsWith(".jpeg")) return "image/jpeg";
            if (f.endsWith(".png")) return "image/png";
            if (f.endsWith(".gif")) return "image/gif";
            if (f.endsWith(".svg")) return "image/svg+xml";
            if (f.endsWith(".ico")) return "image/x-icon";
            return "application/octet-stream";
        }

        private void send(HttpExchange ex, int code, String type, byte[] data) throws IOException {
            ex.getResponseHeaders().set("Content-Type", type);
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            ex.sendResponseHeaders(code, data.length);
            ex.getResponseBody().write(data);
            ex.close();
        }

        private void serveError(HttpExchange ex, int code) throws IOException {
            if (code == 404) {
                try {
                    String errHtml = readFile(WEB_DIR + "errors/404.html");
                    if (!errHtml.contains("Файл не найден")) {
                        send(ex, 404, "text/html; charset=UTF-8", errHtml.getBytes("UTF-8"));
                        return;
                    }
                } catch (Exception ignored) {}
            }
            String html = "<h1 style='text-align:center;margin-top:20%;font-family:sans-serif;color:#333'>" + code + " Error</h1>";
            send(ex, code, "text/html; charset=UTF-8", html.getBytes("UTF-8"));
        }

        private void logRequest(HttpExchange ex, String method, String path) {
            try {
                String line = String.format("[%s] %s %s %s\n", LocalDateTime.now().format(FMT), ex.getRemoteAddress().getAddress().getHostAddress(), method, path);
                Files.write(Paths.get(ACCESS_LOG), line.getBytes("UTF-8"), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {}
        }
    }
}