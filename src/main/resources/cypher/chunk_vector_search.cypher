CALL db.index.vector.queryNodes($vectorIndex, $topK, $queryVector)
YIELD node AS chunk, score
  WHERE score >= $similarityThreshold
RETURN {
         text:  coalesce(chunk.text, chunk.content),
         id:    chunk.id,
         score: score
       } AS result
  ORDER BY result.score DESC
