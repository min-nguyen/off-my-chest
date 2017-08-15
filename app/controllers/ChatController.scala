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

case class SocketActor(out: ActorRef) extends Actor {
    //Actor instance receives message
    def receive = {
        case msg: String => {
            println("hi")
            out ! ("Message: " + msg)}
    }
}

case object SocketActor{
    //Create actor
    def props(out: ActorRef) = {
        Props(new SocketActor(out))
    }
}

case class User(chatRoom: ActorRef) extends Actor{
    def receive = {
        case msg: String => chatRoom ! msg
    }
}

case class ChatRoomActor(roomId: Int, chatRoom: ActorRef) extends Actor {
    override def preStart(): Unit = {
        chatRoom ! Connect("Peter", self)
    }
    println(akka.serialization.Serialization.serializedActorPath(self))
    def receive: Receive = {
        case Connected(outgoing) =>
            context.become(connected(outgoing))
    }
    def connected(outgoing: ActorRef): Receive = {
        case clientMsg: String => 
            println("Child actor received " + clientMsg)
            outgoing ! "Y"
            chatRoom ! Send(clientMsg)
    }
}

case object ChatRoomActor{
    def props(id: Int, chatRoom: ActorRef): Props = {
        Props(new ChatRoomActor(id, chatRoom))
    }
}

sealed trait Msg
case class Connect(username: String, user: ActorRef) extends Msg
case class Connected(outgoing: ActorRef)
case class Send(msg: String) extends Msg
case object ReqChild extends Msg
case object PollId extends Msg

case class ChatRoom(roomId: Int) extends Actor{

    var users: List[ActorRef] = List()
    val id = roomId.toString()
    println(akka.serialization.Serialization.serializedActorPath(self))
    def connectUser(user: ActorRef) = {
        users = users ::: List(user)
    }
    // def newChild() : ActorRef = context.actorOf(ChatRoomActor.props(0, self,))
    def receive: Receive = {
        case ReqChild => 
            val child : ActorRef = context.actorOf(ChatRoomActor.props(users.length, self))
            sender ! child 
        case PollId =>
            sender ! roomId
        case Send(msg) => 
            println("Chatroom " + roomId + " received: " + msg)
            users.map (u => u ! Send(msg))
        case Connect(username, user) =>
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

    val controller : ActorRef = actorSystem.actorOf(ChatRoom.props(0), name = "Chatroom:0")

    // // // 
    def retrieveChatRoom(id: Int) = {
       
        implicit val timeout : Timeout = Timeout(5, TimeUnit.SECONDS)
        val fute = actorSystem.actorSelection("/user/yes").resolveOne().onComplete {
            // result => println(result)
            case Success(actor) => println(actor)
            case Failure(ex) => 
                // val actor = system.actorOf(Props(classOf[ActorClass]), name)
                // actor ! message
        }
    }

    def socket(id: Int) : WebSocket = WebSocket.accept[String, String] {
        request => println(request);
        //New chatroom
        val chatRoom : ActorRef = actorSystem.actorOf(ChatRoom.props(id), id.toString)
        //Wait for it to provide us a child actor ref
        implicit val timeout = Timeout(5 seconds)
        val future : Future[Any] = chatRoom ? ReqChild
        val child = Await.result(future, timeout.duration).asInstanceOf[ActorRef]
        println("Result child actor ref is " + child)
        
        val in = Sink.foreach[String](s => child ! s)
        val out: Source[String, NotUsed] =
        Source.actorRef[String](10, OverflowStrategy.fail)
        .mapMaterializedValue{
            outActor => child ! Connected(outActor)
            NotUsed
        }
        .map( outMsg => "sending" + outMsg)

        Flow.fromSinkAndSource(in, out)
    }
    def index(id: Int) = Action { implicit request: Request[AnyContent] =>
        val socket = routes.ChatController.socket(id)
        val url = socket.webSocketURL()
        println(url)
        retrieveChatRoom(0)
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