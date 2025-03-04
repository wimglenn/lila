package lila.setup

import akka.stream.scaladsl._
import chess.variant.Variant
import chess.{ Clock, Mode }
import org.joda.time.DateTime
import play.api.data._
import play.api.data.Forms._
import play.api.libs.json.Json
import scala.concurrent.duration._

import lila.common.Json.daysFormat
import lila.common.{ Bearer, Days, Template }
import lila.game.{ Game, GameRule, IdGenerator }
import lila.oauth.{ AccessToken, OAuthScope, OAuthServer }
import lila.user.User

object SetupBulk {

  val maxGames = 500

  case class BulkFormData(
      tokens: String,
      variant: Variant,
      clock: Option[Clock.Config],
      days: Option[Days],
      rated: Boolean,
      pairAt: Option[DateTime],
      startClocksAt: Option[DateTime],
      message: Option[Template],
      rules: Set[GameRule]
  ) {
    def clockOrDays = clock.toLeft(days | Days(3))
  }

  private def timestampInNearFuture = longNumber(
    min = 0,
    max = DateTime.now.plusDays(1).getMillis
  )

  def form = Form[BulkFormData](
    mapping(
      "players" -> nonEmptyText
        .verifying("Not enough tokens", t => extractTokenPairs(t).nonEmpty)
        .verifying(s"Too many tokens (max: ${maxGames * 2})", t => extractTokenPairs(t).sizeIs < maxGames)
        .verifying(
          "Tokens must be unique",
          t => {
            val tokens = extractTokenPairs(t).view.flatMap { case (w, b) => Vector(w, b) }.toVector
            tokens.size == tokens.distinct.size
          }
        ),
      SetupForm.api.variant,
      SetupForm.api.clock,
      SetupForm.api.optionalDays,
      "rated"         -> boolean,
      "pairAt"        -> optional(timestampInNearFuture),
      "startClocksAt" -> optional(timestampInNearFuture),
      "message"       -> SetupForm.api.message,
      "rules"         -> optional(Mappings.gameRules)
    ) {
      (
          tokens: String,
          variant: Option[String],
          clock: Option[Clock.Config],
          days: Option[Days],
          rated: Boolean,
          pairTs: Option[Long],
          clockTs: Option[Long],
          message: Option[String],
          rules: Option[Set[GameRule]]
      ) =>
        BulkFormData(
          tokens,
          Variant orDefault ~variant,
          clock,
          days,
          rated,
          pairTs.map { new DateTime(_) },
          clockTs.map { new DateTime(_) },
          message map Template,
          ~rules
        )
    }(_ => None)
      .verifying(
        "clock or correspondence days required",
        c => c.clock.isDefined || c.days.isDefined
      )
  )

  private[setup] def extractTokenPairs(str: String): List[(Bearer, Bearer)] =
    str
      .split(',')
      .view
      .map(_ split ":")
      .collect { case Array(w, b) =>
        w.trim -> b.trim
      }
      .collect {
        case (w, b) if w.nonEmpty && b.nonEmpty => (Bearer(w), Bearer(b))
      }
      .toList

  case class BadToken(token: Bearer, error: OAuthServer.AuthError)

  case class ScheduledGame(id: Game.ID, white: User.ID, black: User.ID)

  case class ScheduledBulk(
      _id: String,
      by: User.ID,
      games: List[ScheduledGame],
      variant: Variant,
      clock: Either[Clock.Config, Days],
      mode: Mode,
      pairAt: DateTime,
      startClocksAt: Option[DateTime],
      scheduledAt: DateTime,
      message: Option[Template],
      rules: Set[GameRule] = Set.empty,
      pairedAt: Option[DateTime] = None
  ) {
    def userSet = Set(games.flatMap(g => List(g.white, g.black)))
    def collidesWith(other: ScheduledBulk) = {
      pairAt == other.pairAt || startClocksAt == other.startClocksAt
    } && userSet.exists(other.userSet.contains)
    def nonEmptyRules = rules.nonEmpty option rules
  }

