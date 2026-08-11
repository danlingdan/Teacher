package com.sqlteacher.infrastructure.knowledge;

import com.sqlteacher.application.knowledge.CourseKnowledgeArticle;
import com.sqlteacher.application.knowledge.CourseKnowledgeService;
import com.sqlteacher.application.knowledge.ObsidianVaultImportService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class DefaultObsidianVaultImportService implements ObsidianVaultImportService {
    private static final int MAX_FILES = 10_000;
    private static final long MAX_MARKDOWN_BYTES = 2L * 1024 * 1024;
    private static final Pattern WIKI = Pattern.compile("(!)?\\[\\[([^]#|]+)(?:#[^]|]+)?(?:\\|[^]]+)?]]");
    private static final java.util.Set<String> ATTACHMENT_EXTENSIONS = java.util.Set.of(
        ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".pdf"
    );
    private static final long TOKEN_TTL_SECONDS = 15 * 60;

    private final CourseKnowledgeService knowledge;
    private final Map<String, PendingPreview> previews = new ConcurrentHashMap<>();

    public DefaultObsidianVaultImportService(CourseKnowledgeService knowledge) {
        this.knowledge = knowledge;
    }

    @Override
    public Preview preview(Path requestedRoot, ImportMapping mapping) {
        Path root = normalizeRoot(requestedRoot);
        Map<String, CourseKnowledgeArticle> existing = new HashMap<>();
        // CourseKnowledgeArticle intentionally does not expose the source name. Resolve conflicts by
        // inspecting details while keeping imported content inside the application service boundary.
        knowledge.listArticles().forEach(article -> {
            try {
                existing.put(knowledge.getArticle(article.id()).revision().sourceName(), article);
            } catch (RuntimeException ignored) {
                // A concurrently removed article is treated as a new file in this preview.
            }
        });

        List<ScannedFile> scanned = scan(root, mapping, existing);
        String token = UUID.randomUUID().toString();
        previews.put(token, new PendingPreview(root, mapping, scanned, Instant.now()));
        int links = scanned.stream().mapToInt(ScannedFile::wikiLinks).sum();
        int attachments = scanned.stream().mapToInt(ScannedFile::attachments).sum();
        int missing = scanned.stream().mapToInt(ScannedFile::missingAttachments).sum();
        List<String> warnings = new ArrayList<>();
        if (missing > 0) warnings.add("存在 " + missing + " 个无法在知识库根目录内解析的附件引用");
        if (scanned.size() == MAX_FILES) warnings.add("已达到 10,000 个 Markdown 文件的单次导入上限");
        return new Preview(token, root.toString(), mapping, scanned.size(),
            count(scanned, "IMPORT"), count(scanned, "REVISE"), count(scanned, "SKIP"),
            links, attachments, missing, scanned.stream().map(ScannedFile::item).toList(), warnings);
    }

    @Override
    public ImportReport execute(String previewToken) {
        PendingPreview pending = previews.remove(required(previewToken, "previewToken"));
        if (pending == null || pending.createdAt().plusSeconds(TOKEN_TTL_SECONDS).isBefore(Instant.now())) {
            throw new IllegalArgumentException("Import preview is missing or expired");
        }
        int imported = 0;
        int revised = 0;
        int skipped = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();
        for (ScannedFile file : pending.files()) {
            try {
                verifyUnchanged(pending.root(), file);
                if ("SKIP".equals(file.action())) {
                    skipped++;
                } else if ("REVISE".equals(file.action())) {
                    knowledge.reviseArticle(file.existingArticleId(), file.path(), List.of());
                    revised++;
                } else {
                    knowledge.importArticle(file.path(), pending.mapping().courseTitle(), file.sectionTitle(), List.of());
                    imported++;
                }
            } catch (RuntimeException error) {
                failed++;
                errors.add(file.relativePath() + ": " + safeError(error));
            }
        }
        int resolved = pending.files().stream().mapToInt(ScannedFile::attachments).sum()
            - pending.files().stream().mapToInt(ScannedFile::missingAttachments).sum();
        int missing = pending.files().stream().mapToInt(ScannedFile::missingAttachments).sum();
        return new ImportReport(pending.root().toString(), imported, revised, skipped, failed,
            resolved, missing, errors.stream().limit(100).toList());
    }

    private static List<ScannedFile> scan(Path root, ImportMapping mapping,
                                           Map<String, CourseKnowledgeArticle> existing) {
        List<ScannedFile> result = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(item -> item.getFileName().toString().toLowerCase().endsWith(".md"))
                    .sorted(Comparator.comparing(Path::toString)).limit(MAX_FILES).toList()) {
                ensureInside(root, path);
                if (Files.isSymbolicLink(path) || Files.size(path) > MAX_MARKDOWN_BYTES) {
                    throw new IllegalArgumentException("Markdown file is linked or exceeds 2 MiB: " + root.relativize(path));
                }
                String content = Files.readString(path, StandardCharsets.UTF_8);
                String sourceName = root.relativize(path).toString().replace('\\', '/');
                String hash = sha256(normalizeContent(content));
                CourseKnowledgeArticle article = existing.get(path.getFileName().toString());
                String action = article == null ? "IMPORT" : article.contentHash().equals(hash) ? "SKIP" : "REVISE";
                LinkStats links = linkStats(root, path, content, mapping.includeAttachments());
                String title = firstHeading(content, path);
                String section = sectionTitle(root, path, mapping.sectionDepth());
                result.add(new ScannedFile(path, sourceName, title, section, hash, action,
                    article == null ? "" : article.id(), links.wikiLinks(), links.attachments(), links.missingAttachments()));
            }
        } catch (IOException error) {
            throw new IllegalArgumentException("Unable to read the selected Obsidian vault", error);
        }
        return List.copyOf(result);
    }

    private static LinkStats linkStats(Path root, Path note, String content, boolean includeAttachments) {
        int links = 0;
        int attachments = 0;
        int missing = 0;
        Matcher matcher = WIKI.matcher(content);
        while (matcher.find()) {
            links++;
            if (matcher.group(1) == null) continue;
            attachments++;
            String target = matcher.group(2).trim();
            if (!includeAttachments || !attachmentExists(root, note.getParent(), target)) missing++;
        }
        return new LinkStats(links, attachments, missing);
    }

    private static boolean attachmentExists(Path root, Path noteDirectory, String target) {
        String lower = target.toLowerCase();
        if (ATTACHMENT_EXTENSIONS.stream().noneMatch(lower::endsWith)) return false;
        Path direct = noteDirectory.resolve(target).normalize();
        if (direct.startsWith(root) && Files.isRegularFile(direct, LinkOption.NOFOLLOW_LINKS)) return true;
        try (Stream<Path> paths = Files.find(root, 8,
                (path, attrs) -> attrs.isRegularFile() && path.getFileName().toString().equals(Path.of(target).getFileName().toString()))) {
            return paths.findFirst().isPresent();
        } catch (IOException error) {
            return false;
        }
    }

    private static Path normalizeRoot(Path requested) {
        if (requested == null) throw new IllegalArgumentException("root path is required");
        try {
            Path root = requested.toAbsolutePath().normalize().toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
                throw new IllegalArgumentException("Selected Obsidian root must be a real directory");
            }
            return root;
        } catch (IOException error) {
            throw new IllegalArgumentException("Selected Obsidian root does not exist", error);
        }
    }

    private static void verifyUnchanged(Path root, ScannedFile file) {
        ensureInside(root, file.path());
        try {
            if (Files.isSymbolicLink(file.path()) || Files.size(file.path()) > MAX_MARKDOWN_BYTES
                    || !sha256(normalizeContent(Files.readString(file.path(), StandardCharsets.UTF_8))).equals(file.hash())) {
                throw new IllegalArgumentException("File changed after preview");
            }
        } catch (IOException error) {
            throw new IllegalArgumentException("Unable to verify previewed file", error);
        }
    }

    private static void ensureInside(Path root, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) throw new IllegalArgumentException("Path escapes the selected vault");
    }

    private static String sectionTitle(Path root, Path file, int depth) {
        Path parent = root.relativize(file).getParent();
        if (parent == null || depth == 0) return "未分组";
        int index = Math.min(depth - 1, parent.getNameCount() - 1);
        return parent.getName(index).toString();
    }

    private static String firstHeading(String content, Path path) {
        return content.lines().filter(line -> line.startsWith("# ")).map(line -> line.substring(2).trim())
            .filter(value -> !value.isBlank()).findFirst()
            .orElseGet(() -> path.getFileName().toString().replaceFirst("(?i)\\.md$", ""));
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static String normalizeContent(String content) {
        return content.replace("\u0000", "").replace("\r\n", "\n").trim();
    }

    private static int count(List<ScannedFile> files, String action) {
        return (int) files.stream().filter(file -> action.equals(file.action())).count();
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }

    private static String safeError(RuntimeException error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private record PendingPreview(Path root, ImportMapping mapping, List<ScannedFile> files, Instant createdAt) { }
    private record LinkStats(int wikiLinks, int attachments, int missingAttachments) { }
    private record ScannedFile(Path path, String relativePath, String title, String sectionTitle,
                               String hash, String action, String existingArticleId, int wikiLinks,
                               int attachments, int missingAttachments) {
        PreviewItem item() {
            return new PreviewItem(relativePath, title, sectionTitle, action, wikiLinks, attachments, missingAttachments);
        }
    }
}
