package com.luigi;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class GptService {

    @Value("${openai.api.key:}")
    private String openaiApiKey;

    @Value("${gemini.api.key:}")
    private String geminiApiKey;

    @Value("${app.base-url}")
    private String baseUrl;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    private final String DATA_DIR = "chat_threads";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int KEY_SIZE = 256;
    private static final int PBKDF2_ITERATIONS = 65536;

    // ====== PREZZI (per 1K tokens, USD) ======
    private static final Map<String, double[]> PRICE_MAP = new HashMap<>();
    static {
        PRICE_MAP.put("gpt-4o",              new double[]{0.005,    0.015});
        PRICE_MAP.put("gpt-4o-mini",         new double[]{0.00015,  0.0006});
        PRICE_MAP.put("gpt-5.1",             new double[]{0.005,    0.015});
        PRICE_MAP.put("o1",                  new double[]{0.015,    0.060});
        PRICE_MAP.put("o3-mini",             new double[]{0.0011,   0.0044});
        PRICE_MAP.put("gemini-2.0-flash",    new double[]{0.0001,   0.0004});
        PRICE_MAP.put("gemini-2.5-pro",      new double[]{0.00125,  0.010});
        PRICE_MAP.put("gemini-1.5-pro",      new double[]{0.00125,  0.005});
        PRICE_MAP.put("gemini-1.5-flash",    new double[]{0.0000375,0.00015});
    }

    // ====== ENTRYPOINT PRINCIPALE ======
    public String askGpt(String userText,
                         MultipartFile image,
                         String system,
                         String threadId,
                         String username,
                         String model,
                         boolean shortContext,
                         String reasoningEffort,
                         String encKey) throws Exception {

        if (threadId == null || threadId.isBlank()) {
            threadId = java.util.UUID.randomUUID().toString();
        }

        Path threadPath = Paths.get(DATA_DIR, username, threadId);
        Files.createDirectories(threadPath);

        File metaFile = threadPath.resolve("thread.json").toFile();
        Map<String, Object> threadData = metaFile.exists()
                ? loadEncryptedJson(metaFile.toPath(), encKey)
                : new HashMap<>();

        if (system == null || system.isBlank()) system = "Rispondi sempre in italiano.";
        threadData.put("system", system);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages =
                (List<Map<String, Object>>) threadData.getOrDefault("messages", new ArrayList<>());
        messages.removeIf(m -> "system".equals(m.get("role")));

        // Snapshot history per Gemini (prima di aggiungere il messaggio corrente)
        List<Map<String, Object>> historySnapshot = shortContext ? new ArrayList<>() : new ArrayList<>(messages);

        // Salva messaggio utente in formato storage
        if (image != null && !image.isEmpty()) {
            String originalName = image.getOriginalFilename() == null ? "image.jpg" : image.getOriginalFilename();
            String ext = originalName.contains(".") ? originalName.substring(originalName.lastIndexOf(".")) : ".jpg";
            Path uploadDir = threadPath.resolve("uploads");
            Files.createDirectories(uploadDir);
            Path savedFile = uploadDir.resolve(System.currentTimeMillis() + ext);
            Files.write(savedFile, image.getBytes());

            List<Object> content = new ArrayList<>();
            content.add(Map.of("type", "text", "text", userText == null ? "" : userText));
            content.add(Map.of("type", "image_file", "image_file", Map.of("path", savedFile.toString())));
            messages.add(Map.of("role", "user", "content", content));
        } else {
            List<Object> content = new ArrayList<>();
            content.add(Map.of("type", "text", "text", userText == null ? "" : userText));
            messages.add(Map.of("role", "user", "content", content));
        }

        // Routing per provider
        String effectiveModel = (model == null || model.isBlank()) ? "gpt-4o" : model;
        int[] tokenCounts = {0, 0};
        String reply;

        if (effectiveModel.startsWith("gemini")) {
            reply = callGemini(userText, image, system, historySnapshot, effectiveModel, tokenCounts);
        } else {
            // Costruisci messagesToSend in formato OpenAI Responses API
            List<Map<String, Object>> messagesToSend = new ArrayList<>();
            if (!shortContext) {
                for (Map<String, Object> msg : historySnapshot) {
                    Object rawContent = msg.get("content");
                    List<Map<String, Object>> parts = contentToOpenAIParts(rawContent);
                    if (!parts.isEmpty()) {
                        messagesToSend.add(Map.of("role", String.valueOf(msg.get("role")), "content", parts));
                    }
                }
            }

            List<Object> userParts = new ArrayList<>();
            userParts.add(Map.of("type", "input_text", "text", userText == null ? "" : userText.trim()));
            if (image != null && !image.isEmpty()) {
                String mime = image.getContentType() != null ? image.getContentType() : "image/jpeg";
                String b64 = Base64.getEncoder().encodeToString(image.getBytes());
                userParts.add(Map.of("type", "input_image", "image_url", "data:" + mime + ";base64," + b64));
            }
            messagesToSend.add(Map.of("role", "user", "content", userParts));

            reply = callOpenAI(messagesToSend, effectiveModel, system, reasoningEffort, tokenCounts);
        }

        if (reply == null) reply = "";

        double usd = estimateCostUsd(effectiveModel, tokenCounts[0], tokenCounts[1]);
        addUsage(username, effectiveModel, tokenCounts[0], tokenCounts[1], usd);

        messages.add(Map.of("role", "assistant", "content", reply));
        threadData.put("messages", messages);

        if (reply.contains("|")) exportTableAsCsvAndXlsx(reply, threadPath);

        saveEncryptedJson(metaFile.toPath(), threadData, encKey);
        return reply;
    }

    // ====== OPENAI (Responses API) ======
    private String callOpenAI(List<Map<String, Object>> messagesToSend,
                               String model, String system, String reasoningEffort,
                               int[] tokensOut) throws Exception {

        int estTokens = estimateTokenCount(messagesToSend);
        int maxOutTokens = Math.max(400, estTokens / 2);

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("instructions", system);
        body.put("input", messagesToSend);
        body.put("max_output_tokens", maxOutTokens);
        body.put("text", Map.of("verbosity", "low"));
        body.put("reasoning", Map.of("effort", reasoningEffort == null ? "auto" : reasoningEffort));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/responses"))
                .header("Authorization", "Bearer " + openaiApiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = mapper.readTree(response.body());

        System.out.println("OpenAI resp_id=" + root.path("id").asText()
                + " status=" + root.path("status").asText()
                + " in=" + root.path("usage").path("input_tokens").asInt()
                + " out=" + root.path("usage").path("output_tokens").asInt());

        if (root.has("error") && !root.path("error").isNull()) {
            return "❌ Errore OpenAI: " + root.path("error").path("message").asText();
        }

        tokensOut[0] = root.path("usage").path("input_tokens").asInt(0);
        tokensOut[1] = root.path("usage").path("output_tokens").asInt(0);

        String reply = root.path("output_text").asText();
        if (reply == null || reply.isBlank()) {
            JsonNode output = root.path("output");
            if (output.isArray()) {
                for (JsonNode item : output) {
                    for (JsonNode c : item.path("content")) {
                        if ("output_text".equals(c.path("type").asText())) {
                            reply = c.path("text").asText();
                            break;
                        }
                    }
                    if (reply != null && !reply.isBlank()) break;
                }
            }
        }
        return reply != null ? reply : "";
    }

    // ====== GEMINI ======
    private String callGemini(String userText,
                               MultipartFile image,
                               String system,
                               List<Map<String, Object>> history,
                               String model, int[] tokensOut) throws Exception {

        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            return "❌ Gemini API key non configurata (gemini.api.key in application.properties).";
        }

        List<Map<String, Object>> contents = new ArrayList<>();

        // History
        for (Map<String, Object> msg : history) {
            String role = String.valueOf(msg.get("role"));
            String gemRole = "assistant".equals(role) ? "model" : "user";
            String text = extractText(msg.get("content"));
            if (text != null && !text.isBlank()) {
                contents.add(Map.of("role", gemRole,
                        "parts", List.of(Map.of("text", text))));
            }
        }

        // Messaggio utente corrente
        List<Map<String, Object>> userParts = new ArrayList<>();
        if (userText != null && !userText.isBlank()) {
            userParts.add(Map.of("text", userText));
        }
        if (image != null && !image.isEmpty()) {
            String mime = image.getContentType() != null ? image.getContentType() : "image/jpeg";
            String b64 = Base64.getEncoder().encodeToString(image.getBytes());
            userParts.add(Map.of("inlineData", Map.of("mimeType", mime, "data", b64)));
        }
        if (!userParts.isEmpty()) {
            contents.add(Map.of("role", "user", "parts", userParts));
        }

        Map<String, Object> body = new HashMap<>();
        body.put("contents", contents);
        if (system != null && !system.isBlank()) {
            body.put("systemInstruction",
                    Map.of("parts", List.of(Map.of("text", system))));
        }
        body.put("generationConfig", Map.of("maxOutputTokens", 8192));

        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                + model + ":generateContent?key=" + geminiApiKey;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = mapper.readTree(response.body());

        if (root.has("error")) {
            return "❌ Errore Gemini: " + root.path("error").path("message").asText();
        }

        tokensOut[0] = root.path("usageMetadata").path("promptTokenCount").asInt(0);
        tokensOut[1] = root.path("usageMetadata").path("candidatesTokenCount").asInt(0);

        System.out.println("Gemini model=" + model
                + " in=" + tokensOut[0] + " out=" + tokensOut[1]);

        return root.path("candidates").path(0)
                .path("content").path("parts").path(0)
                .path("text").asText("");
    }

    // ====== HELPERS ======
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> contentToOpenAIParts(Object rawContent) {
        List<Map<String, Object>> parts = new ArrayList<>();
        if (rawContent instanceof String) {
            parts.add(Map.of("type", "input_text", "text", rawContent));
        } else if (rawContent instanceof List<?>) {
            for (Object item : (List<?>) rawContent) {
                if (item instanceof Map) {
                    Map<?, ?> m = (Map<?, ?>) item;
                    String type = String.valueOf(m.get("type"));
                    if ("text".equals(type)) {
                        parts.add(Map.of("type", "input_text", "text", m.get("text")));
                    }
                }
            }
        }
        return parts;
    }

    @SuppressWarnings("unchecked")
    private String extractText(Object rawContent) {
        if (rawContent instanceof String) return (String) rawContent;
        if (rawContent instanceof List<?>) {
            for (Object item : (List<?>) rawContent) {
                if (item instanceof Map) {
                    Object t = ((Map<?, ?>) item).get("text");
                    if (t != null) return t.toString();
                }
            }
        }
        return null;
    }

    private int estimateTokenCount(List<Map<String, Object>> messages) {
        int count = 0;
        for (Map<String, Object> msg : messages) {
            Object content = msg.get("content");
            if (content instanceof List<?>) {
                for (Object o : (List<?>) content) {
                    if (o instanceof Map) {
                        String text = (String) ((Map<?, ?>) o).get("text");
                        if (text != null) count += text.split("\\s+").length;
                    }
                }
            }
        }
        return (int) (count * 1.3);
    }

    // ====== Export tabella ======
    private void exportTableAsCsvAndXlsx(String reply, Path threadPath) throws IOException {
        String[] lines = reply.split("\n");
        if (lines.length < 2) return;
        Path exportDir = threadPath.resolve("exports");
        Files.createDirectories(exportDir);

        try (BufferedWriter writer = Files.newBufferedWriter(exportDir.resolve("table.csv"));
             CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT)) {
            for (String line : lines) {
                if (line.contains("|")) {
                    String[] cols = Arrays.stream(line.split("\\|"))
                            .map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
                    printer.printRecord((Object[]) cols);
                }
            }
        }

        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Table");
            int rowIdx = 0;
            for (String line : lines) {
                if (line.contains("|")) {
                    String[] cols = Arrays.stream(line.split("\\|"))
                            .map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
                    Row row = sheet.createRow(rowIdx++);
                    for (int i = 0; i < cols.length; i++) {
                        row.createCell(i, CellType.STRING).setCellValue(cols[i]);
                    }
                }
            }
            try (OutputStream os = Files.newOutputStream(exportDir.resolve("table.xlsx"))) {
                wb.write(os);
            }
        }
    }

    // ====== Cifratura JSON ======
    private void saveEncryptedJson(Path path, Map<String, Object> data, String password) throws Exception {
        byte[] jsonBytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(data);
        Files.write(path, encrypt(jsonBytes, password));
    }

    Map<String, Object> loadEncryptedJson(Path path, String password) throws Exception {
        byte[] decrypted = decrypt(Files.readAllBytes(path), password);
        return mapper.readValue(decrypted, new TypeReference<Map<String, Object>>() {});
    }

    byte[] encrypt(byte[] data, String password) throws Exception {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        SecretKeySpec key = deriveKey(password, salt);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        byte[] cipherText = cipher.doFinal(data);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(salt); baos.write(iv); baos.write(cipherText);
        return baos.toByteArray();
    }

    byte[] decrypt(byte[] encData, String password) throws Exception {
        byte[] salt       = Arrays.copyOfRange(encData, 0, 16);
        byte[] iv         = Arrays.copyOfRange(encData, 16, 28);
        byte[] cipherText = Arrays.copyOfRange(encData, 28, encData.length);
        SecretKeySpec key = deriveKey(password, salt);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        return cipher.doFinal(cipherText);
    }

    private SecretKeySpec deriveKey(String password, byte[] salt) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_SIZE);
        return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
    }

    // ====== Thread list ======
    public List<Map<String, String>> listThreads(String username) {
        if (StringUtils.isEmpty(username)) return List.of();
        try (Stream<Path> stream = Files.list(Paths.get(DATA_DIR, username))) {
            return stream.filter(Files::isDirectory).sorted((a, b) -> {
                try { return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a)); }
                catch (IOException e) { return 0; }
            }).map(path -> {
                String id = path.getFileName().toString();
                String title = "Nuova Chat";
                try {
                    Path tp = path.resolve("title.txt");
                    if (Files.exists(tp)) title = Files.readString(tp);
                } catch (IOException ignored) {}
                return Map.of("id", id, "title", title);
            }).collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    // ====== Usage ======
    private double estimateCostUsd(String model, int inputTokens, int outputTokens) {
        double[] p = PRICE_MAP.getOrDefault(model,
                model.startsWith("gemini") ? PRICE_MAP.get("gemini-2.0-flash") : PRICE_MAP.get("gpt-4o"));
        return round2((inputTokens / 1000.0) * p[0] + (outputTokens / 1000.0) * p[1]);
    }

    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    private Path usageDirFor(String username) throws IOException {
        Path p = Paths.get("usage", username);
        Files.createDirectories(p);
        return p;
    }

    private synchronized void addUsage(String username, String model,
                                        int inTok, int outTok, double usd) throws IOException {
        LocalDate today = LocalDate.now();
        YearMonth ym = YearMonth.now();

        Path daily = usageDirFor(username).resolve(today.toString() + ".json");
        Map<String, Object> d = Files.exists(daily)
                ? mapper.readValue(daily.toFile(), new TypeReference<Map<String, Object>>() {}) : new HashMap<>();
        d.put("usd",           round2(((Number) d.getOrDefault("usd", 0)).doubleValue() + usd));
        d.put("input_tokens",  ((Number) d.getOrDefault("input_tokens", 0)).intValue() + inTok);
        d.put("output_tokens", ((Number) d.getOrDefault("output_tokens", 0)).intValue() + outTok);
        d.put("last_model", model);
        mapper.writerWithDefaultPrettyPrinter().writeValue(daily.toFile(), d);

        Path monthly = usageDirFor(username).resolve(ym.toString() + ".json");
        Map<String, Object> m = Files.exists(monthly)
                ? mapper.readValue(monthly.toFile(), new TypeReference<Map<String, Object>>() {}) : new HashMap<>();
        m.put("usd",           round2(((Number) m.getOrDefault("usd", 0)).doubleValue() + usd));
        m.put("input_tokens",  ((Number) m.getOrDefault("input_tokens", 0)).intValue() + inTok);
        m.put("output_tokens", ((Number) m.getOrDefault("output_tokens", 0)).intValue() + outTok);
        m.put("last_model", model);
        mapper.writerWithDefaultPrettyPrinter().writeValue(monthly.toFile(), m);
    }
}
