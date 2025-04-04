package gitbucket.core.controller

import java.io.File

import gitbucket.core.account.html
import gitbucket.core.helper
import gitbucket.core.model._
import gitbucket.core.service._
import gitbucket.core.service.WebHookService._
import gitbucket.core.ssh.SshUtil
import gitbucket.core.util.SyntaxSugars._
import gitbucket.core.util.Directory._
import gitbucket.core.util.Implicits._
import gitbucket.core.util.StringUtil._
import gitbucket.core.util._
import org.scalatra.i18n.Messages
import org.scalatra.BadRequest
import org.scalatra.forms._
import org.scalatra.Forbidden

class AccountController
    extends AccountControllerBase
    with AccountService
    with RepositoryService
    with ActivityService
    with WikiService
    with LabelsService
    with SshKeyService
    with GpgKeyService
    with OneselfAuthenticator
    with UsersAuthenticator
    with GroupManagerAuthenticator
    with ReadableUsersAuthenticator
    with AccessTokenService
    with WebHookService
    with PrioritiesService
    with RepositoryCreationService
    with RequestCache

trait AccountControllerBase extends AccountManagementControllerBase {
  self: AccountService & RepositoryService & ActivityService & WikiService & LabelsService & SshKeyService &
    GpgKeyService & OneselfAuthenticator & UsersAuthenticator & GroupManagerAuthenticator & ReadableUsersAuthenticator &
    AccessTokenService & WebHookService & PrioritiesService & RepositoryCreationService =>

  private case class AccountNewForm(
    userName: String,
    password: String,
    fullName: String,
    mailAddress: String,
    extraMailAddresses: List[String],
    description: Option[String],
    url: Option[String],
    fileId: Option[String]
  )

  private case class AccountEditForm(
    password: Option[String],
    fullName: String,
    mailAddress: String,
    extraMailAddresses: List[String],
    description: Option[String],
    url: Option[String],
    fileId: Option[String],
    clearImage: Boolean
  )

  private case class SshKeyForm(title: String, publicKey: String)

  private case class GpgKeyForm(title: String, publicKey: String)

  private case class PersonalTokenForm(note: String)

  private case class SyntaxHighlighterThemeForm(theme: String)

  private val newForm = mapping(
    "userName" -> trim(label("User name", text(required, maxlength(100), identifier, uniqueUserName, reservedNames))),
    "password" -> trim(label("Password", text(required, maxlength(40)))),
    "fullName" -> trim(label("Full Name", text(required, maxlength(100)))),
    "mailAddress" -> trim(label("Mail Address", text(required, maxlength(100), uniqueMailAddress()))),
    "extraMailAddresses" -> list(
      trim(label("Additional Mail Address", text(maxlength(100), uniqueExtraMailAddress())))
    ),
    "description" -> trim(label("bio", optional(text()))),
    "url" -> trim(label("URL", optional(text(maxlength(200))))),
    "fileId" -> trim(label("File ID", optional(text())))
  )(AccountNewForm.apply)

  private val editForm = mapping(
    "password" -> trim(label("Password", optional(text(maxlength(40))))),
    "fullName" -> trim(label("Full Name", text(required, maxlength(100)))),
    "mailAddress" -> trim(label("Mail Address", text(required, maxlength(100), uniqueMailAddress("userName")))),
    "extraMailAddresses" -> list(
      trim(label("Additional Mail Address", text(maxlength(100), uniqueExtraMailAddress("userName"))))
    ),
    "description" -> trim(label("bio", optional(text()))),
    "url" -> trim(label("URL", optional(text(maxlength(200))))),
    "fileId" -> trim(label("File ID", optional(text()))),
    "clearImage" -> trim(label("Clear image", boolean()))
  )(AccountEditForm.apply)

  private val sshKeyForm = mapping(
    "title" -> trim(label("Title", text(required, maxlength(100)))),
    "publicKey" -> trim2(label("Key", text(required, validPublicKey)))
  )(SshKeyForm.apply)

