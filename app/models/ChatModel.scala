package models
import javax.inject._
import play.api.data._
import play.api.data.Forms._
import play.api._
import play.api.mvc._
import play.api.db._
import java.sql.DriverManager
import java.sql.Connection
import java.sql.ResultSet
import java.util.Date
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import play.api.libs.json._




case class ChatMessage(text: String, user: String, roomId: Int, time: LocalDateTime)
case object ChatMessage{
    def create(text: String, user: String, roomId: Int) = 
        new ChatMessage(text, user, roomId, LocalDateTime.now())
    implicit val msg = Json.format[ChatMessage]
}

case class ChatModel @Inject()(db: Database){

  /** MYSQL**/
  val conn = db.getConnection()
  val stmt = conn.createStatement()

  val results = stmt.executeQuery("USE mydb")
  
  val create = stmt.executeUpdate("""CREATE TABLE IF NOT EXISTS chatroom 
                                  (text VARCHAR(255), user VARCHAR(255), 
                                  roomId INT(11), time DATETIME, 
                                  FOREIGN KEY (roomID) REFERENCES threads(entry))""")
  
  def viewDB : Unit = {
    val resultSet = stmt.executeQuery("SELECT * FROM chatroom")
    while ( resultSet.next() ) {
          val text = resultSet.getString("text")
          val user = resultSet.getString("user")
          val roomId = resultSet.getInt("roomId")
          val time = resultSet.getInt("time")
    }
  }
  
  def insertMessage(msg: ChatMessage) : Unit = {
    val stmt_insert = conn.createStatement()
    stmt.executeUpdate("INSERT INTO chatroom (text, user, roomId, time) VALUES ('" 
                        + msg.text + "','" + msg.user + "','" + msg.roomId + "','" + msg.time + "')")
    stmt_insert.close()
  }

  def selectMessages(id: Int) : List[ChatMessage] = {
    val stmt_select = conn.createStatement()
    val resultSet = stmt_select.executeQuery("SELECT * FROM chatroom WHERE roomId = '" 
                                            + id + "' ORDER BY time DESC") 

    def foldResultSet(resultSet: ResultSet, list: List[ChatMessage]) : List[ChatMessage] = {
      if(resultSet.next()){
        foldResultSet(resultSet, ChatMessage(
          text = resultSet.getString("text"),
          user = resultSet.getString("user"),
          roomId = resultSet.getInt("roomId"),
          time = resultSet.getTimestamp("time").toLocalDateTime()
        ) :: list)
      }
      else{
        stmt_select.close()
        list
      }
    }

    foldResultSet(resultSet, List())
  }

}

