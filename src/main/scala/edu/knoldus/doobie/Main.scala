package edu.knoldus.doobie

import cats.effect.{ ExitCode => CatsExitCode }
import edu.knoldus.doobie.configuration.Configuration
import edu.knoldus.doobie.persistence.{ DBTransactor, UserPersistence }
import org.http4s.server.Router
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.server.middleware.CORS
import zio._
import zio.blocking.Blocking
import zio.clock.Clock
import zio.console.putStrLn
import zio.interop.catz._

object Main extends App {

  type AppEnvironment = Configuration with Clock with DBTransactor with UserPersistence

  type AppTask[A] = RIO[AppEnvironment, A]
  val appEnvironment: ZLayer[Any, Throwable, Configuration with Blocking with DBTransactor with UserPersistence] =
    Configuration.live >+> Blocking.live >+> UserPersistenceService.transactorLive >+> UserPersistenceService.live

  override def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] = {
    val program: ZIO[AppEnvironment, Throwable, Unit] =
      for {
        _   <- UserPersistenceService.createUserTable
        api <- configuration.apiConfig
        httpApp = Router[AppTask](
          "/users" -> Api(s"${api.endpoint}/users").route
        )

        server <- ZIO.runtime[AppEnvironment].flatMap { implicit rts =>
          BlazeServerBuilder[AppTask]
            .bindHttp(api.port, api.endpoint)
            .withHttpApp(CORS(httpApp))
            .serve
            .compile[AppTask, AppTask, CatsExitCode]
            .drain
        }
      } yield server

    program
      .provideSomeLayer[ZEnv](appEnvironment)
      .tapError(err => putStrLn(s"Execution failed with: $err"))
      .exitCode
  }
}
