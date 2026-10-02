# PostgreSQL 16 with both Apache AGE and pgvector, for PostgresAgeStorageProviderIntegrationTest.
# The official apache/age image does not bundle pgvector, and the full provider bootstrap needs it.
FROM apache/age:release_PG16_1.6.0
RUN apt-get update \
    && apt-get install -y --no-install-recommends postgresql-16-pgvector \
    && rm -rf /var/lib/apt/lists/*
