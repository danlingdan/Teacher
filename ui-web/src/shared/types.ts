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

export interface KnowledgeSample {
  id: string;
  title: string;
  markdown: string;
  trustedHtml: false;
  externalResourcesAllowed: false;
}

export interface CourseActivity { id: string; title: string; type: string; difficulty: string; estimatedMinutes: number; enabled: boolean; knowledgePoints: string[]; }
export interface CourseSection { id: string; title: string; sortOrder: number; activities: CourseActivity[]; }
export interface CourseSummary { id: string; title: string; version: string; sections: CourseSection[]; }
export interface KnowledgeArticle { id: string; courseTitle: string; sectionTitle: string; title: string; visibility: string; currentRevision: number; knowledgePoints: string[]; contentHash: string; updatedAt: string; }
export interface CourseWorkspace { courses: CourseSummary[]; articles: KnowledgeArticle[]; articleCount: number; }
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
export interface RunnerCapability { language: "JAVA" | "PYTHON" | "C" | "CPP"; available: boolean; reasonCode: string; }
export interface RunnerResult { failureReason: string; exitCode: number; standardOutput: string; standardError: string; resourceUsage: { wallTime: number | string; outputBytes: number; filesCreated: number }; }

export interface ConnectionSummary { id: string; displayName: string; dialect: string; readOnly: boolean; enabled: boolean; builtIn: boolean; selected: boolean; }
export interface DatabaseColumn { name: string; typeName: string; nullable: boolean; primaryKey: boolean; }
export interface DatabaseTable { name: string; columns: DatabaseColumn[]; }
export interface SqlRisk { level: string; executable: boolean; confirmationRequired: boolean; multiStatement: boolean; statementType: string; reasons: string[]; confirmationToken?: string; confirmationExpiresAt?: string; enforcedBy: "java"; maxRows: number; timeoutSeconds: number; }
export interface SqlPage { resultId: string; success: boolean; columns: string[]; rows: Array<Record<string, unknown>>; page: number; pageSize: number; totalRows: number; hasMore: boolean; affectedRows: number; truncated: boolean; message: string; durationMillis: number; auditRecorded: boolean; }
export interface AiKnowledgeAnswer { aiGenerated: boolean; answer: string; model: string; citations: Array<{ number: number; documentId: string; articleTitle: string; revision: number; chunkIndex: number; snippet: string }>; message: string; }

export interface TeachingWorkspace {
  role: AppRole;
  canPublish: boolean;
  authority: "java-and-cloud-server";
  exercises: ExerciseSummary[];
  progressOverview: { sessions: number; attempts: number; submissions: number; passedSubmissions: number; submissionPassRate: number; averageSubmissionDuration: number | string; hintsUsed: number; completedExercises: number };
  progressItems: Array<{ exerciseId: string; title: string; knowledgePoint: string; attempts: number; failedSubmissions: number; passed: boolean; lastAttemptAt?: string }>;
}

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

export interface SettingsWorkspace {
  role: AppRole;
  developerMode: boolean;
  canMaintainLocalData: boolean;
  connectivity: string;
  secretsExposed: false;
  manualPathPolicy: string;
  general: { automaticUpdateChecks: boolean; reducedMotion: boolean; highContrast: boolean; language: string; nativeNotificationsEnabled: boolean; meteredNetwork: boolean };
  storage: { categoryBytes: Record<string, number>; usableBytes: number };
  runnerCapabilities: RunnerCapability[];
  components: Array<{ id: string; displayName: string; state: string; detail: string; source: string; license: string; requiresAdministrator: boolean; restartMayBeRequired: boolean }>;
}

export interface MigrationStatus {
  version: string;
  stage: "ALPHA_COMPLETE";
  defaultProductionUiChanged: false;
  javaFxFallback: boolean;
  offlineCore: boolean;
  schemaSemanticsChanged: boolean;
  features: Array<{ id: string; title: string; status: "COMPLETE" }>;
}
