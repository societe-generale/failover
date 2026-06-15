/*
 * Copyright 2022-2026, Société Générale All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.societegenerale.failover.store.jdbc.resolver;

import com.societegenerale.failover.core.payload.ReferentialPayload;
import com.societegenerale.failover.store.jdbc.serializer.Serializer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

import java.sql.Timestamp;
import java.sql.Types;
import java.util.regex.Pattern;

import static org.springframework.util.StringUtils.replace;

/**
 * Default {@link FailoverStoreQueryResolver} that resolves all JDBC queries against a given table
 * prefix, selects the appropriate native merge/upsert dialect for the detected database, and owns
 * all parameter binding and result-set mapping logic.
 *
 * <p>Keeping SQL text, column order, SQL types, and parameter builders co-located here ensures
 * that a DDL change (e.g. adding or reordering a column) requires edits in exactly one class.
 *
 * <h2>No schema qualifier in SQL</h2>
 * <p>All queries target {@code {tablePrefix}FAILOVER_STORE} with no schema prefix. Schema-level
 * tenant isolation is achieved at the {@link javax.sql.DataSource} level: each tenant's
 * {@code FailoverStoreJdbc} is wired with a dedicated {@link org.springframework.jdbc.core.JdbcTemplate}
 * pointing to that tenant's schema or database. The SQL itself never changes between tenants —
 * only the connection it runs on differs.
 *
 * <h2>Supported databases and merge strategy</h2>
 * <ul>
 *   <li><b>H2</b> — {@code MERGE INTO ... KEY (FAILOVER_NAME, FAILOVER_KEY)}</li>
 *   <li><b>PostgreSQL</b> — {@code INSERT ... ON CONFLICT (FAILOVER_NAME, FAILOVER_KEY) DO UPDATE SET ...}
 *       (requires a {@code UNIQUE} or {@code PRIMARY KEY} constraint on those two columns)</li>
 *   <li><b>MySQL / MariaDB</b> — {@code INSERT ... ON DUPLICATE KEY UPDATE ...}</li>
 *   <li><b>Oracle</b> — {@code MERGE INTO ... USING (SELECT ... FROM DUAL)}</li>
 *   <li><b>Other</b> — falls back to separate INSERT + UPDATE (no native upsert)</li>
 * </ul>
 *
 * <p>This class has no I/O dependencies and is fully unit-testable.
 *
 * @author Anand Manissery
 */
@Slf4j
public class DefaultFailoverStoreQueryResolver implements FailoverStoreQueryResolver {

    private static final String PREFIX = "%PREFIX%";

    /** Identifier characters, optionally in dot-separated qualifier segments (e.g. "MYAPP_", "SCHEMA.", "SCHEMA.MYAPP_"). */
    private static final Pattern TABLE_PREFIX_PATTERN = Pattern.compile("([A-Za-z0-9_]+\\.)*[A-Za-z0-9_]*");

    // -----------------------------------------------------------------
    // SQL templates  (prefix placeholder = %PREFIX%)
    // -----------------------------------------------------------------

    /** Params: FAILOVER_NAME, FAILOVER_KEY, AS_OF, EXPIRE_ON, PAYLOAD, PAYLOAD_CLASS */
    private static final String INSERT_SQL = "INSERT INTO " + PREFIX + "FAILOVER_STORE (FAILOVER_NAME, FAILOVER_KEY, AS_OF, EXPIRE_ON, PAYLOAD, PAYLOAD_CLASS) VALUES (?, ?, ?, ?, ?, ?)";

    /** Params: AS_OF, EXPIRE_ON, PAYLOAD, PAYLOAD_CLASS, FAILOVER_NAME, FAILOVER_KEY */
    private static final String UPDATE_SQL = "UPDATE " + PREFIX + "FAILOVER_STORE SET AS_OF = ? , EXPIRE_ON = ? , PAYLOAD = ? , PAYLOAD_CLASS = ? WHERE FAILOVER_NAME = ? AND FAILOVER_KEY = ?";

    private static final String SELECT_SQL              = "SELECT FAILOVER_NAME, FAILOVER_KEY, AS_OF, EXPIRE_ON, PAYLOAD, PAYLOAD_CLASS FROM " + PREFIX + "FAILOVER_STORE WHERE FAILOVER_NAME = ? AND FAILOVER_KEY = ?";
    private static final String SELECT_ALL_BY_NAME_SQL  = "SELECT FAILOVER_NAME, FAILOVER_KEY, AS_OF, EXPIRE_ON, PAYLOAD, PAYLOAD_CLASS FROM " + PREFIX + "FAILOVER_STORE WHERE FAILOVER_NAME = ?";
    private static final String DELETE_SQL              = "DELETE FROM " + PREFIX + "FAILOVER_STORE WHERE FAILOVER_NAME = ? AND FAILOVER_KEY = ?";
    private static final String CLEAN_UP_SQL = "DELETE FROM " + PREFIX + "FAILOVER_STORE WHERE EXPIRE_ON < ?";

