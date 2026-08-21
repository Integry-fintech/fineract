## Description

Describe the changes made and why they were made.

## Checklist

- [ ] The build is green. It is the author's responsibility to get a proposed PR passing CI, not the reviewer's.
- [ ] Unit or integration tests were created/updated to cover the changes.
- [ ] Coding conventions were followed (`./gradlew spotlessApply` and `./gradlew checkstyleMain` pass).
- [ ] Any API change carries the required Swagger annotations and updates the API documentation.
- [ ] Database changes ship as Liquibase changesets, and are backward compatible with the currently deployed version.
- [ ] The change is scoped to one concern. Large refactors go in their own PR.
