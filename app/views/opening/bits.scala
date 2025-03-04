package views.html.opening

import cats.data.NonEmptyList
import chess.opening.FullOpening
import controllers.routes
import play.api.libs.json.{ JsArray, Json }

import lila.api.Context
import lila.app.templating.Environment._
import lila.app.ui.ScalatagsTemplate._
import lila.common.String.html.safeJsonValue
import lila.opening.{ Opening, OpeningConfig, OpeningExplored, OpeningPage, OpeningQuery, ResultCounts }
import lila.opening.OpeningSearchResult

private object bits {

  def beta = span(cls := "opening__beta")("BETA")

  def whatsNext(explored: OpeningExplored)(implicit ctx: Context) =
    div(cls := "opening__nexts")(
      explored.next.map { next =>
        a(cls := "opening__next", href := queryUrl(next.query))(
          span(cls := "opening__next__popularity")(
            span(style := s"width:${percentNumber(next.percent)}%", title := "Popularity")(
              s"${Math.round(next.percent)}%"
            )
          ),
          span(cls := "opening__next__title")(
            span(cls := "opening__next__name")(next.shortName.fold(nbsp)(frag(_))),
            span(cls := "opening__next__san")(next.san)
          ),
          span(cls := "opening__next__result-board")(
            span(cls := "opening__next__result result-bar") {
              resultSegments(next.result)
            },
            span(cls := "opening__next__board")(
              views.html.board.bits.mini(next.fen, lastMove = next.uci.uci)(span)
            )
          )
        )
      }
    )

  def configForm(config: OpeningConfig, thenTo: String)(implicit ctx: Context) = {
    import OpeningConfig._
    details(cls := "opening__config")(
      summary(cls := "opening__config__summary")(
        div(cls := "opening__config__summary__short")(
          iconTag('')
        ),
        div(cls := "opening__config__summary__large")(
          "Speed: ",
          span(cls := "opening__config__summary__speed")(config.showSpeeds),
          nbsp,
          nbsp,
          "Rating: ",
          span(cls := "opening__config__summary__rating")(config.showRatings)
        )
      ),
      postForm(
        cls    := "opening__config__form",
        action := routes.Opening.config(thenTo)
      )(
        checkboxes(form("speeds"), speedChoices, config.speeds.map(_.id)),
        checkboxes(form("ratings"), ratingChoices, config.ratings),
        div(cls                           := "opening__config__form__submit")(
          form3.submit(trans.apply())(cls := "button-empty")
        )
      )
    )
  }

  def moreJs(page: Option[OpeningPage])(implicit ctx: Context) = frag(
    jsModule("opening"),
    embedJsUnsafeLoadThen {
      page match {
        case Some(p) =>
          s"""LichessOpening.page(${safeJsonValue(
              Json.obj("history" -> p.explored.??(_.history))
            )})"""
        case None =>
          s"""LichessOpening.search()"""
      }
    }
  )

  def splitName(op: FullOpening) =
    Opening.sectionsOf(op.name) match {
      case NonEmptyList(family, variations) =>
        frag(
          span(cls := "opening-name__family")(family),
          variations.nonEmpty option ": ",
          fragList(
            variations.map { variation =>
              span(cls := "opening-name__variation")(variation)
            },
            ", "
          )
        )
    }

  def queryUrl(q: OpeningQuery) = routes.Opening.query(q.key)

  val lpvPreload = div(cls := "lpv__board")(div(cls := "cg-wrap")(cgWrapContent))

  def percentNumber(v: Double) = f"${v}%1.2f"
  def percentFrag(v: Double)   = frag(strong(percentNumber(v)), "%")

  def resultSegments(result: ResultCounts) = result.sum > 0 option {
    import result._
    val (blackV, drawsV, whiteV) = exaggerateResults(result)
    frag(
      resultSegment("black", "Black wins", blackPercent, blackV),
      resultSegment("draws", "Draws", drawsPercent, drawsV),
      resultSegment("white", "White wins", whitePercent, whiteV)
    )
  }

  def resultSegment(key: String, help: String, percent: Double, visualPercent: Double) = {
    val visible = visualPercent > 7
    val text    = s"${Math.round(percent)}%"
    span(
      cls   := key,
      style := s"height:${percentNumber(visualPercent)}%",
      title := s"$text $help"
    )(visible option text)
  }

  private def exaggerateResults(result: ResultCounts) = {
    import result._
    val (lower, upper)   = (30d, 70d)
    val factor           = 100d / (upper - lower)
    val drawSquishing    = 50d / 100d
    val drawHalfSquished = drawsPercent * factor * drawSquishing * 50d / 100d
    val drawTransformed  = drawsPercent * factor - 2 * drawHalfSquished
    val blackTransformed = (blackPercent - lower) * factor + drawHalfSquished
    val whiteTransformed = (whitePercent - lower) * factor + drawHalfSquished
    (blackTransformed, drawTransformed, whiteTransformed)
  }

}
