package dev.jdx.index.store.sqlite

import dev.jdx.core.model.Access
import dev.jdx.core.model.AnnotationInfo
import dev.jdx.core.model.ClassInfo
import dev.jdx.core.model.ClassSignature
import dev.jdx.core.model.FieldInfo
import dev.jdx.core.model.FieldSignature
import dev.jdx.core.model.GenericSignature
import dev.jdx.core.model.JvmDescriptor
import dev.jdx.core.model.MethodInfo
import dev.jdx.core.model.MethodSignature
import dev.jdx.core.model.ReferenceEdge
import dev.jdx.core.model.ReferenceKind
import dev.jdx.core.model.TypeKind
import dev.jdx.core.model.TypeName
import dev.jdx.core.model.typeNameFromBinaryName
import dev.jdx.index.store.ClassHit
import dev.jdx.index.store.IndexStore
import dev.jdx.index.store.NewArtifact
import dev.jdx.index.store.ReferenceHit
import dev.jdx.index.store.SqliteSchemaVersion
import dev.jdx.index.store.StoredArtifact
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * Thrown when the index database itself cannot be opened, read or written
 * (missing file, newer schema, I/O failure). Agent-triggerable misses (unknown
 * class, unknown artifact) are `null`/`false` returns, never this — so callers
 * can map this to exit 6 and a miss to exit 1 without inspecting messages.
 */
public class IndexStoreException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * The SQLite implementation of [IndexStore] (T-013, D-013).
 *
 * This package is the only place SQL may appear (see `:index` `ModuleInfo`, rule 2).
 * Everything else speaks [ClassInfo] and content hashes.
 *
 * Schema notes (columns beyond PROPOSAL.md §10.3 are deliberate v1 extensions):
 * - `class.fqn` holds the **binary name** (`$`-joined nesting), the key the live
 *   reader matches on — not the dotted FQN, so nested classes meet exactly.
 * - `class.super_fqn` / `class.outer_fqn` denormalise the hierarchy edges as text.
 *   `super_id`/`outer_id` stay `NULL` in v1: a supertype may live in an artifact
 *   that is not indexed yet (or never will be), and dropping the edge when its row
 *   is absent would lie about the hierarchy. T-014 fills the ids when it can.
 * - `member.constant_value` has no §10.3 column (`default_value` is the annotation
 *   element default); fields reuse it instead of overloading `default_value`.
 * - Member order is declaration order: [IndexStore.replaceClasses] inserts fields
 *   then methods (the `ClassInfo.members` order) and every read orders by
 *   `member.id` — ids are monotonic within one replacement, so the order is stable
 *   across processes without an extra position column.
 * - `is_kotlin` is always `0` in v1; `@Metadata` decoding is T-035's job.
 * - `ktmeta`, `doc`, `srcmap`, `ref`, `name_idx` exist so the schema is complete
 *   for T-014/T-017 readers, but no writer fills them yet (sources index lazily
 *   on first source query, PROPOSAL.md §10.4; edges land with T-029).
 */
