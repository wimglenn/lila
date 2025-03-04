package lila.analyse

import play.api.libs.json._

import lila.game.Game
import lila.tree.Eval.JsonHandlers._
import lila.common.Maths

object JsonView {

  def moves(analysis: Analysis, withGlyph: Boolean = true) =
    JsArray(analysis.infoAdvices map { case (info, adviceOption) =>
      Json
        .obj()
        .add("eval" -> info.cp)
        .add("mate" -> info.mate)
        .add("best" -> info.best.map(_.uci))
        .add("variation" -> info.variation.nonEmpty.option(info.variation mkString " "))
        .add("judgment" -> adviceOption.map { a =>
          Json
            .obj(
              "name"    -> a.judgment.name,
              "comment" -> a.makeComment(withEval = false, withBestMove = true)
            )
            .add(
              "glyph" -> withGlyph.option(
                Json.obj(
                  "name"   -> a.judgment.glyph.name,
                  "symbol" -> a.judgment.glyph.symbol
                )
              )
            )
        })
    })

  def player(pov: Game.SideAndStart)(analysis: Analysis) =
    analysis.summary
      .find(_._1 == pov.color)
      .map(_._2)
      .map { s =>
        JsObject(s map { case (nag, nb) =>
          nag.toString.toLowerCase -> JsNumber(nb)
        })
          .add("acpl", lila.analyse.AccuracyCP.mean(pov, analysis))
          .add("accuracy", lila.analyse.AccuracyPercent.gameAccuracy(pov, analysis).map(_.toInt))
      }

  def bothPlayers(game: Game, analysis: Analysis) =
    Json.obj(
      "id"    -> analysis.id,
      "white" -> player(game.whitePov.sideAndStart)(analysis),
      "black" -> player(game.blackPov.sideAndStart)(analysis)
    )

  def bothPlayers(pov: Game.SideAndStart, analysis: Analysis) =
    Json.obj(
      "id"    -> analysis.id,
      "white" -> player(pov.copy(color = chess.White))(analysis),
      "black" -> player(pov.copy(color = chess.Black))(analysis)
    )

  def mobile(game: Game, analysis: Analysis) =
    Json.obj(
      "summary" -> bothPlayers(game, analysis),
      "moves"   -> moves(analysis)
    )
}
