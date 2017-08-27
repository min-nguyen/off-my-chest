package controllers
import scala.util.parsing.json._

import javax.inject._
import play.api._
import play.api.mvc._
import org.bytedeco.javacv.{ FrameGrabber, Frame }
import play.api.libs.json._
import scala.concurrent.blocking
import models.WebcamConnector
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import akka.NotUsed
import akka.stream.OverflowStrategy
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.actor._
import akka.stream.Materializer
import akka.NotUsed
import akka.util.Timeout;
import akka.actor.ActorIdentity;
import akka.stream.OverflowStrategy
import akka.pattern._
import play.api.mvc.WebSocket.MessageFlowTransformer
import scala.concurrent._
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._
import java.util.concurrent.TimeUnit;
import org.json4s.jackson.Serialization


trait RTCsignal {
  
}
case class Offer(data: JsValue) extends RTCsignal
case class Answer(data: JsValue)  extends RTCsignal
case class IceCandidate(data: JsValue)  extends RTCsignal

case object RequestChild
case class RequestName(name: String)
case class FromServer(name: String, msg: String)
case class ToServer(name: String, msg: String)

class WebcamServer extends Actor{
    println("server created")
    private var users: List[ActorRef] = List()
    implicit val timeout : Timeout = Timeout(5, TimeUnit.SECONDS)
    implicit val actorSystem = ActorSystem
    def receive: Receive = {
        case RequestChild => 
          val child : ActorRef = context.actorOf(Props(new WebcamActor(self)))
          sender ! child
        case ToServer(fromName, msg) => {
          val ob = JSON.parseFull(msg)
          // PROCESS NAME
          ob.map(m => m match {
            case m1: Map[String, String] => {
              (m1 get "name").map{
                toNameFound =>
                  val future: Future[Any] = context.actorSelection("*") ? RequestName(toNameFound)
                  val toActorFound = Await.result(future, timeout.duration).asInstanceOf[ActorRef]
                  toActorFound ! FromServer(fromName, msg)
              }  
            }
            case _ => "NOPE"
          })
        }
    }

    def parseType(rtctype: String, rtc: JsValue, name: String): Option[RTCsignal] = rtctype match {
      case "offer" => Some(Offer(rtc))
      case "answer" => Some(Answer(rtc))
      case "candidate" => Some(IceCandidate(rtc))
      case _    => None
    }
    def process(signal: RTCsignal) = signal match {
      case Offer(data) => users.map(u => u ! Json.stringify(data)); println("sent")
      case Answer(data) => users.map(u => u ! Json.stringify(data))
      case IceCandidate(data) => users.map(u => u ! Json.stringify(data)); println("ICE CANDIDATE SENT")
    }
}

class WebcamActor(server: ActorRef) extends Actor {
  val actorName = self.path.name
  implicit val formats = org.json4s.DefaultFormats

  println(actorName)
    def receive: Receive = {
      case actor: ActorRef => context.become(initialisedSource(actor))
    }
    def initialisedSource(outActor: ActorRef): Receive = {
      case maybeName: String => {
        val js = JSON.parseFull(maybeName)
        js match {
          case Some(mapping: Map[String, String]) => (mapping get "name").map(name => context.become(initialisedName(outActor, name)))
          case None => 
        }
      }
    }
    def initialisedName(outActor: ActorRef, name: String): Receive = {
      case RequestName(requestedName: String) => {
        if (requestedName == name) sender ! self
      }
      case FromServer(fromName: String, msg: String) => {
        val ob = JSON.parseFull(msg)
        ob.map(m => m match {
          case m1: Map[String, String] => {
            val newMap: Map[String, String] = m1 + ("name" -> fromName) 
            val stringified = Serialization.write(newMap)
            outActor ! stringified
          }
        })
      }
      case msg: String => server ! ToServer(name, msg)
    }
}

@Singleton
class WebcamController @Inject()(implicit actorSystem: ActorSystem, mat: Materializer) extends Controller{
  implicit val timeout : Timeout = Timeout(5, TimeUnit.SECONDS)
  val server = actorSystem.actorOf(Props(new WebcamServer))

  def home() = Action { implicit request: Request[AnyContent] =>
    val socket = routes.WebcamController.socket(0)
    val url = socket.webSocketURL()
    Ok(views.html.webcam(url))
  }

  def test() = Action {
    implicit request: Request[AnyContent] =>
    Ok(views.html.webcamsender())
  }

  def retrieveChild: ActorRef = {
        val future : Future[Any] = server ? RequestChild
        val child = Await.result(future, timeout.duration).asInstanceOf[ActorRef]
        return child;
  }

  def socket(id: Int) : WebSocket = WebSocket.accept[String, String] { 
     request => 
     
     val child: ActorRef = retrieveChild

     val out: Source[String, NotUsed] = Source.actorRef[String](10, OverflowStrategy.fail)
     .mapMaterializedValue{ 
            outActor => child ! outActor
            NotUsed
     }
     val in = Sink.foreach[Any](
        s => child ! s
     )
    Flow.fromSinkAndSource(in, out)
  }
}