package controllers

import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json._
import play.api.mvc._
import scala.concurrent.duration._
import views._

import lila.api.Context
import lila.app._
import lila.common.{ config, HTTPRequest, IpAddress }
import lila.memo.RateLimit
import lila.team.{ Requesting, Team => TeamModel }
import lila.user.{ Holder, User => UserModel }

final class Team(
    env: Env,
    apiC: => Api
) extends LilaController(env) {

  private def forms     = env.team.forms
  private def api       = env.team.api
  private def paginator = env.team.paginator

  def all(page: Int) =
    Open { implicit ctx =>
      Reasonable(page) {
        paginator popularTeams page map {
          html.team.list.all(_)
        }
      }
    }

  def home(page: Int) =
    Open { implicit ctx =>
      ctx.me.??(api.hasTeams) map {
        case true  => Redirect(routes.Team.mine)
        case false => Redirect(routes.Team.all(page))
      }
    }

  def show(id: String, page: Int, mod: Boolean) =
    Open { implicit ctx =>
      Reasonable(page) {
        OptionFuOk(api team id) { renderTeam(_, page, mod) }
      }
    }

  def members(id: String, page: Int) =
    Open { implicit ctx =>
      Reasonable(page, config.Max(50)) {
        OptionFuResult(api teamEnabled id) { team =>
          val canSee =
            fuccess(team.publicMembers || isGranted(_.ManageTeam)) >>| ctx.userId.?? {
              api.belongsTo(team.id, _)
            }
          canSee flatMap {
            case true =>
              paginator.teamMembersWithDate(team, page) map {
                html.team.members(team, _)
              }
            case false => authorizationFailed
          }
        }
      }
    }

  def search(text: String, page: Int) =
    OpenBody { implicit ctx =>
      Reasonable(page) {
        if (text.trim.isEmpty) paginator popularTeams page map { html.team.list.all(_) }
        else
          env.teamSearch(text, page) map { html.team.list.search(text, _) }
      }
    }

  private def renderTeam(team: TeamModel, page: Int = 1, requestModView: Boolean = false)(implicit
      ctx: Context
  ) =
    for {
      info    <- env.teamInfo(team, ctx.me, withForum = canHaveForum(team, requestModView))
      members <- paginator.teamMembers(team, page)
      log     <- (requestModView && isGranted(_.ManageTeam)).??(env.mod.logApi.teamLog(team.id))
      hasChat = canHaveChat(team, info, requestModView)
      chat <-
        hasChat ?? env.chat.api.userChat.cached
          .findMine(lila.chat.Chat.Id(team.id), ctx.me)
          .map(some)
      _ <- env.user.lightUserApi preloadMany {
        team.leaders.toList ::: info.userIds ::: chat.??(_.chat.userIds)
      }
      version <- hasChat ?? env.team.version(team.id).dmap(some)
    } yield html.team.show(team, members, info, chat, version, requestModView, log)

  private def canHaveChat(team: TeamModel, info: lila.app.mashup.TeamInfo, requestModView: Boolean = false)(
      implicit ctx: Context
  ): Boolean =
    team.enabled && !team.isChatFor(_.NONE) && ctx.noKid && HTTPRequest.isHuman(ctx.req) && {
      (team.isChatFor(_.LEADERS) && ctx.userId.exists(team.leaders)) ||
      (team.isChatFor(_.MEMBERS) && info.mine) ||
      (isGranted(_.Shusher) && requestModView)
    }

  private def canHaveForum(team: TeamModel, requestModView: Boolean)(isMember: Boolean)(implicit
      ctx: Context
  ): Boolean =
    team.enabled && !team.isForumFor(_.NONE) && ctx.noKid && {
      team.isForumFor(_.EVERYONE) ||
      (team.isForumFor(_.LEADERS) && ctx.userId.exists(team.leaders)) ||
      (team.isForumFor(_.MEMBERS) && isMember) ||
      (isGranted(_.ModerateForum) && requestModView)
    }

  def users(teamId: String) =
    AnonOrScoped(_.Team.Read) { req => me =>
      api teamEnabled teamId flatMap {
        _ ?? { team =>
          val canView: Fu[Boolean] =
            if (team.publicMembers) fuccess(true)
            else me.??(u => api.belongsTo(team.id, u.id))
          canView map {
            case true =>
              apiC.jsonStream(
                env.team
                  .memberStream(team, config.MaxPerSecond(20))
                  .map(env.api.userApi.one(_, withOnline = false))
              )(req)
            case false => Unauthorized
          }
        }
      }
    }

  def tournaments(teamId: String) =
    Open { implicit ctx =>
      api teamEnabled teamId flatMap {
        _ ?? { team =>
          env.teamInfo.tournaments(team, 30, 30) map { tours =>
            Ok(html.team.tournaments.page(team, tours))
          }
        }
      }
    }

  def edit(id: String) =
    Auth { implicit ctx => _ =>
      WithOwnedTeamEnabled(id) { team =>
        fuccess(html.team.form.edit(team, forms edit team))
      }
    }

  def update(id: String) =
    AuthBody { implicit ctx => me =>
      WithOwnedTeamEnabled(id) { team =>
        implicit val req = ctx.body
        forms
          .edit(team)
          .bindFromRequest()
          .fold(
            err => BadRequest(html.team.form.edit(team, err)).fuccess,
            data => api.update(team, data, me) inject Redirect(routes.Team.show(team.id)).flashSuccess
          )
      }
    }

  def kickForm(id: String) =
    Auth { implicit ctx => _ =>
      WithOwnedTeamEnabled(id) { team =>
        Ok(html.team.admin.kick(team, forms.members)).fuccess
      }
    }

  def kick(id: String) =
    AuthBody { implicit ctx => me =>
      WithOwnedTeamEnabled(id) { team =>
        implicit val req = ctx.body
        forms.members.bindFromRequest().value ?? { api.kickMembers(team, _, me).sequenceFu } inject Redirect(
          routes.Team.show(team.id)
        ).flashSuccess
      }
    }

  private val ApiKickRateLimitPerIP = lila.memo.RateLimit.composite[IpAddress](
    key = "team.kick.api.ip",
    enforce = env.net.rateLimit.value
  )(
    ("fast", 10, 2.minutes),
    ("slow", 50, 1.day)
  )
  private val kickLimitReportOnce = lila.memo.OnceEvery(10.minutes)

  def kickUser(teamId: String, userId: String) =
    Scoped(_.Team.Lead) { req => me =>
      WithOwnedTeamEnabledApi(teamId, me) { team =>
        ApiKickRateLimitPerIP[Fu[Api.ApiResult]](
          HTTPRequest ipAddress req,
          cost = if (me.isVerified || me.isApiHog) 0 else 1
        ) {
          api.kick(team, userId, me) inject Api.Done
        } {
          if (kickLimitReportOnce(userId))
            lila
              .log("security")
              .warn(s"API team.kick limited team:${teamId} user:${me.id} ip:${HTTPRequest ipAddress req}")
          fuccess(Api.Limited)
        }
      }
    }

  def leadersForm(id: String) =
    Auth { implicit ctx => _ =>
      WithOwnedTeamEnabled(id) { team =>
        Ok(html.team.admin.leaders(team, forms leaders team)).fuccess
      }
    }

  def leaders(id: String) =
    AuthBody { implicit ctx => me =>
      WithOwnedTeamEnabled(id) { team =>
        implicit val req = ctx.body
        forms.leaders(team).bindFromRequest().value ?? {
          api.setLeaders(team, _, me, isGranted(_.ManageTeam))
        } inject Redirect(
          routes.Team.show(team.id)
        ).flashSuccess
      }
    }

  def close(id: String) =
    SecureBody(_.ManageTeam) { implicit ctx => me =>
      OptionFuResult(api team id) { team =>
        implicit val body = ctx.body
        forms.explain
          .bindFromRequest()
          .fold(
            _ => funit,
            explain =>
              api.delete(team, me.user, explain) >>
                env.mod.logApi.deleteTeam(me.id, team.id, explain)
          ) inject Redirect(routes.Team all 1).flashSuccess
      }
    }

  def disable(id: String) =
    AuthBody { implicit ctx => me =>
      WithOwnedTeamEnabled(id) { team =>
        implicit val body = ctx.body
        forms.explain
          .bindFromRequest()
          .fold(
            _ => funit,
            explain =>
              api.toggleEnabled(team, me, explain) >> {
                env.mod.logApi.toggleTeam(me.id, team.id, team.enabled, explain)
              }
          )
      } inject Redirect(routes.Team show id).flashSuccess
    }

  def form =
    Auth { implicit ctx => me =>
      LimitPerWeek(me) {
        forms.anyCaptcha map { captcha =>
          Ok(html.team.form.create(forms.create, captcha))
        }
      }
    }

  private val OneAtAtATime = new lila.common.WorkQueue.Unbuffered[UserModel.ID]

  def create =
    AuthBody { implicit ctx => implicit me =>
      OneAtAtATime(me.id) {
        api hasJoinedTooManyTeams me flatMap { tooMany =>
          if (tooMany) tooManyTeams(me)
          else
            LimitPerWeek(me) {
              implicit val req = ctx.body
              forms.create
                .bindFromRequest()
                .fold(
                  err =>
                    forms.anyCaptcha map { captcha =>
                      BadRequest(html.team.form.create(err, captcha))
                    },
                  data =>
                    api.create(data, me) map { team =>
                      Redirect(routes.Team.show(team.id)).flashSuccess
                    }
                )
            }
        }
      } | rateLimitedFu
    }

  def mine =
    Auth { implicit ctx => me =>
      api mine me map {
        html.team.list.mine(_)
      }
    }

  private def tooManyTeams(me: UserModel)(implicit ctx: Context) =
    api mine me map html.team.list.mine map { BadRequest(_) }

  def leader =
    Auth { implicit ctx => me =>
      env.team.teamRepo enabledTeamsByLeader me.id map {
        html.team.list.ledByMe(_)
      }
    }

  def join(id: String) =
    AuthOrScopedBody(_.Team.Write)(
      auth = implicit ctx =>
        me =>
          api.teamEnabled(id) flatMap {
            _ ?? { team =>
              OneAtAtATime(me.id) {
                api hasJoinedTooManyTeams me flatMap { tooMany =>
                  if (tooMany)
                    negotiate(
                      html = tooManyTeams(me),
                      api = _ => BadRequest(jsonError("You have joined too many teams")).fuccess
                    )
                  else
                    negotiate(
                      html = webJoin(team, me, request = none, password = none),
                      api = _ => {
                        implicit val body = ctx.body
                        forms
                          .apiRequest(team)
                          .bindFromRequest()
                          .fold(
                            newJsonFormError,
                            setup =>
                              api.join(team, me, setup.message, setup.password) flatMap {
                                case Requesting.Joined => jsonOkResult.fuccess
                                case Requesting.NeedRequest =>
                                  BadRequest(jsonError("This team requires confirmation.")).fuccess
                                case Requesting.NeedPassword =>
                                  BadRequest(jsonError("This team requires a password.")).fuccess
                                case _ => notFoundJson("Team not found")
                              }
                          )
                      }
                    )
                }
              } | rateLimitedFu
            }
          },
      scoped = implicit req =>
        me =>
          api.team(id) flatMap {
            _ ?? { team =>
              implicit val lang = reqLang
              forms
                .apiRequest(team)
                .bindFromRequest()
                .fold(
                  newJsonFormError,
                  setup =>
                    OneAtAtATime(me.id) {
                      api.join(team, me, setup.message, setup.password) flatMap {
                        case Requesting.Joined => jsonOkResult.fuccess
                        case Requesting.NeedPassword =>
                          Forbidden(jsonError("This team requires a password.")).fuccess
                        case Requesting.NeedRequest =>
                          Forbidden(
                            jsonError(
                              "This team requires confirmation, and is not owned by the oAuth app owner."
                            )
                          ).fuccess
                      }
                    } | rateLimitedJson.fuccess
                )
            }
          }
    )

  def subscribe(teamId: String) = {
    def doSub(req: Request[_], me: UserModel) =
      Form(single("subscribe" -> optional(boolean)))
        .bindFromRequest()(req, formBinding)
        .fold(_ => funit, v => api.subscribe(teamId, me.id, ~v))
    AuthOrScopedBody(_.Team.Write)(
      auth = ctx => me => doSub(ctx.body, me) inject jsonOkResult,
      scoped = req => me => doSub(req, me) inject jsonOkResult
    )
  }

  def requests =
    Auth { implicit ctx => me =>
      import lila.memo.CacheApi._
      env.team.cached.nbRequests invalidate me.id
      api requestsWithUsers me map { html.team.request.all(_) }
    }

  def requestForm(id: String) =
    Auth { implicit ctx => me =>
      OptionFuOk(api.requestable(id, me)) { team =>
        fuccess(html.team.request.requestForm(team, forms.request(team)))
      }
    }

  def requestCreate(id: String) =
    AuthBody { implicit ctx => me =>
      OptionFuResult(api.requestable(id, me)) { team =>
        implicit val req = ctx.body
        forms
          .request(team)
          .bindFromRequest()
          .fold(
            err => BadRequest(html.team.request.requestForm(team, err)).fuccess,
            setup =>
              if (team.open) webJoin(team, me, request = none, password = setup.password)
              else
                setup.message ?? { msg =>
                  api.createRequest(team, me, msg) inject Redirect(routes.Team.show(team.id)).flashSuccess
                }
          )
      }
    }

  private def webJoin(team: TeamModel, me: UserModel, request: Option[String], password: Option[String]) =
    api.join(team, me, request = request, password = password) flatMap {
      case Requesting.Joined => Redirect(routes.Team.show(team.id)).flashSuccess.fuccess
      case Requesting.NeedRequest | Requesting.NeedPassword =>
        Redirect(routes.Team.requestForm(team.id)).flashSuccess.fuccess
    }

  def requestProcess(requestId: String) =
    AuthBody { implicit ctx => me =>
      import cats.implicits._
      OptionFuRedirectUrl(for {
        requestOption <- api request requestId
        teamOption    <- requestOption.??(req => env.team.teamRepo.byLeader(req.team, me.id))
      } yield (teamOption, requestOption).mapN((_, _))) { case (team, request) =>
        implicit val req = ctx.body
        forms.processRequest
          .bindFromRequest()
          .fold(
            _ => fuccess(routes.Team.show(team.id).toString),
            { case (decision, url) =>
              api.processRequest(team, request, decision) inject url
            }
          )
      }
    }

  def declinedRequests(id: String, page: Int) =
    Auth { implicit ctx => _ =>
      WithOwnedTeamEnabled(id) { team =>
        paginator.declinedRequests(team, page) map { requests =>
          Ok(html.team.declinedRequest.all(team, requests))
        }
      }
    }

  def quit(id: String) =
    AuthOrScoped(_.Team.Write)(
      auth = implicit ctx =>
        me =>
          OptionFuResult(api team id) { team =>
            if (team isOnlyLeader me.id)
              negotiate(
                html = Redirect(routes.Team.edit(team.id))
                  .flashFailure(lila.i18n.I18nKeys.team.onlyLeaderLeavesTeam.txt())
                  .fuccess,
                api = _ => jsonOkResult.fuccess
              )
            else
              api.cancelRequestOrQuit(team, me) >>
                negotiate(
                  html = Redirect(routes.Team.mine).flashSuccess.fuccess,
                  api = _ => jsonOkResult.fuccess
                )
          }(ctx),
      scoped = _ =>
        me =>
          api team id flatMap {
            _.fold(notFoundJson()) { team =>
              api.cancelRequestOrQuit(team, me) inject jsonOkResult
            }
          }
    )

  def autocomplete =
    Action.async { req =>
      get("term", req).filter(_.nonEmpty) match {
        case None => BadRequest("No search term provided").fuccess
        case Some(term) =>
          for {
            teams <- api.autocomplete(term, 10)
            _     <- env.user.lightUserApi preloadMany teams.map(_.createdBy)
          } yield JsonOk {
            JsArray(teams map { team =>
              Json.obj(
                "id"      -> team.id,
                "name"    -> team.name,
                "owner"   -> env.user.lightUserApi.sync(team.createdBy).fold(team.createdBy)(_.name),
                "members" -> team.nbMembers
              )
            })
          }
      }
    }

  def pmAll(id: String) =
    Auth { implicit ctx => _ =>
      WithOwnedTeamEnabled(id) { team =>
        renderPmAll(team, forms.pmAll)
      }
    }

  private def renderPmAll(team: TeamModel, form: Form[_])(implicit ctx: Context) =
    for {
      tours  <- env.tournament.api.visibleByTeam(team.id, 0, 20).dmap(_.next)
      unsubs <- env.team.cached.unsubs.get(team.id)
    } yield Ok(html.team.admin.pmAll(team, form, tours, unsubs))

  def pmAllSubmit(id: String) =
    AuthOrScopedBody(_.Team.Lead)(
      auth = implicit ctx =>
        me =>
          WithOwnedTeamEnabled(id) { team =>
            doPmAll(team, me)(ctx.body).fold(
              err => renderPmAll(team, err),
              _ map { res =>
                Redirect(routes.Team.show(team.id))
                  .flashing(res match {
                    case RateLimit.Through => "success" -> ""
                    case RateLimit.Limited => "failure" -> rateLimitedMsg
                  })
              }
            )
          },
      scoped = implicit req =>
        me =>
          api teamEnabled id flatMap {
            _.filter(_ leaders me.id) ?? { team =>
              doPmAll(team, me).fold(
                err => BadRequest(errorsAsJson(err)(reqLang)).fuccess,
                _ map {
                  case RateLimit.Through => jsonOkResult
                  case RateLimit.Limited => rateLimitedJson
                }
              )
            }
          }
    )

  // API

  def apiAll(page: Int) =
    Action.async {
      import env.team.jsonView._
      import lila.common.paginator.PaginatorJson._
      JsonOk {
        paginator popularTeams page flatMap { pager =>
          env.user.lightUserApi.preloadMany(pager.currentPageResults.flatMap(_.leaders)) inject pager
        }
      }
    }

  def apiShow(id: String) =
    Open { ctx =>
      JsonOptionOk {
        api teamEnabled id flatMap {
          _ ?? { team =>
            for {
              joined    <- ctx.userId.?? { api.belongsTo(id, _) }
              requested <- ctx.userId.ifFalse(joined).?? { env.team.requestRepo.exists(id, _) }
            } yield {
              env.team.jsonView.teamWrites.writes(team) ++ Json
                .obj(
                  "joined"    -> joined,
                  "requested" -> requested
                )
            }.some
          }
        }
      }
    }

  def apiSearch(text: String, page: Int) =
    Action.async {
      import env.team.jsonView._
      import lila.common.paginator.PaginatorJson._
      JsonOk {
        if (text.trim.isEmpty) paginator popularTeams page
        else env.teamSearch(text, page)
      }
    }

  def apiTeamsOf(username: String) =
    Action.async {
      import env.team.jsonView._
      JsonOk {
        api teamsOf username flatMap { teams =>
          env.user.lightUserApi.preloadMany(teams.flatMap(_.leaders)) inject teams
        }
      }
    }

  def apiRequests(teamId: String) =
    Scoped(_.Team.Read) { _ => me =>
      WithOwnedTeamEnabledApi(teamId, me) { team =>
        api.requestsWithUsers(team) map { reqs =>
          Api.Data(JsArray(reqs map env.team.jsonView.requestWithUserWrites.writes))
        }
      }
    }

  def apiRequestProcess(teamId: String, userId: String, decision: String) =
    Scoped(_.Team.Lead) { req => me =>
      WithOwnedTeamEnabledApi(teamId, me) { team =>
        api request lila.team.Request.makeId(team.id, UserModel normalize userId) flatMap {
          case None      => fuccess(Api.ClientError("No such team join request"))
          case Some(req) => api.processRequest(team, req, decision) inject Api.Done
        }
      }
    }

  private def doPmAll(team: TeamModel, me: UserModel)(implicit
      req: Request[_]
  ): Either[Form[_], Fu[RateLimit.Result]] =
    forms.pmAll
      .bindFromRequest()
      .fold(
        err => Left(err),
        msg =>
          Right {
            PmAllLimitPerTeam[RateLimit.Result](team.id, if (me.isVerifiedOrAdmin) 1 else pmAllCost) {
              val url = s"${env.net.baseUrl}${routes.Team.show(team.id)}"
              val full = s"""$msg
---
You received this because you are subscribed to messages of the team $url."""
              env.msg.api
                .multiPost(
                  Holder(me),
                  env.team.memberStream.subscribedIds(team, config.MaxPerSecond(50)),
                  full
                )
                .addEffect { nb =>
                  lila.mon.msg.teamBulk(team.id).record(nb).unit
                }
              // we don't wait for the stream to complete, it would make lichess time out
              fuccess(RateLimit.Through)
            }(RateLimit.Limited)
          }
      )

  private val pmAllCost = 5
  private val PmAllLimitPerTeam = env.memo.mongoRateLimitApi[lila.team.Team.ID](
    "team.pm.all",
    credits = 7 * pmAllCost,
    duration = 7.days
  )

  private def LimitPerWeek[A <: Result](me: UserModel)(a: => Fu[A])(implicit ctx: Context): Fu[Result] =
    api.countCreatedRecently(me) flatMap { count =>
      val allow =
        isGranted(_.ManageTeam) ||
          (isGranted(_.Verified) && count < 100) ||
          (isGranted(_.Teacher) && count < 10) ||
          count < 3
      if (allow) a
      else Forbidden(views.html.site.message.teamCreateLimit).fuccess
    }

  private def WithOwnedTeam(teamId: String)(f: TeamModel => Fu[Result])(implicit ctx: Context): Fu[Result] =
    OptionFuResult(api team teamId) { team =>
      if (ctx.userId.exists(team.leaders.contains) || isGranted(_.ManageTeam)) f(team)
      else renderTeam(team) map { Forbidden(_) }
    }

  private def WithOwnedTeamEnabled(
      teamId: String
  )(f: TeamModel => Fu[Result])(implicit ctx: Context): Fu[Result] =
    WithOwnedTeam(teamId) { team =>
      if (team.enabled || isGranted(_.ManageTeam)) f(team)
      else notFound
    }

  private def WithOwnedTeamEnabledApi(teamId: String, me: UserModel)(
      f: TeamModel => Fu[Api.ApiResult]
  ): Fu[Result] =
    api teamEnabled teamId flatMap {
      case Some(team) if team leaders me.id => f(team)
      case Some(_)                          => fuccess(Api.ClientError("Not your team"))
      case None                             => fuccess(Api.NoData)
    } map apiC.toHttp
}
