# Attachment dependency gate — 2026-10-12

Status: contracts and Java 8 packaging gates passed; security lock and real WildFly 10 gate remain OPEN. Do not treat this document as full attachment acceptance.

## Candidate decisions and primary-source evidence

- Initial candidates MinIO 8.5.17 / PDFBox 2.0.34 were tested first, then rejected as the final lock.
- [MinIO advisory GHSA-h7rh-xfpj-hpcm](https://github.com/minio/minio-java/security/advisories/GHSA-h7rh-xfpj-hpcm), published 2025-09-29: high severity XML property/environment substitution, affected <8.6.0, patched 8.6.0. The [8.6.0 release](https://github.com/minio/minio-java/releases/tag/8.6.0), published 2025-09-26, also records commons-compress update for CVE-2025-48924. Candidate is now MinIO 8.6.0; architecture and public store interface retained.
- [Apache PDFBox security table](https://pdfbox.apache.org/security.html): CVE-2026-23907 affects 2.0.24–2.0.35, fixed 2.0.36; CVE-2026-33929 affects 2.0.24–2.0.36, fixed 2.0.37. These affect ExtractEmbeddedFiles example code; the slice rejects embedded files and does not package examples. Still select maintained 2.0.37. [Downloads](https://pdfbox.apache.org/download.html) identifies 2.0.37 as requiring Java 6.
- [MinIO 8.6.0 Maven POM](https://repo.maven.apache.org/maven2/io/minio/minio/8.6.0/minio-8.6.0.pom) selects OkHttp 5.1.0 and Jackson 2.19.1. Maven resolves okhttp metadata without JVM classes: probe test compilation failed on okhttp3.HttpUrl. Explicit okhttp-jvm 5.1.0 restores the JVM artifact. Both SDK instantiation and all packaged base-class bytecodes then pass on Java 1.8.0_112.
- MinIO initially produced Jackson core/annotations 2.19.1 with managed databind 2.15.4. PaaS declares local databind 2.19.1, yielding one coherent local trio; contract and Portal POM/version management were not modified.
- Jackson 2.19.1 was subsequently rejected: [GHSA-wv8q-qhhj-9h54](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-wv8q-qhhj-9h54) (unknown fallback type-ID retention) and [GHSA-cxp5-3px4-pw24](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-cxp5-3px4-pw24) (quadratic identity-reference resolution), both high, affect 2.19.0–2.21.6 and patch at 2.21.7. No 2.19 patch can fix these ranges. PaaS imports local Jackson BOM2.21.7 and declares databind2.21.7; lower patched 2.18.11 is below the SDK compile dependency and was not blindly selected. This also supersedes the earlier polymorphic/DNS advisories. Contract and Portal remain at baseline2.15.4; their pre-existing dependency risks are not reclassified as fixed.
- [Guava GHSA-xxph-c9ww-hj94](https://github.com/google/guava/security/advisories/GHSA-xxph-c9ww-hj94) reports medium uncontrolled allocation during Java deserialization, affected4.0–33.7.1, patched33.7.2. MinIO selects33.4.8-jre, so PaaS explicitly selects33.7.2-jre. Official Maven artifact existence verified HTTP200. Java8/WAR checks are the gate, not an assumption from the version.
- SECURITY STILL OPEN: the reviewed known MinIO/PDFBox/Jackson/Guava advisories drove patched candidates. Square/BouncyCastle advisory endpoint reads were empty/unavailable, not comprehensive proof of absence. Transitive coverage is incomplete, real container fixture remains absent. No blanket vulnerability-free claim.

Internet collection used agent-reach web routing first. Jina HTTPS/HTTP attempts timed out, mcporter was absent, connector fallback returned transport failure. Direct official-source HTTP reads succeeded; no third-party summaries used as authority.

## Actual verification

- Java 1.8.0_112 throughout Maven; compiler target 1.8.
- First focused RED: missing new contract types/enum constants (test compilation failure).
- SDK RED after contract implementation: ClassNotFoundException io.minio.MinioClient.
- Descriptor RED: missing AttachmentDescriptors / limits / exception.
- Security version RED: old MinIO candidate fails required 8.6.0 selection.
- Additional dependency RED: new probe compilation could not access okhttp3.HttpUrl; resolved by explicit JVM artifact.
- Package gate: success on changed candidates; SDK probe is test-only and absent from product WAR.
- Whole course suite: exit 0, 415 tests found, 378 executed, 37 optional MySQL skipped; no failures/errors. Compiler deprecation and Tomcat/JDK reflection/cache warnings persist from baseline.
- Final focused gate: exit 0, 11 found / 10 executed / 1 skipped. Skipped item is ownedWildFlyJava8Probe because COURSE_ATTACHMENTS_FIXTURE is absent. This is explicitly NOT a compatible-container pass.
- Packaged WAR rejects Java EE/RESTEasy jars, confirms test probe exclusion, scans every base .class major <=52. Multi-release META-INF/versions and module-info entries are excluded because Java8 does not load them.
- Class load and MinioClient builder instantiation passed. No network storage operation is implied.
- Real WildFly 10 probe remains unexecuted. Test builds a separate probe WAR containing product dependency jars and only a test servlet, runs Task9 ownership guard immediately before copying to owned deployments, then checks actual HTTP JSON and Java8 runtime. Fixture schema/guard integration must be reviewed with Task9 before enabling it.

## WAR library inventory

annotations-13.0; bcprov-jdk18on-1.81; commons-codec-1.18.0; commons-compress-1.28.0; commons-io-2.1 (existing baseline); commons-lang3-3.18.0; commons-logging-1.2; contract-0.1.0-SNAPSHOT; error_prone_annotations-2.36.0; failureaccess-1.0.3; fontbox-2.0.37; guava-33.4.8-jre; j2objc-annotations-3.0.0; jackson-annotations/core/databind-2.19.1; jbcrypt-0.4; jspecify-1.0.0; kotlin-stdlib-2.2.0; listenablefuture-9999.0-empty-to-avoid-conflict-with-guava; minio-8.6.0; okhttp/okhttp-jvm-5.1.0; okio/okio-jvm-3.15.0; pdfbox-2.0.37; simple-xml-safe-2.7.1; snappy-java-1.1.10.7.

Evidence logs remain ignored under .superpowers/sdd/2026-10-12-medical-cloud-course-attachments/: task-1-red.log, task-1-sdk-red.log, task-1-descriptors-red.log, task-1-security-red.log, task-1-green.log, task-1-dependency-tree.log, task-1-package.log, task-1-full-suite.log, task-1-final-focused.log.

## Later stable candidate pass

Jackson2.21.7 version assertion RED: task-1-jackson-red.log exit1. Jackson package and focused gates then exit0, 11 found/10 executed/1 fixture skipped; Java8/base52/product-WAR checks passed. Guava version assertion RED: task-1-guava-red.log exit1. Final stable package+dependency tree is task-1-final-package-tree.log exit0. Final whole-course suite task-1-stable-full-suite.log/session47133 exit0:416 found/378 executed/38 skipped (37 optional MySQL,1 real container fixture), failures0/errors0;1m54s. Known compiler/Tomcat warnings remain. Final SDK instantiation/base52/WAR tests pass. Final inventory supersedes earlier history with Guava33.7.2-jre, Jackson databind/core2.21.7 and annotations2.21 (exact official BOM mapping). Contract/Portal stay2.15.4. No real WildFly pass inferred.
