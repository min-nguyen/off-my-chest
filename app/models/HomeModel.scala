package models
import javax.inject._
import play.api.data._
import play.api.data.Forms._
import play.api._
import play.api.mvc._
import play.api.db._
import java.sql.DriverManager
import java.sql.Connection
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

case class Post(post: String, test: String)

case object Post{
   def post = Form(
    mapping(
      "post" -> text,
      "test" -> text
    )(Post.apply)(Post.unapply)
  )
}

case class IndexModel @Inject()(db: Database){
  
  /** MYSQL**/
  val conn = db.getConnection()
  val stmt = conn.createStatement()

  val results = stmt.executeQuery("USE mydb")
  
  val create = stmt.executeUpdate("""CREATE TABLE IF NOT EXISTS threads 
                                  (post VARCHAR(255), entry INT(11) AUTO_INCREMENT, 
                                  time DATETIME, PRIMARY KEY(entry))""")

  val resultSet = stmt.executeQuery("SELECT * FROM threads")
  while ( resultSet.next() ) {
        val jobref = resultSet.getString("post")
        val firstname = resultSet.getString("entry")
        println("jobref, firstname = " + jobref + ", " + firstname)
  }
  
  def insertPost(post: Post) = {
    val now = LocalDateTime.now()
    stmt.executeUpdate("INSERT INTO threads (post, time) VALUES ('" + post.post + "','" + LocalDateTime.now() + "')")
  }

  def selectPost() = {
    
  }
}

case object IndexModel{}


// object Users {

//   var users: Seq[User] = Seq()

//   def add(user: User): String = {
//     users = users :+ user.copy(id = users.length) // manual id increment
//     "User successfully added"
//   }

//   def delete(id: Long): Option[Int] = {
//     val originalSize = users.length
//     users = users.filterNot(_.id == id)
//     Some(originalSize - users.length) // returning the number of deleted users
//   }

//   def get(id: Long): Option[User] = users.find(_.id == id)

//   def listAll: Seq[User] = users

// }

