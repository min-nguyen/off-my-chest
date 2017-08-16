package controllers
import java.sql.DriverManager
import java.sql.Connection
import java.net.URL
import java.util.concurrent.TimeUnit;
import javax.inject._
import play.api._
import play.api.mvc._
import play.api.db._
import play.api.data._
import play.api.libs.json._
import play.api.libs.streams.ActorFlow
import akka.actor._
import akka.actor.ActorSystem
import akka.actor.ActorSelection
import akka.stream.Materializer
import akka.NotUsed
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.pattern.ask
import akka.util.Timeout;
import akka.actor.ActorIdentity;
import akka.stream.OverflowStrategy
import akka.pattern.AskableActorSelection;
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Success, Failure}
import scala.concurrent._
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._
import models.ChatMessage
import models.ChatModel

sealed trait Msg

//Controller => ChatRoomActor
case class Connected(outgoing: ActorRef)
case class Incoming(msg: String) extends Msg
//ChatRoomActor => ChatRoom
case class AcknowledgeCon(username: String, user: ActorRef) extends Msg
case class ToChatRoom(msg: String, user: String) extends Msg
//ChatRoom => ChatRoomActor
case class FromChatRoom(msg: String, user: String) extends Msg
case object FinishHistory extends Msg //Indicate loading history has finished
//Controller => ChatRoom
case object ReqChild extends Msg
//ChatRoom => ChatDatabase
case class SaveHistory(msg: String, user: String, roomId: Int) extends Msg
case class RequestHistory(roomId: Int) extends Msg

//Saves & Loads ChatRoom History
case class ChatDB(chatModel: ChatModel) extends Actor{
    def receive: Receive = {
        case SaveHistory(msg, user, roomId) => 
            val chatMsg: ChatMessage = ChatMessage.create(msg, user, roomId)
            chatModel.insertMessage(chatMsg)
        case RequestHistory(roomId) =>
            val history: List[ChatMessage] = chatModel.selectMessages(roomId)
            sender ! history
    }
}

case object ChatDB{
    def props(chatModel: ChatModel): Props = {
        Props(new ChatDB(chatModel))
    }
}

case class ChatRoomActor(roomId: Int, chatRoom: ActorRef) extends Actor {
    var history: List[ChatMessage] = List()
    def receive: Receive = {
        case Connected(outgoing) =>
            context.become(reqUser(outgoing))
    }
    def reqUser(outgoing: ActorRef): Receive = {
        case Incoming(user) => 
            chatRoom ! AcknowledgeCon(user, self)
            context.become(connected(outgoing, user))
    }
    def connected(outgoing: ActorRef, user: String): Receive = {
        case Incoming(msg) => 
            println("Child actor received " + msg)
            chatRoom ! ToChatRoom(msg, user)
        case FromChatRoom(msg, user) =>
            outgoing ! user + ": " + msg
        case x @ ChatMessage(msg, user, roomId, time) =>
            history ::= x
        case FinishHistory =>
            outgoing ! Json.toJson(history).toString
    }
}

case object ChatRoomActor{
    def props(id: Int, chatRoom: ActorRef): Props = {
        Props(new ChatRoomActor(id, chatRoom))
    }
}


case class ChatRoom(roomId: Int, chatDB: ActorRef) extends Actor{
    var users: List[ActorRef] = List()
    val id = roomId.toString()
    
    
    def connectUser(user: ActorRef) = {
        users = users ::: List(user)
    }
    def loadHistory: List[ChatMessage] = {
        implicit val timeout : Timeout = Timeout(5, TimeUnit.SECONDS)
        val history: Future[Any] = chatDB ? RequestHistory(roomId)
        val result = Await.result(history, timeout.duration).asInstanceOf[List[ChatMessage]]
        result
    }
    def receive: Receive = {
        case ReqChild => 
            val child : ActorRef = context.actorOf(ChatRoomActor.props(users.length, self))
            sender ! child

        case ToChatRoom(msg, user) => 
            users.map (u => u ! FromChatRoom(msg,user))
            chatDB ! SaveHistory(msg, user, roomId)
        case AcknowledgeCon(username, userref) =>
            connectUser(userref)
            //Load history to user
            val history: List[ChatMessage] = loadHistory
            history.map(msg => userref ! msg)
            userref ! FinishHistory
            //Alert users of new connection
            users.map(u => u ! FromChatRoom(username + " has connected", "CHATROOM"))
            println("New connection: " + username + " : " + users)
        case msg => 
            println(msg)
            sender ! "You sent " + msg

    }
}

case object ChatRoom{
    def props(id: Int, chatDB: ActorRef): Props = {
        Props(new ChatRoom(id, chatDB))
    }
}

@Singleton
class ChatController @Inject()(cc: ControllerComponents)
                              (implicit actorSystem: ActorSystem,
                               mat: Materializer,
                               db: Database) extends Controller{

    implicit val timeout : Timeout = Timeout(5, TimeUnit.SECONDS)
    val chatModel = new ChatModel(db)
    val chatDB = actorSystem.actorOf(ChatDB.props(chatModel))
    chatModel.selectMessages(3)
    //Retrieve existing chatroom, or create new one
    def retrieveChatRoom(id: Int): ActorRef = { 
        implicit val timeout = Timeout(5 seconds)
        val select = actorSystem.actorSelection("/user/Chatroom:" + id)
        val asker = new AskableActorSelection(select);
        val fut  = asker.ask(Identify(1))
        val identity =  Await.result(fut, timeout.duration).asInstanceOf[ActorIdentity]
        val res = identity.getRef
        val chatRoom: ActorRef = 
        if(res == null) {
            actorSystem.actorOf(ChatRoom.props(id, chatDB), "Chatroom:" + id.toString)
        } 
        else res
        return chatRoom;
    }
    //Wait for it to provide us a child actor ref
    def retrieveChatRoomActor(chatRoom: ActorRef): ActorRef = {
        val future : Future[Any] = chatRoom ? ReqChild
        val child = Await.result(future, timeout.duration).asInstanceOf[ActorRef]
        return child;
    }
    def socket(id: Int) : WebSocket = WebSocket.accept[String, String] {
        request =>

        val chatRoom = retrieveChatRoom(id)
        
        val chatRoomActor = retrieveChatRoomActor(chatRoom)
        //Create flow towards child actor
        val in = Sink.foreach[String](
            s => chatRoomActor ! Incoming (s) 
        )
        val out: Source[String, NotUsed] =
        Source.actorRef[String](10, OverflowStrategy.fail)
        .mapMaterializedValue{
            outActor => chatRoomActor ! Connected(outActor)
            NotUsed
        }
        .map( outMsg => outMsg) 

        Flow.fromSinkAndSource(in, out)

    }

    def index(id: Int) = Action { implicit request: Request[AnyContent] =>
        val socket = routes.ChatController.socket(id)
        val url = socket.webSocketURL()
        Ok(views.html.chat(url))
    }

    def sameOriginCheck(implicit rh: RequestHeader): Boolean = {
    rh.headers.get("Origin") match {
        case Some(origin) =>
        try {
            val url = new URL(origin)
            url.getHost == "localhost" &&
            (url.getPort match { case 9000 | 19001 => true; case _ => false })   
        }
        case Some(badOrigin) => false

        case None => false
    }
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