public class SqliteIndexStore private constructor(
    private val dbFile: String,
    private val connection: Connection,
) : IndexStore {
    private val lock: Any = Any()

    public companion object {
        /** Opens (creating) the store at [dbFile]. Parent directories are created. */
        public fun open(dbFile: Path): SqliteIndexStore {
            if (dbFile.parent != null) {
                try {
                    Files.createDirectories(dbFile.parent)
                } catch (e: Exception) {
                    throw IndexStoreException("cannot create index directory ${dbFile.parent}: ${e.message}", e)
                }
            }
            try {
                Class.forName("org.sqlite.JDBC")
            } catch (e: ClassNotFoundException) {
                throw IndexStoreException("SQLite JDBC driver is not on the classpath", e)
            }
            val connection = try {
                DriverManager.getConnection("jdbc:sqlite:${dbFile.toAbsolutePath()}")
            } catch (e: Exception) {
                throw IndexStoreException("cannot open index database $dbFile: ${e.message}", e)
            }
            val store = SqliteIndexStore(dbFile.toString(), connection)
            try {
                store.configure()
                store.migrate()
            } catch (e: Exception) {
                runCatching { connection.close() }
                throw e
            }
            return store
        }

        /** The shared database path (D-013): `~/.cache/jdx/index/v1.db`. */
        public fun defaultDbFile(): Path =
            Path.of(System.getProperty("user.home"), ".cache", "jdx", "index", "v1.db")
    }

    private fun configure() {
        // WAL: many readers alongside one writer (D-013). NORMAL + 5 s busy wait
        // is the standard multi-process SQLite posture; a corrupt/foreign file
        // surfaces here as an exception, never as a half-open store.
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA journal_mode=WAL")
            statement.execute("PRAGMA synchronous=NORMAL")
            statement.execute("PRAGMA busy_timeout=5000")
        }
    }

    private fun userVersion(): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA user_version").use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    /**
     * Brings the file to [SqliteSchemaVersion.CURRENT]. Each version adds one
     * branch below; a file from a *newer* `jdx` refuses to open (downgrades
     * would silently misread rows) with a message that names both versions.
     */
    private fun migrate() {
        when (val found = userVersion()) {
            0 -> {
                createV1()
                setUserVersion(SqliteSchemaVersion.CURRENT)
            }
            SqliteSchemaVersion.CURRENT -> {
                // A CURRENT stamp with missing tables means a zero-byte or
                // hand-created file — build the schema rather than failing
                // every later statement with "no such table".
                if (!hasArtifactTable()) createV1()
            }
            else -> throw IndexStoreException(
                "index database $dbFile has schema version $found, " +
                    "newer than this jdx understands (${SqliteSchemaVersion.CURRENT}): " +
                    "upgrade jdx instead of downgrading the database",
            )
        }
    }

    private fun hasArtifactTable(): Boolean =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='artifact'",
            ).use { rows -> rows.next() }
        }

    private fun setUserVersion(version: Int) {
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA user_version=$version")
        }
    }

    override fun schemaVersion(): Int = SqliteSchemaVersion.CURRENT

    override fun close() {
        synchronized(lock) {
            runCatching { connection.close() }
        }
    }

    // -- artifacts ----------------------------------------------------------

    override fun upsertArtifact(newArtifact: NewArtifact): StoredArtifact {
        synchronized(lock) {
            return try {
                connection.prepareStatement(
                    "INSERT INTO artifact(hash, path, sources_path, kind, jar_mtime, jar_size, indexed_at, schema_ver) " +
                        "VALUES(?, ?, ?, ?, ?, ?, ?, ?) " +
                        "ON CONFLICT(hash) DO UPDATE SET " +
                        "path=excluded.path, sources_path=excluded.sources_path, kind=excluded.kind, " +
                        "jar_mtime=excluded.jar_mtime, jar_size=excluded.jar_size, " +
                        "indexed_at=excluded.indexed_at, schema_ver=excluded.schema_ver",
                ).use { insert ->
                    val now = System.currentTimeMillis()
                    insert.setString(1, newArtifact.hash)
                    insert.setString(2, newArtifact.path)
                    insert.setNullableString(3, newArtifact.sourcesPath)
                    insert.setString(4, newArtifact.kind)
                    insert.setNullableLong(5, newArtifact.jarMtime)
                    insert.setNullableLong(6, newArtifact.jarSize)
                    insert.setLong(7, now)
                    insert.setInt(8, SqliteSchemaVersion.CURRENT)
                    insert.executeUpdate()
                }
                // Contract: upsert records *fresh* metadata, so the row it returns
                // is always CURRENT. Stale rows surface through find/list (which never
                // write), and the indexer flow is upsert → replaceClasses.
                findArtifactByHash(newArtifact.hash)
                    ?: throw IndexStoreException("upserted artifact ${newArtifact.hash} vanished on read-back")
            } catch (e: IndexStoreException) {
                throw e
            } catch (e: Exception) {
                throw IndexStoreException("cannot record artifact ${newArtifact.hash}: ${e.message}", e)
            }
        }
    }

    override fun findArtifactByHash(hash: String): StoredArtifact? {
        synchronized(lock) {
            return try {
                connection.prepareStatement(
                    "SELECT id, hash, path, sources_path, kind, jar_mtime, jar_size, indexed_at, schema_ver " +
                        "FROM artifact WHERE hash=?",
                ).use { query ->
                    query.setString(1, hash)
                    query.executeQuery().use { rows ->
                        if (!rows.next()) return null
                        readArtifact(rows)
                    }
                }
            } catch (e: Exception) {
                throw IndexStoreException("cannot look up artifact $hash: ${e.message}", e)
            }
        }
    }

    override fun listArtifacts(): List<StoredArtifact> {
        synchronized(lock) {
            return try {
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT id, hash, path, sources_path, kind, jar_mtime, jar_size, indexed_at, schema_ver " +
                            "FROM artifact ORDER BY hash",
                    ).use { rows ->
                        buildList {
                            while (rows.next()) add(readArtifact(rows))
                        }
                    }
                }
            } catch (e: Exception) {
                throw IndexStoreException("cannot list artifacts: ${e.message}", e)
            }
        }
    }

    override fun deleteArtifactByHash(hash: String): Boolean {
        synchronized(lock) {
            try {
                val id = connection.prepareStatement("SELECT id FROM artifact WHERE hash=?").use { query ->
                    query.setString(1, hash)
                    query.executeQuery().use { rows ->
                        if (!rows.next()) return false
                        rows.getLong(1)
                    }
                }
                val wasAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    deleteScopedRows(id)
                    connection.prepareStatement("DELETE FROM artifact WHERE id=?").use { delete ->
                        delete.setLong(1, id)
                        delete.executeUpdate()
                    }
                    connection.commit()
                } catch (e: Exception) {
                    connection.rollback()
                    throw e
                } finally {
                    connection.autoCommit = wasAutoCommit
                }
                return true
            } catch (e: Exception) {
                throw IndexStoreException("cannot delete artifact $hash: ${e.message}", e)
            }
        }
    }

    // -- classes ------------------------------------------------------------

    override fun replaceClasses(artifactId: Long, classes: List<ClassInfo>) {
        synchronized(lock) {
            try {
                val wasAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    deleteScopedRows(artifactId)
                    // Statements are prepared once per artifact, not once per row:
                    // a 33k-class JDK writes ~700k rows, and re-preparing each one
                    // costs more than executing it (T-014 benchmark). Order, ids
                    // and bytes are unchanged — only the preparation moves out of
                    // the loop.
                    BulkWriter(connection).use { writer ->
                        for (clazz in classes) writer.insertClass(artifactId, clazz)
                    }
                    connection.commit()
                } catch (e: Exception) {
                    connection.rollback()
                    throw e
                } finally {
                    connection.autoCommit = wasAutoCommit
                }
            } catch (e: IndexStoreException) {
                throw e
            } catch (e: Exception) {
                throw IndexStoreException("cannot index ${classes.size} classes: ${e.message}", e)
            }
        }
    }

    override fun findClassesByFqn(binaryName: String): List<ClassHit> {
        synchronized(lock) {
            return try {
                connection.prepareStatement(
                    "SELECT a.id, a.hash, a.path, a.sources_path, a.kind, a.jar_mtime, a.jar_size, a.indexed_at, a.schema_ver, " +
                        "c.id FROM artifact a JOIN class c ON c.artifact_id=a.id " +
                        "WHERE c.fqn=? ORDER BY a.hash",
                ).use { query ->
                    query.setString(1, binaryName)
                    query.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                val artifact = StoredArtifact(
                                    id = rows.getLong(1),
                                    hash = rows.getString(2),
                                    path = rows.getString(3),
                                    sourcesPath = rows.getString(4),
                                    kind = rows.getString(5),
                                    jarMtime = rows.nullableLong(6),
                                    jarSize = rows.nullableLong(7),
                                    indexedAt = rows.getLong(8),
                                    schemaVer = rows.getInt(9),
                                )
                                add(ClassHit(artifact, readClass(rows.getLong(10))))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                throw IndexStoreException("cannot search for class $binaryName: ${e.message}", e)
            }
        }
    }

    override fun loadClass(artifactId: Long, binaryName: String): ClassInfo? {
        synchronized(lock) {
            return try {
                connection.prepareStatement(
                    "SELECT id FROM class WHERE artifact_id=? AND fqn=?",
                ).use { query ->
                    query.setLong(1, artifactId)
                    query.setString(2, binaryName)
                    query.executeQuery().use { rows ->
                        if (!rows.next()) return null
                        readClass(rows.getLong(1))
                    }
                }
            } catch (e: Exception) {
                throw IndexStoreException("cannot load class $binaryName: ${e.message}", e)
            }
        }
    }

    override fun listClassFqns(artifactId: Long): List<String> {
        synchronized(lock) {
            return try {
                connection.prepareStatement(
                    "SELECT fqn FROM class WHERE artifact_id=? ORDER BY fqn",
                ).use { query ->
                    query.setLong(1, artifactId)
                    query.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) add(rows.getString(1))
                        }
                    }
                }
            } catch (e: Exception) {
                throw IndexStoreException("cannot list classes: ${e.message}", e)
            }
        }
    }

    override fun classCount(artifactId: Long): Int {
        synchronized(lock) {
            return try {
                connection.prepareStatement(
                    "SELECT COUNT(*) FROM class WHERE artifact_id=?",
                ).use { query ->
                    query.setLong(1, artifactId)
                    query.executeQuery().use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
                }
            } catch (e: Exception) {
                throw IndexStoreException("cannot count classes: ${e.message}", e)
            }
        }
    }

    // -- references (T-029, PROPOSAL.md §10.3 `ref`) ----------------------------

    override fun replaceReferences(artifactId: Long, edges: List<ReferenceEdge>) {
        synchronized(lock) {
            try {
                val wasAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    // Resolve every from-method to its stored row id in one
                    // query: per-edge lookups would cost a round-trip each on
                    // JDK-scale artifacts (hundreds of thousands of edges).
                    val fromIds = connection.prepareStatement(
                        "SELECT c.fqn, m.name, m.descriptor, m.id FROM member m " +
                            "JOIN class c ON c.id=m.class_id WHERE c.artifact_id=?",
                    ).use { query ->
                        query.setLong(1, artifactId)
                        query.executeQuery().use { rows ->
                            buildMap {
                                while (rows.next()) {
                                    put(
                                        Triple(rows.getString(1), rows.getString(2), rows.getString(3)),
                                        rows.getLong(4),
                                    )
                                }
                            }
                        }
                    }
                    connection.prepareStatement(
                        "DELETE FROM ref WHERE from_member_id IN " +
                            "(SELECT id FROM member WHERE class_id IN " +
                            "(SELECT id FROM class WHERE artifact_id=?))",
                    ).use { delete ->
                        delete.setLong(1, artifactId)
                        delete.executeUpdate()
                    }
                    connection.prepareStatement(
                        "INSERT INTO ref(from_member_id, to_fqn, to_member, kind, line) " +
                            "VALUES(?, ?, ?, ?, NULL)",
                    ).use { insert ->
                        // True JDBC batching (T-064's warning does not apply:
                        // ref rows need no generated ids, so there is no
                        // `last_insert_rowid` round-trip and no cross-process
                        // id race — one flush per chunk, not one per row).
                        var batched = 0
                        fun flush() {
                            if (batched > 0) {
                                insert.executeBatch()
                                batched = 0
                            }
                        }
                        for (edge in edges) {
                            val fromId = fromIds[Triple(edge.fromClass, edge.fromMember, edge.fromDescriptor)]
                                ?: continue
                            insert.setLong(1, fromId)
                            insert.setString(2, edge.toOwner)
                            insert.setNullableString(3, encodeToMember(edge.toMember, edge.toDescriptor))
                            insert.setString(4, edge.kind.name)
                            insert.addBatch()
                            if (++batched >= 5_000) flush()
                        }
                        flush()
                    }
                    connection.commit()
                } catch (e: Exception) {
                    connection.rollback()
                    throw e
                } finally {
                    connection.autoCommit = wasAutoCommit
                }
            } catch (e: IndexStoreException) {
                throw e
            } catch (e: Exception) {
                throw IndexStoreException("cannot store ${edges.size} reference edges: ${e.message}", e)
            }
        }
    }

    override fun findReferencesTo(
        toFqn: String,
        toMember: String?,
        toDescriptor: String?,
    ): List<ReferenceHit> {
        synchronized(lock) {
            return try {
                // The member filter rides in SQL so a crowded target (every
                // edge to java.lang.String) does not drag the whole table
                // through the JNI boundary; the descriptor-exact case is a
                // plain equality, the name-only case a prefix match on the
                // `name<sep>descriptor` encoding (LIKE wildcards escaped —
                // member names may legally contain `_`).
                val sql = buildString {
                    append(
                        "SELECT a.id, a.hash, a.path, a.sources_path, a.kind, a.jar_mtime, a.jar_size, " +
                            "a.indexed_at, a.schema_ver, c.fqn, m.name, m.descriptor, " +
                            "r.to_fqn, r.to_member, r.kind " +
                            "FROM ref r JOIN member m ON m.id=r.from_member_id " +
                            "JOIN class c ON c.id=m.class_id JOIN artifact a ON a.id=c.artifact_id " +
                            "WHERE r.to_fqn=?",
                    )
                    if (toMember != null && toDescriptor != null) append(" AND r.to_member=?")
                    else if (toMember != null) append(" AND (r.to_member=? OR r.to_member LIKE ? ESCAPE '\\')")
                    append(" ORDER BY a.hash, c.fqn, m.name, m.descriptor, r.to_fqn, r.to_member, r.kind")
                }
                connection.prepareStatement(sql).use { query ->
                    query.setString(1, toFqn)
                    if (toMember != null && toDescriptor != null) {
                        query.setString(2, encodeToMember(toMember, toDescriptor))
                    } else if (toMember != null) {
                        query.setString(2, toMember)
                        query.setString(3, escapeLike(toMember) + MEMBER_SEPARATOR + "%")
                    }
                    query.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                val artifact = StoredArtifact(
                                    id = rows.getLong(1),
                                    hash = rows.getString(2),
                                    path = rows.getString(3),
                                    sourcesPath = rows.getString(4),
                                    kind = rows.getString(5),
                                    jarMtime = rows.nullableLong(6),
                                    jarSize = rows.nullableLong(7),
                                    indexedAt = rows.getLong(8),
                                    schemaVer = rows.getInt(9),
                                )
                                val (member, descriptor) = decodeToMember(rows.getString(14))
                                add(
                                    ReferenceHit(
                                        artifact = artifact,
                                        fromClass = rows.getString(10),
                                        fromMember = rows.getString(11),
                                        fromDescriptor = rows.getString(12),
                                        toOwner = rows.getString(13),
                                        toMember = member,
                                        toDescriptor = descriptor,
                                        kind = ReferenceKind.valueOf(rows.getString(15)),
                                    ),
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                throw IndexStoreException("cannot search references to $toFqn: ${e.message}", e)
            }
        }
    }

    override fun countReferences(artifactId: Long): Int {
        synchronized(lock) {
            return try {
                connection.prepareStatement(
                    "SELECT COUNT(*) FROM ref WHERE from_member_id IN " +
                        "(SELECT id FROM member WHERE class_id IN " +
                        "(SELECT id FROM class WHERE artifact_id=?))",
                ).use { query ->
                    query.setLong(1, artifactId)
                    query.executeQuery().use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
                }
            } catch (e: Exception) {
                throw IndexStoreException("cannot count references: ${e.message}", e)
            }
        }
    }

    // -- scoped deletes (artifact replacement and gc share this) --------------

    /**
     * Deletes every row scoped to [artifactId] except the artifact row itself.
     * The `*_id` link tables (`ktmeta`, `doc`, …) hold no v1 writers, but their
     * deletes ride along so a future writer can never leave stale rows behind
     * a replacement — one place to extend, not five call sites to find.
     */
    private fun deleteScopedRows(artifactId: Long) {
        val classIds = connection.prepareStatement("SELECT id FROM class WHERE artifact_id=?").use { query ->
            query.setLong(1, artifactId)
            query.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(rows.getLong(1))
                }
            }
        }
        val deleteAnnots = connection.prepareStatement(
            "DELETE FROM annot WHERE owner_kind='class' AND owner_id=?",
        )
        val deleteMemberAnnots = connection.prepareStatement(
            "DELETE FROM annot WHERE owner_kind='member' AND owner_id IN (SELECT id FROM member WHERE class_id=?)",
        )
        val deleteMembers = connection.prepareStatement("DELETE FROM member WHERE class_id=?")
        val deleteIfaces = connection.prepareStatement("DELETE FROM iface WHERE class_id=?")
        val deleteKtmeta = connection.prepareStatement("DELETE FROM ktmeta WHERE class_id=?")
        val deleteDoc = connection.prepareStatement(
            "DELETE FROM doc WHERE owner_kind='class' AND owner_id=?",
        )
        val deleteSrcmap = connection.prepareStatement(
            "DELETE FROM srcmap WHERE owner_kind='class' AND owner_id=?",
        )
        val deleteRef = connection.prepareStatement(
            "DELETE FROM ref WHERE from_member_id IN (SELECT id FROM member WHERE class_id=?)",
        )
        val deleteNameIdx = connection.prepareStatement("DELETE FROM name_idx WHERE class_id=?")
        // Member-scoped doc/srcmap rows reference member ids, which vanish with
        // the members — delete them before the members go.
        val deleteMemberDoc = connection.prepareStatement(
            "DELETE FROM doc WHERE owner_kind='member' AND owner_id IN (SELECT id FROM member WHERE class_id=?)",
        )
        val deleteMemberSrcmap = connection.prepareStatement(
            "DELETE FROM srcmap WHERE owner_kind='member' AND owner_id IN (SELECT id FROM member WHERE class_id=?)",
        )
        deleteAnnots.use { _ ->
            deleteMemberAnnots.use { _ ->
                deleteMembers.use { _ ->
                    deleteIfaces.use { _ ->
                        deleteKtmeta.use { _ ->
                            deleteDoc.use { _ ->
                                deleteSrcmap.use { _ ->
                                    deleteRef.use { _ ->
                                        deleteNameIdx.use { _ ->
                                            deleteMemberDoc.use { _ ->
                                                deleteMemberSrcmap.use { _ ->
                                                    for (classId in classIds) {
                                                        deleteMemberAnnots.setLong(1, classId)
                                                        deleteMemberAnnots.executeUpdate()
                                                        deleteMemberDoc.setLong(1, classId)
                                                        deleteMemberDoc.executeUpdate()
                                                        deleteMemberSrcmap.setLong(1, classId)
                                                        deleteMemberSrcmap.executeUpdate()
                                                        deleteRef.setLong(1, classId)
                                                        deleteRef.executeUpdate()
                                                        deleteAnnots.setLong(1, classId)
                                                        deleteAnnots.executeUpdate()
                                                        deleteMembers.setLong(1, classId)
                                                        deleteMembers.executeUpdate()
                                                        deleteIfaces.setLong(1, classId)
                                                        deleteIfaces.executeUpdate()
                                                        deleteKtmeta.setLong(1, classId)
                                                        deleteKtmeta.executeUpdate()
                                                        deleteDoc.setLong(1, classId)
                                                        deleteDoc.executeUpdate()
                                                        deleteSrcmap.setLong(1, classId)
                                                        deleteSrcmap.executeUpdate()
                                                        deleteNameIdx.setLong(1, classId)
                                                        deleteNameIdx.executeUpdate()
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        connection.prepareStatement("DELETE FROM class WHERE artifact_id=?").use { delete ->
            delete.setLong(1, artifactId)
            delete.executeUpdate()
        }
    }

    // -- class insert ---------------------------------------------------------

    private enum class MemberKind { FIELD, METHOD }

    /**
     * The per-artifact bulk writer behind [replaceClasses] (T-014).
     *
     * One instance per `replaceClasses` call, closed (statements released) when
     * the artifact is done. Every `INSERT` the store executes is prepared once
     * here and rebound per row — preparing per row costs more than executing
     * for JDK-scale artifacts (~700k rows), while the SQL, the bind order and
     * the `ORDER BY id` declaration-order invariant are byte-for-byte the
     * single-row version's. Callers outside one `replaceClasses` must never
     * share an instance: statements are not thread-safe, the store lock is.
     */
    private class BulkWriter(private val connection: java.sql.Connection) : java.io.Closeable {
        private val classInsert = connection.prepareStatement(
            "INSERT INTO class(artifact_id, fqn, simple_name, package, access, kind, signature, " +
                "super_fqn, super_id, source_file, is_kotlin, deprecated, outer_fqn, outer_id) " +
                "VALUES(?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, 0, ?, ?, NULL)",
        )
        private val rowIdQuery = connection.prepareStatement("SELECT last_insert_rowid()")
        private val ifaceInsert =
            connection.prepareStatement("INSERT INTO iface(class_id, iface_fqn) VALUES(?, ?)")
        private val fieldInsert = connection.prepareStatement(
            "INSERT INTO member(class_id, name, descriptor, signature, access, kind, " +
                "param_names, throws_text, default_value, deprecated, constant_value) " +
                "VALUES(?, ?, ?, ?, ?, ?, NULL, NULL, NULL, ?, ?)",
        )
        private val methodInsert = connection.prepareStatement(
            "INSERT INTO member(class_id, name, descriptor, signature, access, kind, " +
                "param_names, throws_text, default_value, deprecated, constant_value) " +
                "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)",
        )
        private val classAnnotInsert = connection.prepareStatement(
            "INSERT INTO annot(owner_kind, owner_id, annot_fqn, annot_values) VALUES('class', ?, ?, ?)",
        )
        private val memberAnnotInsert = connection.prepareStatement(
            "INSERT INTO annot(owner_kind, owner_id, annot_fqn, annot_values) VALUES('member', ?, ?, ?)",
        )

        fun insertClass(artifactId: Long, clazz: ClassInfo): Long {
            classInsert.setLong(1, artifactId)
            classInsert.setString(2, clazz.name.binaryName)
            classInsert.setString(3, clazz.name.simpleName)
            classInsert.setString(4, clazz.name.packageName)
            classInsert.setInt(5, clazz.access.mask)
            classInsert.setString(6, clazz.kind.name)
            classInsert.setNullableString(7, clazz.genericSignature?.signature)
            classInsert.setNullableString(8, clazz.superclass?.binaryName)
            classInsert.setNullableString(9, clazz.sourceFileName)
            classInsert.setInt(10, if (clazz.deprecated) 1 else 0)
            classInsert.setNullableString(11, clazz.outerClass?.binaryName)
            classInsert.executeUpdate()
            val classId = lastRowId()
            for (iface in clazz.interfaces) {
                ifaceInsert.setLong(1, classId)
                ifaceInsert.setString(2, iface.binaryName)
                ifaceInsert.executeUpdate()
            }
            // Fields then methods: the ClassInfo.members order renderers print.
            for (field in clazz.fields) insertMember(classId, field, MemberKind.FIELD)
            for (method in clazz.methods) insertMember(classId, method, MemberKind.METHOD)
            for (annotation in clazz.annotations) {
                classAnnotInsert.setLong(1, classId)
                classAnnotInsert.setString(2, annotation.type.binaryName)
                classAnnotInsert.setString(3, encodeStringMap(annotation.values))
                classAnnotInsert.executeUpdate()
            }
            return classId
        }

        private fun insertMember(
            classId: Long,
            member: dev.jdx.core.model.MemberInfo,
            kind: MemberKind,
        ): Long {
            val memberId = when (member) {
                is FieldInfo -> {
                    fieldInsert.setLong(1, classId)
                    fieldInsert.setString(2, member.name)
                    fieldInsert.setString(3, member.type.descriptor)
                    fieldInsert.setNullableString(4, member.genericSignature?.signature)
                    fieldInsert.setInt(5, member.access.mask)
                    fieldInsert.setString(6, kind.name)
                    fieldInsert.setInt(7, if (member.deprecated) 1 else 0)
                    fieldInsert.setNullableString(8, member.constantValue)
                    fieldInsert.executeUpdate()
                    lastRowId()
                }
                is MethodInfo -> {
                    methodInsert.setLong(1, classId)
                    methodInsert.setString(2, member.name)
                    methodInsert.setString(3, member.descriptor.descriptor)
                    methodInsert.setNullableString(4, member.genericSignature?.signature)
                    methodInsert.setInt(5, member.access.mask)
                    methodInsert.setString(6, kind.name)
                    methodInsert.setString(7, encodeNullableStringList(member.parameterNames))
                    methodInsert.setNullableString(8, encodeTypeList(member.throwsTypes))
                    methodInsert.setNullableString(9, member.annotationDefault)
                    methodInsert.setInt(10, if (member.deprecated) 1 else 0)
                    methodInsert.executeUpdate()
                    lastRowId()
                }
            }
            for (annotation in member.annotations) {
                memberAnnotInsert.setLong(1, memberId)
                memberAnnotInsert.setString(2, annotation.type.binaryName)
                memberAnnotInsert.setString(3, encodeStringMap(annotation.values))
                memberAnnotInsert.executeUpdate()
            }
            return memberId
        }

        private fun lastRowId(): Long =
            rowIdQuery.executeQuery().use { rows ->
                rows.next()
                rows.getLong(1)
            }

        override fun close() {
            // Reverse creation order; every close is attempted even when one fails.
            runCatching { memberAnnotInsert.close() }
            runCatching { classAnnotInsert.close() }
            runCatching { methodInsert.close() }
            runCatching { fieldInsert.close() }
            runCatching { ifaceInsert.close() }
            runCatching { rowIdQuery.close() }
            runCatching { classInsert.close() }.getOrThrow()
        }
    }

    // -- class read -----------------------------------------------------------

    /**
     * Reads one class with its interfaces, members and annotations. Members come
     * back in declaration order (`ORDER BY id`, the insertion invariant above),
     * split back into fields and methods by their stored kind.
     */
    private fun readClass(classId: Long): ClassInfo {
        val row = connection.prepareStatement(
            "SELECT fqn, simple_name, package, access, kind, signature, super_fqn, source_file, deprecated, outer_fqn " +
                "FROM class WHERE id=?",
        ).use { query ->
            query.setLong(1, classId)
            query.executeQuery().use { rows ->
                if (!rows.next()) throw IndexStoreException("class id $classId vanished on read-back")
                ClassRow(
                    fqn = rows.getString(1),
                    access = rows.getInt(4),
                    kind = rows.getString(5),
                    signature = rows.getString(6),
                    superFqn = rows.getString(7),
                    sourceFile = rows.getString(8),
                    deprecated = rows.getInt(9) != 0,
                    outerFqn = rows.getString(10),
                )
            }
        }
        val interfaces = connection.prepareStatement(
            "SELECT iface_fqn FROM iface WHERE class_id=? ORDER BY rowid",
        ).use { query ->
            query.setLong(1, classId)
            query.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(typeName(rows.getString(1)))
                }
            }
        }
        val classAnnots = readAnnots("class", classId)
        val fields = mutableListOf<FieldInfo>()
        val methods = mutableListOf<MethodInfo>()
        connection.prepareStatement(
            "SELECT id, name, descriptor, signature, access, kind, param_names, throws_text, " +
                "default_value, deprecated, constant_value FROM member WHERE class_id=? ORDER BY id",
        ).use { query ->
            query.setLong(1, classId)
            query.executeQuery().use { rows ->
                while (rows.next()) {
                    val memberId = rows.getLong(1)
                    val name = rows.getString(2)
                    val descriptor = rows.getString(3)
                    val signature = rows.getString(4)
                    val access = Access(rows.getInt(5))
                    val kind = rows.getString(6)
                    val annots = readAnnots("member", memberId)
                    when (kind) {
                        "FIELD" -> {
                            val fieldDescriptor = JvmDescriptor.parse(descriptor) as? JvmDescriptor.Field
                                ?: throw IndexStoreException("stored field $name has malformed descriptor $descriptor")
                            fields.add(
                                FieldInfo(
                                    name = name,
                                    type = fieldDescriptor.type,
                                    access = access,
                                    genericSignature = signature?.let { GenericSignature.parse(it) as? FieldSignature },
                                    annotations = annots,
                                    deprecated = rows.getInt(10) != 0,
                                    constantValue = rows.getString(11),
                                ),
                            )
                        }
                        else -> {
                            val methodDescriptor = JvmDescriptor.parse(descriptor) as? JvmDescriptor.Method
                                ?: throw IndexStoreException("stored method $name has malformed descriptor $descriptor")
                            methods.add(
                                MethodInfo(
                                    name = name,
                                    descriptor = methodDescriptor,
                                    access = access,
                                    genericSignature = signature?.let { GenericSignature.parse(it) as? MethodSignature },
                                    parameterNames = decodeNullableStringList(rows.getString(7)),
                                    throwsTypes = decodeTypeList(rows.getString(8)),
                                    annotations = annots,
                                    deprecated = rows.getInt(10) != 0,
                                    annotationDefault = rows.getString(9),
                                ),
                            )
                        }
                    }
                }
            }
        }
        return ClassInfo(
            name = classTypeName(row.fqn),
            kind = TypeKind.valueOf(row.kind),
            access = Access(row.access),
            superclass = row.superFqn?.let { typeName(it) },
            interfaces = interfaces,
            genericSignature = row.signature?.let { GenericSignature.parseClass(it) },
            fields = fields,
            methods = methods,
            annotations = classAnnots,
            outerClass = row.outerFqn?.let { classTypeName(it) },
            sourceFileName = row.sourceFile,
            deprecated = row.deprecated,
        )
    }

    private data class ClassRow(
        val fqn: String,
        val access: Int,
        val kind: String,
        val signature: String?,
        val superFqn: String?,
        val sourceFile: String?,
        val deprecated: Boolean,
        val outerFqn: String?,
    )

    private fun readAnnots(ownerKind: String, ownerId: Long): List<AnnotationInfo> =
        connection.prepareStatement(
            "SELECT annot_fqn, annot_values FROM annot WHERE owner_kind=? AND owner_id=? ORDER BY rowid",
        ).use { query ->
            query.setString(1, ownerKind)
            query.setLong(2, ownerId)
            query.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(AnnotationInfo(typeName(rows.getString(1)), decodeStringMap(rows.getString(2))))
                    }
                }
            }
        }

    private fun readArtifact(rows: java.sql.ResultSet): StoredArtifact =
        StoredArtifact(
            id = rows.getLong(1),
            hash = rows.getString(2),
            path = rows.getString(3),
            sourcesPath = rows.getString(4),
            kind = rows.getString(5),
            jarMtime = rows.nullableLong(6),
            jarSize = rows.nullableLong(7),
            indexedAt = rows.getLong(8),
            schemaVer = rows.getInt(9),
        )

    // -- schema ---------------------------------------------------------------

    private fun createV1() {
        connection.createStatement().use { statement ->
            statement.execute(
                "CREATE TABLE IF NOT EXISTS artifact(" +
                    "id INTEGER PRIMARY KEY, hash TEXT NOT NULL UNIQUE, path TEXT NOT NULL, " +
                    "sources_path TEXT, kind TEXT NOT NULL, jar_mtime INTEGER, jar_size INTEGER, " +
                    "indexed_at INTEGER NOT NULL, schema_ver INTEGER NOT NULL)",
            )
            statement.execute(
                "CREATE TABLE IF NOT EXISTS class(" +
                    "id INTEGER PRIMARY KEY, artifact_id INTEGER NOT NULL, fqn TEXT NOT NULL, " +
                    "simple_name TEXT NOT NULL, package TEXT NOT NULL, access INTEGER NOT NULL, " +
                    "kind TEXT NOT NULL, signature TEXT, super_fqn TEXT, super_id INTEGER, " +
                    "source_file TEXT, is_kotlin INTEGER NOT NULL DEFAULT 0, " +
                    "deprecated INTEGER NOT NULL DEFAULT 0, outer_fqn TEXT, outer_id INTEGER, " +
                    "UNIQUE(artifact_id, fqn))",
            )
            statement.execute(
                "CREATE TABLE IF NOT EXISTS iface(class_id INTEGER NOT NULL, iface_fqn TEXT NOT NULL)",
            )
            statement.execute(
                "CREATE TABLE IF NOT EXISTS member(" +
                    "id INTEGER PRIMARY KEY, class_id INTEGER NOT NULL, name TEXT NOT NULL, " +
                    "descriptor TEXT NOT NULL, signature TEXT, access INTEGER NOT NULL, kind TEXT NOT NULL, " +
                    "param_names TEXT, throws_text TEXT, default_value TEXT, " +
                    "deprecated INTEGER NOT NULL DEFAULT 0, constant_value TEXT)",
            )
            statement.execute(
                "CREATE TABLE IF NOT EXISTS annot(" +
                    "owner_kind TEXT NOT NULL, owner_id INTEGER NOT NULL, " +
                    "annot_fqn TEXT NOT NULL, annot_values TEXT NOT NULL DEFAULT '')",
            )
            statement.execute("CREATE TABLE IF NOT EXISTS ktmeta(class_id INTEGER PRIMARY KEY, blob BLOB)")
            statement.execute(
                "CREATE TABLE IF NOT EXISTS doc(" +
                    "owner_kind TEXT NOT NULL, owner_id INTEGER NOT NULL, " +
                    "text TEXT NOT NULL, tags TEXT NOT NULL DEFAULT '')",
            )
            statement.execute(
                "CREATE TABLE IF NOT EXISTS srcmap(" +
                    "owner_kind TEXT NOT NULL, owner_id INTEGER NOT NULL, file TEXT NOT NULL, " +
                    "start_line INTEGER NOT NULL, end_line INTEGER NOT NULL, " +
                    "start_col INTEGER NOT NULL, end_col INTEGER NOT NULL)",
            )
            statement.execute(
                "CREATE TABLE IF NOT EXISTS ref(" +
                    "from_member_id INTEGER NOT NULL, to_fqn TEXT NOT NULL, to_member TEXT, " +
                    "kind TEXT NOT NULL, line INTEGER)",
            )
            statement.execute(
                "CREATE TABLE IF NOT EXISTS name_idx(" +
                    "simple_name TEXT NOT NULL, camel_humps TEXT NOT NULL, class_id INTEGER NOT NULL)",
            )
            statement.execute("CREATE INDEX IF NOT EXISTS idx_class_fqn ON class(fqn)")
            statement.execute("CREATE INDEX IF NOT EXISTS idx_class_simple ON class(simple_name)")
            statement.execute("CREATE INDEX IF NOT EXISTS idx_class_artifact ON class(artifact_id)")
            statement.execute("CREATE INDEX IF NOT EXISTS idx_member_name ON member(name)")
            statement.execute("CREATE INDEX IF NOT EXISTS idx_member_class ON member(class_id)")
            statement.execute("CREATE INDEX IF NOT EXISTS idx_iface_fqn ON iface(iface_fqn)")
            statement.execute("CREATE INDEX IF NOT EXISTS idx_ref_to_fqn ON ref(to_fqn)")
            statement.execute("CREATE INDEX IF NOT EXISTS idx_ref_to_member ON ref(to_member)")
            statement.execute("CREATE INDEX IF NOT EXISTS idx_name_idx_humps ON name_idx(camel_humps)")
        }
    }
}

// -- nullable helpers (getLong/getString convention traps) --------------------

/**
 * `ResultSet.getLong` returns `0` for SQL `NULL` — callers must consult
 * `wasNull`, or a missing mtime reads as the epoch. These helpers centralise
 * that trap (and its `setNull` mirror) so no read/write pair can disagree.
 */
private fun java.sql.ResultSet.nullableLong(column: Int): Long? {
    val value = getLong(column)
    return if (wasNull()) null else value
}

private fun java.sql.PreparedStatement.setNullableLong(index: Int, value: Long?) {
    if (value == null) setNull(index, java.sql.Types.INTEGER) else setLong(index, value)
}

private fun java.sql.PreparedStatement.setNullableString(index: Int, value: String?) {
    if (value == null) setNull(index, java.sql.Types.VARCHAR) else setString(index, value)
}

// -- value codecs (all TEXT, all deterministic) --------------------------------

/** Unit separator: cannot appear in a Java identifier, so it never needs escaping. */
private const val LIST_SEPARATOR: String = "\u001F"

private fun typeName(binaryName: String): TypeName =
    try {
        typeNameFromBinaryName(binaryName)
    } catch (e: IllegalArgumentException) {
        throw IndexStoreException("stored type name is malformed: $binaryName")
    }

/**
 * Separates the member name from its descriptor inside `ref.to_member`
 * (`name<sep>descriptor`, bare `name` when the descriptor is absent, SQL NULL
 * when the edge names no member). The v1 schema (T-013) has no descriptor
 * column; this encoding keeps overload precision without a migration. Same
 * unit separator as LIST_SEPARATOR, same rationale.
 */
private const val MEMBER_SEPARATOR: String = "\u001F"

/** `ref.to_member` encoding: `null` → NULL, bare name, or `name<sep>descriptor`. */
private fun encodeToMember(name: String?, descriptor: String?): String? {
    if (name == null) return null
    if (descriptor == null) return name
    return name + MEMBER_SEPARATOR + descriptor
}

/** Inverse of [encodeToMember]: splits on the first separator, if any. */
private fun decodeToMember(raw: String?): Pair<String?, String?> {
    if (raw == null) return Pair(null, null)
    val separator = raw.indexOf(MEMBER_SEPARATOR)
    if (separator < 0) return Pair(raw, null)
    return Pair(raw.substring(0, separator), raw.substring(separator + 1))
}

/** Escapes `%`, `_` and the escape char itself for a `LIKE ... ESCAPE '\'` match. */
private fun escapeLike(raw: String): String = buildString {
    for (char in raw) {
        if (char == '\\' || char == '%' || char == '_') append('\\')
        append(char)
    }
}

private fun classTypeName(binaryName: String): TypeName.ClassType =
    when (val name = typeName(binaryName)) {
        is TypeName.ClassType -> name
        else -> throw IndexStoreException("stored class name is not a class type: $binaryName")
    }

private fun encodeTypeList(types: List<TypeName>): String? =
    if (types.isEmpty()) null else types.joinToString(LIST_SEPARATOR) { it.binaryName }

private fun decodeTypeList(raw: String?): List<TypeName> =
    if (raw == null) emptyList() else raw.split(LIST_SEPARATOR).map { typeName(it) }

/**
 * JSON string-array codec for parameter names, where `null` (unknown name) is
 * significant and must survive apart from `""`. Hand-rolled: the store keeps
 * `core` dependency-free, and a 20-line codec beats a JSON library here.
 */
private fun encodeNullableStringList(values: List<String?>): String =
    values.joinToString(",", "[", "]") { value ->
        if (value == null) "null" else "\"" + escapeJsonString(value) + "\""
    }

private fun decodeNullableStringList(raw: String?): List<String?> {
    if (raw == null) return emptyList()
    val text = raw.trim()
    if (text == "[]") return emptyList()
    require(text.startsWith("[") && text.endsWith("]")) { "malformed param_names: $raw" }
    val body = text.substring(1, text.length - 1)
    val result = mutableListOf<String?>()
    var index = 0
    while (index < body.length) {
        when {
            body.startsWith("null", index) -> {
                result.add(null)
                index += 4
            }
            body[index] == '"' -> {
                val decoded = StringBuilder()
                index++
                while (true) {
                    if (index >= body.length) throw IndexStoreException("stored param_names are malformed: $raw")
                    val char = body[index++]
                    if (char == '"') break
                    if (char == '\\') {
                        if (index >= body.length) throw IndexStoreException("stored param_names are malformed: $raw")
                        when (val escape = body[index++]) {
                            '"', '\\', '/' -> decoded.append(escape)
                            'b' -> decoded.append('\b')
                            'f' -> decoded.append('\u000C')
                            'n' -> decoded.append('\n')
                            'r' -> decoded.append('\r')
                            't' -> decoded.append('\t')
                            'u' -> {
                                if (index + 4 > body.length) throw IndexStoreException("stored param_names are malformed: $raw")
                                decoded.append(body.substring(index, index + 4).toInt(16).toChar())
                                index += 4
                            }
                            else -> throw IndexStoreException("stored param_names are malformed: $raw")
                        }
                    } else {
                        decoded.append(char)
                    }
                }
                result.add(decoded.toString())
            }
            else -> throw IndexStoreException("stored param_names are malformed: $raw")
        }
        if (index < body.length) {
            if (body[index] != ',') throw IndexStoreException("stored param_names are malformed: $raw")
            index++
        }
    }
    return result
}

private fun escapeJsonString(value: String): String = buildString {
    for (char in value) {
        when (char) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
        }
    }
}

/**
 * Annotation values (`Map<String, String>`) as a JSON object with keys sorted —
 * sorted so two identical annotations always produce identical bytes (D-007).
 */
private fun encodeStringMap(values: Map<String, String>): String =
    values.entries.sortedBy { it.key }
        .joinToString(",", "{", "}") { (key, value) ->
            "\"" + escapeJsonString(key) + "\":\"" + escapeJsonString(value) + "\""
        }

private fun decodeStringMap(raw: String): Map<String, String> {
    val text = raw.trim()
    if (text == "{}" || text.isEmpty()) return emptyMap()
    require(text.startsWith("{") && text.endsWith("}")) { "malformed annot_values: $raw" }
    // Reuses the array codec's string reader by slicing key/value pairs out of
    // the object body: entries are `"key":"value"` joined with commas.
    val result = linkedMapOf<String, String>()
    var index = 1
    fun readJsonString(): String {
        if (index >= text.length || text[index] != '"') throw IndexStoreException("stored annot_values are malformed: $raw")
        index++
        val decoded = StringBuilder()
        while (true) {
            if (index >= text.length) throw IndexStoreException("stored annot_values are malformed: $raw")
            val char = text[index++]
            if (char == '"') break
            if (char == '\\') {
                if (index >= text.length) throw IndexStoreException("stored annot_values are malformed: $raw")
                when (val escape = text[index++]) {
                    '"', '\\', '/' -> decoded.append(escape)
                    'b' -> decoded.append('\b')
                    'f' -> decoded.append('\u000C')
                    'n' -> decoded.append('\n')
                    'r' -> decoded.append('\r')
                    't' -> decoded.append('\t')
                    'u' -> {
                        if (index + 4 > text.length) throw IndexStoreException("stored annot_values are malformed: $raw")
                        decoded.append(text.substring(index, index + 4).toInt(16).toChar())
                        index += 4
                    }
                    else -> throw IndexStoreException("stored annot_values are malformed: $raw")
                }
            } else {
                decoded.append(char)
            }
        }
        return decoded.toString()
    }
    if (text.length == 2) return emptyMap()
    while (true) {
        val key = readJsonString()
        if (index >= text.length || text[index] != ':') throw IndexStoreException("stored annot_values are malformed: $raw")
        index++
        result[key] = readJsonString()
        if (index >= text.length) throw IndexStoreException("stored annot_values are malformed: $raw")
        if (text[index] == '}') break
        if (text[index] != ',') throw IndexStoreException("stored annot_values are malformed: $raw")
        index++
    }
    return result
}
