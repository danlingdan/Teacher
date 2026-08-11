export interface HealthResult {
  status: "ready";
  contractVersion: string;
  applicationVersion: string;
  javaVersion: string;
  javaVendor: string;
  coreInitialized: boolean;
  timestamp: string;
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
