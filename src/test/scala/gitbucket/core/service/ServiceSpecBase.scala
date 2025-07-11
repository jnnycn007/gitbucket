package gitbucket.core.service

import gitbucket.core.GitBucketCoreModule
import gitbucket.core.util.{DatabaseConfig, Directory, FileUtil, JGitUtil}
import io.github.gitbucket.solidbase.Solidbase
import liquibase.database.core.H2Database
import liquibase.database.jvm.JdbcConnection
import gitbucket.core.model.*
import gitbucket.core.model.Profile.profile.blockingApi.*
import gitbucket.core.model.Session
import org.apache.commons.io.FileUtils

import java.sql.DriverManager
import java.io.File
import gitbucket.core.controller.Context
import gitbucket.core.service.SystemSettingsService.{
  BasicBehavior,
  RepositoryOperation,
  RepositoryViewerSettings,
  Ssh,
  SystemSettings
}

import javax.servlet.http.{HttpServletRequest, HttpSession}
import org.mockito.Mockito.*

import scala.util.Random
import scala.util.Using

trait ServiceSpecBase {

  val request: HttpServletRequest = mock(classOf[HttpServletRequest])
  val session: HttpSession = mock(classOf[HttpSession])
  when(request.getRequestURL).thenReturn(new StringBuffer("http://localhost:8080/path.html"))
  when(request.getRequestURI).thenReturn("/path.html")
  when(request.getContextPath).thenReturn("")
  when(request.getSession).thenReturn(session)

  def createSystemSettings(): SystemSettings =
    SystemSettings(
      baseUrl = None,
      information = None,
      basicBehavior = BasicBehavior(
        allowAccountRegistration = false,
        allowResetPassword = false,
        allowAnonymousAccess = true,
        isCreateRepoOptionPublic = true,
        repositoryOperation = RepositoryOperation(
          create = true,
          delete = true,
          rename = true,
          transfer = true,
          fork = true
        ),
        gravatar = false,
        notification = false,
        limitVisibleRepositories = false,
      ),
      ssh = Ssh(
        enabled = false,
        bindAddress = None,
        publicAddress = None
      ),
      useSMTP = false,
      smtp = None,
      ldapAuthentication = false,
      ldap = None,
      oidcAuthentication = false,
      oidc = None,
      skinName = "skin-blue",
      userDefinedCss = None,
      showMailAddress = false,
      webHook = SystemSettingsService.WebHook(
        blockPrivateAddress = false,
        whitelist = Nil
      ),
      upload = SystemSettingsService.Upload(
        maxFileSize = 3 * 1024 * 1024,
        timeout = 30 * 10000,
        largeMaxFileSize = 3 * 1024 * 1024,
        largeTimeout = 30 * 10000
      ),
      repositoryViewer = RepositoryViewerSettings(
        maxFiles = 0,
        maxDiffFiles = 100,
        maxDiffLines = 1000
      ),
      defaultBranch = "main"
    )

  def withTestDB[A](action: (Session) => A): A = {
    FileUtil.withTmpDir(new File(FileUtils.getTempDirectory(), Random.alphanumeric.take(10).mkString)) { dir =>
      val (url, user, pass) = (DatabaseConfig.url(Some(dir.toString)), DatabaseConfig.user, DatabaseConfig.password)
      org.h2.Driver.load()
      Using.resource(DriverManager.getConnection(url, user, pass)) { conn =>
        val solidbase = new Solidbase()
        val db = new H2Database()
        db.setConnection(new JdbcConnection(conn)) // TODO Remove setConnection in the future
        solidbase.migrate(conn, Thread.currentThread.getContextClassLoader, db, GitBucketCoreModule)
      }
      Database.forURL(url, user, pass).withSession { session =>
        action(session)
      }
    }
  }

  def generateNewAccount(name: String)(implicit s: Session): Account = {
    AccountService.createAccount(name, name, name, s"${name}@example.com", false, None, None)
    user(name)
  }

  def user(name: String)(implicit s: Session): Account = AccountService.getAccountByUserName(name).get

  lazy val dummyService = new RepositoryService
    with AccountService
    with ActivityService
    with IssuesService
    with MergeService
    with PullRequestService
    with CommitsService
    with CommitStatusService
    with LabelsService
    with MilestonesService
    with PrioritiesService
    with WebHookService
    with WebHookPullRequestService
    with WebHookPullRequestReviewCommentService
    with RequestCache {
    override def fetchAsPullRequest(
      userName: String,
      repositoryName: String,
      requestUserName: String,
      requestRepositoryName: String,
      requestBranch: String,
      issueId: Int
    ): Unit = {}
  }

  def generateNewUserWithDBRepository(userName: String, repositoryName: String)(implicit s: Session): Account = {
    val ac = AccountService.getAccountByUserName(userName).getOrElse(generateNewAccount(userName))
    val dir = Directory.getRepositoryDir(userName, repositoryName)
    if (dir.exists()) {
      FileUtils.deleteQuietly(dir)
    }
    JGitUtil.initRepository(dir, "main")
    dummyService.insertRepository(repositoryName, userName, None, false, "main")
    ac
  }

  def generateNewIssue(userName: String, repositoryName: String, loginUser: String = "root")(implicit
    s: Session
  ): Int = {
    dummyService.insertIssue(
      owner = userName,
      repository = repositoryName,
      loginUser = loginUser,
      title = "issue title",
      content = None,
      milestoneId = None,
      priorityId = None,
      isPullRequest = true
    )
  }

  def generateNewPullRequest(base: String, request: String, loginUser: String)(implicit
    s: Session
  ): (Issue, PullRequest) = {
    implicit val context = Context(createSystemSettings(), None, this.request)
    val Array(baseUserName, baseRepositoryName, baesBranch) = base.split("/")
    val Array(requestUserName, requestRepositoryName, requestBranch) = request.split("/")
    val issueId = generateNewIssue(baseUserName, baseRepositoryName, Option(loginUser).getOrElse(requestUserName))
    val baseRepository = dummyService.getRepository(baseUserName, baseRepositoryName)
    val loginAccount = dummyService.getAccountByUserName(loginUser)
    dummyService.createPullRequest(
      originRepository = baseRepository.get,
      issueId = issueId,
      originBranch = baesBranch,
      requestUserName = requestUserName,
      requestRepositoryName = requestRepositoryName,
      requestBranch = requestBranch,
      commitIdFrom = baesBranch,
      commitIdTo = requestBranch,
      isDraft = false,
      loginAccount = loginAccount.get,
      settings = createSystemSettings()
    )
    dummyService.getPullRequest(baseUserName, baseRepositoryName, issueId).get
  }
}
