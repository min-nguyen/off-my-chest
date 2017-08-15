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

case class Post(post: String, entry: Int, time: String)
case object Post{
   def post = Form(
    mapping(
      "post" -> text,
      "entry" -> number,
      "time" -> text
    )(Post.apply)(Post.unapply)
  )
}

case class PostInsert(post: String)
case object PostInsert{
   def post = Form(
    mapping(
      "post" -> text
    )(PostInsert.apply)(PostInsert.unapply)
  )
}

case class PostRequest(entry: Int)
case object PostRequest{
   def postRequest = Form(
    mapping(
      "entry" -> number
    )(PostRequest.apply)(PostRequest.unapply)
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

  def viewDB {
    val resultSet = stmt.executeQuery("SELECT * FROM threads")
    while ( resultSet.next() ) {
          val post = resultSet.getString("post")
          val entry = resultSet.getInt("entry")
          val time = resultSet.getInt("time")
    }
  }
  
  def insertPost(post: PostInsert) {
    val now = LocalDateTime.now()
    val stmt_insert = conn.createStatement()
    stmt.executeUpdate("INSERT INTO threads (post, time) VALUES ('" + post.post + "','" + LocalDateTime.now() + "')")
    stmt_insert.close()
  }

  def selectPost(postRequest: PostRequest) : Option[Post] = {
    val stmt_select = conn.createStatement()
    val resultSet = stmt_select.executeQuery("SELECT * FROM threads WHERE entry = '" + postRequest.entry + "'") 
  
    if (!resultSet.next()) {
        stmt_select.close()
        None
    }
    else{ 
        val post = resultSet.getString("post")
        val entry : Int = resultSet.getInt("entry")
        val time = resultSet.getString("time")
        stmt_select.close()
        Some(Post(post, entry, time))
    }
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

