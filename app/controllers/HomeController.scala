package controllers

import javax.inject._
import play.api._
import play.api.mvc._
import play.api.db._
import play.api.data._
import play.api.data.Forms._
import java.sql.DriverManager
import java.sql.Connection
import models.IndexModel
import models.PostInsert
import models.PostRequest
import models.Post
import play.api.libs.json._
@Singleton
class HomeController @Inject()(db: Database) extends Controller{
  import play.api.libs.json._
  val index_model = new IndexModel(db);

  def home() = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.home())
  }
  def getPost() = Action { implicit request: Request[AnyContent] =>
    val body : PostRequest = PostRequest.postRequest.bindFromRequest.fold(
      formWithErrors => {
        PostRequest(0)
      },
      userData => {
        PostRequest.postRequest.bindFromRequest.get
      }
    )
    val post : Option[Post] = index_model.selectPost(body)
    Ok(post.toString())
   
  }

  def submitPost() = Action { implicit request: Request[AnyContent] =>
    val body : Option[PostInsert] = PostInsert.post.bindFromRequest.fold(
      formWithErrors => {
        None
      },
      userData => {
          Some(PostInsert.post.bindFromRequest.get)
      }
    )
    val insert = body match{
      case None => 
      case Some(x) => if (x.post != "") index_model.insertPost(x) 
    }
    Redirect("/home")
  }
}

// class redundant(body){
//   def unwrap(body: Option[Map[String, Seq[String]]]) : Map[String, Seq[String]] = body match {
//       case Some(x) => x
//       case None => Map()
//     }
//   var body = unwrap((request.body).asFormUrlEncoded)
//   println(body)
// }
