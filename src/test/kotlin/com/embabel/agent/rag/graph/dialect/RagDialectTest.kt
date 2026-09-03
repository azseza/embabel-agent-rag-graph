/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.agent.rag.graph.dialect

import org.drivine.connection.DatabaseType
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Unit coverage for the [RagDialect] resolution and the per-engine search/embedding Cypher templates.
 *
 * Schema creation is no longer part of [RagDialect] (it is handled by Drivine's schema managers), so
 * there are no index/constraint-DDL assertions here — the real end-to-end schema + search behaviour
 * is exercised by the cross-engine `*RagSearchCharacterizationTest`s.
 */
class RagDialectTest {

    /**
     * Pins the two halves of the fulltext scoring contract together, because each is unsafe alone.
     *
     * 1. The candidate set is bounded — `ORDER BY score DESC LIMIT $candidateLimit` — and the bound
     *    lands BEFORE the scoring projection, so nothing downstream ever sees more than
     *    `$candidateLimit` rows. Without it a common-word query against a multi-million-chunk
     *    corpus exhausts `dbms.memory.transaction.total.max` (Le Maître prod, 2026-09-03).
     * 2. Scores are normalised with the saturating `score / (score + k)` form and NOT against the
     *    result set (`collect(...)` / `max(score)`, which upstream PR #18 removed): a
     *    result-set-relative normalisation forced the top hit to 1.0 whatever its quality, and made
     *    scores incomparable between calls.
     *
     * They are asserted in one place because the combination is what the merge had to preserve:
     * result-set-relative scoring plus a `LIMIT` would have silently changed every score, so a
     * future edit that reinstates `collect(` must fail here rather than pass a bound-only check.
     */
    private fun assertBoundedBeforeScoring(cypher: String, leg: String) {
        val limitIndex = cypher.indexOf("LIMIT \$candidateLimit")
        val scoringIndex = cypher.indexOf("score / (score + ")
        assertTrue(limitIndex >= 0, "Expected 'LIMIT \$candidateLimit' in $leg fulltext Cypher")
        assertTrue(
            cypher.indexOf("ORDER BY score DESC").let { it in 0..<limitIndex },
            "Candidate LIMIT must be ordered by raw score in $leg fulltext Cypher",
        )
        assertTrue(scoringIndex >= 0, "Expected bm25 'score / (score + k)' scoring in $leg fulltext Cypher")
        assertTrue(limitIndex < scoringIndex, "Candidate LIMIT must precede the scoring projection")
        assertFalse(
            cypher.contains("collect(") || cypher.contains("max(score)"),
            "$leg fulltext Cypher must not normalise against the result set",
        )
    }

    @Test
    fun `forDatabaseType resolves Neo4j`() {
        assertTrue(RagDialect.forDatabaseType(DatabaseType.NEO4J) is Neo4jRagDialect)
    }

    @Test
    fun `forDatabaseType resolves FalkorDB`() {
        assertTrue(RagDialect.forDatabaseType(DatabaseType.FALKORDB) is FalkorDbRagDialect)
    }

    @Test
    fun `forDatabaseType rejects unsupported types`() {
        assertThrows<IllegalArgumentException> {
            RagDialect.forDatabaseType(DatabaseType.POSTGRES)
        }
    }

    @Test
    fun `forDatabaseType resolves Memgraph`() {
        assertTrue(RagDialect.forDatabaseType(DatabaseType.MEMGRAPH) is MemgraphRagDialect)
    }

    @Nested
    inner class Neo4j {
        private val dialect = Neo4jRagDialect()

        @Test
        fun `chunk vector search uses db_index_vector_queryNodes`() {
            val cypher = dialect.chunkVectorSearchCypher()
            assertTrue(cypher.contains("db.index.vector.queryNodes"))
            assertTrue(cypher.contains("\$vectorIndex"))
        }

        @Test
        fun `chunk fulltext search is supported`() {
            assertNotNull(dialect.chunkFullTextSearchCypher())
        }

        @Test
        fun `chunk fulltext search bounds candidates before scoring`() {
            assertBoundedBeforeScoring(dialect.chunkFullTextSearchCypher()!!, "chunk")
        }

        @Test
        fun `chunk fulltext search pushes procLimit into the fulltext procedure's options map`() {
            val cypher = dialect.chunkFullTextSearchCypher()!!
            assertTrue(
                cypher.contains("db.index.fulltext.queryNodes(\$fulltextIndex, \$searchText, {limit: \$procLimit})"),
                "Expected the fulltext procedure call to carry an options map with limit: \$procLimit"
            )
        }

        @Test
        fun `entity fulltext search is supported`() {
            assertNotNull(dialect.entityFullTextSearchCypher())
        }

        @Test
        fun `entity fulltext search bounds candidates before scoring`() {
            assertBoundedBeforeScoring(dialect.entityFullTextSearchCypher()!!, "entity")
        }

        @Test
        fun `entity fulltext search pushes procLimit into the fulltext procedure's options map`() {
            val cypher = dialect.entityFullTextSearchCypher()!!
            assertTrue(
                cypher.contains("db.index.fulltext.queryNodes(\$fulltextIndex, \$searchText, {limit: \$procLimit})"),
                "Expected the fulltext procedure call to carry an options map with limit: \$procLimit"
            )
        }

        @Test
        fun `store embedding uses property set`() {
            val cypher = dialect.storeEmbeddingCypher("Chunk:ContentElement")
            assertTrue(cypher.contains("SET n.embedding"))
            assertTrue(cypher.contains("Chunk:ContentElement"))
            assertTrue(cypher.contains("\$embeddedText"))
            assertTrue(cypher.contains("REMOVE n._text"))
            assertTrue(cypher.contains("SET n._text"))
        }
    }

    @Nested
    inner class FalkorDB {
        private val dialect = FalkorDbRagDialect()

        @Test
        fun `chunk vector search uses db_idx_vector_queryNodes with vecf32`() {
            val cypher = dialect.chunkVectorSearchCypher()
            assertTrue(cypher.contains("db.idx.vector.queryNodes"))
            assertTrue(cypher.contains("vecf32"))
            assertTrue(cypher.contains("\$chunkLabel"))
        }

        @Test
        fun `chunk vector search converts distance to similarity`() {
            val cypher = dialect.chunkVectorSearchCypher()
            assertTrue(cypher.contains("1.0 - score"))
        }

        @Test
        fun `chunk fulltext search uses db_idx_fulltext_queryNodes`() {
            val cypher = dialect.chunkFullTextSearchCypher()
            assertNotNull(cypher)
            assertTrue(cypher!!.contains("db.idx.fulltext.queryNodes"))
            assertTrue(cypher.contains("\$chunkLabel"))
        }

        @Test
        fun `entity vector search uses entityNodeName param`() {
            val cypher = dialect.entityVectorSearchCypher()
            assertTrue(cypher.contains("\$entityNodeName"))
            assertTrue(cypher.contains("vecf32"))
        }

        @Test
        fun `embedding literal wraps vecf32 for storage`() {
            assertTrue(dialect.embeddingLiteral("embedding") == "vecf32(\$embedding)")
        }

        @Test
        fun `store embedding wraps value in vecf32`() {
            val cypher = dialect.storeEmbeddingCypher("Chunk:ContentElement")
            assertTrue(cypher.contains("SET n.embedding = vecf32(\$embedding)"))
            assertTrue(cypher.contains("\$embeddedText"))
            assertTrue(cypher.contains("REMOVE n._text"))
            assertTrue(cypher.contains("SET n._text"))
        }
    }

    @Nested
    inner class Memgraph {
        private val dialect = MemgraphRagDialect()

        @Test
        fun `forName resolves Memgraph`() {
            assertTrue(RagDialect.forName("memgraph") is MemgraphRagDialect)
            assertTrue(RagDialect.forName("MEMGRAPH") is MemgraphRagDialect)
        }

        @Test
        fun `chunk vector search uses vector_search_search`() {
            val cypher = dialect.chunkVectorSearchCypher()
            assertTrue(cypher.contains("vector_search.search"))
            assertTrue(cypher.contains("similarity AS score"))
        }

        @Test
        fun `chunk vector search projects with WITH before WHERE`() {
            val cypher = dialect.chunkVectorSearchCypher()
            assertTrue(cypher.contains("WITH chunk, score"))
        }

        @Test
        fun `chunk fulltext search uses text_search_search_all`() {
            val cypher = dialect.chunkFullTextSearchCypher()
            assertNotNull(cypher)
            assertTrue(cypher!!.contains("text_search.search_all"))
        }

        @Test
        fun `chunk fulltext search bounds candidates before scoring`() {
            assertBoundedBeforeScoring(dialect.chunkFullTextSearchCypher()!!, "chunk")
        }

        @Test
        fun `entity vector search uses vector_search_search`() {
            val cypher = dialect.entityVectorSearchCypher()
            assertTrue(cypher.contains("vector_search.search"))
            assertTrue(cypher.contains("similarity AS score"))
        }

        @Test
        fun `entity fulltext search is supported`() {
            assertNotNull(dialect.entityFullTextSearchCypher())
        }

        @Test
        fun `entity fulltext search bounds candidates before scoring`() {
            assertBoundedBeforeScoring(dialect.entityFullTextSearchCypher()!!, "entity")
        }

        @Test
        fun `store embedding uses property set`() {
            val cypher = dialect.storeEmbeddingCypher("Chunk:ContentElement")
            assertTrue(cypher.contains("SET n.embedding"))
            assertTrue(cypher.contains("Chunk:ContentElement"))
            assertTrue(cypher.contains("\$embeddedText"))
            assertTrue(cypher.contains("REMOVE n._text"))
            assertTrue(cypher.contains("SET n._text"))
        }
    }
}
