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
package com.embabel.agent.rag.graph.util

import com.embabel.agent.rag.graph.fulltext.FullTextQueryMode

/**
 * Escapes Lucene QueryParser reserved characters before calling
 * `db.index.fulltext.queryNodes`.
 *
 * Agents and callers frequently concatenate conversation context such as
 * `[Recent Messages]\nUser: ...` into the fulltext query. Unescaped, Lucene
 * parses the leading `[` as a range query and fails the procedure with a
 * `ParseException`, breaking all chunk/entity retrieval downstream. This
 * sanitizer escapes every reserved character so the query is treated as
 * plain text.
 *
 * Mirrors `com.lemaitre.common.util.LuceneQuery` from the Le Maître
 * common-lib so that this module — which cannot depend on common-lib —
 * stays self-contained.
 */
object LuceneQuery {

    // Lucene reserved characters: + - ! ( ) { } [ ] ^ " ~ * ? : \ /
    // plus the two-char operators && and || (single & or | alone are not reserved).
    private val RESERVED = Regex("""([+\-!(){}\[\]^"~*?:\\/]|&&|\|\|)""")

    @JvmStatic
    fun sanitize(q: String?): String {
        if (q.isNullOrBlank()) return "*"
        return RESERVED.replace(q) { "\\" + it.value }
    }

    /**
     * The last thing done to a query before it reaches `db.index.fulltext.queryNodes`, applied to
     * the query [com.embabel.agent.rag.graph.fulltext.searchPreparedQuery] hands to its search
     * lambda — so both the primary form and its relaxed fallback are covered.
     *
     * Escaping depends on the mode because under [FullTextQueryMode.LITERAL] the escaping has
     * already happened: `FullTextQueryPreparation` escapes every reserved character itself and then
     * injects `+` operators of its own, so a second pass here would escape those operators and
     * double-escape the backslashes it just wrote — turning a required identifier into a search for
     * punctuation.
     *
     * Under [FullTextQueryMode.EXPRESSION] — the default, and what this fork runs in production —
     * upstream passes the caller's string through byte for byte. Callers here are agents that
     * concatenate conversation context (`[Recent Messages]\nUser: ...`) rather than authors of
     * Lucene expressions, so an unescaped `[` is a `ParseException` that takes down all chunk and
     * entity retrieval. [sanitize] is what keeps that from happening, at the documented cost of
     * neutering any `+` a model emits: this store's precision has always come from `topK` and rank.
     */
    @JvmStatic
    fun sanitizeForMode(q: String?, mode: FullTextQueryMode): String = when (mode) {
        FullTextQueryMode.EXPRESSION -> sanitize(q)
        FullTextQueryMode.LITERAL -> if (q.isNullOrBlank()) "*" else q
    }
}
