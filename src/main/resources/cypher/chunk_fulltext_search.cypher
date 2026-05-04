// Normalize from 0-1

CALL db.index.fulltext.queryNodes($fulltextIndex, $searchText)
YIELD node AS chunk, score
WITH collect({node: chunk, score: score}) AS results, max(score) AS maxScore
UNWIND results AS result
WITH result.node AS chunk,
     result.score / maxScore AS normalizedScore
  WHERE normalizedScore >= $similarityThreshold
    AND ($domain IS NULL OR chunk.domain = $domain)
RETURN {
         text: coalesce(chunk.text, chunk.content),
         id:   chunk.id,
         score: normalizedScore
       } AS result
  ORDER BY result.score DESC
  LIMIT $topK