    /** H2 native MERGE. Params: FAILOVER_NAME, FAILOVER_KEY, AS_OF, EXPIRE_ON, PAYLOAD, PAYLOAD_CLASS */
    private static final String MERGE_SQL_H2 = "MERGE INTO " + PREFIX + "FAILOVER_STORE (FAILOVER_NAME, FAILOVER_KEY, AS_OF, EXPIRE_ON, PAYLOAD, PAYLOAD_CLASS) KEY (FAILOVER_NAME, FAILOVER_KEY) VALUES (?, ?, ?, ?, ?, ?)";

    /** PostgreSQL ON CONFLICT. Params: FAILOVER_NAME, FAILOVER_KEY, AS_OF, EXPIRE_ON, PAYLOAD, PAYLOAD_CLASS */
    private static final String MERGE_SQL_POSTGRESQL = "INSERT INTO " + PREFIX + "FAILOVER_STORE (FAILOVER_NAME, FAILOVER_KEY, AS_OF, EXPIRE_ON, PAYLOAD, PAYLOAD_CLASS) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (FAILOVER_NAME, FAILOVER_KEY) DO UPDATE SET AS_OF = EXCLUDED.AS_OF, EXPIRE_ON = EXCLUDED.EXPIRE_ON, PAYLOAD = EXCLUDED.PAYLOAD, PAYLOAD_CLASS = EXCLUDED.PAYLOAD_CLASS";

    /** MySQL / MariaDB ON DUPLICATE KEY. Params: FAILOVER_NAME, FAILOVER_KEY, AS_OF, EXPIRE_ON, PAYLOAD, PAYLOAD_CLASS */
    private static final String MERGE_SQL_MYSQL = "INSERT INTO " + PREFIX + "FAILOVER_STORE (FAILOVER_NAME, FAILOVER_KEY, AS_OF, EXPIRE_ON, PAYLOAD, PAYLOAD_CLASS) VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE AS_OF = VALUES(AS_OF), EXPIRE_ON = VALUES(EXPIRE_ON), PAYLOAD = VALUES(PAYLOAD), PAYLOAD_CLASS = VALUES(PAYLOAD_CLASS)";

    /** Oracle MERGE USING subquery. Params: FAILOVER_NAME, FAILOVER_KEY, AS_OF, EXPIRE_ON, PAYLOAD, PAYLOAD_CLASS */
    private static final String MERGE_SQL_ORACLE = "MERGE INTO " + PREFIX + "FAILOVER_STORE target USING (SELECT ? AS FAILOVER_NAME, ? AS FAILOVER_KEY, ? AS AS_OF, ? AS EXPIRE_ON, ? AS PAYLOAD, ? AS PAYLOAD_CLASS FROM DUAL) src ON (target.FAILOVER_NAME = src.FAILOVER_NAME AND target.FAILOVER_KEY = src.FAILOVER_KEY) WHEN MATCHED THEN UPDATE SET target.AS_OF = src.AS_OF, target.EXPIRE_ON = src.EXPIRE_ON, target.PAYLOAD = src.PAYLOAD, target.PAYLOAD_CLASS = src.PAYLOAD_CLASS WHEN NOT MATCHED THEN INSERT (FAILOVER_NAME, FAILOVER_KEY, AS_OF, EXPIRE_ON, PAYLOAD, PAYLOAD_CLASS) VALUES (src.FAILOVER_NAME, src.FAILOVER_KEY, src.AS_OF, src.EXPIRE_ON, src.PAYLOAD, src.PAYLOAD_CLASS)";

    // -----------------------------------------------------------------
    // Resolved queries (prefix substituted at construction time)
    // -----------------------------------------------------------------

    @Getter private final String insertQuery;
    @Getter private final String updateQuery;
    @Getter private final String selectQuery;
    @Getter private final String selectAllByNameQuery;
    @Getter private final String deleteQuery;
    @Getter private final String cleanUpQuery;

    /**
     * Native merge/upsert query resolved for the detected database, or {@code null} when
     * no known dialect is available — the store falls back to INSERT + UPDATE in that case.
     */
    @Nullable
    @Getter private final String mergeQuery;

    // -----------------------------------------------------------------
    // Dependencies for parameter binding and result-set mapping
    // -----------------------------------------------------------------

    private final Serializer serializer;

    private final PayloadColumnResolver payloadColumnResolver;

    // -----------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------

