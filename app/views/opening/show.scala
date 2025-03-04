package views.html.opening

import controllers.routes

import lila.api.Context
import lila.app.templating.Environment._
import lila.app.ui.ScalatagsTemplate._
import lila.opening.NamePart
import lila.opening.{ OpeningPage, OpeningQuery }
import lila.puzzle.PuzzleOpening

object show {

  import bits._

  def apply(page: OpeningPage, puzzleKey: Option[String])(implicit ctx: Context) =
    views.html.base.layout(
      moreCss = cssTag("opening"),
      moreJs = moreJs(page.some),
      title = s"${trans.opening.txt()} • ${page.name}",
      openGraph = lila.app.ui
        .OpenGraph(
          `type` = "article",
          image = cdnUrl(
            s"${routes.Export.fenThumbnail(page.query.fen.value, chess.White.name, page.opening.flatMap(_.uci.split(" ").lastOption), none, ctx.pref.theme.some, ctx.pref.pieceSet.some).url}"
          ).some,
          title = page.name,
          url = s"$netBaseUrl${queryUrl(page.query)}",
          description = page.opening.??(_.pgn)
        )
        .some,
      csp = defaultCsp.withInlineIconFont.some
    ) {
      main(cls := "page box box-pad opening")(
        index.searchAndConfig(page.query.config, "", page.query.key),
        search.resultsList(Nil),
        h1(cls := "opening__title")(
          page.query.prev match {
            case Some(prev) => a(href := queryUrl(prev), title := prev.name, dataIcon := "")
            case None       => a(href := routes.Opening.index(), dataIcon := "")
          },
          span(cls := "opening__name")(
            page.nameParts.zipWithIndex map { case (part, i) =>
              frag(
                part match {
                  case Left(move) => span(cls := "opening__name__move")(i > 0 option ", ", move)
                  case Right((name, key)) =>
                    val className = s"opening__name__section opening__name__section--${i + 1}"
                    frag(
                      if (i == 0) emptyFrag else if (i == 1) ": " else ", ",
                      key.fold(span(cls := className)(name)) { k =>
                        a(href := routes.Opening.query(k))(cls := className)(name)
                      }
                    )
                }
              )
            },
            beta
          )
        ),
        div(cls := "opening__intro")(
          div(cls := "opening__intro__result-lpv")(
            div(cls := "opening__intro__result result-bar")(page.explored map { exp =>
              resultSegments(exp.result)
            }),
            div(
              cls              := "lpv lpv--todo lpv--moves-bottom",
              st.data("pgn")   := page.query.pgnString,
              st.data("title") := page.opening.map(_.name)
            )(lpvPreload)
          ),
          div(cls := "opening__intro__content")(
            wiki(page),
            div(cls := "opening__popularity-actions")(
              div(
                cls := "opening__actions"
              )(
                puzzleKey.map { key =>
                  a(cls := "button text", dataIcon := "", href := routes.Puzzle.show(key))(
                    "Train with puzzles"
                  )
                },
                a(
                  cls      := "button text",
                  dataIcon := "",
                  href     := s"${routes.UserAnalysis.pgn(page.query.pgn mkString "_")}#explorer"
                )(trans.openingExplorer())
              ),
              if (page.explored.??(_.history).nonEmpty)
                div(cls      := "opening__popularity opening__popularity--chart")(
                  canvas(cls := "opening__popularity__chart")
                )
              else
                p(cls := "opening__popularity opening__error")(
                  "Couldn't fetch the popularity history, try again later."
                )
            )
          )
        ),
        div(cls := "opening__panels")(
          views.html.base.bits.ariaTabList("opening", "next")(
            (
              "next",
              "Popular continuations",
              page.explored.map(whatsNext) |
                p(cls := "opening__error")("Couldn't fetch the next moves, try again later.")
            ),
            ("games", "Example games", exampleGames(page))
          )
        )
      )
    }

  private def exampleGames(page: OpeningPage)(implicit ctx: Context) =
    div(cls := "opening__games")(page.explored.??(_.games).map { game =>
      div(
        cls              := "opening__games__game lpv lpv--todo lpv--moves-bottom",
        st.data("pgn")   := game.pgn.toString,
        st.data("ply")   := page.query.pgn.size + 1,
        st.data("title") := titleGame(game.game)
      )(lpvPreload)
    })
}
