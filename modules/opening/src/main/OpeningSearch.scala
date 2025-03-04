package lila.opening

import chess.opening.{ FullOpening, FullOpeningDB }
import java.text.Normalizer

import lila.common.base.StringUtils.levenshtein
import lila.common.Chronometer
import lila.common.Heapsort.implicits._
import lila.memo.CacheApi

case class OpeningSearchResult(opening: FullOpening)

final class OpeningSearch(cacheApi: CacheApi, explorer: OpeningExplorer) {

  val max = 32

  private val cache = cacheApi.scaffeine
    .maximumSize(1024)
    .build[String, List[OpeningSearchResult]](doSearch _)

  def apply(q: String): List[OpeningSearchResult] = cache get q

  def doSearch(q: String): List[OpeningSearchResult] =
    OpeningSearch(q, max).map(OpeningSearchResult)
}

// linear performance but it's fine for 3,067 unique openings
object OpeningSearch {

  private val openings: Vector[FullOpening] = FullOpeningDB.shortestLines.values.toVector

  private type Token = String
  private type Score = Int

  private[opening] object tokenize {
    private val nonLetterRegex = """[^a-zA-Z0-9]+""".r
    private val exclude        = Set("opening", "variation")
    def apply(str: String): Set[Token] = {
      str
        .take(200)
        .replace("_", " ")
        .replace("-", " ")
        .split(' ')
        .view
        .map(_.trim)
        .filter(_.nonEmpty)
        .take(6)
        .map { token =>
          Normalizer
            .normalize(token, Normalizer.Form.NFD)
            .toLowerCase
            .replaceAllIn(nonLetterRegex, "")
        }
        .toSet
        .diff(exclude)
    }
    def apply(opening: FullOpening): Set[Token] =
      opening.key.toLowerCase.replace("-", "_").split('_').view.filterNot(exclude.contains).toSet +
        opening.eco.toLowerCase ++
        opening.pgn.split(' ').take(6).toSet
  }

  private case class Query(raw: String, numberedPgn: String, tokens: Set[Token])
  private def makeQuery(userInput: String) = {
    val clean = userInput.trim.toLowerCase
    val numberedPgn = // try to produce numbered PGN "1. e4 e5 2. f4" from a query like "e4 e5 f4"
      clean.split(' ').toList.map(_.trim).filter(_.nonEmpty).grouped(2).toList.zipWithIndex.map {
        case (moves, index) => s"${index + 1}. ${moves mkString " "}"
      } mkString " "
    Query(clean, numberedPgn, tokenize(clean))
  }
  private case class Entry(opening: FullOpening, tokens: Set[Token])
  private case class Match(opening: FullOpening, score: Score)

  private val index: List[Entry] =
    openings.view.map { op =>
      Entry(op, tokenize(op))
    }.toList

  private def scoreOf(query: Query, entry: Entry): Option[Score] = {
    def exactMatch(token: Token) =
      entry.tokens(token) ||
        entry.tokens(s"${token}s") // King's and Queen's can be matched by king and queen
    if (
      entry.opening.pgn.startsWith(query.raw) ||
      entry.opening.pgn.startsWith(query.numberedPgn) ||
      entry.opening.uci.startsWith(query.raw)
    )
      (query.raw.size * 1000 - entry.opening.nbMoves)
    else
      query.tokens
        .foldLeft((query.tokens, 0)) { case ((remaining, score), token) =>
          if (exactMatch(token)) (remaining - token, score + token.size * 100)
          else (remaining, score)
        } match {
        case (remaining, score) =>
          score + remaining.map { t =>
            entry.tokens.map { e =>
              if (e startsWith t) t.size * 50
              else if (e contains t) t.size * 20
              else 0
            }.sum
          }.sum
      }
  }.some.filter(0 <).map(_ - entry.opening.key.size)

  private def searchOrdering =
    Ordering.by[Match, Score] { case Match(_, score) => score }

  def apply(str: String, max: Int): List[FullOpening] = Chronometer.syncMon(_.opening.searchTime) {
    val query = makeQuery(str)
    index
      .flatMap { entry =>
        scoreOf(query, entry) map {
          Match(entry.opening, _)
        }
      }
      .topN(max)(searchOrdering)
      .map(_.opening)
  }
}
