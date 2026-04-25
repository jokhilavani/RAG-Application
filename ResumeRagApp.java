package com.resumerag;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ResumeRagApp {
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
    private static final Path RESUME_PATH = Path.of("data", "resume.txt");
    private static final Pattern JSON_FIELD = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"");

    private final RagEngine ragEngine;
    private final AiClient aiClient;

    public ResumeRagApp() throws IOException {
        this.ragEngine = new RagEngine(RESUME_PATH);
        this.aiClient = new AiClient();
    }

    public static void main(String[] args) throws Exception {
        ResumeRagApp app = new ResumeRagApp();
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", app::serveStatic);
        server.createContext("/api/ask", app.withCors(app::ask));
        server.createContext("/api/resume", app.withCors(app::resume));
        server.createContext("/api/upload", app.withCors(app::upload));
        server.createContext("/api/status", app.withCors(app::status));
        server.setExecutor(null);
        server.start();
        System.out.println("Resume RAG app running at http://localhost:" + PORT);
    }

    private HttpHandler withCors(HttpHandler handler) {
        return exchange -> {
            Headers headers = exchange.getResponseHeaders();
            headers.add("Access-Control-Allow-Origin", "*");
            headers.add("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
            headers.add("Access-Control-Allow-Headers", "Content-Type");
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            handler.handle(exchange);
        };
    }

    private void ask(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"POST required\"}");
            return;
        }

        Map<String, String> body = parseJson(readBody(exchange));
        String question = body.getOrDefault("question", "").trim();
        String tone = body.getOrDefault("tone", "professional").trim();
        String mode = body.getOrDefault("mode", "grounded").trim();
        String requestApiKey = body.getOrDefault("apiKey", "").trim();
        String requestModel = body.getOrDefault("model", "").trim();

        if (question.isBlank()) {
            sendJson(exchange, 400, "{\"error\":\"Please ask a question.\"}");
            return;
        }

        RagAnswer ragAnswer = ragEngine.answer(question, tone);
        String answer = ragAnswer.answer();
        boolean usedExternalAi = false;

        if (!ragAnswer.exact() && (aiClient.isConfigured() || !requestApiKey.isBlank()) && !"local".equalsIgnoreCase(mode)) {
            try {
                answer = aiClient.answer(question, tone, ragAnswer.context(), requestApiKey, requestModel);
                usedExternalAi = true;
            } catch (Exception ex) {
                answer = ragAnswer.answer() + "\n\nAI engine note: the external model was unavailable, so I answered using the local candidate-analysis engine.";
            }
        }

        String json = "{"
                + "\"answer\":\"" + escapeJson(answer) + "\","
                + "\"usedExternalAi\":" + usedExternalAi + ","
                + "\"sources\":[]"
                + "}";
        sendJson(exchange, 200, json);
    }

    private void resume(HttpExchange exchange) throws IOException {
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            String resume = Files.exists(RESUME_PATH) ? Files.readString(RESUME_PATH) : "";
            sendJson(exchange, 200, "{\"resume\":\"" + escapeJson(resume) + "\"}");
            return;
        }

        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            Map<String, String> body = parseJson(readBody(exchange));
            String resume = body.getOrDefault("resume", "").trim();
            if (resume.length() < 40) {
                sendJson(exchange, 400, "{\"error\":\"Please paste a fuller resume before saving.\"}");
                return;
            }
            Files.createDirectories(RESUME_PATH.getParent());
            Files.writeString(RESUME_PATH, resume, StandardCharsets.UTF_8);
            ragEngine.reload();
            sendJson(exchange, 200, "{\"saved\":true}");
            return;
        }

        sendJson(exchange, 405, "{\"error\":\"GET or POST required\"}");
    }

    private void upload(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"POST required\"}");
            return;
        }

        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).contains("multipart/form-data")) {
            sendJson(exchange, 400, "{\"error\":\"Please upload the resume as form data.\"}");
            return;
        }

        MultipartFile file = parseMultipart(readBytes(exchange), contentType);
        if (file == null || file.bytes().length == 0) {
            sendJson(exchange, 400, "{\"error\":\"No resume file was uploaded.\"}");
            return;
        }

        String resume = extractResumeText(file.fileName(), file.bytes()).trim();
        if (resume.length() < 40) {
            sendJson(exchange, 400, "{\"error\":\"I could not read enough text from this file. Try a .txt or .docx resume, or paste the resume text.\"}");
            return;
        }

        Files.createDirectories(RESUME_PATH.getParent());
        Files.writeString(RESUME_PATH, resume, StandardCharsets.UTF_8);
        ragEngine.reload();

        String json = "{"
                + "\"saved\":true,"
                + "\"fileName\":\"" + escapeJson(file.fileName()) + "\","
                + "\"characters\":" + resume.length() + ","
                + "\"chunks\":" + ragEngine.chunkCount()
                + "}";
        sendJson(exchange, 200, json);
    }

    private void status(HttpExchange exchange) throws IOException {
        String json = "{"
                + "\"chunks\":" + ragEngine.chunkCount() + ","
                + "\"externalAiConfigured\":" + aiClient.isConfigured()
                + "}";
        sendJson(exchange, 200, json);
    }

    private void serveStatic(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/")) {
            path = "/index.html";
        }

        Path file = Path.of("public").resolve(path.substring(1)).normalize();
        if (!file.startsWith(Path.of("public")) || !Files.exists(file) || Files.isDirectory(file)) {
            sendText(exchange, 404, "Not found", "text/plain; charset=utf-8");
            return;
        }

        String contentType = contentType(file);
        byte[] bytes = Files.readAllBytes(file);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static byte[] readBytes(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody()) {
            return input.readAllBytes();
        }
    }

    private static MultipartFile parseMultipart(byte[] body, String contentType) {
        String boundary = null;
        for (String part : contentType.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("boundary=")) {
                boundary = trimmed.substring("boundary=".length()).replace("\"", "");
                break;
            }
        }
        if (boundary == null || boundary.isBlank()) {
            return null;
        }

        String payload = new String(body, StandardCharsets.ISO_8859_1);
        String marker = "--" + boundary;
        for (String section : payload.split(Pattern.quote(marker))) {
            if (!section.contains("Content-Disposition") || !section.contains("filename=")) {
                continue;
            }

            int dataStart = section.indexOf("\r\n\r\n");
            if (dataStart < 0) {
                continue;
            }

            String headers = section.substring(0, dataStart);
            String fileName = extractHeaderValue(headers, "filename");
            String data = section.substring(dataStart + 4);
            if (data.endsWith("\r\n")) {
                data = data.substring(0, data.length() - 2);
            }
            if (data.endsWith("--")) {
                data = data.substring(0, data.length() - 2);
            }
            return new MultipartFile(fileName == null ? "resume" : fileName, data.getBytes(StandardCharsets.ISO_8859_1));
        }
        return null;
    }

    private static String extractHeaderValue(String headers, String key) {
        Matcher matcher = Pattern.compile(key + "=\"([^\"]*)\"").matcher(headers);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String extractResumeText(String fileName, byte[] bytes) throws IOException {
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".docx")) {
            return extractDocxText(bytes);
        }
        if (lowerName.endsWith(".pdf")) {
            return extractPdfText(bytes);
        }
        if (lowerName.endsWith(".html") || lowerName.endsWith(".htm")) {
            return stripHtml(new String(bytes, StandardCharsets.UTF_8));
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String extractDocxText(byte[] bytes) throws IOException {
        StringBuilder xml = new StringBuilder();
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    xml.append(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                    break;
                }
            }
        }
        return stripHtml(xml.toString()
                .replace("</w:p>", "\n")
                .replace("</w:tr>", "\n"));
    }

    private static String extractPdfText(byte[] bytes) {
        String raw = new String(bytes, StandardCharsets.ISO_8859_1);
        List<String> textParts = new ArrayList<>();

        Matcher literalStrings = Pattern.compile("\\(([^()]{2,})\\)\\s*Tj").matcher(raw);
        while (literalStrings.find()) {
            textParts.add(unescapePdfString(literalStrings.group(1)));
        }

        Matcher arrayStrings = Pattern.compile("\\(([^()]{2,})\\)").matcher(raw);
        while (arrayStrings.find() && textParts.size() < 400) {
            String part = unescapePdfString(arrayStrings.group(1));
            if (part.matches(".*[A-Za-z]{2,}.*")) {
                textParts.add(part);
            }
        }

        String extracted = String.join(" ", textParts).replaceAll("\\s+", " ").trim();
        if (extracted.length() > 40) {
            return extracted;
        }

        return raw.replaceAll("[^A-Za-z0-9@+/#.,:;()\\-\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String unescapePdfString(String value) {
        return value.replace("\\(", "(")
                .replace("\\)", ")")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\n")
                .replace("\\t", " ");
    }

    private static String stripHtml(String value) {
        return value.replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static Map<String, String> parseJson(String json) {
        Map<String, String> result = new HashMap<>();
        Matcher matcher = JSON_FIELD.matcher(json);
        while (matcher.find()) {
            result.put(matcher.group(1), unescapeJson(matcher.group(2)));
        }
        return result;
    }

    private static String unescapeJson(String value) {
        return value.replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        sendText(exchange, status, json, "application/json; charset=utf-8");
    }

    private static void sendText(HttpExchange exchange, int status, String text, String contentType) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String contentType(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
        return "application/octet-stream";
    }

    private static String toJsonArray(List<String> values) {
        return values.stream()
                .map(value -> "\"" + escapeJson(value) + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "")
                .replace("\n", "\\n");
    }

    private record RagAnswer(String answer, String context, List<String> sources, boolean exact) {
    }

    private record MultipartFile(String fileName, byte[] bytes) {
    }

    private static class RagEngine {
        private final Path resumePath;
        private String fullResume = "";
        private CandidateProfile profile = CandidateProfile.empty();
        private List<String> chunks = new ArrayList<>();

        RagEngine(Path resumePath) throws IOException {
            this.resumePath = resumePath;
            reload();
        }

        void reload() throws IOException {
            if (!Files.exists(resumePath)) {
                fullResume = "";
                profile = CandidateProfile.empty();
                chunks = List.of("No resume has been added yet.");
                return;
            }
            fullResume = Files.readString(resumePath, StandardCharsets.UTF_8);
            profile = CandidateProfile.from(fullResume);
            chunks = chunk(fullResume);
        }

        int chunkCount() {
            return chunks.size();
        }

        RagAnswer answer(String question, String tone) {
            List<ScoredChunk> top = retrieve(question, 5);
            String relevantContext = top.stream().map(ScoredChunk::text).collect(Collectors.joining("\n\n"));
            String context = profile.toPromptText() + "\n\nRelevant resume context:\n" + relevantContext;
            List<String> sources = top.stream()
                    .filter(chunk -> chunk.score() > 0)
                    .map(chunk -> snippet(chunk.text()))
                    .toList();

            if (fullResume.isBlank() || fullResume.length() < 40) {
                return new RagAnswer(
                        "Please upload or paste the candidate's resume first. Once the resume is available, I can analyze the candidate's skills, projects, education, experience, and role fit.",
                        context,
                        List.of(),
                        true
                );
            }

            String lower = question.toLowerCase(Locale.ROOT);
            String exactAnswer = answerExactFact(lower, fullResume, profile);
            if (exactAnswer != null) {
                return new RagAnswer(exactAnswer, context, List.of(), true);
            }

            String answer;
            if (containsAny(lower, "introduce", "self introduction", "tell me about", "about candidate")) {
                answer = answerIntroduction(profile, context);
            } else if (containsAny(lower, "skill", "technology", "tech stack", "tools", "know", "language")) {
                answer = answerSkills(profile, context);
            } else if (containsAny(lower, "project", "built", "developed", "application", "system")) {
                answer = answerProjects(profile, context);
            } else if (containsAny(lower, "education", "degree", "college", "university", "study", "studied")) {
                answer = answerEducation(profile);
            } else if (containsAny(lower, "experience", "work", "intern", "job", "professional")) {
                answer = answerExperience(profile, context);
            } else if (containsAny(lower, "fit", "suitable", "role", "hire", "good for", "eligible", "recommend", "select")) {
                answer = answerFit(profile, context, question);
            } else if (containsAny(lower, "strength", "strong", "best", "advantage")) {
                answer = answerStrengths(profile, context);
            } else if (containsAny(lower, "weakness", "improve", "improvement", "missing", "gap", "lack")) {
                answer = answerImprovements(profile, context);
            } else if (containsAny(lower, "certification", "certificate", "achievement", "award")) {
                answer = answerCredentials(profile);
            } else if (containsAny(lower, "why", "reason")) {
                answer = answerWhy(profile, context, question);
            } else if (containsAny(lower, "contact", "email", "phone", "linkedin", "github")) {
                answer = "This question is asking for direct personal details. I can use the resume to understand the candidate, but I will keep the response focused on the candidate's profile rather than exposing copied resume data. Ask about their skills, strengths, projects, education, or job fit.";
            } else if (containsAny(lower, "summary", "about", "profile", "introduce")) {
                answer = answerSummary(profile, context);
            } else {
                answer = answerGeneral(question, profile, context);
            }

            if ("short".equalsIgnoreCase(tone)) {
                answer = firstSentence(answer);
            } else if ("interview".equalsIgnoreCase(tone)) {
                answer = "A strong interview-style answer would be:\n\n" + answer;
            }

            return new RagAnswer(answer, context, sources, false);
        }

        private List<ScoredChunk> retrieve(String question, int limit) {
            Set<String> queryTerms = tokenize(question);
            return chunks.stream()
                    .map(chunk -> new ScoredChunk(chunk, score(queryTerms, chunk)))
                    .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                    .limit(limit)
                    .toList();
        }

        private static double score(Set<String> queryTerms, String chunk) {
            Set<String> terms = tokenize(chunk);
            double score = 0;
            for (String term : queryTerms) {
                if (terms.contains(term)) {
                    score += term.length() > 5 ? 2.0 : 1.0;
                }
            }
            return score / Math.max(1, Math.sqrt(terms.size()));
        }

        private static List<String> chunk(String text) {
            List<String> blocks = new ArrayList<>();
            String[] sections = text.split("\\n\\s*\\n");
            StringBuilder current = new StringBuilder();
            for (String section : sections) {
                if (current.length() + section.length() > 900 && current.length() > 0) {
                    blocks.add(current.toString().trim());
                    current.setLength(0);
                }
                current.append(section.trim()).append("\n\n");
            }
            if (!current.isEmpty()) {
                blocks.add(current.toString().trim());
            }
            return blocks.isEmpty() ? List.of(text) : blocks;
        }

        private static Set<String> tokenize(String value) {
            Set<String> stopWords = Set.of("the", "and", "for", "with", "that", "this", "from", "what", "tell", "about", "are", "was", "you", "your");
            Set<String> terms = new HashSet<>();
            for (String raw : value.toLowerCase(Locale.ROOT).split("[^a-z0-9+#.]+")) {
                if (raw.length() > 1 && !stopWords.contains(raw)) {
                    terms.add(raw);
                }
            }
            return terms;
        }

        private static boolean containsAny(String value, String... words) {
            for (String word : words) {
                if (value.contains(word)) {
                    return true;
                }
            }
            return false;
        }

        private static String extractKeywords(String text) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (String token : tokenize(text)) {
                if (token.length() > 2) {
                    counts.merge(token, 1, Integer::sum);
                }
            }
            return counts.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(12)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.joining(", "));
        }

        private static String answerSummary(CandidateProfile profile, String context) {
            return paragraphs(
                    "The candidate appears to be " + candidateIdentity(profile) + ". Their strongest direction is " + capabilityText(profile, context) + ".",
                    projectsInsight(profile, context) + educationInsight(profile) + "This gives the candidate a practical, learning-focused profile rather than a purely theoretical one.",
                    "Overall, the candidate looks suitable for opportunities where programming fundamentals, application building, and willingness to improve are important."
            );
        }

        private static String answerExactFact(String lowerQuestion, String resume, CandidateProfile profile) {
            if (containsAny(lowerQuestion, "cgpa", "gpa")) {
                String value = findCgpa(resume);
                return value == null ? "Not mentioned" : value;
            }

            if (containsAny(lowerQuestion, "percentage", "percent", "marks")) {
                String value = firstMatch(resume,
                        "(?i)\\b(?:percentage|percent|marks)\\s*[:\\-]?\\s*([0-9]{1,3}(?:\\.\\d{1,2})?\\s*%)",
                        "(?i)\\b([0-9]{1,3}(?:\\.\\d{1,2})?\\s*%)");
                return value == null ? "Not mentioned" : value;
            }

            if (containsAny(lowerQuestion, "email", "mail id", "mail")) {
                String value = firstMatch(resume, "([A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,})");
                return value == null ? "Not mentioned" : value;
            }

            if (containsAny(lowerQuestion, "phone", "mobile", "contact number")) {
                String value = firstMatch(resume, "(?i)(?:\\+?\\d[\\d\\s\\-()]{8,}\\d)");
                return value == null ? "Not mentioned" : value.trim();
            }

            if (containsAny(lowerQuestion, "degree", "course")) {
                String value = firstMatch(profile.education(),
                        "(?i)\\b(Bachelor of [A-Za-z ]+|Master of [A-Za-z ]+|B\\.Tech|BTech|M\\.Tech|MTech|BCA|MCA|B\\.Sc|M\\.Sc|MBA)\\b");
                return value == null ? "Not mentioned" : value;
            }

            if (containsAny(lowerQuestion, "college", "university", "institution")) {
                String value = firstMatch(profile.education(),
                        "(?i)(?:college|university|institution)\\s*[:\\-]?\\s*([^,.;\\n]+)",
                        "(?i)([A-Z][A-Za-z&. ]+(?:College|University|Institute)[A-Za-z&. ]*)");
                return value == null ? "Not mentioned" : value.trim();
            }

            if (containsAny(lowerQuestion, "graduation year", "passing year", "passed out", "year of passing")) {
                String value = firstMatch(profile.education(), "\\b(20\\d{2}|19\\d{2})\\b");
                return value == null ? "Not mentioned" : value;
            }

            if (containsAny(lowerQuestion, "skills", "technical skills", "technologies")) {
                if (profile.skills().isEmpty()) {
                    return null;
                }
                return "Skills: " + joinNatural(profile.skills(), 12);
            }

            if (containsAny(lowerQuestion, "certification", "certificate")) {
                if (profile.credentials().isBlank()) {
                    return "Not mentioned";
                }
                return profile.credentials();
            }

            return null;
        }

        private static String findCgpa(String resume) {
            for (String line : resume.split("\\R")) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.contains("cgpa") || lower.contains("gpa")) {
                    String value = firstMatch(line,
                            "(?i)\\bC\\.?G\\.?P\\.?A\\.?\\s*[:\\-]?\\s*([0-9](?:\\.\\d{1,2})?\\s*(?:/\\s*10)?)",
                            "(?i)\\bG\\.?P\\.?A\\.?\\s*[:\\-]?\\s*([0-9](?:\\.\\d{1,2})?\\s*(?:/\\s*10)?)",
                            "([0-9](?:\\.\\d{1,2})?\\s*/\\s*10)",
                            "\\b([0-9](?:\\.\\d{1,2})?)\\b");
                    if (value != null) {
                        return value;
                    }
                }
            }
            return firstMatch(resume, "\\b([0-9](?:\\.\\d{1,2})\\s*/\\s*10)\\b");
        }

        private static String firstMatch(String text, String... patterns) {
            if (text == null || text.isBlank()) {
                return null;
            }
            for (String pattern : patterns) {
                Matcher matcher = Pattern.compile(pattern).matcher(text);
                if (matcher.find()) {
                    return matcher.group(matcher.groupCount() >= 1 ? 1 : 0).replaceAll("\\s+", " ").trim();
                }
            }
            return null;
        }

        private static String answerIntroduction(CandidateProfile profile, String context) {
            return paragraphs(
                    "The candidate can be introduced as " + candidateIdentity(profile) + ".",
                    "Their profile is centered on " + capabilityText(profile, context) + ", with practical learning shown through projects and technical practice.",
                    "A strong introduction would present them as someone who is building a solid software-development foundation and is ready to apply it in a junior, trainee, or internship-level opportunity."
            );
        }

        private static String answerSkills(CandidateProfile profile, String context) {
            return paragraphs(
                    "The candidate's main technical strength is " + capabilityText(profile, context) + ".",
                    "The important point is how the skills work together. The profile suggests that the candidate can connect programming concepts with usable application features, instead of treating each technology as a separate topic.",
                    "For a technical discussion, I would present the candidate as someone with a foundation in software development, user-facing interfaces, data handling, and project implementation."
            );
        }

        private static String answerProjects(CandidateProfile profile, String context) {
            return paragraphs(
                    projectsInsight(profile, context),
                    "The important takeaway is that the candidate has evidence of hands-on learning. The projects show that they are trying to convert technical knowledge into working software.",
                    "In an interview, these projects can be explained as examples of understanding requirements, choosing tools, organizing logic, and producing something usable."
            );
        }

        private static String answerEducation(CandidateProfile profile) {
            if (profile.education().isBlank()) {
                return "I do not see clear education details in the resume. Add the degree, institution, and study years so I can answer this accurately.";
            }
            return paragraphs(
                    "The candidate's education supports their technical direction.",
                    "It gives them an academic base for the programming and software-development work shown in the rest of the profile.",
                    "From a hiring perspective, I would treat the education as the foundation and the projects as the proof that they are applying what they learn."
            );
        }

        private static String answerExperience(CandidateProfile profile, String context) {
            String experience = bestText(profile.experience(), summarizeSentences(context, Set.of("experience", "intern", "work", "company", "completed"), 3));
            if (experience.isBlank()) {
                return "The resume does not show strong formal work experience. The candidate should be presented more through projects, skills, and learning ability.";
            }
            return paragraphs(
                    "The candidate's experience shows practical exposure rather than only theoretical knowledge.",
                    "I would explain it as evidence that they have worked on real tasks, learned tools through use, and connected their technical foundation with practical outcomes.",
                    "This makes the candidate stronger when the role values learning ability, reliability, and the confidence to apply concepts in real situations."
            );
        }

        private static String answerFit(CandidateProfile profile, String context, String question) {
            String role = inferRole(question);
            return paragraphs(
                    "For " + role + ", the candidate looks like a developing but practical profile.",
                    "Their fit comes from " + capabilityText(profile, context) + ", supported by project work that shows they can apply what they learn.",
                    "They would be strongest for beginner, internship, trainee, or junior-level opportunities where learning speed, fundamentals, and project ownership are valued. If the role expects deep professional experience, they may need more production exposure."
            );
        }

        private static String answerStrengths(CandidateProfile profile, String context) {
            return paragraphs(
                    "The candidate's main strengths are practical learning, application-building ability, and " + capabilityText(profile, context) + ".",
                    "The profile gives the impression of someone who is still growing, but who has already started turning knowledge into working projects.",
                    "That makes them a better match for roles that value initiative, fundamentals, and the ability to keep improving."
            );
        }

        private static String answerImprovements(CandidateProfile profile, String context) {
            return paragraphs(
                    "The candidate's improvement areas should be described constructively. The profile shows promise, but it would become stronger with more detailed proof of real-world impact.",
                    "The main gaps are likely deeper professional exposure, clearer project outcomes, and more measurable achievements. For example, projects become more convincing when they explain the problem solved, the tools used, and the result achieved.",
                    "To improve the candidate's profile, they should add stronger project descriptions, quantify outcomes where possible, include deployment or GitHub links, and show how their skills were applied in real situations."
            );
        }

        private static String answerCredentials(CandidateProfile profile) {
            if (profile.credentials().isBlank()) {
                return paragraphs(
                        "I do not see strong certification or achievement details in the resume profile.",
                        "If the candidate has certificates, awards, completed courses, hackathons, or notable academic achievements, adding them would make the profile more credible.",
                        "Without those details, the candidate should be evaluated mainly through education, skills, and project work."
                );
            }
            return paragraphs(
                    "The candidate has additional credentials that support their profile.",
                    "These credentials help show learning effort beyond ordinary coursework and can make the candidate more convincing for beginner or junior opportunities.",
                    "The best way to present them is to connect each certificate or achievement to the skill it proves, rather than listing it alone."
            );
        }

        private static String answerWhy(CandidateProfile profile, String context, String question) {
            String role = inferRole(question);
            return paragraphs(
                    "The candidate is a reasonable match for " + role + " because the resume shows a combination of technical learning and practical application.",
                    "Their strongest support comes from " + capabilityText(profile, context) + ", along with project work that indicates they can apply concepts rather than only memorize them.",
                    "The candidate should be positioned as someone with growth potential: suitable for roles that value fundamentals, consistency, and the ability to learn quickly."
            );
        }

        private static String answerGeneral(String question, CandidateProfile profile, String context) {
            return paragraphs(
                    "For this question, I would understand the candidate as a practical learner with a software-development focus.",
                    "The most relevant interpretation is that the candidate has " + capabilityText(profile, context) + ", with practical proof coming from project work and technical learning.",
                    "So the correct answer is that the candidate has a foundation in this area, but should be presented as an early-career profile unless the resume includes deeper professional experience."
            );
        }

        private static String candidateIdentity(CandidateProfile profile) {
            if (!profile.experience().isBlank()) {
                return "a software-focused candidate with practical exposure";
            }
            if (!profile.education().isBlank() && !profile.projects().isEmpty()) {
                return "a technically oriented student or early-career candidate with project experience";
            }
            if (!profile.projects().isEmpty()) {
                return "an early-career software-development candidate with hands-on project practice";
            }
            return "an early-career candidate with a software-development direction";
        }

        private static String capabilityText(CandidateProfile profile, String context) {
            Set<String> skills = new HashSet<>(profile.skills().stream()
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .toList());
            String combined = String.join(" ", profile.skills()).toLowerCase(Locale.ROOT) + " " + context.toLowerCase(Locale.ROOT);
            List<String> capabilities = new ArrayList<>();

            if (containsAny(combined, "java", "spring", "object", "oops", "collections")) {
                capabilities.add("Java and object-oriented programming");
            }
            if (containsAny(combined, "html", "css", "javascript", "frontend", "front end", "web")) {
                capabilities.add("web application development");
            }
            if (containsAny(combined, "sql", "mysql", "database", "dbms")) {
                capabilities.add("database and data-handling fundamentals");
            }
            if (containsAny(combined, "api", "rest", "http")) {
                capabilities.add("API-based application development");
            }
            if (containsAny(combined, "python", "machine learning", "ai", "rag")) {
                capabilities.add("AI or automation-oriented problem solving");
            }
            if (containsAny(combined, "git", "github")) {
                capabilities.add("basic development workflow awareness");
            }

            if (capabilities.isEmpty() && !skills.isEmpty()) {
                return "a developing technical foundation across the skills listed in the resume";
            }
            if (capabilities.isEmpty()) {
                return "a developing software-development foundation";
            }
            return joinNatural(capabilities, 5);
        }

        private static String projectsInsight(CandidateProfile profile, String context) {
            int projectCount = profile.projects().size();
            if (projectCount > 0) {
                return "Their project work shows practical implementation ability, because the candidate has attempted to build working software rather than only study concepts.";
            }
            String projectSummary = summarizeSentences(context, Set.of("project", "built", "developed", "created", "application", "system", "website"), 2);
            if (!projectSummary.isBlank()) {
                return "Their project work shows practical implementation ability.";
            }
            return "The resume gives limited project detail, so project strength should be described carefully.";
        }

        private static String educationInsight(CandidateProfile profile) {
            if (profile.education().isBlank()) {
                return "";
            }
            return " Their education supports the same direction and gives context for their technical learning.";
        }

        private static String inferRole(String question) {
            String lower = question.toLowerCase(Locale.ROOT);
            if (lower.contains("java")) return "a Java developer role";
            if (lower.contains("web") || lower.contains("frontend") || lower.contains("front end")) return "a web development role";
            if (lower.contains("backend") || lower.contains("back end")) return "a backend development role";
            if (lower.contains("data")) return "a data-focused role";
            return "this role";
        }

        private static String bestText(String primary, String fallback) {
            return primary == null || primary.isBlank() ? fallback : primary;
        }

        private static String joinNatural(List<String> values, int limit) {
            List<String> clean = values.stream()
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .limit(limit)
                    .toList();
            if (clean.isEmpty()) return "the technical areas described in the resume";
            if (clean.size() == 1) return clean.get(0);
            if (clean.size() == 2) return clean.get(0) + " and " + clean.get(1);
            return String.join(", ", clean.subList(0, clean.size() - 1)) + ", and " + clean.get(clean.size() - 1);
        }

        private static String readableKeywordList(String keywords) {
            List<String> values = List.of(keywords.split(",")).stream()
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .limit(8)
                    .toList();
            if (values.isEmpty()) {
                return "the technical areas described in the resume";
            }
            if (values.size() == 1) {
                return values.get(0);
            }
            return String.join(", ", values.subList(0, values.size() - 1)) + ", and " + values.get(values.size() - 1);
        }

        private static String summarizeSentences(String text, Set<String> needles, int limit) {
            return List.of(text.split("(?<=[.!?])\\s+|\\n+")).stream()
                    .map(String::trim)
                    .filter(sentence -> sentence.length() > 12)
                    .filter(sentence -> {
                        String lower = sentence.toLowerCase(Locale.ROOT);
                        return needles.stream().anyMatch(needle -> lower.contains(needle.toLowerCase(Locale.ROOT)));
                    })
                    .limit(limit)
                    .collect(Collectors.joining(" "));
        }

        private static String paraphrase(String text) {
            return text.replace("Name:", "the candidate is")
                    .replace("Professional Summary", "professional summary")
                    .replace("Skills", "skills")
                    .replace("Projects", "projects")
                    .replace("Education", "education")
                    .replace("Experience", "experience")
                    .replaceAll("\\s+", " ")
                    .trim();
        }

        private static String paragraphs(String... values) {
            return List.of(values).stream()
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .map(RagEngine::ensureSentence)
                    .collect(Collectors.joining("\n\n"));
        }

        private static String ensureSentence(String value) {
            String clean = value.replaceAll("\\s+", " ").trim();
            if (clean.isBlank()) {
                return clean;
            }
            if (clean.endsWith(".") || clean.endsWith("!") || clean.endsWith("?")) {
                return clean;
            }
            return clean + ".";
        }

        private static String completeClause(String value) {
            String clean = value.replaceAll("\\s+", " ").trim();
            if (clean.isBlank()) {
                return "an early-career candidate with a software-development focus";
            }
            Matcher sentence = Pattern.compile("^(.+?[.!?])\\s").matcher(clean + " ");
            if (sentence.find()) {
                return sentence.group(1).replaceAll("[.!?]$", "");
            }
            return clean.replaceAll("[.!?]$", "");
        }

        private static String joinSentences(String text, int limit) {
            return List.of(text.split("(?<=[.!?])\\s+|\\n+")).stream()
                    .map(String::trim)
                    .filter(sentence -> sentence.length() > 20)
                    .limit(limit)
                    .collect(Collectors.joining(" "));
        }

        private static String joinMatchingSentences(String text, Set<String> needles, int limit) {
            String result = List.of(text.split("(?<=[.!?])\\s+|\\n+")).stream()
                    .map(String::trim)
                    .filter(sentence -> {
                        String lower = sentence.toLowerCase(Locale.ROOT);
                        return needles.stream().anyMatch(lower::contains);
                    })
                    .limit(limit)
                    .collect(Collectors.joining(" "));
            return result.isBlank() ? joinSentences(text, limit) : result;
        }

        private static String firstSentence(String answer) {
            int dot = answer.indexOf('.');
            return dot > 0 ? answer.substring(0, dot + 1) : answer;
        }

        private static String snippet(String text) {
            String clean = text.replaceAll("\\s+", " ").trim();
            return clean.length() <= 180 ? clean : clean.substring(0, 180) + "...";
        }
    }

    private record CandidateProfile(
            String summary,
            List<String> skills,
            List<String> projects,
            String education,
            String experience,
            String credentials
    ) {
        static CandidateProfile empty() {
            return new CandidateProfile("", List.of(), List.of(), "", "", "");
        }

        static CandidateProfile from(String resume) {
            Map<String, String> sections = sections(resume);
            String summary = firstNonBlank(
                    sections.get("professional summary"),
                    sections.get("summary"),
                    sections.get("profile"),
                    sections.get("objective"),
                    firstParagraph(resume)
            );
            List<String> skills = splitItems(firstNonBlank(
                    sections.get("skills"),
                    sections.get("technical skills"),
                    sections.get("technologies"),
                    sections.get("tools")
            ));
            List<String> projects = splitProjectNames(firstNonBlank(
                    sections.get("projects"),
                    sections.get("project"),
                    sections.get("academic projects"),
                    sections.get("personal projects")
            ));
            String education = firstNonBlank(
                    sections.get("education"),
                    sections.get("academic background"),
                    sections.get("qualification")
            );
            String experience = firstNonBlank(
                    sections.get("experience"),
                    sections.get("work experience"),
                    sections.get("internship"),
                    sections.get("internships")
            );
            String credentials = firstNonBlank(
                    sections.get("certifications"),
                    sections.get("certification"),
                    sections.get("achievements"),
                    sections.get("awards")
            );
            return new CandidateProfile(summary, skills, projects, cleanBlock(education), cleanBlock(experience), cleanBlock(credentials));
        }

        String toPromptText() {
            return "Candidate profile understood from resume:\n"
                    + "Summary: " + valueOrMissing(summary) + "\n"
                    + "Skills: " + (skills.isEmpty() ? "Not clearly listed" : String.join(", ", skills)) + "\n"
                    + "Projects: " + (projects.isEmpty() ? "Not clearly listed" : String.join(", ", projects)) + "\n"
                    + "Education: " + valueOrMissing(education) + "\n"
                    + "Experience: " + valueOrMissing(experience) + "\n"
                    + "Credentials: " + valueOrMissing(credentials);
        }

        private static Map<String, String> sections(String resume) {
            List<String> headings = List.of(
                    "professional summary", "summary", "profile", "objective",
                    "skills", "technical skills", "technologies", "tools",
                    "projects", "project", "academic projects", "personal projects",
                    "education", "academic background", "qualification",
                    "experience", "work experience", "internship", "internships",
                    "certifications", "certification", "achievements", "awards"
            );

            Map<String, String> found = new LinkedHashMap<>();
            String[] lines = resume.replace("\r", "").split("\n");
            String current = "";
            StringBuilder buffer = new StringBuilder();

            for (String line : lines) {
                String normalized = normalizeHeading(line);
                if (headings.contains(normalized)) {
                    if (!current.isBlank()) {
                        found.put(current, buffer.toString().trim());
                    }
                    current = normalized;
                    buffer.setLength(0);
                } else if (!current.isBlank()) {
                    buffer.append(line).append('\n');
                }
            }

            if (!current.isBlank()) {
                found.put(current, buffer.toString().trim());
            }
            return found;
        }

        private static String normalizeHeading(String line) {
            return line.toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z ]", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
        }

        private static List<String> splitItems(String block) {
            if (block == null || block.isBlank()) {
                return List.of();
            }
            return List.of(block.split("[,;\\n•|]+")).stream()
                    .map(CandidateProfile::cleanListItem)
                    .filter(value -> value.length() > 1)
                    .distinct()
                    .limit(18)
                    .toList();
        }

        private static List<String> splitProjectNames(String block) {
            if (block == null || block.isBlank()) {
                return List.of();
            }
            return List.of(block.split("\\n")).stream()
                    .map(CandidateProfile::cleanListItem)
                    .filter(value -> value.length() > 4)
                    .map(value -> {
                        int colon = value.indexOf(':');
                        return colon > 0 ? value.substring(0, colon).trim() : value;
                    })
                    .distinct()
                    .limit(6)
                    .toList();
        }

        private static String cleanListItem(String value) {
            return value.replaceAll("^[\\-–*•\\d.)\\s]+", "")
                    .replaceAll("\\s+", " ")
                    .trim();
        }

        private static String cleanBlock(String value) {
            return value == null ? "" : value.replaceAll("\\s+", " ").trim();
        }

        private static String firstParagraph(String resume) {
            return List.of(resume.split("\\n\\s*\\n")).stream()
                    .map(String::trim)
                    .filter(value -> value.length() > 35)
                    .findFirst()
                    .orElse("");
        }

        private static String firstNonBlank(String... values) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
            return "";
        }

        private static String valueOrMissing(String value) {
            return value == null || value.isBlank() ? "Not clearly listed" : value;
        }
    }

    private record ScoredChunk(String text, double score) {
    }

    private static class AiClient {
        private final String apiKey = System.getenv("OPENAI_API_KEY");
        private final String model = System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4.1-mini");
        private final HttpClient client = HttpClient.newHttpClient();

        boolean isConfigured() {
            return apiKey != null && !apiKey.isBlank();
        }

        String answer(String question, String tone, String context, String requestApiKey, String requestModel) throws IOException, InterruptedException {
            String effectiveApiKey = requestApiKey == null || requestApiKey.isBlank() ? apiKey : requestApiKey;
            String effectiveModel = requestModel == null || requestModel.isBlank() ? model : requestModel;
            String prompt = "You are the candidate's software representative. Answer only from the resume context. "
                    + "First understand the complete candidate profile: summary, skills, projects, education, experience, credentials, strengths, gaps, and role fit. "
                    + "Then answer the user's question in your own words like a polished AI assistant. "
                    + "Do not copy resume lines, do not show resume snippets, do not reveal raw contact details, and do not paste project descriptions. "
                    + "Use the resume only as private knowledge. Convert it into accurate conclusions about the candidate. "
                    + "Focus on the candidate: their strengths, abilities, suitability, background, and potential. "
                    + "Write complete sentences in polished paragraphs. Do not answer with fragments. "
                    + "If the question asks for judgment, give a balanced answer with strengths and limitations. "
                    + "If the answer is missing, say it is not found in the resume. Tone: " + tone + ".\n\n"
                    + "Resume context:\n" + context + "\n\nQuestion: " + question;

            String payload = "{"
                    + "\"model\":\"" + escapeJson(effectiveModel) + "\","
                    + "\"messages\":["
                    + "{\"role\":\"system\",\"content\":\"You answer resume questions accurately and cite only provided context.\"},"
                    + "{\"role\":\"user\",\"content\":\"" + escapeJson(prompt) + "\"}"
                    + "],"
                    + "\"temperature\":0.2"
                    + "}";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + effectiveApiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IOException("OpenAI API error: " + response.body());
            }

            Matcher matcher = Pattern.compile("\"content\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"").matcher(response.body());
            if (matcher.find()) {
                return unescapeJson(matcher.group(1));
            }
            throw new IOException("Could not parse AI response.");
        }
    }
}
