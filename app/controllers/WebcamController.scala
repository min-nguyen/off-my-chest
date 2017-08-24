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

trait RTCsignal {
  
}
case class Offer(data: JsValue) extends RTCsignal
case class Answer(data: JsValue)  extends RTCsignal
case class IceCandidate(data: JsValue)  extends RTCsignal

case class WebcamClient(ref: ActorRef)

class WebcamServer extends Actor{
    println("server created")
    private var users: List[ActorRef] = List()

    def receive: Receive = {
        case WebcamClient(ref) => 
          users = ref :: users
        case msg: String => 
          val jsvalue = Json.parse(msg)
          val ob = JSON.parseFull(msg)
          println(msg)
          println(ob)
          ob.map(m => m match {
            case m1: Map[String, String] => (m1 get "type") match {
              case Some(rtctype) => parseType(rtctype, jsvalue).map(process)
              case None => val maybeCandidate = (m1 get "candidate").map(_ => parseType("candidate", jsvalue))
                           maybeCandidate match {
                             case Some(candidate) => candidate.map(process)
                             case None => println("ERR")
                           }
            }               
            case _ => "NOPE"
          })
    }

    def parseType(rtctype: String, rtc: JsValue): Option[RTCsignal] = rtctype match {
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





@Singleton
class WebcamController @Inject()(implicit actorSystem: ActorSystem, mat: Materializer) extends Controller{
   

  val server = actorSystem.actorOf(Props(new WebcamServer))

  def home() = Action { implicit request: Request[AnyContent] =>
    val socket = routes.WebcamController.socket(0)
    val url = socket.webSocketURL()
    Ok(views.html.webcam(url))
  }


  def socket(id: Int) : WebSocket = WebSocket.accept[String, String] { 
    implicit val transformer = MessageFlowTransformer
     request => 
     val out: Source[String, NotUsed] = Source.actorRef[String](10, OverflowStrategy.fail)
     .mapMaterializedValue{ 
            outActor => server ! WebcamClient(outActor)
            NotUsed
     }
     val in = Sink.foreach[Any](
        s => server ! s
     )
    Flow.fromSinkAndSource(in, out)
  }
}