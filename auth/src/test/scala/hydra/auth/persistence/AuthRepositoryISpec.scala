package hydra.auth.persistence

import com.typesafe.config.ConfigFactory
import hydra.auth.persistence.RepositoryModels.Token
import hydra.auth.persistence.TokenInfoRepository.{MissingTokenException, TokenInfo}
import hydra.common.logging.LoggingAdapter
import hydra.core.persistence.{FlywaySupport, H2PersistenceComponent}
import org.joda.time.DateTime
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Span}
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}

import scala.util.Try

class AuthRepositoryISpec extends FlatSpec
  with Matchers
  with BeforeAndAfterAll
  with ScalaFutures
  with LoggingAdapter
  with RepositoryModels {

  implicit val ec = scala.concurrent.ExecutionContext.global

  val persistenceDelegate = new H2PersistenceComponent(ec)
  val db = persistenceDelegate.db

  private val expectedTokenInfo = TokenInfo("test-token", Set("resourceA", "resourceB"))

  private val toDeleteToken = "to-delete-token"

  override def beforeAll(): Unit = {
    // This migration also inserts test data into the db.
    FlywaySupport.migrate(ConfigFactory.load().getConfig("h2-db"))
  }

  override def afterAll(): Unit = {
    Try(db.close()).recover { case _ => log.warn("Unable to shut down database") }
  }

  "A TokenRepository" should "retrieve token info" in {
    val tokenInfoRepo = new TokenInfoRepository(persistenceDelegate)

    whenReady(tokenInfoRepo.getTokenInfo(expectedTokenInfo.token)) { actualTokenInfo =>
      actualTokenInfo shouldEqual expectedTokenInfo
    }
  }

  it should "insert a new token" in {
    val tokenInfoRepo = new TokenInfoRepository(persistenceDelegate)
    val token = Token(
      111,
      DateTime.parse("2000-03-15"),
      DateTime.parse("2000-03-15"),
      "insert-token",
      1
    )

    val f = for {
      x <- tokenInfoRepo.insertToken(token)
      y <- tokenInfoRepo.getTokenInfo(token.token)
    } yield (x, y)

    whenReady(f, Timeout(Span(500, Millis))) {
      case (x, y) =>
        x shouldBe true
        y shouldEqual TokenInfo("insert-token", Set("resourceA", "resourceB"))
    }
  }

  it should "return a failure for missing tokens" in {
    val tokenInfoRepo = new TokenInfoRepository(persistenceDelegate)

    whenReady(tokenInfoRepo.getTokenInfo("does-not-exist").failed) { e =>
      e shouldBe a[MissingTokenException]
    }
  }

  it should "remove a token" in {
    val tokenInfoRepo = new TokenInfoRepository(persistenceDelegate)

    whenReady(tokenInfoRepo.removeToken(toDeleteToken)) { result =>
      result shouldEqual true
    }
  }

  it should "insert a resource" in {
    fail()
  }
}