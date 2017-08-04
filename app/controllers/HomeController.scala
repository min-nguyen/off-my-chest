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
import models.Post


@Singleton
class HomeController @Inject()(db: Database) extends Controller{
  import play.api.libs.json._
  val index_model = new IndexModel(db);

  def index() = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.index())
  }
  def home() = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.home())
  }
  def getPost()= Action { implicit request: Request[AnyContent] =>
    Ok("gotpost")
  }

 

  def submitPost()= Action { implicit request: Request[AnyContent] =>
    val body : Post = Post.post.bindFromRequest.get
    
    println({body})
    
    index_model.insertPost(body);
    Ok("username: '${body}'")
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