  private val gpgKeyForm = mapping(
    "title" -> trim(label("Title", text(required, maxlength(100)))),
    "publicKey" -> label("Key", text(required, validGpgPublicKey))
  )(GpgKeyForm.apply)

  private val personalTokenForm = mapping(
    "note" -> trim(label("Token", text(required, maxlength(100))))
  )(PersonalTokenForm.apply)

  private val syntaxHighlighterThemeForm = mapping(
    "highlighterTheme" -> trim(label("Theme", text(required)))
  )(SyntaxHighlighterThemeForm.apply)

  private val resetPasswordEmailForm = mapping(
    "mailAddress" -> trim(label("Email", text(required)))
  )(ResetPasswordEmailForm.apply)

  private val resetPasswordForm = mapping(
    "token" -> trim(label("Token", text(required))),
    "password" -> trim(label("Password", text(required, maxlength(40))))
  )(ResetPasswordForm.apply)

  private case class NewGroupForm(
    groupName: String,
    description: Option[String],
    url: Option[String],
    fileId: Option[String],
    members: String
  )
  private case class EditGroupForm(
    groupName: String,
    description: Option[String],
    url: Option[String],
    fileId: Option[String],
    members: String,
    clearImage: Boolean
  )
  private case class ResetPasswordEmailForm(
    mailAddress: String
  )
  private case class ResetPasswordForm(
    token: String,
    password: String
  )

  private val newGroupForm = mapping(
    "groupName" -> trim(label("Group name", text(required, maxlength(100), identifier, uniqueUserName, reservedNames))),
    "description" -> trim(label("Group description", optional(text()))),
    "url" -> trim(label("URL", optional(text(maxlength(200))))),
    "fileId" -> trim(label("File ID", optional(text()))),
    "members" -> trim(label("Members", text(required, members)))
  )(NewGroupForm.apply)

  private val editGroupForm = mapping(
    "groupName" -> trim(label("Group name", text(required, maxlength(100), identifier))),
    "description" -> trim(label("Group description", optional(text()))),
    "url" -> trim(label("URL", optional(text(maxlength(200))))),
    "fileId" -> trim(label("File ID", optional(text()))),
    "members" -> trim(label("Members", text(required, members))),
    "clearImage" -> trim(label("Clear image", boolean()))
  )(EditGroupForm.apply)

  private case class RepositoryCreationForm(
    owner: String,
    name: String,
    description: Option[String],
    isPrivate: Boolean,
    initOption: String,
    sourceUrl: Option[String]
  )

  private val newRepositoryForm = mapping(
    "owner" -> trim(label("Owner", text(required, maxlength(100), identifier, existsAccount))),
    "name" -> trim(label("Repository name", text(required, maxlength(100), repository, uniqueRepository))),
    "description" -> trim(label("Description", optional(text()))),
    "isPrivate" -> trim(label("Repository Type", boolean())),
    "initOption" -> trim(label("Initialize option", text(required))),
    "sourceUrl" -> trim(label("Source URL", optionalRequired(_.value("initOption") == "COPY", text())))
  )(RepositoryCreationForm.apply)

  private case class AccountForm(accountName: String)

  private val accountForm = mapping(
    "account" -> trim(label("Group/User name", text(required, validAccountName)))
  )(AccountForm.apply)

  // for account web hook url addition.
  private case class AccountWebHookForm(
    url: String,
    events: Set[WebHook.Event],
    ctype: WebHookContentType,
    token: Option[String]
  )

  private def accountWebHookForm(update: Boolean) =
    mapping(
      "url" -> trim(label("url", text(required, accountWebHook(update)))),
      "events" -> accountWebhookEvents,
      "ctype" -> label("ctype", text()),
      "token" -> optional(trim(label("token", text(maxlength(100)))))
    )((url, events, ctype, token) => AccountWebHookForm(url, events, WebHookContentType.valueOf(ctype), token))

