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
package com.embabel.agent.rag.graph

import com.embabel.agent.rag.ingestion.ChunkTransformer
import com.embabel.agent.rag.ingestion.ContentChunker
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.core.types.TextSimilaritySearchRequest
import io.mockk.mockk
import org.drivine.manager.PersistenceManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.transaction.PlatformTransactionManager

/**
 * Pure unit coverage (no Spring context, no live database) for [DrivineStore.commonParameters] —
 * specifically the `candidateLimit` and `procLimit` bind parameters that bound the fulltext
 * candidate set (see [Neo4jRagDialect][com.embabel.agent.rag.graph.dialect.Neo4jRagDialect] KDoc):
 * `candidateLimit` bounds what Cypher's `collect()` materialises after the procedure yields;
 * `procLimit` (= 2x `candidateLimit`) is pushed into `db.index.fulltext.queryNodes`'s own options
 * map to bound what the procedure itself computes/yields in the first place.
 */
class DrivineStoreCommonParametersTest {

    private fun newStore(properties: GraphRagServiceProperties = GraphRagServiceProperties()): DrivineStore =
        DrivineStore(
            persistenceManager = mockk<PersistenceManager>(relaxed = true),
            properties = properties,
            chunkerConfig = ContentChunker.Config(),
            chunkTransformer = ChunkTransformer.NO_OP,
            embeddingService = mockk<EmbeddingService>(relaxed = true),
            platformTransactionManager = mockk<PlatformTransactionManager>(relaxed = true),
            cypherSearch = mockk<CypherSearch>(relaxed = true),
        )

    @Test
    fun `candidateLimit for topK=6 is 300 with default properties`() {
        val store = newStore()
        val request = TextSimilaritySearchRequest("delai de prescription", 0.3, 6)

        val params = store.commonParameters(request)

        assertEquals(300, params["candidateLimit"])
        assertEquals(6, params["topK"])
    }

    @Test
    fun `procLimit is twice candidateLimit`() {
        val store = newStore()
        val request = TextSimilaritySearchRequest("delai de prescription", 0.3, 6)

        val params = store.commonParameters(request)

        assertEquals(300, params["candidateLimit"])
        assertEquals(600, params["procLimit"])
    }

    @Test
    fun `procLimit tracks candidateLimit at the floor and the cap`() {
        val store = newStore()

        val floored = store.commonParameters(TextSimilaritySearchRequest("query", 0.3, 1))
        assertEquals(200, floored["candidateLimit"])
        assertEquals(400, floored["procLimit"])

        val capped = store.commonParameters(TextSimilaritySearchRequest("query", 0.3, 100))
        assertEquals(1000, capped["candidateLimit"])
        assertEquals(2000, capped["procLimit"])
    }

    @Test
    fun `candidateLimit is floored at 200 for small topK`() {
        val store = newStore()
        val request = TextSimilaritySearchRequest("query", 0.3, 1)

        val params = store.commonParameters(request)

        assertEquals(200, params["candidateLimit"])
    }

    @Test
    fun `candidateLimit is capped at configured max for large topK`() {
        val store = newStore()
        val request = TextSimilaritySearchRequest("query", 0.3, 100)

        val params = store.commonParameters(request)

        // 100 * 50 = 5000, capped at the default max of 1000.
        assertEquals(1000, params["candidateLimit"])
    }

    @Test
    fun `candidateLimit honours configured multiplier and cap`() {
        val properties = GraphRagServiceProperties().apply {
            fulltextCandidateLimitMultiplier = 10
            fulltextCandidateLimitMax = 80
        }
        val store = newStore(properties)
        val request = TextSimilaritySearchRequest("query", 0.3, 6)

        val params = store.commonParameters(request)

        // 6 * 10 = 60, floored at 200 -> 200, then capped at 80.
        assertEquals(80, params["candidateLimit"])
    }
}
