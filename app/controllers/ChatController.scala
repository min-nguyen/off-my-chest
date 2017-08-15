package controllers

import javax.inject._
import play.api._
import play.api.mvc._
import play.api.db._
import play.api.data._
import java.sql.DriverManager
import java.sql.Connection
import play.api.libs.json._
import akka.actor._
import akka.actor.ActorSystem
import akka.actor.ActorSelection
import akka.stream.Materializer
import akka.NotUsed
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import play.api.libs.streams.ActorFlow
import akka.pattern.ask
import scala.concurrent._
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._
import akka.pattern.AskableActorSelection;
import akka.util.Timeout;
import akka.actor.ActorIdentity;
import java.util.concurrent.TimeUnit;
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Success, Failure}
import akka.stream.OverflowStrategy


sealed trait Msg
case class AcknowledgeCon(username: String, user: ActorRef) extends Msg
case class Connected(outgoing: ActorRef)
case class Outgoing(msg: String) extends Msg
case class Incoming(msg: String)
case object ReqChild extends Msg
case object PollId extends Msg

case class ChatRoomActor(roomId: Int, chatRoom: ActorRef) extends Actor {

    override def preStart(): Unit = {
        chatRoom ! AcknowledgeCon("Peter", self)
    }
    def receive: Receive = {
        case Connected(outgoing) =>
            context.become(connected(outgoing))
    }
    def connected(outgoing: ActorRef): Receive = {
        case Incoming(msg) => 
            println("Child actor received " + msg)
            chatRoom ! Incoming(msg)
        case Outgoing(msg) =>
            println("Received outgoing msg " + msg)
            outgoing ! msg

    }
}

case object ChatRoomActor{
    def props(id: Int, chatRoom: ActorRef): Props = {
        Props(new ChatRoomActor(id, chatRoom))
    }
}


case class ChatRoom(roomId: Int) extends Actor{

    var users: List[ActorRef] = List()
    val id = roomId.toString()
    println("PATH:" + akka.serialization.Serialization.serializedActorPath(self))
    def connectUser(user: ActorRef) = {
        users = users ::: List(user)
    }
    
    def receive: Receive = {
        case ReqChild => 
            val child : ActorRef = context.actorOf(ChatRoomActor.props(users.length, self))
            sender ! child 
        case PollId =>
            sender ! roomId
        case Incoming(msg) => 
            println("Chatroom " + roomId + " received: " + msg)
            users.map (u => u ! Outgoing(msg))
        case AcknowledgeCon(username, user) =>
            connectUser(user)
            println("New connection: " + username + " : " + users)
        case msg => 
            println(msg)
            sender ! "You sent " + msg
     
    }
}

case object ChatRoom{
    def props(id: Int): Props = {
        Props(new ChatRoom(id))
    }
}

@Singleton
class ChatController @Inject()(cc: ControllerComponents)
                              (implicit actorSystem: ActorSystem,
                               mat: Materializer) extends Controller{

    //temporary stateful
    implicit val timeout : Timeout = Timeout(5, TimeUnit.SECONDS)

    // // // 
    def retrieveChatRoom(id: Int) = { 
        actorSystem.actorSelection("/user/Chatroom:"+id).resolveOne().onComplete {
            case Success(actor) => println("returning" + actor); actor
            case Failure(ex) => actorSystem.actorOf(ChatRoom.props(id), name = "Chatroom:" + id)
        }
    }

    def socket(id: Int) : WebSocket = WebSocket.accept[String, String] {
        request =>
        //Retrieve existing chatroom, or create new one
        implicit val timeout = Timeout(5 seconds)
        val select = actorSystem.actorSelection("/user/Chatroom:" + id)
        val asker = new AskableActorSelection(select);
        val fut  = asker.ask(Identify(1))
        val identity =  Await.result(fut, timeout.duration).asInstanceOf[ActorIdentity]
        val res = identity.getRef
        val chatRoom: ActorRef = if(res == null) {actorSystem.actorOf(ChatRoom.props(id), "Chatroom:" + id.toString)} else res

        //Wait for it to provide us a child actor ref
        val future : Future[Any] = chatRoom ? ReqChild
        val child = Await.result(future, timeout.duration).asInstanceOf[ActorRef]
        
        //Create flow towards child actor
        val in = Sink.foreach[String](s => child ! Incoming(s))
        val out: Source[String, NotUsed] =
        Source.actorRef[String](10, OverflowStrategy.fail)
        .mapMaterializedValue{
            outActor => child ! Connected(outActor)
            NotUsed
        }
        .map( outMsg => outMsg)

        Flow.fromSinkAndSource(in, out)

    }

    def index(id: Int) = Action { implicit request: Request[AnyContent] =>
        val socket = routes.ChatController.socket(id)
        val url = socket.webSocketURL()
        println(url)
        Ok(views.html.chat(url))
    }
}


case class ChatAdmin(actorSystem: ActorSystem) extends Actor{
    def receive: Receive = {
        case msg => println(msg)
    }
}

case object ChatAdmin{
    def props(): Props = {
        Props(new ChatAdmin(ActorSystem()))
    }
}


/**  Ask if actor exists with future and Askable**/
// implicit val timeout : Timeout = Timeout(5, TimeUnit.SECONDS)
// val select = actorSystem.actorSelection("user/*")
// val asker = new AskableActorSelection(select);
// val fut  = asker.ask(Identify(1))
// val identity =  Await.result(fut, timeout.duration).asInstanceOf[ActorIdentity]
// val ref = ident.getRef()
// println("Found identity: " + identity)