  /**
   * Provides duplication check for web hook url. duplicated from RepositorySettingsController.scala
   */
  private def accountWebHook(needExists: Boolean): Constraint = new Constraint() {
    override def validate(name: String, value: String, messages: Messages): Option[String] =
      if (getAccountWebHook(params("userName"), value).isDefined != needExists) {
        Some(if (needExists) {
          "URL had not been registered yet."
        } else {
          "URL had been registered already."
        })
      } else {
        None
      }
  }

  private def accountWebhookEvents = new ValueType[Set[WebHook.Event]] {
    def convert(name: String, params: Map[String, Seq[String]], messages: Messages): Set[WebHook.Event] = {
      WebHook.Event.values.flatMap { t =>
        params.optionValue(name + "." + t.name).map(_ => t)
      }.toSet
    }
    def validate(name: String, params: Map[String, Seq[String]], messages: Messages): Seq[(String, String)] =
      if (convert(name, params, messages).isEmpty) {
        Seq(name -> messages("error.required").format(name))
      } else {
        Nil
      }
  }

  /**
   * Displays user information.
   */
  get("/:userName") {
    val userName = params("userName")
    getAccountByUserName(userName).map { account =>
      val extraMailAddresses = getAccountExtraMailAddresses(userName)
      params.getOrElse("tab", "repositories") match {
        // Public Activity
        case "activity" =>
          gitbucket.core.account.html.activity(
            account,
            if (account.isGroupAccount) Nil else getGroupsByUserName(userName),
            getActivitiesByUser(userName, publicOnly = true),
            extraMailAddresses
          )

        // Members
        case "members" if account.isGroupAccount =>
          val members = getGroupMembers(account.userName)
          gitbucket.core.account.html.members(
            account,
            members,
            extraMailAddresses,
            isGroupManager(context.loginAccount, members)
          )

        // Repositories
        case _ =>
          val members = getGroupMembers(account.userName)
          gitbucket.core.account.html.repositories(
            account,
            if (account.isGroupAccount) Nil else getGroupsByUserName(userName),
            getVisibleRepositories(context.loginAccount, Some(userName)),
            extraMailAddresses,
            isGroupManager(context.loginAccount, members)
          )
      }
    } getOrElse NotFound()
  }

  get("/:userName.atom") {
    val userName = params("userName")
    contentType = "application/atom+xml; type=feed"
    helper.xml.feed(getActivitiesByUser(userName, publicOnly = true))
  }

  get("/:userName.keys") {
    val keys = getPublicKeys(params("userName"))
    contentType = "text/plain; charset=utf-8"
    keys.map(_.publicKey).mkString("", "\n", "\n")
  }

  get("/:userName/_avatar") {
    val userName = params("userName")
    contentType = "image/png"
    getAccountByUserName(userName)
      .flatMap { account =>
        response.setDateHeader("Last-Modified", account.updatedDate.getTime)
        account.image
          .map { image =>
            Some(
              RawData(FileUtil.getMimeType(image), new File(getUserUploadDir(userName), FileUtil.checkFilename(image)))
            )
          }
          .getOrElse {
            if (account.isGroupAccount) {
              TextAvatarUtil.textGroupAvatar(account.fullName)
            } else {
              TextAvatarUtil.textAvatar(account.fullName)
            }
          }
      }
      .getOrElse {
        response.setHeader("Cache-Control", "max-age=3600")
        Thread.currentThread.getContextClassLoader.getResourceAsStream("noimage.png")
      }
  }

  get("/:userName/_edit")(oneselfOnly {
    val userName = params("userName")
    getAccountByUserName(userName).map { x =>
      val extraMails = getAccountExtraMailAddresses(userName)
      html.edit(x, extraMails, flash.get("info"), flash.get("error"))
    } getOrElse NotFound()
  })

