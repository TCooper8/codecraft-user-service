package codecraft.user

import akka.actor._
import codecraft.user._
import codecraft.platform._
import codecraft.platform.amqp._
import scala.concurrent.duration._
import scala.util.{Try, Success, Failure}

case class UserServiceImpl() extends UserService {
  var users = Map.empty[String, User]
  var indexEmailToId = Map.empty[String, String]

  def uuid = java.util.UUID.randomUUID.toString

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
      users += (id -> User(
        id,
        cmd.firstName,
        cmd.lastName,
        cmd.email
      ))
      indexEmailToId += (cmd.email -> id)

      AddUserReply(Some(id), None)
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
    UserRoutingGroup.cmdInfo.map {
      case registry => (registry.key, registry)
    } toMap,
    Map(
      UserRoutingGroup.groupRouting.queueName -> UserRoutingGroup.groupRouting
    )
  )

  def main(argv: Array[String]) {
    val system = ActorSystem("service")
    val service = UserServiceImpl()

    val cloud = AmqpCloud(
      system,
      List(
        "amqp://192.168.99.101:5672"
      ),
      routingInfo
    )

    import system.dispatcher

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
