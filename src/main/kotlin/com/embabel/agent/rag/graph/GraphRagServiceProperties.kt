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

import com.embabel.agent.rag.model.NamedEntityData
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * @param chunkNodeName the name of the node representing a chunk in the knowledge graph
 * @param entityNodeName the name of a node representing an entity in the knowledge graph
 * @param fulltextCandidateLimitMultiplier multiplier applied to a search's `topK` to compute the
 *   fulltext candidate-set bound (see [fulltextCandidateLimit]) that the chunk/entity fulltext
 *   Cypher (`dialect.chunkFullTextSearchCypher()` / `entityFullTextSearchCypher()`) applies with
 *   `ORDER BY score DESC LIMIT $candidateLimit` right after the index `YIELD`, before the
 *   `collect(...)` that normalises scores by the pool's max. Without this bound, a common-word
 *   query against a multi-million-chunk corpus collects every match and can exhaust
 *   `dbms.memory.transaction.total.max`. Env override:
 *   `EMBABEL_AGENT_RAG_GRAPH_FULLTEXT_CANDIDATE_LIMIT_MULTIPLIER`.
 * @param fulltextCandidateLimitMax hard ceiling on the fulltext candidate-set bound computed by
 *   [fulltextCandidateLimit], regardless of how large `topK * fulltextCandidateLimitMultiplier`
 *   grows. Env override: `EMBABEL_AGENT_RAG_GRAPH_FULLTEXT_CANDIDATE_LIMIT_MAX`.
 */
@ConfigurationProperties(prefix = "embabel.agent.rag.graph")
class GraphRagServiceProperties {

    var chunkNodeName: String = "Chunk"
    var entityNodeName: String = NamedEntityData.ENTITY_LABEL
    var name: String = "DrivineRagService"
    var description: String = "Neo RAG service using Drivine for querying and embedding"
    var contentElementIndex: String = "embabel_content_index"
    var entityIndex: String = "embabel_entity_index"
    var contentElementFullTextIndex: String = "embabel_content_fulltext_index"
    var entityFullTextIndex: String = "embabel_entity_fulltext_index"
    var fulltextCandidateLimitMultiplier: Int = 50
    var fulltextCandidateLimitMax: Int = 1000

    /**
     * The fulltext candidate-set bound for a search requesting `topK` results:
     * `topK * fulltextCandidateLimitMultiplier`, floored at [FULLTEXT_CANDIDATE_LIMIT_FLOOR] and
     * capped at [fulltextCandidateLimitMax]. Bound as `$candidateLimit` in
     * [com.embabel.agent.rag.graph.DrivineStore]'s `commonParameters`.
     */
    fun fulltextCandidateLimit(topK: Int): Int =
        (topK * fulltextCandidateLimitMultiplier)
            .coerceAtLeast(FULLTEXT_CANDIDATE_LIMIT_FLOOR)
            .coerceAtMost(fulltextCandidateLimitMax)

    override fun toString(): String {
        return "${javaClass.simpleName}(chunkNodeName='$chunkNodeName', entityNodeName='$entityNodeName', name='$name', description='$description', contentElementIndex='$contentElementIndex', entityIndex='$entityIndex', contentElementFullTextIndex='$contentElementFullTextIndex', entityFullTextIndex='$entityFullTextIndex', fulltextCandidateLimitMultiplier=$fulltextCandidateLimitMultiplier, fulltextCandidateLimitMax=$fulltextCandidateLimitMax)"
    }

    companion object {
        private const val FULLTEXT_CANDIDATE_LIMIT_FLOOR = 200
    }
}