  post("/:userName/_edit", editForm)(oneselfOnly { form =>
    val userName = params("userName")
    getAccountByUserName(userName).map { account =>
      updateAccount(
        account.copy(
          password = form.password.map(pbkdf2_sha256).getOrElse(account.password),
          fullName = form.fullName,
          mailAddress = form.mailAddress,
          description = form.description,
          url = form.url
        )
      )

      updateImage(userName, form.fileId, form.clearImage)
      updateAccountExtraMailAddresses(userName, form.extraMailAddresses.filter(_ != ""))
      flash.update("info", "Account information has been updated.")
      redirect(s"/$userName/_edit")

    } getOrElse NotFound()
  })

  get("/:userName/_delete")(oneselfOnly {
    val userName = params("userName")

    getAccountByUserName(userName, includeRemoved = true).map { account =>
      if (isLastAdministrator(account)) {
        flash.update("error", "Account can't be removed because this is last one administrator.")
        redirect(s"/$userName/_edit")
      } else {
//      // Remove repositories
//      getRepositoryNamesOfUser(userName).foreach { repositoryName =>
//        deleteRepository(userName, repositoryName)
//        FileUtils.deleteDirectory(getRepositoryDir(userName, repositoryName))
//        FileUtils.deleteDirectory(getWikiRepositoryDir(userName, repositoryName))
//        FileUtils.deleteDirectory(getTemporaryDir(userName, repositoryName))
//      }
        suspendAccount(account)
        session.invalidate()
        redirect("/")
      }
    } getOrElse NotFound()
  })

  get("/:userName/_ssh")(oneselfOnly {
    val userName = params("userName")
    getAccountByUserName(userName).map { x =>
      html.ssh(x, getPublicKeys(x.userName))
    } getOrElse NotFound()
  })

  post("/:userName/_ssh", sshKeyForm)(oneselfOnly { form =>
    val userName = params("userName")
    addPublicKey(userName, form.title, form.publicKey)
    redirect(s"/$userName/_ssh")
  })

  get("/:userName/_ssh/delete/:id")(oneselfOnly {
    val userName = params("userName")
    val sshKeyId = params("id").toInt
    deletePublicKey(userName, sshKeyId)
    redirect(s"/$userName/_ssh")
  })

  get("/:userName/_gpg")(oneselfOnly {
    val userName = params("userName")
    getAccountByUserName(userName).map { x =>
      // html.ssh(x, getPublicKeys(x.userName))
      html.gpg(x, getGpgPublicKeys(x.userName))
    } getOrElse NotFound()
  })

  post("/:userName/_gpg", gpgKeyForm)(oneselfOnly { form =>
    val userName = params("userName")
    addGpgPublicKey(userName, form.title, form.publicKey)
    redirect(s"/$userName/_gpg")
  })

  get("/:userName/_gpg/delete/:id")(oneselfOnly {
    val userName = params("userName")
    val keyId = params("id").toInt
    deleteGpgPublicKey(userName, keyId)
    redirect(s"/$userName/_gpg")
  })

  get("/:userName/_application")(oneselfOnly {
    val userName = params("userName")
    getAccountByUserName(userName).map { x =>
      var tokens = getAccessTokens(x.userName)
      val generatedToken = flash.get("generatedToken") match {
        case Some((tokenId: Int, token: String)) =>
          val gt = tokens.find(_.accessTokenId == tokenId)
          gt.map { t =>
            tokens = tokens.filterNot(_ == t)
            (t, token)
          }
        case _ => None
      }
      html.application(x, tokens, generatedToken)
    } getOrElse NotFound()
  })

  post("/:userName/_personalToken", personalTokenForm)(oneselfOnly { form =>
    val userName = params("userName")
    getAccountByUserName(userName).foreach { _ =>
      val (tokenId, token) = generateAccessToken(userName, form.note)
      flash.update("generatedToken", (tokenId, token))
    }
    redirect(s"/$userName/_application")
  })

  get("/:userName/_personalToken/delete/:id")(oneselfOnly {
    val userName = params("userName")
    val tokenId = params("id").toInt
    deleteAccessToken(userName, tokenId)
    redirect(s"/$userName/_application")
  })

