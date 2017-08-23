
package models

import akka.NotUsed
import akka.stream.Materializer
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
import java.util.function.Supplier
import akka.actor.{ DeadLetterSuppression, Props, ActorSystem, ActorLogging }
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage.{ Cancel, Request }
import akka.stream.scaladsl.Source
import org.bytedeco.javacpp.opencv_core._
import org.bytedeco.javacpp.opencv_videoio._
import org.bytedeco.javacv.{ FrameGrabber, Frame }
import org.bytedeco.javacv.FrameGrabber.ImageMode
import org.bytedeco.javacv.CanvasFrame
import org.bytedeco.javacv.OpenCVFrameGrabber
import org.bytedeco.javacv.OpenCVFrameConverter
import akka.serialization._
case class Dimensions(width: Int, height: Int)

object WebcamConnector {
    def run(): RunnableGraph[NotUsed] = {
      implicit val system = ActorSystem()
      implicit val materializer = ActorMaterializer()
      
      val act = system.actorOf(Props(new receiver()))
      val imageDimensions = Dimensions(width = 640, height = 480)
      val webcamSource = Webcam.source(deviceId = 0, dimensions = imageDimensions)

      val serialization = SerializationExtension(system)

      val graph = webcamSource
          .map(MediaConversion.toMat) // most OpenCV manipulations require a Matrix
          .map(Flip.horizontal)
          .map(MediaConversion.toFrame) // convert back to a frame
          .map(frame => act ! frame)
          .to(Sink.ignore)
      graph.run()
      graph
    }
}

object Flip {
  /**
   * Clones the image and returns a flipped version of the given image matrix along the y axis (horizontally)
   */
  def horizontal(mat: Mat): Mat = {
    val cloned = mat.clone()
    flip(cloned, cloned, 1)
    cloned
  }
}

class receiver() extends Actor with ActorLogging {
    val canvas = new CanvasFrame("Webcam")
    //  Set Canvas frame to close on exit
    canvas.setDefaultCloseOperation(javax.swing.JFrame.EXIT_ON_CLOSE)

    def receive: Receive = {
      case x: Frame => canvas.showImage(x)
    }
  

  case object Continue extends DeadLetterSuppression

}


object MediaConversion {

  // Each thread gets its own greyMat for safety
  private val frameToMatConverter = ThreadLocal.withInitial(new Supplier[OpenCVFrameConverter.ToMat] {
    def get(): OpenCVFrameConverter.ToMat = new OpenCVFrameConverter.ToMat
  })

  /**
   * Returns an OpenCV Mat for a given JavaCV frame
   */
  def toMat(frame: Frame): Mat = frameToMatConverter.get().convert(frame)

  /**
   * Returns a JavaCV Frame for a given OpenCV Mat
   */
  def toFrame(mat: Mat): Frame = frameToMatConverter.get().convert(mat)

}


object Webcam {
  /**
   * Builds a Frame [[Source]]
   *
   * @param deviceId device ID for the webcam
   * @param dimensions
   * @param bitsPerPixel
   * @param imageMode
   * @param system ActorSystem
   * @return a Source of [[Frame]]s
   */
  def source(   deviceId: Int, 
                dimensions: Dimensions, 
                bitsPerPixel: Int = CV_8U, 
                imageMode: ImageMode = ImageMode.COLOR)
                (implicit system: ActorSystem): Source[Frame,NotUsed] = {
    val props = Props(
      new WebcamFramePublisher(
        deviceId = deviceId,
        imageWidth = dimensions.width,
        imageHeight = dimensions.height,
        bitsPerPixel = bitsPerPixel,
        imageMode = imageMode
      )
    )

    val webcamActorRef = system.actorOf(props)
    val webcamActorPublisher = ActorPublisher[Frame](webcamActorRef)

    Source.fromPublisher(webcamActorPublisher)
  }

  // Building a started grabber seems finicky if not synchronised; there may be some freaky stuff happening somewhere.
  private def buildGrabber(
    deviceId: Int,
    imageWidth: Int,
    imageHeight: Int,
    bitsPerPixel: Int,
    imageMode: ImageMode
  ): FrameGrabber = synchronized {
    val g = FrameGrabber.createDefault(deviceId)
    g.setImageWidth(imageWidth)
    g.setImageHeight(imageHeight)
    g.setBitsPerPixel(bitsPerPixel)
    g.setImageMode(imageMode)
    g.start()
    g
  }

  /**
   * Actor that backs the Akka Stream source
   */
  private class WebcamFramePublisher(

      deviceId: Int,
      imageWidth: Int,
      imageHeight: Int,
      bitsPerPixel: Int,
      imageMode: ImageMode
  ) extends ActorPublisher[Frame] with ActorLogging {

    private implicit val ec = context.dispatcher

    // Lazy so that nothing happens until the flow begins
    private lazy val grabber = buildGrabber(
      deviceId = deviceId,
      imageWidth = imageWidth,
      imageHeight = imageHeight,
      bitsPerPixel = bitsPerPixel,
      imageMode = imageMode
    )
    
    def receive: Receive = {
      case _: Request => emitFrames()
      case Continue => emitFrames()
      case Cancel => onCompleteThenStop()
      case unexpectedMsg => log.warning(s"Unexpected message: $unexpectedMsg")
    }

    private def emitFrames(): Unit = {
      if (isActive && totalDemand > 0) {
        /*
          Grabbing a frame is a blocking I/O operation, so we don't send too many at once.
         */
        grabFrame().foreach(onNext)
        if (totalDemand > 0) {
          self ! Continue //!!!!!!!!!!ITS FUCKING THIS ONE. I cant do sender ! continue. Need a way of receiving frames??
        }
      }
    }

    private def grabFrame(): Option[Frame] = {
      Option(grabber.grab())
    }
  }

  private case object Continue extends DeadLetterSuppression

}
