package controllers

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


class WebcamServer extends Actor{
    def receive: Receive = {
        case x @ _ => println(x)
    }
}

@Singleton
class WebcamController @Inject()(implicit actorSystem: ActorSystem, mat: Materializer) extends Controller{
  var x = 0

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
            outActor => outActor
            NotUsed
     }
     val in = Sink.foreach[Any](
        s => server ! s
     )
    Flow.fromSinkAndSource(in, out)
  }

}