  /**
   * Display the user preference settings page
   */
  get("/:userName/_preferences")(oneselfOnly {
    val userName = params("userName")
    val currentTheme = getAccountPreference(userName) match {
      case Some(accountHighlighter) => accountHighlighter.highlighterTheme
      case _                        => "github-v2"
    }
    getAccountByUserName(userName).map { x =>
      html.preferences(x, currentTheme)
    } getOrElse NotFound()
  })

  /**
   * Update the syntax highlighter setting of user
   */
  post("/:userName/_preferences/highlighter", syntaxHighlighterThemeForm)(oneselfOnly { form =>
    val userName = params("userName")
    addOrUpdateAccountPreference(userName, form.theme)
    redirect(s"/$userName/_preferences")
  })

  get("/:userName/_hooks")(managersOnly {
    val userName = params("userName")
    getAccountByUserName(userName).map { account =>
      gitbucket.core.account.html.hooks(account, getAccountWebHooks(account.userName), flash.get("info"))
    } getOrElse NotFound()
  })

  /**
   * Display the account web hook edit page.
   */
  get("/:userName/_hooks/new")(managersOnly {
    val userName = params("userName")
    getAccountByUserName(userName).map { account =>
      val webhook = AccountWebHook(userName, "", WebHookContentType.FORM, None)
      html.edithook(webhook, Set(WebHook.Push), account, create = true)
    } getOrElse NotFound()
  })

  /**
   * Add the account web hook URL.
   */
  post("/:userName/_hooks/new", accountWebHookForm(false))(managersOnly { form =>
    val userName = params("userName")
    addAccountWebHook(userName, form.url, form.events, form.ctype, form.token)
    flash.update("info", s"Webhook ${form.url} created")
    redirect(s"/$userName/_hooks")
  })

  /**
   * Delete the account web hook URL.
   */
  get("/:userName/_hooks/delete")(managersOnly {
    val userName = params("userName")
    deleteAccountWebHook(userName, params("url"))
    flash.update("info", s"Webhook ${params("url")} deleted")
    redirect(s"/$userName/_hooks")
  })

  /**
   * Display the account web hook edit page.
   */
  get("/:userName/_hooks/edit")(managersOnly {
    val userName = params("userName")
    getAccountByUserName(userName).flatMap { account =>
      getAccountWebHook(userName, params("url")).map { case (webhook, events) =>
        html.edithook(webhook, events, account, create = false)
      }
    } getOrElse NotFound()
  })

  /**
   * Update account web hook settings.
   */
  post("/:userName/_hooks/edit", accountWebHookForm(true))(managersOnly { form =>
    val userName = params("userName")
    updateAccountWebHook(userName, form.url, form.events, form.ctype, form.token)
    flash.update("info", s"webhook ${form.url} updated")
    redirect(s"/$userName/_hooks")
  })

