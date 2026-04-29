package com.luigi;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.FilenameUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final GptService gptService;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    @Value("${app.budget-usd:5.0}")
    private double budgetUsd;

    private static final String DATA_DIR = "chat_threads";

    public ChatController(GptService gptService) {
        this.gptService = gptService;
    }

    /* ===========================
       ME / WHOAMI
       =========================== */
    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest req) {
        String username = (String) req.getSession().getAttribute("username");
        boolean isAdmin = Boolean.TRUE.equals(req.getSession().getAttribute("isAdmin"))
                || "admin".equals(req.getSession().getAttribute("role"))
                || "luigi".equalsIgnoreCase(username);
        Map<String, Object> out = new HashMap<>();
        out.put("username", username == null ? "guest" : username);
        out.put("admin", isAdmin);
        return out;
    }

    /* ===========================
       USAGE / COSTI
       =========================== */
    @GetMapping("/usage")
    public Map<String, Object> usage(HttpServletRequest req) {
        String username = (String) req.getSession().getAttribute("username");
        if (username == null) username = "guest";

        LocalDate today = LocalDate.now();
        YearMonth ym = YearMonth.now();

        Path daily = Paths.get("usage", username, today.toString() + ".json");
        Path monthly = Paths.get("usage", username, ym.toString() + ".json");

        Map<String, Object> out = new HashMap<>();
        try {
            Map<String, Object> d = Files.exists(daily)
                    ? mapper.readValue(daily.toFile(), new TypeReference<Map<String, Object>>() {}) : new HashMap<>();
            Map<String, Object> m = Files.exists(monthly)
                    ? mapper.readValue(monthly.toFile(), new TypeReference<Map<String, Object>>() {}) : new HashMap<>();

            double todayUsd  = ((Number) d.getOrDefault("usd", 0)).doubleValue();
            double monthUsd  = ((Number) m.getOrDefault("usd", 0)).doubleValue();
            int todayIn      = ((Number) d.getOrDefault("input_tokens", 0)).intValue();
            int todayOut     = ((Number) d.getOrDefault("output_tokens", 0)).intValue();
            int monthIn      = ((Number) m.getOrDefault("input_tokens", 0)).intValue();
            int monthOut     = ((Number) m.getOrDefault("output_tokens", 0)).intValue();

            out.put("todayUsd",           round2(todayUsd));
            out.put("monthUsd",           round2(monthUsd));
            out.put("budgetUsd",          round2(budgetUsd));
            out.put("remainingUsd",       round2(Math.max(0.0, budgetUsd - monthUsd)));
            out.put("todayInputTokens",   todayIn);
            out.put("todayOutputTokens",  todayOut);
            out.put("monthInputTokens",   monthIn);
            out.put("monthOutputTokens",  monthOut);
        } catch (Exception e) {
            out.put("todayUsd", 0.0); out.put("monthUsd", 0.0);
            out.put("budgetUsd", budgetUsd); out.put("remainingUsd", budgetUsd);
            out.put("todayInputTokens", 0); out.put("todayOutputTokens", 0);
            out.put("monthInputTokens", 0); out.put("monthOutputTokens", 0);
        }
        return out;
    }

    /* ===========================
       CHAT: ASK
       =========================== */
    @PostMapping(value = "/ask", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public void ask(
            @RequestParam(value = "text", required = false) String text,
            @RequestParam(value = "image", required = false) MultipartFile image,
            @RequestParam("thread") String threadId,
            @RequestParam(value = "system", required = false, defaultValue = "") String system,
            @RequestParam(value = "model", required = false) String model,
            @RequestParam(value = "shortContext", required = false, defaultValue = "true") boolean shortContext,
            @RequestParam(value = "reasoningEffort", required = false, defaultValue = "auto") String reasoningEffort,
            HttpServletRequest request,
            HttpServletResponse resp
    ) {
        resp.setContentType("text/plain; charset=UTF-8");
        try {
            String username = (String) request.getSession().getAttribute("username");
            if (username == null) username = "guest";

            String encKey = (String) request.getSession().getAttribute("encKey");
            if (encKey == null || encKey.trim().isEmpty()) {
                encKey = username + ":server-fallback-secret";
            }

            String reply = gptService.askGpt(
                    text == null ? "" : text,
                    image,
                    system,
                    threadId,
                    username,
                    model,
                    shortContext,
                    reasoningEffort,
                    encKey
            );

            resp.getWriter().write(reply);
            resp.getWriter().flush();
        } catch (Exception e) {
            try {
                resp.getWriter().write("❌ Errore AI: " + (e.getMessage() == null ? "" : e.getMessage()));
                resp.getWriter().flush();
            } catch (IOException ignored) {}
        }
    }

    /* ===========================
       THREADS LIST / LOAD / DELETE
       =========================== */
    @GetMapping("/threads")
    public List<Map<String, String>> listThreads(HttpServletRequest req) {
        String username = (String) req.getSession().getAttribute("username");
        if (username == null) username = "guest";
        return gptService.listThreads(username);
    }

    @GetMapping("/thread")
    public Map<String, Object> loadThread(
            @RequestParam("id") String id,
            @RequestParam(value = "pwd", required = false) String pwd,
            HttpServletRequest req
    ) {
        String username = (String) req.getSession().getAttribute("username");
        if (username == null) username = "guest";

        String encKey = (String) req.getSession().getAttribute("encKey");
        if (encKey == null || encKey.trim().isEmpty()) {
            encKey = username + ":server-fallback-secret";
        }
        // pwd param overrides session key if provided
        if (pwd != null && !pwd.isBlank()) encKey = pwd;

        Path meta = Paths.get(DATA_DIR, username, id, "thread.json");
        if (!Files.exists(meta)) {
            Map<String, Object> m = new HashMap<>();
            m.put("messages", Collections.emptyList());
            m.put("system", "");
            return m;
        }
        try {
            return gptService.loadEncryptedJson(meta, encKey);
        } catch (Exception e) {
            Map<String, Object> m = new HashMap<>();
            m.put("messages", Collections.emptyList());
            m.put("system", "");
            m.put("error", "decrypt_failed");
            return m;
        }
    }

    @DeleteMapping("/thread")
    public Map<String, String> deleteThread(
            @RequestParam("id") String id,
            HttpServletRequest req
    ) {
        String username = (String) req.getSession().getAttribute("username");
        if (username == null) username = "guest";

        Path path = Paths.get(DATA_DIR, username, id);
        if (Files.exists(path)) {
            try (Stream<Path> walk = Files.walk(path)) {
                walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            } catch (IOException ignored) {}
        }
        return singletonMap("status", "ok");
    }

    @PostMapping("/thread/title")
    public Map<String, String> setThreadTitle(
            @RequestParam("id") String id,
            @RequestParam("title") String title,
            HttpServletRequest req
    ) {
        String username = (String) req.getSession().getAttribute("username");
        if (username == null) username = "guest";
        try {
            Path titlePath = Paths.get(DATA_DIR, username, id, "title.txt");
            Files.createDirectories(titlePath.getParent());
            Files.write(titlePath, (title == null || title.trim().isEmpty() ? "Nuova Chat" : title.trim())
                    .getBytes(StandardCharsets.UTF_8));
            return singletonMap("status", "ok");
        } catch (IOException e) {
            Map<String, String> m = new HashMap<>();
            m.put("status", "error");
            m.put("message", e.getMessage());
            return m;
        }
    }

    /* ===========================
       IMAGE PREVIEW
       =========================== */
    @GetMapping("/image")
    public void serveImage(
            @RequestParam("path") String path,
            @RequestParam(value = "pwd", required = false) String pwd,
            HttpServletRequest req,
            HttpServletResponse response
    ) throws IOException {
        Path p = Paths.get(path);
        if (!Files.exists(p)) { response.setStatus(404); return; }

        byte[] bytes = Files.readAllBytes(p);

        String encKey = pwd;
        if (encKey == null) {
            encKey = (String) req.getSession().getAttribute("encKey");
        }
        if (encKey != null && bytes.length > 28) {
            try { bytes = gptService.decrypt(bytes, encKey); } catch (Exception ignored) {}
        }

        String ext = FilenameUtils.getExtension(p.getFileName().toString()).toLowerCase();
        String mime;
        switch (ext) {
            case "jpg": case "jpeg": mime = "image/jpeg"; break;
            case "png":  mime = "image/png"; break;
            case "webp": mime = "image/webp"; break;
            case "gif":  mime = "image/gif"; break;
            default:     mime = "application/octet-stream";
        }

        response.setContentType(mime);
        response.setHeader("Cache-Control", "no-store");
        OutputStream os = response.getOutputStream();
        try { os.write(bytes); } finally { os.flush(); }
    }

    /* ===========================
       EXPORTS (CSV/XLSX)
       =========================== */
    @GetMapping("/exports")
    public List<Map<String, String>> listExports(
            @RequestParam("thread") String threadId,
            HttpServletRequest req
    ) {
        String username = (String) req.getSession().getAttribute("username");
        if (username == null) username = "guest";

        Path dir = Paths.get(DATA_DIR, username, threadId, "exports");
        if (!Files.exists(dir)) return Collections.emptyList();
        try (Stream<Path> s = Files.list(dir)) {
            final String uname = username;
            return s.filter(Files::isRegularFile).sorted().map(fp -> {
                Map<String, String> m = new HashMap<>();
                m.put("name", fp.getFileName().toString());
                String url = baseUrl + "/api/download?thread=" + url(threadId)
                        + "&file=" + url(fp.getFileName().toString())
                        + "&username=" + url(uname);
                m.put("url", url);
                return m;
            }).collect(Collectors.toList());
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    @GetMapping("/download")
    public void download(
            @RequestParam("thread") String threadId,
            @RequestParam("file") String fileName,
            @RequestParam(value = "username", required = false, defaultValue = "default") String username,
            HttpServletResponse response
    ) throws IOException {
        Path file = Paths.get(DATA_DIR, username, threadId, "exports", fileName);
        if (!Files.exists(file)) { response.setStatus(404); return; }
        String ext = FilenameUtils.getExtension(fileName).toLowerCase();
        String mime;
        if ("csv".equals(ext)) mime = "text/csv; charset=UTF-8";
        else if ("xlsx".equals(ext)) mime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        else mime = "application/octet-stream";

        response.setContentType(mime);
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + URLEncoder.encode(fileName, "UTF-8") + "\"");
        try (InputStream is = Files.newInputStream(file)) {
            StreamUtils.copy(is, response.getOutputStream());
        }
    }

    /* ===========================
       UTILS
       =========================== */
    private static String url(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (UnsupportedEncodingException e) { return s; }
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    private static Map<String, String> singletonMap(String k, String v) {
        Map<String, String> m = new HashMap<>(1);
        m.put(k, v);
        return m;
    }
}
