export interface HealthResult {
  status: "ready";
  contractVersion: string;
  applicationVersion: string;
  javaVersion: string;
  javaVendor: string;
  coreInitialized: boolean;
  timestamp: string;
}

export type AppRole = "STUDENT" | "TEACHER" | "ADMINISTRATOR";

export interface SessionResult {
  subjectId: string;
  displayName: string;
  role: AppRole;
  authenticated: boolean;
  permissions: string[];
  roleLabel?: string;
}

export interface LearningActionSummary {
  id: string;
  type: string;
  title: string;
  description: string;
  priority: number;
  exerciseId: string;
  knowledgePoint: string;
  reason: string;
}

export interface HomeSummary {
  ownerId: string;
  policyVersion: string;
  knowledgePointCount: number;
  needsPracticeCount: number;
  cloudAvailable: boolean;
  calculationMillis: number;
  actions: LearningActionSummary[];
}

export interface CourseActivity { id: string; title: string; type: string; difficulty: string; estimatedMinutes: number; enabled: boolean; knowledgePoints: string[]; }
export interface CourseSection { id: string; title: string; sortOrder: number; activities: CourseActivity[]; }
export interface CourseSummary { id: string; title: string; version: string; sections: CourseSection[]; }
export interface KnowledgeArticle { id: string; courseTitle: string; sectionTitle: string; title: string; visibility: string; currentRevision: number; knowledgePoints: string[]; contentHash: string; updatedAt: string; }
export interface CourseWorkspace { courses: CourseSummary[]; articles: KnowledgeArticle[]; articleCount: number; }
export interface ActivityDefinition {
  id: string; courseId: string; sectionId: string; title: string; description: string;
  knowledgePointIds: string[]; difficulty: string; estimatedMinutes: number; version: number;
  enabled: boolean; type: "QUIZ" | "TRACE" | "SIMULATION" | "CODE" | "PROJECT" | "LAB" | "READING" | "SQL";
  nextSubmissionVersion: number; latestFeedback?: { comment: string; createdAt: string };
  specification: Record<string, unknown>;
}
export interface ActivitySubmission {
  sessionId: string; evaluationId: string; occurredAt: string;
  evaluation: { status: string; passed: boolean; summary: string; reasonCode: string; criteria: Array<{ criterion: string; passed: boolean; feedback: string }> };
}
export interface KnowledgeArticleDetail { article: KnowledgeArticle; markdown: string; sourceName: string; revision: number; trustedHtml: false; externalResourcesAllowed: false; }
export interface KnowledgeSearchItem { articleId: string; documentId: string; title: string; sourceName: string; chunkIndex: number; snippet: string; relevance: number; }
export interface KnowledgeSearchResult { items: KnowledgeSearchItem[]; }
export interface ImportPreviewItem { relativePath: string; title: string; sectionTitle: string; action: "IMPORT" | "REVISE" | "SKIP"; wikiLinks: number; attachments: number; missingAttachments: number; }
export interface ImportPreview { token: string; root: string; markdownFiles: number; newFiles: number; changedFiles: number; unchangedFiles: number; wikiLinks: number; attachments: number; missingAttachments: number; items: ImportPreviewItem[]; warnings: string[]; }
export interface ImportReport { root: string; imported: number; revised: number; skipped: number; failed: number; attachmentsResolved: number; attachmentsMissing: number; errors: string[]; }

export interface ExerciseSummary { id: string; title: string; knowledgePoint: string; difficulty: string; version: number; enabled: boolean; }
export interface ExerciseView extends ExerciseSummary { description: string; schemaSummary: string; }
export interface ExerciseSession { id: string; exercise: ExerciseView; startedAt: string; hintsUsed: number; completed: boolean; }
export interface ExerciseAttempt { attemptId: string; sessionId: string; status: string; execution?: SqlPage; evaluation?: { passed: boolean; feedback: string; errorCode: string; criteria: Array<{ criterion: string; passed: boolean; feedback: string }> }; occurredAt: string; }
export interface ExerciseHint { level: number; text: string; exhausted: boolean; }
export interface RunnerCapability { language: "JAVA" | "PYTHON" | "C" | "CPP"; available: boolean; reasonCode: string; }
export interface RunnerResult { failureReason: string; exitCode: number; standardOutput: string; standardError: string; resourceUsage: { wallTime: number | string; outputBytes: number; filesCreated: number }; }