  /**
   * Send the test request to registered account web hook URLs.
   */
  ajaxPost("/:userName/_hooks/test")(managersOnly {
    // TODO Is it possible to merge with [[RepositorySettingsController.ajaxPost]]?
    import scala.concurrent.duration.*
    import scala.concurrent.*
    import scala.util.control.NonFatal
    import org.apache.http.util.EntityUtils
    import scala.concurrent.ExecutionContext.Implicits.global

    def _headers(h: Array[org.apache.http.Header]): Array[Array[String]] = h.map { h =>
      Array(h.getName, h.getValue)
    }

    val userName = params("userName")
    val url = params("url")
    val token = Some(params("token"))
    val ctype = WebHookContentType.valueOf(params("ctype"))
    val dummyWebHookInfo =
      RepositoryWebHook(userName = userName, repositoryName = "dummy", url = url, ctype = ctype, token = token)
    val dummyPayload = {
      val ownerAccount = getAccountByUserName(userName).get
      WebHookPushPayload.createDummyPayload(ownerAccount)
    }

    val (webHook, json, reqFuture, resFuture) =
      callWebHook(WebHook.Push, List(dummyWebHookInfo), dummyPayload, context.settings).head

    val toErrorMap: PartialFunction[Throwable, Map[String, String]] = {
      case e: java.net.UnknownHostException                  => Map("error" -> s"Unknown host ${e.getMessage}")
      case _: java.lang.IllegalArgumentException             => Map("error" -> "invalid url")
      case _: org.apache.http.client.ClientProtocolException => Map("error" -> "invalid url")
      case NonFatal(e)                                       => Map("error" -> s"${e.getClass} ${e.getMessage}")
    }

    contentType = formats("json")
    org.json4s.jackson.Serialization.write(
      Map(
        "url" -> url,
        "request" -> Await.result(
          reqFuture
            .map(req =>
              Map(
                "headers" -> _headers(req.getAllHeaders),
                "payload" -> json
              )
            )
            .recover(toErrorMap),
          20 seconds
        ),
        "response" -> Await.result(
          resFuture
            .map(res =>
              Map(
                "status" -> res.getStatusLine,
                "body" -> EntityUtils.toString(res.getEntity),
                "headers" -> _headers(res.getAllHeaders)
              )
            )
            .recover(toErrorMap),
          20 seconds
        )
      )
    )
  })

  get("/register") {
    if (context.settings.basicBehavior.allowAccountRegistration) {
      if (context.loginAccount.isDefined) {
        redirect("/")
      } else {
        html.register()
      }
    } else NotFound()
  }

  post("/register", newForm) { form =>
    if (context.settings.basicBehavior.allowAccountRegistration) {
      createAccount(
        form.userName,
        pbkdf2_sha256(form.password),
        form.fullName,
        form.mailAddress,
        isAdmin = false,
        form.description,
        form.url
      )
      updateImage(form.userName, form.fileId, clearImage = false)
      updateAccountExtraMailAddresses(form.userName, form.extraMailAddresses.filter(_ != ""))
      redirect("/signin")
    } else NotFound()
  }

  get("/reset") {
    if (context.settings.basicBehavior.allowResetPassword) {
      html.reset()
    } else NotFound()
  }

  post("/reset", resetPasswordEmailForm) { form =>
    if (context.settings.basicBehavior.allowResetPassword) {
      getAccountByMailAddress(form.mailAddress).foreach { account =>
        val token = generateResetPasswordToken(form.mailAddress)
        val mailer = new Mailer(context.settings)
        mailer.send(
          form.mailAddress,
          "Reset password",
          s"""Hello, ${account.fullName}!
            |
            |You requested to reset the password for your GitBucket account.
            |If you are not sure about the request, you can ignore this email.
            |Otherwise, click the following link to set the new password:
            |${context.baseUrl}/reset/form/$token
            |""".stripMargin
        )
      }
      redirect("/reset/sent")
    } else NotFound()
  }

  get("/reset/sent") {
    if (context.settings.basicBehavior.allowResetPassword) {
      html.resetsent()
    } else NotFound()
  }

  get("/reset/form/:token") {
    if (context.settings.basicBehavior.allowResetPassword) {
      val token = params("token")
      decodeResetPasswordToken(token)
        .map { _ =>
          html.resetform(token)
        }
        .getOrElse(NotFound())
    } else NotFound()
  }

  post("/reset/form", resetPasswordForm) { form =>
    if (context.settings.basicBehavior.allowResetPassword) {
      decodeResetPasswordToken(form.token)
        .flatMap { mailAddress =>
          getAccountByMailAddress(mailAddress).map { account =>
            updateAccount(account.copy(password = pbkdf2_sha256(form.password)))
            html.resetcomplete()
          }
        }
        .getOrElse(NotFound())
    } else NotFound()
  }

