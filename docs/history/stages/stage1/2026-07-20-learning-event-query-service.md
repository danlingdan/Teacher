# Learning event query service

## Scope

The July 20 integration window adds `LearningEventQueryService` as the read-side application
contract for the existing `learning_events` store. It supports filtering by event type or
connection and provides simple aggregate counts needed to verify the P0 event-recording flow.
This does not add a dashboard or expose JDBC details outside the infrastructure layer.

## Integration impact

- Application and desktop callers depend only on `LearningEventQueryService` and its immutable
  result records.
- `DatabaseServiceConfig` provides the JDBC implementation when the real database profile is used.
- Existing event writers require no migration. Attribute encoding remains compatible with simple
  existing `key=value` records and now safely round-trips commas, equals signs, and backslashes.
- Time-range bounds are inclusive. A start instant after the end instant is rejected.

## SQL safety update

Risk analysis now removes SQL comments outside quoted literals before classifying the statement.
This keeps ordinary and AI-generated leading comments compatible while ensuring comments cannot
hide forbidden forms such as `DROP /* comment */ DATABASE`.

## Verification

Run:

```powershell
mvn test
```

Focused coverage is provided by `JdbcLearningEventQueryServiceTest`,
`JdbcLearningEventRecorderTest`, `DefaultSqlRiskAnalysisServiceTest`, and
`SqlRiskRegressionTest`.