export interface ConnectionSummary {
  id: string; displayName: string; dialect: string; readOnly: boolean; enabled: boolean; builtIn: boolean; selected: boolean;
  databasePath?: string; host?: string; port?: number; databaseName?: string; username?: string;
  jdbcUrl?: string; driverClass?: string; driverJar?: string;
}
export interface ConnectionTestResult { successful: boolean; message: string; databaseProduct: string; databaseVersion: string; elapsed: number | string; }
export interface DatabaseColumn { name: string; typeName: string; nullable: boolean; primaryKey: boolean; }
export interface DatabaseTable { name: string; columns: DatabaseColumn[]; }
export interface SqlRisk { level: string; executable: boolean; confirmationRequired: boolean; multiStatement: boolean; statementType: string; reasons: string[]; confirmationToken?: string; confirmationExpiresAt?: string; enforcedBy: "java"; maxRows: number; timeoutSeconds: number; }
export interface SqlPage { resultId: string; success: boolean; columns: string[]; rows: Array<Record<string, unknown>>; page: number; pageSize: number; totalRows: number; hasMore: boolean; affectedRows: number; truncated: boolean; message: string; durationMillis: number; auditRecorded: boolean; }
export interface AiKnowledgeAnswer { aiGenerated: boolean; answer: string; model: string; citations: Array<{ number: number; documentId: string; articleTitle: string; revision: number; chunkIndex: number; snippet: string }>; message: string; }
export interface AiContextPreview { taskType: string; categories: string[]; sources: string[]; characterCount: number; redactions: string[]; }
export interface Nl2SqlSafetyResult { plan: { sqlDraft: string; intent: string; explanation: string; model: string; promptVersion: string }; riskAnalysis: SqlRisk; accepted: boolean; draftAvailable: boolean; }

export interface TeachingWorkspace {
  role: AppRole;
  canPublish: boolean;
  authority: "java-and-cloud-server";
  exercises: ExerciseSummary[];
  progressOverview: { sessions: number; attempts: number; submissions: number; passedSubmissions: number; submissionPassRate: number; averageSubmissionDuration: number | string; hintsUsed: number; completedExercises: number };
  progressItems: Array<{ exerciseId: string; title: string; knowledgePoint: string; attempts: number; failedSubmissions: number; passed: boolean; lastAttemptAt?: string }>;
  datasets: Array<{ id: string; name: string; setupSql: string; version: number }>;
}
export interface ExerciseDefinition extends ExerciseSummary { description: string; datasetId: string; referenceSql: string; evaluationRule: { compareColumns: boolean; compareRows: boolean; rowOrderMatters: boolean; expectedRowCount?: number; requiredSqlKeywords: string[] }; hints: string[]; createdAt: string; updatedAt: string; }
export interface InterventionCandidate { id: string; classroomName: string; assignmentTitle: string; studentDisplayName: string; reason: string; evidenceSummary: string; priority: number; status: "OPEN" | "ACKNOWLEDGED" | "RESOLVED" | "DISMISSED"; updatedAt: string; }
export interface LearningAnalytics { generatedAt: string; overview: Record<string, number>; exercises: Array<Record<string, unknown>>; commonErrors: Array<Record<string, unknown>>; knowledgePoints: Array<Record<string, unknown>>; }

export interface CloudWorkspace {
  signedIn: boolean;
  state: "SIGNED_OUT" | "LOCAL_READY" | "READY" | "DEGRADED";
  message: string;
  displayName?: string;
  role?: AppRole;
  errorCode?: string;
  recoverable: boolean;
  sync: { state: string; pending: number; attempt: number; lastSuccessAt?: string; nextRetryAt?: string; errorCode?: string };
  classes: Array<{ id: string; name: string; createdAt: string; members: Array<{ userId: string; role: string }> }>;
}
export interface CloudAssignment { id: string; classroomId: string; exerciseId: string; title: string; description: string; status: "DRAFT" | "PUBLISHED" | "CLOSED" | "WITHDRAWN" | "ARCHIVED"; dueAt?: string; createdAt: string; updatedAt: string; version: number; }
export interface ActiveSession { id: string; current: boolean; createdAt: string; lastSeenAt: string; userAgent?: string; ipAddress?: string; }

export interface SettingsWorkspace {
  role: AppRole;
  developerMode: boolean;
  canMaintainLocalData: boolean;
  connectivity: string;
  secretsExposed: false;
  manualPathPolicy: string;
  general: { automaticUpdateChecks: boolean; skippedVersion: string; proxyMode: "DIRECT" | "SYSTEM" | "MANUAL"; proxyHost: string; proxyPort: number; reducedMotion: boolean; highContrast: boolean; supportLogging: boolean; supportLoggingExpiresAt: number; updateMirrorsEnabled: boolean; language: "zh" | "en"; nativeNotificationsEnabled: boolean; meteredNetwork: boolean; theme: "system" | "light" | "dark"; font: "modern" | "system" | "classic"; density: "comfortable" | "compact" };
  storage: { categoryBytes: Record<string, number>; usableBytes: number };
  runnerCapabilities: RunnerCapability[];
  components: Array<{ id: string; displayName: string; state: string; detail: string; source: string; license: string; requiresAdministrator: boolean; restartMayBeRequired: boolean }>;
  notifications: Array<{ id: string; category: string; title: string; message: string; target: string; createdAt: string; read: boolean }>;
  tasks: Array<{ id: string; type: string; title: string; progress: number; state: string; errorCode: string; retryable: boolean }>;
  helpTopics: string[];
}
export interface BackupSnapshot { id: string; createdAt: string; sizeBytes: number; automatic: boolean; }
export interface UpdateCheck { status: string; message: string; available?: { version?: string | { major: number; minor: number; patch: number }; releaseNotesUrl?: string }; }