  get("/groups/new")(usersOnly {
    context.withLoginAccount { loginAccount =>
      html.creategroup(List(GroupMember("", loginAccount.userName, isManager = true)))
    }
  })

  post("/groups/new", newGroupForm)(usersOnly { form =>
    createGroup(form.groupName, form.description, form.url)
    updateGroupMembers(
      form.groupName,
      form.members
        .split(",")
        .map {
          _.split(":") match {
            case Array(userName, isManager) => (userName, isManager.toBoolean)
          }
        }
        .toList
    )
    updateImage(form.groupName, form.fileId, clearImage = false)
    redirect(s"/${form.groupName}")
  })

  get("/:groupName/_editgroup")(managersOnly {
    val groupName = params("groupName")
    getAccountByUserName(groupName, includeRemoved = true).map { account =>
      html.editgroup(account, getGroupMembers(groupName), flash.get("info"))
    } getOrElse NotFound()
  })

  get("/:groupName/_deletegroup")(managersOnly {
    val groupName = params("groupName")
    // Remove from GROUP_MEMBER
    updateGroupMembers(groupName, Nil)
    // Disable group
    getAccountByUserName(groupName, includeRemoved = false).foreach { account =>
      updateGroup(groupName, account.description, account.url, removed = true)
    }
//      // Remove repositories
//      getRepositoryNamesOfUser(groupName).foreach { repositoryName =>
//        deleteRepository(groupName, repositoryName)
//        FileUtils.deleteDirectory(getRepositoryDir(groupName, repositoryName))
//        FileUtils.deleteDirectory(getWikiRepositoryDir(groupName, repositoryName))
//        FileUtils.deleteDirectory(getTemporaryDir(groupName, repositoryName))
//      }
    redirect("/")
  })

  post("/:groupName/_editgroup", editGroupForm)(managersOnly { form =>
    val groupName = params("groupName")
    val members = form.members
      .split(",")
      .map {
        _.split(":") match {
          case Array(userName, isManager) => (userName, isManager.toBoolean)
        }
      }
      .toList

    getAccountByUserName(groupName, includeRemoved = true).map { _ =>
      updateGroup(groupName, form.description, form.url, removed = false)

      // Update GROUP_MEMBER
      updateGroupMembers(form.groupName, members)
//        // Update COLLABORATOR for group repositories
//        getRepositoryNamesOfUser(form.groupName).foreach { repositoryName =>
//          removeCollaborators(form.groupName, repositoryName)
//          members.foreach { case (userName, isManager) =>
//            addCollaborator(form.groupName, repositoryName, userName)
//          }
//        }

      updateImage(form.groupName, form.fileId, form.clearImage)

      flash.update("info", "Account information has been updated.")
      redirect(s"/$groupName/_editgroup")

    } getOrElse NotFound()
  })

  /**
   * Show the new repository form.
   */
  get("/new")(usersOnly {
    context.withLoginAccount { loginAccount =>
      html.newrepo(getGroupsByUserName(loginAccount.userName), context.settings.basicBehavior.isCreateRepoOptionPublic)
    }
  })

  /**
   * Create new repository.
   */
  post("/new", newRepositoryForm)(usersOnly { form =>
    context.withLoginAccount { loginAccount =>
      if (context.settings.basicBehavior.repositoryOperation.create || loginAccount.isAdmin) {
        LockUtil.lock(s"${form.owner}/${form.name}") {
          if (getRepository(form.owner, form.name).isDefined) {
            // redirect to the repository if repository already exists
            redirect(s"/${form.owner}/${form.name}")
          } else if (!canCreateRepository(form.owner, loginAccount)) {
            // Permission error
            Forbidden()
          } else {
            // create repository asynchronously
            createRepository(
              loginAccount,
              form.owner,
              form.name,
              form.description,
              form.isPrivate,
              form.initOption,
              form.sourceUrl,
              context.settings.defaultBranch
            )
            // redirect to the repository
            redirect(s"/${form.owner}/${form.name}")
          }
        }
      } else Forbidden()
    }
  })