    /**
     * Constructs the resolver: substitutes {@code tablePrefix} into all SQL templates and
     * selects the appropriate merge dialect from the database product name.
     *
     * @param tablePrefix          prefix prepended to {@code FAILOVER_STORE} in every SQL statement
     *                             (e.g. {@code "MYAPP_"} produces {@code "MYAPP_FAILOVER_STORE"})
     * @param serializer           used to serialize payloads and resolve class names for parameter binding
     * @param databaseResolver     detects the database product name for merge dialect selection
     * @param payloadColumnResolver determines the SQL type of the PAYLOAD column
     */
    public DefaultFailoverStoreQueryResolver(String tablePrefix, Serializer serializer, DatabaseResolver databaseResolver, PayloadColumnResolver payloadColumnResolver) {
        validateTablePrefix(tablePrefix);
        this.serializer          = serializer;
        this.payloadColumnResolver = payloadColumnResolver;
        this.insertQuery           = applyPrefix(INSERT_SQL,             tablePrefix);
        this.updateQuery           = applyPrefix(UPDATE_SQL,             tablePrefix);
        this.selectQuery           = applyPrefix(SELECT_SQL,             tablePrefix);
        this.selectAllByNameQuery  = applyPrefix(SELECT_ALL_BY_NAME_SQL, tablePrefix);
        this.deleteQuery           = applyPrefix(DELETE_SQL,             tablePrefix);
        this.cleanUpQuery          = applyPrefix(CLEAN_UP_SQL,           tablePrefix);
        this.mergeQuery            = resolveMergeQuery(tablePrefix, databaseResolver.resolve());
    }

    // -----------------------------------------------------------------
    // Parameter builders — kept co-located with SQL to guard column order
    // -----------------------------------------------------------------

    @Override
    public <T> Object[] buildInsertMergeParams(ReferentialPayload<T> p) {
        return new Object[]{
                p.getName(),
                p.getKey(),
                Timestamp.from(p.getAsOf()),
                Timestamp.from(p.getExpireOn()),
                serializer.serialize(p.getPayload()),
                serializer.toClassName(p.getPayload())
        };
    }

    @Override
    public int[] buildInsertMergeTypes() {
        return new int[]{Types.VARCHAR, Types.VARCHAR, Types.TIMESTAMP, Types.TIMESTAMP, payloadColumnResolver.payloadType(), Types.VARCHAR};
    }

    @Override
    public <T> Object[] buildUpdateParams(ReferentialPayload<T> p) {
        return new Object[]{
                Timestamp.from(p.getAsOf()),
                Timestamp.from(p.getExpireOn()),
                serializer.serialize(p.getPayload()),
                serializer.toClassName(p.getPayload()),
                p.getName(),
                p.getKey()
        };
    }

    @Override
    public int[] buildUpdateTypes() {
        return new int[]{Types.TIMESTAMP, Types.TIMESTAMP, payloadColumnResolver.payloadType(), Types.VARCHAR, Types.VARCHAR, Types.VARCHAR};
    }

    // -----------------------------------------------------------------
    // Merge dialect detection
    // -----------------------------------------------------------------

    @Nullable
    private String resolveMergeQuery(String tablePrefix, @Nullable String dbProduct) {
        if (dbProduct == null) {
            log.info("No database product name provided — merge/upsert disabled, using INSERT/UPDATE fallback.");
            return null;
        }
        String db = dbProduct.toLowerCase();
        if (db.contains("h2")) {
            log.info("Database '{}' detected — using H2 native MERGE.", dbProduct);
            return applyPrefix(MERGE_SQL_H2, tablePrefix);
        }
        if (db.contains("postgresql") || db.contains("postgres")) {
            log.info("Database '{}' detected — using ON CONFLICT DO UPDATE merge.", dbProduct);
            return applyPrefix(MERGE_SQL_POSTGRESQL, tablePrefix);
        }
        if (db.contains("mysql") || db.contains("mariadb")) {
            log.info("Database '{}' detected — using ON DUPLICATE KEY UPDATE merge.", dbProduct);
            return applyPrefix(MERGE_SQL_MYSQL, tablePrefix);
        }
        if (db.contains("oracle")) {
            log.info("Database '{}' detected — using Oracle MERGE USING DUAL.", dbProduct);
            return applyPrefix(MERGE_SQL_ORACLE, tablePrefix);
        }
        log.info("Database '{}' has no known merge dialect — using INSERT/UPDATE fallback.", dbProduct);
        return null;
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private static String applyPrefix(String template, String prefix) {
        return replace(template, PREFIX, prefix);
    }

    /**
     * The prefix is concatenated into SQL text as an identifier fragment, so it must never
     * contain anything beyond identifier characters and dot-separated qualifier segments
     * (schema qualification like {@code "SCHEMA."} is supported) — this also catches config
     * typos (quotes, spaces, semicolons) at startup instead of as SQL grammar errors later.
     */
    private static void validateTablePrefix(String tablePrefix) {
        if (tablePrefix == null || !TABLE_PREFIX_PATTERN.matcher(tablePrefix).matches()) {
            throw new IllegalArgumentException(
                    "Invalid failover store table prefix '%s': only letters, digits, underscores and dot-separated qualifiers are allowed (pattern %s)."
                            .formatted(tablePrefix, TABLE_PREFIX_PATTERN.pattern()));
        }
    }
}
