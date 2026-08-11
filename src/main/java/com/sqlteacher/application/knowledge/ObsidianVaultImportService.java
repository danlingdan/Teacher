package com.sqlteacher.application.knowledge;

import java.nio.file.Path;
import java.util.List;

/** Safely previews and imports an explicitly selected Obsidian vault. */
public interface ObsidianVaultImportService {
    Preview preview(Path root, ImportMapping mapping);

    ImportReport execute(String previewToken);

    record ImportMapping(String courseTitle, int sectionDepth, boolean includeAttachments) {
        public ImportMapping {
            courseTitle = courseTitle == null ? "" : courseTitle.trim();
            if (courseTitle.isBlank()) courseTitle = "Obsidian 知识库";
            if (sectionDepth < 0 || sectionDepth > 4) {
                throw new IllegalArgumentException("sectionDepth must be between 0 and 4");
            }
        }
    }

    record Preview(String token, String root, ImportMapping mapping, int markdownFiles,
                   int newFiles, int changedFiles, int unchangedFiles, int wikiLinks,
                   int attachments, int missingAttachments, List<PreviewItem> items,
                   List<String> warnings) {
        public Preview {
            items = List.copyOf(items);
            warnings = List.copyOf(warnings);
        }
    }

    record PreviewItem(String relativePath, String title, String sectionTitle, String action,
                       int wikiLinks, int attachments, int missingAttachments) {
    }

    record ImportReport(String root, int imported, int revised, int skipped, int failed,
                        int attachmentsResolved, int attachmentsMissing, List<String> errors) {
        public ImportReport {
            errors = List.copyOf(errors);
        }
    }
}