  get("/:owner/:repository/fork")(readableUsersOnly { repository =>
    context.withLoginAccount { loginAccount =>
      if (
        repository.repository.options.allowFork && (context.settings.basicBehavior.repositoryOperation.fork || loginAccount.isAdmin)
      ) {
        val loginUserName = loginAccount.userName
        val groups = getGroupsByUserName(loginUserName)
        groups match {
          case _: List[String] =>
            val managerPermissions = groups.map { group =>
              val members = getGroupMembers(group)
              context.loginAccount.exists(x =>
                members.exists { member =>
                  member.userName == x.userName && member.isManager
                }
              )
            }
            helper.html.forkrepository(
              repository,
              (groups zip managerPermissions).sortBy(_._1)
            )
          case _ => redirect(s"/$loginUserName")
        }
      } else BadRequest()
    }
  })

  post("/:owner/:repository/fork", accountForm)(readableUsersOnly { (form, repository) =>
    context.withLoginAccount { loginAccount =>
      if (
        repository.repository.options.allowFork && (context.settings.basicBehavior.repositoryOperation.fork || loginAccount.isAdmin)
      ) {
        val loginUserName = loginAccount.userName
        val accountName = form.accountName

        if (getRepository(accountName, repository.name).isDefined) {
          // redirect to the repository if repository already exists
          redirect(s"/$accountName/${repository.name}")
        } else if (!canCreateRepository(accountName, loginAccount)) {
          // Permission error
          Forbidden()
        } else {
          // fork repository asynchronously
          forkRepository(accountName, repository, loginUserName)
          // redirect to the repository
          redirect(s"/$accountName/${repository.name}")
        }
      } else Forbidden()
    }
  })

  private def existsAccount: Constraint = new Constraint() {
    override def validate(name: String, value: String, messages: Messages): Option[String] =
      if (getAccountByUserNameIgnoreCase(value).isEmpty) Some("User or group does not exist.") else None
  }

  private def uniqueRepository: Constraint = new Constraint() {
    override def validate(
      name: String,
      value: String,
      params: Map[String, Seq[String]],
      messages: Messages
    ): Option[String] = {
      for {
        userName <- params.optionValue("owner")
        _ <- getRepositoryNamesOfUser(userName).find(_.equalsIgnoreCase(value))
      } yield {
        "Repository already exists."
      }
    }
  }

  private def members: Constraint = new Constraint() {
    override def validate(name: String, value: String, messages: Messages): Option[String] = {
      if (
        value.split(",").exists {
          _.split(":") match { case Array(userName, isManager) => isManager.toBoolean }
        }
      ) None
      else Some("Must select one manager at least.")
    }
  }

  private def validPublicKey: Constraint = new Constraint() {
    override def validate(name: String, value: String, messages: Messages): Option[String] =
      SshUtil.str2PublicKey(value) match {
        case Some(_) if !getAllKeys().exists(_.publicKey == value) => None
        case _                                                     => Some("Key is invalid.")
      }
  }

  private def validGpgPublicKey: Constraint = new Constraint() {
    override def validate(name: String, value: String, messages: Messages): Option[String] = {
      GpgUtil.str2GpgKeyId(value) match {
        case Some(s) if GpgUtil.getGpgKey(s).isEmpty =>
          None
        case Some(_) =>
          Some("GPG key is duplicated.")
        case None =>
          Some("GPG key is invalid.")
      }
    }

  }

  private def validAccountName: Constraint = new Constraint() {
    override def validate(name: String, value: String, messages: Messages): Option[String] = {
      getAccountByUserName(value) match {
        case Some(_) => None
        case None    => Some("Invalid Group/User Account.")
      }
    }
  }

  private def isGroupManager(account: Option[Account], members: Seq[GroupMember]): Boolean = {
    account.exists { account =>
      account.isAdmin || members.exists { member =>
        member.userName == account.userName && member.isManager
      }
    }
  }

}
