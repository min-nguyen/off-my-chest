package controllers

import java.sql.DriverManager
import java.sql.Connection
import java.net.URL
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors
import javax.inject._
import play.api.mvc._
import play.api.db._
import play.api.data._
import play.api.libs.json._
import play.api.mvc.WebSocket.MessageFlowTransformer
import akka.actor._
import akka.stream.Materializer
import akka.NotUsed
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.util.Timeout;
import akka.actor.ActorIdentity;
import akka.stream.OverflowStrategy
import akka.pattern._
import scala.util.{Success, Failure}
import scala.concurrent._
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._
import models.ChatMessage
import models.ChatModel
import models.Webcam
import models.WebcamConnector
sealed trait Msg


//Controller => ChatRoomActor
case class Connected(outgoing: ActorRef)
case class Incoming(msg: String) extends Msg
//ChatRoomActor => ChatRoom
case class AcknowledgeCon(username: String, user: ActorRef) extends Msg
case class ToChatRoom(msg: String, user: String) extends Msg
case object ReqHistoryRoom
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
        //Save message in db
        case SaveHistory(msg, user, roomId) => 
            val chatMsg: ChatMessage = ChatMessage.create(msg, user, roomId)
            chatModel.insertMessage(chatMsg)
        ///Load history from db
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
    private var history: List[ChatMessage] = List()
    def reqHistory(outgoing: ActorRef) = {
        val fut = chatRoom ? ReqHistoryRoom
        val result = Await.ready(fut, timeout.duration).value.get match{
            case Success(history: List[ChatMessage]) => 
                outgoing ! Json.toJson(history).toString
            case Failure(e) => 
                println("Err in history retrieval")
        }
    }
    implicit val timeout : Timeout = Timeout(5, TimeUnit.SECONDS);
    def receive: Receive = {
        //Establish outgoing client flow
        case Connected(outgoing) =>
            context.become(reqUsername(outgoing))
    }
    def reqUsername(outgoing: ActorRef): Receive = {
        //Establish username and request history
        case Incoming(user) => {
            val fut = chatRoom ! AcknowledgeCon(user, self)
            reqHistory(outgoing)
            context.become(connected(outgoing, user))
        }
    }
    def connected(outgoing: ActorRef, user: String): Receive = {
        //Forward message from ws to chatRoom
        case Incoming(msg) => 
            println("Child actor received " + msg)
            chatRoom ! ToChatRoom(msg, user)
        //Forward message from chatRoom to ws
        case FromChatRoom(msg, user) =>
            outgoing ! user + ": " + msg
    }
}

case object ChatRoomActor{
    def props(id: Int, chatRoom: ActorRef): Props = {
        Props(new ChatRoomActor(id, chatRoom))
    }
}


case class ChatRoom(roomId: Int, chatDB: ActorRef) extends Actor{
    private var users: List[ActorRef] = List()
    val id = roomId.toString()
    
    def connectUser(user: ActorRef) = {
        users = user :: users
    }
    def loadHistory(sender: ActorRef) =
        new Thread(new Runnable{
            def run() {
                implicit val timeout : Timeout = Timeout(5, TimeUnit.SECONDS)
                val fut = chatDB ? RequestHistory(roomId)
                val result  = Await.ready(fut, timeout.duration).value.get match{
                    case Success(t: List[ChatMessage]) => t
                    case Failure(e) => List()
                }
                sender ! result
            }
    }) 
    def receive: Receive = {
        case ReqChild => 
            //Create child for new ws connection
            val child : ActorRef = context.actorOf(ChatRoomActor.props(users.length, self))
            sender ! child
        case ToChatRoom(msg, user) => 
            //Store message in data base
            users.map (u => u ! FromChatRoom(msg,user))
            chatDB ! SaveHistory(msg, user, roomId)
        case AcknowledgeCon(username, userref) =>
            connectUser(userref)
            //Alert users of new connection
            users.map(u => u ! FromChatRoom(username + " has connected", "CHATROOM"))
            println("New connection: '" + username + "'")
        case ReqHistoryRoom =>
            //Create new thread for non-blocking request handling
            loadHistory(sender).start
        case msg => 
            println("Message not encapsulated in trait: " + msg)
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
        } else {
            res
        }
        chatRoom
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
        val chatRoomActor : ActorRef = retrieveChatRoomActor(chatRoom)
        //Create flow from ws towards child actor
        val in = Sink.foreach[String](
            s => chatRoomActor ! Incoming (s) 
        )
        //Create flow from child actor, by creating new Source as actor ref, namely outActor
        //Use Source 'outActor' as where we get our data from
        //mapmaterializedvalue means we get our materialized actor as our Source, which is outActor
        //and map it so that our ChatActor can have a reference to it and send it messages
        val out: Source[String, NotUsed] =
        Source.actorRef[String](10, OverflowStrategy.fail)
        .mapMaterializedValue{
            outActor => chatRoomActor ! Connected(outActor)
            NotUsed
        }
        //Returning this sink and source will automatically treat the Sink 'chatRoomActor' 
        //as the destination of all of the client's sent messages, and the Source 'outActor' 
        //as the client's source of data to receive.
        //
        Flow.fromSinkAndSource(in, out)
    }
    // def wc(id: Int) : WebSocket = WebSocket.accept[Frame, Frame] {
    //     request => 
    //     val serialization = SerializationExtension(actorSystem)
    //     val serializer = serialization.findSerializerFor(Frame)
    //     val in: Source[Frame, NotUsed]
    //     WebcamConnector.run()
        
        // val chatRoom = retrieveChatRoom(id)
        // val chatRoomActor : ActorRef = retrieveChatRoomActor(chatRoom)
        // //Create flow towards child actor
        // val in = Sink.foreach[String](
        //     s => chatRoomActor ! Incoming (s) 
        // )
        // val out: Source[String, NotUsed] =
        // Source.actorRef[String](10, OverflowStrategy.fail)
        // .mapMaterializedValue{
        //     outActor => chatRoomActor ! Connected(outActor)
        //     NotUsed
        // }
        // .map( outMsg => outMsg) 
        // Flow.fromSinkAndSource(in, out)
    // }

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