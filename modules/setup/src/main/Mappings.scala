package lila.setup

import chess.format.FEN
import chess.Mode
import chess.{ variant => V }
import play.api.data.format.Formats._
import play.api.data.Forms._

import lila.common.Days
import lila.common.Form._
import lila.game.GameRule
import lila.lobby.Color
import lila.rating.RatingRange

private object Mappings {

  val variant                   = number.verifying(Config.variants contains _)
  val variantWithFen            = number.verifying(Config.variantsWithFen contains _)
  val aiVariants                = number.verifying(Config.aiVariants contains _)
  val variantWithVariants       = number.verifying(Config.variantsWithVariants contains _)
  val variantWithFenAndVariants = number.verifying(Config.variantsWithFenAndVariants contains _)
  val boardApiVariants = Set(
    V.Standard.key,
    V.Chess960.key,
    V.Crazyhouse.key,
    V.KingOfTheHill.key,
    V.ThreeCheck.key,
    V.Antichess.key,
    V.Atomic.key,
    V.Horde.key,
    V.RacingKings.key
  )
  val boardApiVariantKeys      = text.verifying(boardApiVariants contains _)
  val time                     = of[Double].verifying(HookConfig validateTime _)
  val increment                = number.verifying(HookConfig validateIncrement _)
  val daysChoices              = List(1, 2, 3, 5, 7, 10, 14).map(Days)
  val days                     = of[Days].verifying(mustBeOneOf(daysChoices), daysChoices.contains _)
  def timeMode                 = number.verifying(TimeMode.ids contains _)
  def mode(withRated: Boolean) = optional(rawMode(withRated))
  def rawMode(withRated: Boolean) =
    number
      .verifying(HookConfig.modes contains _)
      .verifying(m => m == Mode.Casual.id || withRated)
  val ratingRange = text.verifying(RatingRange valid _)
  val color       = text.verifying(Color.names contains _)
  val level       = number.verifying(AiConfig.levels contains _)
  val speed       = number.verifying(Config.speeds contains _)
  val fenField = optional {
    import lila.common.Form.fen._
    of[FEN]
      .transform[FEN](f => FEN(f.value.trim), identity)
      .transform[FEN](truncateMoveNumber, identity)
  }
  val gameRules = lila.common.Form.strings
    .separator(",")
    .verifying(_.forall(GameRule.byKey.contains))
    .transform[Set[GameRule]](rs => rs.flatMap(GameRule.byKey.get).toSet, _.map(_.key).toList)
}