  sealed trait ScheduleError
  case class BadTokens(tokens: List[BadToken])    extends ScheduleError
  case class DuplicateUsers(users: List[User.ID]) extends ScheduleError
  case object RateLimited                         extends ScheduleError

  def toJson(bulk: ScheduledBulk) = {
    import bulk._
    import lila.common.Json.jodaWrites
    import lila.game.JsonView.ruleWriter
    Json
      .obj(
        "id" -> _id,
        "games" -> games.map { g =>
          Json.obj(
            "id"    -> g.id,
            "white" -> g.white,
            "black" -> g.black
          )
        },
        "variant"       -> variant.key,
        "rated"         -> mode.rated,
        "pairAt"        -> pairAt,
        "startClocksAt" -> startClocksAt,
        "scheduledAt"   -> scheduledAt,
        "pairedAt"      -> pairedAt
      )
      .add("clock" -> bulk.clock.left.toOption.map { c =>
        Json.obj(
          "limit"     -> c.limitSeconds,
          "increment" -> c.incrementSeconds
        )
      })
      .add("correspondence" -> bulk.clock.toOption.map { days =>
        Json.obj("daysPerTurn" -> days)
      })
      .add("message" -> message.map(_.value))
      .add("rules" -> nonEmptyRules)
  }

}

final class SetupBulkApi(oauthServer: OAuthServer, idGenerator: IdGenerator)(implicit
    ec: scala.concurrent.ExecutionContext,
    mat: akka.stream.Materializer
) {

  import SetupBulk._

  type Result = Either[ScheduleError, ScheduledBulk]

  private val rateLimit = new lila.memo.RateLimit[User.ID](
    credits = maxGames * 3,
    duration = 10.minutes,
    key = "challenge.bulk"
  )

  def apply(data: BulkFormData, me: User): Fu[Result] =
    Source(extractTokenPairs(data.tokens))
      .mapConcat { case (whiteToken, blackToken) =>
        List(whiteToken, blackToken) // flatten now, re-pair later!
      }
      .mapAsync(8) { token =>
        oauthServer.auth(token, List(OAuthScope.Challenge.Write), none) map {
          _.left.map { BadToken(token, _) }
        }
      }
      .runFold[Either[List[BadToken], List[User.ID]]](Right(Nil)) {
        case (Left(bads), Left(bad))       => Left(bad :: bads)
        case (Left(bads), _)               => Left(bads)
        case (Right(_), Left(bad))         => Left(bad :: Nil)
        case (Right(users), Right(scoped)) => Right(scoped.user.id :: users)
      }
      .flatMap {
        case Left(errors) => fuccess(Left(BadTokens(errors.reverse)))
        case Right(allPlayers) =>
          val dups = allPlayers
            .groupBy(identity)
            .view
            .mapValues(_.size)
            .collect {
              case (u, nb) if nb > 1 => u
            }
            .toList
          if (dups.nonEmpty) fuccess(Left(DuplicateUsers(dups)))
          else {
            val pairs = allPlayers.reverse
              .grouped(2)
              .collect { case List(w, b) => (w, b) }
              .toList
            val nbGames = pairs.size
            val cost    = nbGames * (if (me.isVerified || me.isApiHog) 1 else 3)
            rateLimit[Fu[Result]](me.id, cost = nbGames) {
              lila.mon.api.challenge.bulk.scheduleNb(me.id).increment(nbGames).unit
              idGenerator
                .games(nbGames)
                .map {
                  _.toList zip pairs
                }
                .map {
                  _.map { case (id, (w, b)) =>
                    ScheduledGame(id, w, b)
                  }
                }
                .dmap {
                  ScheduledBulk(
                    _id = lila.common.ThreadLocalRandom nextString 8,
                    by = me.id,
                    _,
                    data.variant,
                    data.clockOrDays,
                    Mode(data.rated),
                    pairAt = data.pairAt | DateTime.now,
                    startClocksAt = data.startClocksAt,
                    message = data.message,
                    rules = data.rules,
                    scheduledAt = DateTime.now
                  )
                }
                .dmap(Right.apply)
            }(fuccess(Left(RateLimited)))
          }
      }
}
