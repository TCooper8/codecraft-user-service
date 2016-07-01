package codecraft.user

import akka.actor._
import codecraft.auth._
import codecraft.platform._
import codecraft.platform.amqp._
import codecraft.user._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{Try, Success, Failure, Either}

case class UserServiceImpl(cloud: ICloud) extends UserService {
  val email = "codecraft/userstore"
  val pass = "codecraft"

  // First, this service needs to authenticate.
  var token = getAuth match {
    case GetAuthReply(None, Some(error)) =>
      addAuth match {
        case AddAuthReply(None, Some(error)) =>
          getAuth match {
            case GetAuthReply(None, Some(error)) => throw new Exception(error)
            case GetAuthReply(Some(token), _) => token
          }
        case AddAuthReply(Some(token), _) =>
          token
      }
    case GetAuthReply(Some(token), _) => token
  }

  var users = Map.empty[String, User]
  var indexEmailToId = Map.empty[String, String]

  def uuid = java.util.UUID.randomUUID.toString
  def await[A](f: => scala.concurrent.Awaitable[A]) = Await.result(f, Duration.Inf)

  private[this] def addAuth = await {
    cloud.requestCmd("auth.add", AddAuth(email, pass), 5 seconds).mapTo[AddAuthReply]
  }

  private[this] def getAuth = await {
    cloud.requestCmd("auth.get", GetAuth(email, pass), 5 seconds).mapTo[GetAuthReply]
  }

  private[this] def addRole(id: String, token: String) = await {
    cloud.requestCmd("auth.addRole", AddRole(id, token), 5 seconds).mapTo[AddRoleReply]
  }

  private[this] def addPermission(id: String, token: String, roleId: String) = await {
    cloud.requestCmd("auth.addpermission", AddPermission(id, token, roleId), 5 seconds).mapTo[AddPermissionReply]
  }

  def add(cmd: AddUser): AddUserReply = this.synchronized {
    // Check and make sure the email doesn't already exist.
    indexEmailToId.get(cmd.email) flatMap { id =>
      // This email is already registered.
      // Ensure that it actually links to a user.
      users.get(id) map { user =>
        Some(AddUserReply(None, Some("Email already registered to a user")))
      } getOrElse {
        None
      }
    } getOrElse {
      // Email not linked, or the link was invalid.
      val id = uuid

      // Add a user role to this app.
      addRole(id, token) match {
        case AddRoleReply(None, Some(error)) =>
          AddUserReply(None, Some(error))

        case AddRoleReply(Some(token), _) =>
          // Add user permission for themself.
          addPermission(id, token, id) match {
            case AddPermissionReply(None, Some(error)) =>
              AddUserReply(None, Some(error))

            case AddPermissionReply(Some(token), _) =>
              // Add the user to the actual database.
              users += (id -> User(
                id,
                cmd.firstName,
                cmd.lastName,
                email
              ))
              indexEmailToId += (cmd.email -> id)

              AddUserReply(Some(id), None)
          }
      }
    }
  }

  def get(cmd: GetUser): GetUserReply = {
    users.get (cmd.id) map { user =>
      GetUserReply(Some(user), None)
    } getOrElse {
      GetUserReply(None, Some("User does not exist"))
    }
  }

  def onError(exn: Throwable) {
    println(s"$exn")
  }
}

object Main {
  val routingInfo = RoutingInfo(
    List(
      UserRoutingGroup.cmdInfo.map {
        case registry => (registry.key, registry)
      }.toMap,
      AuthRoutingGroup.cmdInfo.map {
        case registry => (registry.key, registry)
      }.toMap
    ).foldLeft(Map.empty[String, codecraft.codegen.CmdRegistry]) {
      case (acc, a) => acc ++ a
    },
    Map(
      UserRoutingGroup.groupRouting.queueName -> UserRoutingGroup.groupRouting,
      AuthRoutingGroup.groupRouting.queueName -> AuthRoutingGroup.groupRouting
    )
  )

  def main(argv: Array[String]) {
    val system = ActorSystem("service")
    val cloud = AmqpCloud(
      system,
      List(
        "amqp://192.168.99.101:5672"
      ),
      routingInfo
    )
    val service = UserServiceImpl(cloud)

    cloud.subscribeCmd(
      "cmd.user",
      service,
      5 seconds
    ) onComplete {
      case Failure(e) => throw e
      case Success(_) =>
        println("Subscribed")
        cloud.requestCmd(
          "user.get",
          GetUser("id"),
          5 seconds
        ) onComplete {
          case Failure(e) => throw e
          case Success(res) =>
            println(s"res = $res")
        }
    }
  }
}
